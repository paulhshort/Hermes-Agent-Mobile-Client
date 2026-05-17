package dev.hermes.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.TextView
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

private const val LOG_TAG = "HermesWebView"
private const val PREFS_NAME = "hermes_mobile_client"
private const val PREF_LAST_DASHBOARD_BASE = "last_dashboard_base"
private const val PREF_TAILNET_SUFFIX = "tailnet_suffix"
private const val PREF_DISCOVERY_HOSTS = "discovery_hosts"
private const val PREF_DISCOVERY_PORTS = "discovery_ports"
private const val PREF_DISCOVERY_IPV4 = "discovery_ipv4"
private const val PREF_TEXT_ZOOM = "text_zoom"
private const val STATE_WEBVIEW = "state_webview"
private const val DEFAULT_TEXT_ZOOM = 90
private const val REQUEST_RECORD_AUDIO = 2401
private const val DEFAULT_DISCOVERY_HOSTS = "devil,dev01,g4-dev,g4-dt-069,g4dev,g4,hermes,dev"
private const val DEFAULT_DISCOVERY_PORTS = "9119,9120,9121,9122,9123"
private const val DEFAULT_DISCOVERY_IPV4 = ""

object EndpointPolicy {
    fun normalizeDashboardBase(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return ""
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "http://$trimmed"
        }
        return withScheme
            .removeSuffix("/")
            .removeSuffix("/chat")
            .removeSuffix("/")
    }

    fun isAllowedDashboardBase(baseUrl: String): Boolean {
        val normalized = normalizeDashboardBase(baseUrl)
        if (normalized.isBlank()) return false
        val uri = runCatching { URI(normalized) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        if (!uri.userInfo.isNullOrBlank()) return false
        val host = uri.host?.trim()?.lowercase()?.trimEnd('.') ?: return false
        return isTailscaleIpv4(host) || isTailscaleMagicDns(host)
    }

    private fun isTailscaleMagicDns(host: String): Boolean = host.endsWith(".ts.net")

    private fun isTailscaleIpv4(host: String): Boolean {
        val octets = host.split(".").map { it.toIntOrNull() ?: return false }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        // Tailscale CGNAT range: 100.64.0.0/10 through 100.127.255.255.
        return octets[0] == 100 && octets[1] in 64..127
    }
}

object DiscoveryPolicy {
    fun generateDashboardCandidates(
        tailnetSuffix: String,
        hostNames: String,
        ports: String,
        explicitIpv4Addresses: String,
        savedBase: String?,
    ): List<String> {
        val candidates = linkedSetOf<String>()
        val normalizedSaved = savedBase?.let { EndpointPolicy.normalizeDashboardBase(it) }.orEmpty()
        if (EndpointPolicy.isAllowedDashboardBase(normalizedSaved)) candidates += normalizedSaved

        val parsedPorts = parsePorts(ports)
        val suffix = normalizeTailnetSuffix(tailnetSuffix)
        if (suffix.isNotBlank()) {
            parseCsv(hostNames).forEach { host ->
                parsedPorts.forEach { port ->
                    val base = "http://$host.$suffix:$port"
                    if (EndpointPolicy.isAllowedDashboardBase(base)) candidates += base
                }
            }
        }

        parseTailscaleIpv4Addresses(explicitIpv4Addresses)
            .forEach { host ->
                parsedPorts.forEach { port ->
                    candidates += "http://$host:$port"
                }
            }

        return candidates.toList()
    }

    fun parseHosts(raw: String): String = parseCsv(raw).joinToString(",")

    fun parsePorts(raw: String): List<Int> {
        val parsed = parseCsv(raw)
            .mapNotNull { it.toIntOrNull() }
            .filter { it in 1..65535 }
            .distinct()
        return parsed.ifEmpty { listOf(9119) }
    }

    fun parseTailscaleIpv4Addresses(raw: String): List<String> = parseCsv(raw)
        .filter { isTailscaleIpv4(it) }
        .distinct()

    fun normalizeTailnetSuffix(raw: String): String {
        return raw.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore('/')
            .trim('.')
            .lowercase()
            .takeIf { it.endsWith(".ts.net") && it != "ts.net" }
            .orEmpty()
    }

    private fun parseCsv(raw: String): List<String> = raw
        .split(',', '\n', ';', ' ')
        .map { it.trim().lowercase().trim('.') }
        .filter { it.isNotBlank() }
        .distinct()

    private fun isTailscaleIpv4(host: String): Boolean {
        val octets = host.split(".").map { it.toIntOrNull() ?: return false }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        return octets[0] == 100 && octets[1] in 64..127
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var webView: HermesWebView
    private val mainHandler = Handler(Looper.getMainLooper())
    private val startupExecutor = Executors.newSingleThreadExecutor()
    private val attemptedBases = CopyOnWriteArrayList<String>()
    private var showingConnectionHub = false
    private var pendingAudioPermissionRequest: PermissionRequest? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebView.setWebContentsDebuggingEnabled(false)
        CookieManager.getInstance().setAcceptCookie(true)

        webView = HermesWebView(this).apply {
            mobileInputSink = object : HermesWebView.MobileInputSink {
                override fun sendText(text: String) {
                    sendHermesMobileText(text)
                }

                override fun sendKey(key: String) {
                    sendHermesMobileKey(key)
                }
            }

            setBackgroundColor(Color.rgb(4, 28, 28))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )

            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = false
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.textZoom = getSavedTextZoom()
            settings.userAgentString = "${settings.userAgentString} HermesAgentMobile/0.1"

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    return true
                }

                override fun onPermissionRequest(request: PermissionRequest) {
                    mainHandler.post { handleWebPermissionRequest(request) }
                }

                override fun onPermissionRequestCanceled(request: PermissionRequest) {
                    if (pendingAudioPermissionRequest == request) pendingAudioPermissionRequest = null
                    super.onPermissionRequestCanceled(request)
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                    return handleNavigationUrl(url, isMainFrame = true)
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url ?: return false
                    return handleNavigationUrl(uri.toString(), isMainFrame = request.isForMainFrame)
                }

                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    if (isBlockedMainFrameUrl(url)) {
                        view.stopLoading()
                        renderBlockedUrl(url)
                        return
                    }
                    super.onPageStarted(view, url, favicon)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    Log.d(LOG_TAG, "Loaded $url")
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        injectMobileChrome(view)
                        injectMobileInputBridge(view)
                        triggerTerminalRelayout(view)
                    }
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    Log.e(LOG_TAG, "WebView error ${error.errorCode}: ${error.description} for ${request.url}")
                    if (request.isForMainFrame) {
                        renderStatusPage(
                            "Dashboard load failed: ${error.description} (${error.errorCode})",
                            listOf(request.url.toString()),
                        )
                    }
                    super.onReceivedError(view, request, error)
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    errorResponse: WebResourceResponse,
                ) {
                    Log.e(LOG_TAG, "WebView HTTP ${errorResponse.statusCode}: ${errorResponse.reasonPhrase} for ${request.url}")
                    if (request.isForMainFrame) {
                        renderStatusPage(
                            "Dashboard returned HTTP ${errorResponse.statusCode}: ${errorResponse.reasonPhrase}",
                            listOf(request.url.toString()),
                        )
                    }
                    super.onReceivedHttpError(view, request, errorResponse)
                }
            }

        }

        setContentView(webView)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })

        val restored = savedInstanceState?.getBundle(STATE_WEBVIEW)?.let { state ->
            webView.restoreState(state)
            true
        } ?: false
        if (restored) {
            Log.d(LOG_TAG, "Restored WebView state after configuration change")
            mainHandler.post {
                val restoredUrl = webView.url.orEmpty()
                if (isBlockedMainFrameUrl(restoredUrl)) {
                    webView.stopLoading()
                    renderBlockedUrl(restoredUrl)
                }
            }
            return
        }

        val lastBase = getSavedDashboardBase()
        if (!lastBase.isNullOrBlank()) {
            loadDashboardBase(lastBase, persist = false)
        } else {
            renderConnectionHome()
        }
    }

    override fun onDestroy() {
        pendingAudioPermissionRequest?.deny()
        pendingAudioPermissionRequest = null
        startupExecutor.shutdownNow()
        webView.destroy()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val webState = Bundle()
        webView.saveState(webState)
        outState.putBundle(STATE_WEBVIEW, webState)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_RECORD_AUDIO) return
        val request = pendingAudioPermissionRequest ?: return
        pendingAudioPermissionRequest = null
        val requested = request.resources?.toSet().orEmpty()
        val audioOnly = requested.isNotEmpty() && requested.all { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }
        if (audioOnly && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && isAllowedWebPermissionOrigin(request.origin)) {
            request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
        } else {
            request.deny()
        }
    }

    private fun handleWebPermissionRequest(request: PermissionRequest) {
        val requested = request.resources?.toSet().orEmpty()
        val audioOnly = requested.isNotEmpty() && requested.all { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }
        if (!audioOnly || !isAllowedWebPermissionOrigin(request.origin)) {
            request.deny()
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
            return
        }
        pendingAudioPermissionRequest?.deny()
        pendingAudioPermissionRequest = request
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
    }

    private fun isAllowedWebPermissionOrigin(origin: Uri?): Boolean {
        val value = origin?.toString().orEmpty()
        return EndpointPolicy.isAllowedDashboardBase(value)
    }

    private fun renderConnectionHome() {
        showingConnectionHub = true
        val html = """
            <html><body style="margin:0;background:#041c1c;color:#ffe6cb;font-family:monospace">
              <div style="padding:28px">
                <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px">
                  <div style="font-size:22px;letter-spacing:1px">HERMES CONNECTION HUB</div>
                  <a href="hermes://menu" style="text-decoration:none;color:#ffe6cb;background:#0d1d18;border:1px solid #21362d;padding:8px 12px">☰</a>
                </div>
                <div style="font-size:13px;color:#89917e;margin-bottom:18px">Choose a trusted Tailscale dashboard endpoint</div>
                <a href="hermes://discover" style="display:block;text-decoration:none;background:#0d1d18;color:#ffe6cb;padding:16px;border:1px solid #21362d;margin-bottom:10px">Tailnet Auto Discover</a>
                <a href="hermes://configure-discovery" style="display:block;text-decoration:none;background:#0d1d18;color:#ffe6cb;padding:16px;border:1px solid #21362d;margin-bottom:10px">Configure Tailnet Discovery</a>
                <a href="hermes://manual" style="display:block;text-decoration:none;background:#0d1d18;color:#ffe6cb;padding:16px;border:1px solid #21362d;margin-bottom:10px">Tailscale Endpoint (Enter URL)</a>
                <a href="hermes://saved" style="display:block;text-decoration:none;background:#0d1d18;color:#ffe6cb;padding:16px;border:1px solid #21362d;margin-bottom:10px">Use Saved Endpoint</a>
                <a href="hermes://script" style="display:block;text-decoration:none;background:#0d1d18;color:#ffe6cb;padding:16px;border:1px solid #21362d;margin-bottom:10px">Show Tailscale Setup Notes</a>
              </div>
            </body></html>
        """.trimIndent()
        mainHandler.post { webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null) }
    }

    private fun connectSavedOrManual() {
        val lastBase = getSavedDashboardBase()
        if (!lastBase.isNullOrBlank()) {
            loadDashboardBase(lastBase, persist = false)
        } else {
            promptForManualEndpoint()
        }
    }

    private fun bootstrapDashboardConnection() {
        val candidates = DiscoveryPolicy.generateDashboardCandidates(
            tailnetSuffix = getDiscoveryTailnetSuffix(),
            hostNames = getDiscoveryHosts(),
            ports = getDiscoveryPorts(),
            explicitIpv4Addresses = getDiscoveryIpv4Addresses(),
            savedBase = getSavedDashboardBase(),
        )
        attemptedBases.clear()
        attemptedBases.addAll(candidates)
        if (candidates.isEmpty()) {
            renderStatusPage("No Tailnet discovery candidates configured yet. Add your tailnet suffix and Hermes hostnames first.", emptyList())
            promptForDiscoveryConfig()
            return
        }
        renderStatusPage("Scanning trusted Tailnet candidates for Hermes dashboards...", candidates)
        startupExecutor.execute {
            val verified = candidates.filter { isHermesDashboardBase(it) }
            mainHandler.post {
                when (verified.size) {
                    0 -> {
                        renderStatusPage("No Hermes dashboards found on configured Tailnet candidates. Check Tailscale ACLs, dashboard ports, and hostnames.", candidates)
                        renderConnectionHome()
                    }
                    1 -> loadDashboardBase(verified.first(), persist = true)
                    else -> renderDiscoveredDashboards(verified)
                }
            }
        }
    }

    private fun loadDashboardBase(base: String, persist: Boolean) {
        val normalizedBase = EndpointPolicy.normalizeDashboardBase(base)
        if (!EndpointPolicy.isAllowedDashboardBase(normalizedBase)) {
            renderStatusPage(
                "Blocked endpoint. This hardened build only allows Tailscale MagicDNS (*.ts.net) or Tailscale IPv4 (100.64.0.0/10) dashboard URLs.",
                listOf(normalizedBase.ifBlank { base }),
            )
            renderConnectionHome()
            return
        }
        if (persist) {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_LAST_DASHBOARD_BASE, normalizedBase)
                .apply()
        }
        showingConnectionHub = false
        val chatUrl = "$normalizedBase/chat"
        renderStatusPage("Opening Hermes dashboard...", listOf(chatUrl))
        startupExecutor.execute {
            warmupDashboard(normalizedBase)
            mainHandler.post { webView.loadUrl(chatUrl) }
        }
    }

    private fun warmupDashboard(base: String) {
        runCatching {
            val conn = (URL("$base/api/status").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 800
                readTimeout = 800
                instanceFollowRedirects = true
            }
            conn.inputStream.use { it.readNBytes(32) }
            conn.disconnect()
        }
    }

    private fun getSavedDashboardBase(): String? {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(PREF_LAST_DASHBOARD_BASE, null)?.trim()?.removeSuffix("/")
    }

    private fun getDiscoveryTailnetSuffix(): String {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_TAILNET_SUFFIX, "")
            .orEmpty()
    }

    private fun getDiscoveryHosts(): String {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_DISCOVERY_HOSTS, DEFAULT_DISCOVERY_HOSTS)
            .orEmpty()
    }

    private fun getDiscoveryPorts(): String {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_DISCOVERY_PORTS, DEFAULT_DISCOVERY_PORTS)
            .orEmpty()
    }

    private fun getDiscoveryIpv4Addresses(): String {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_DISCOVERY_IPV4, DEFAULT_DISCOVERY_IPV4)
            .orEmpty()
    }

    private fun saveDiscoveryConfig(tailnetSuffix: String, hosts: String, ports: String, ipv4Addresses: String) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_TAILNET_SUFFIX, DiscoveryPolicy.normalizeTailnetSuffix(tailnetSuffix))
            .putString(PREF_DISCOVERY_HOSTS, DiscoveryPolicy.parseHosts(hosts).ifBlank { DEFAULT_DISCOVERY_HOSTS })
            .putString(PREF_DISCOVERY_PORTS, DiscoveryPolicy.parsePorts(ports).joinToString(","))
            .putString(PREF_DISCOVERY_IPV4, DiscoveryPolicy.parseTailscaleIpv4Addresses(ipv4Addresses).joinToString(","))
            .apply()
    }

    private fun getSavedTextZoom(): Int {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(PREF_TEXT_ZOOM, DEFAULT_TEXT_ZOOM)
            .coerceIn(60, 160)
    }

    private fun isHermesDashboardBase(baseUrl: String): Boolean {
        val clean = EndpointPolicy.normalizeDashboardBase(baseUrl)
        if (!EndpointPolicy.isAllowedDashboardBase(clean)) return false
        val statusUrl = "$clean/api/status"
        return try {
            Log.d(LOG_TAG, "probe $statusUrl")
            val conn = (URL(statusUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 1200
                readTimeout = 1200
                instanceFollowRedirects = true
            }
            val code = conn.responseCode
            if (code != 200) return false
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            body.contains("\"version\"") && body.contains("\"gateway_running\"")
        } catch (_: Exception) {
            false
        }
    }

    private fun renderStatusPage(message: String, attempted: List<String>) {
        val attemptedHtml = if (attempted.isEmpty()) {
            "<li>No endpoints attempted yet.</li>"
        } else {
            attempted.joinToString("") { "<li>${htmlEscape(it)}/api/status</li>" }
        }
        val safeMessage = htmlEscape(message)
        val html = """
            <html><body style="background:#041c1c;color:#ffe6cb;font-family:monospace;padding:24px;line-height:1.45">
            <h3 style="margin:0 0 10px 0">Hermes Mobile Client</h3>
            <p style="margin:0 0 10px 0">$safeMessage</p>
            <p style="margin:0 0 6px 0">Attempted endpoints:</p>
            <ul style="margin:0 0 12px 0;padding-left:20px">$attemptedHtml</ul>
            <p style="margin:0">Ensure Hermes dashboard is reachable on port 9119 from this phone network, then relaunch app.</p>
            </body></html>
        """.trimIndent()
        mainHandler.post { webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null) }
    }

    private fun renderDiscoveredDashboards(verified: List<String>) {
        val links = verified.joinToString("") { base ->
            val encoded = URLEncoder.encode(base, "UTF-8")
            "<a href=\"hermes://connect?base=$encoded\" style=\"display:block;text-decoration:none;background:#0d1d18;color:#ffe6cb;padding:14px;border:1px solid #21362d;margin-bottom:10px\">${htmlEscape(base)}</a>"
        }
        val html = """
            <html><body style="margin:0;background:#041c1c;color:#ffe6cb;font-family:monospace">
              <div style="padding:28px">
                <div style="font-size:22px;letter-spacing:1px;margin-bottom:8px">DISCOVERED HERMES DASHBOARDS</div>
                <div style="font-size:13px;color:#89917e;margin-bottom:18px">Choose a trusted Tailnet dashboard</div>
                $links
                <a href="hermes://configure-discovery" style="display:block;text-decoration:none;background:#111;color:#ffe6cb;padding:14px;border:1px solid #21362d;margin-top:18px">Configure Discovery</a>
              </div>
            </body></html>
        """.trimIndent()
        webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    private fun promptForDiscoveryConfig() {
        mainHandler.post {
            val wrapper = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#041c1c"))
                setPadding(36, 28, 36, 20)
            }
            val suffix = EditText(this).apply {
                hint = "example.ts.net"
                setText(getDiscoveryTailnetSuffix())
                setTextColor(Color.parseColor("#ffe6cb"))
                setHintTextColor(Color.parseColor("#89917e"))
                setBackgroundColor(Color.parseColor("#0d1d18"))
                setPadding(28, 18, 28, 18)
            }
            val hosts = EditText(this).apply {
                hint = "devil,g4-dev,other-host"
                setText(getDiscoveryHosts())
                setTextColor(Color.parseColor("#ffe6cb"))
                setHintTextColor(Color.parseColor("#89917e"))
                setBackgroundColor(Color.parseColor("#0d1d18"))
                setPadding(28, 18, 28, 18)
            }
            val ports = EditText(this).apply {
                hint = "9119,9120,9121"
                setText(getDiscoveryPorts())
                setTextColor(Color.parseColor("#ffe6cb"))
                setHintTextColor(Color.parseColor("#89917e"))
                setBackgroundColor(Color.parseColor("#0d1d18"))
                setPadding(28, 18, 28, 18)
            }
            val ipv4 = EditText(this).apply {
                hint = "100.x.y.z,100.a.b.c"
                setText(getDiscoveryIpv4Addresses())
                setTextColor(Color.parseColor("#ffe6cb"))
                setHintTextColor(Color.parseColor("#89917e"))
                setBackgroundColor(Color.parseColor("#0d1d18"))
                setPadding(28, 18, 28, 18)
            }
            val help = TextView(this).apply {
                text = "Tailnet suffix must be your specific <tailnet>.ts.net, not bare ts.net. Hostnames are MagicDNS machine names. Ports cover multiple Hermes deployments per host. Optional IPv4 entries must be explicit Tailscale 100.64.0.0/10 addresses; the app never scans arbitrary LANs or CGNAT ranges."
                setTextColor(Color.parseColor("#89917e"))
                textSize = 12f
                setPadding(0, 0, 0, 14)
            }
            fun label(text: String) = TextView(this).apply {
                this.text = text
                setTextColor(Color.parseColor("#ffe6cb"))
                textSize = 13f
                setPadding(0, 14, 0, 6)
            }
            wrapper.addView(help)
            wrapper.addView(label("Tailnet suffix")); wrapper.addView(suffix)
            wrapper.addView(label("Hermes hostnames")); wrapper.addView(hosts)
            wrapper.addView(label("Dashboard ports")); wrapper.addView(ports)
            wrapper.addView(label("Explicit Tailscale IPv4 addresses (optional)")); wrapper.addView(ipv4)

            AlertDialog.Builder(this)
                .setTitle("Tailnet Discovery")
                .setView(wrapper)
                .setPositiveButton("Save & Scan") { _, _ ->
                    saveDiscoveryConfig(
                        tailnetSuffix = suffix.text?.toString().orEmpty(),
                        hosts = hosts.text?.toString().orEmpty(),
                        ports = ports.text?.toString().orEmpty(),
                        ipv4Addresses = ipv4.text?.toString().orEmpty(),
                    )
                    bootstrapDashboardConnection()
                }
                .setNegativeButton("Back") { _, _ -> renderConnectionHome() }
                .show()
        }
    }

    private fun promptForManualEndpoint() {
        mainHandler.post {
            val input = EditText(this).apply {
                hint = "http://host.tailnet.ts.net:9119"
                setText("http://")
                setTextColor(Color.parseColor("#ffe6cb"))
                setHintTextColor(Color.parseColor("#89917e"))
                setBackgroundColor(Color.parseColor("#0d1d18"))
                setPadding(28, 22, 28, 22)
            }
            val wrapper = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#041c1c"))
                setPadding(36, 28, 36, 20)
            }
            val title = TextView(this).apply {
                text = "TAILSCALE ENDPOINT"
                setTextColor(Color.parseColor("#ffe6cb"))
                textSize = 16f
                setPadding(0, 0, 0, 10)
            }
            val help = TextView(this).apply {
                text = "Enter a Tailscale dashboard URL (example: http://g4-dev.example.ts.net:9119 or http://100.x.y.z:9119)"
                setTextColor(Color.parseColor("#89917e"))
                textSize = 12f
                setPadding(0, 0, 0, 14)
            }
            wrapper.addView(title)
            wrapper.addView(help)
            wrapper.addView(input)

            AlertDialog.Builder(this)
                .setView(wrapper)
                .setCancelable(false)
                .setPositiveButton("Connect") { _, _ ->
                    hideKeyboard(input)
                    val raw = input.text?.toString()?.trim().orEmpty()
                    val base = EndpointPolicy.normalizeDashboardBase(raw)
                    if (base.isNotBlank()) {
                        attemptedBases += base
                        loadDashboardBase(base, persist = true)
                    } else {
                        renderStatusPage("Manual endpoint is empty.", attemptedBases.toList())
                        renderConnectionHome()
                    }
                }
                .setNegativeButton("Back") { _, _ -> renderConnectionHome() }
                .show()
        }
    }

    private fun hideKeyboard(input: EditText) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
    }


    private fun showVpsScriptDialog() {
        val script = """
            # Hardened Tailscale-only Hermes dashboard setup notes
            # 1. Install and authenticate Tailscale on this host.
            # 2. Find the host's Tailscale IP:
            tailscale ip -4
            # 3. Start Hermes dashboard bound to that Tailscale IP only:
            hermes dashboard --host <tailscale-ip> --port 9119 --no-open --insecure --tui
            # 4. Do NOT open public cloud/security-group/router access to TCP 9119.
            # 5. In Tailscale ACLs, allow only your approved phone/user/device to reach :9119.
            # Example mobile endpoint:
            #   http://host.tailnet.ts.net:9119
            #   http://100.x.y.z:9119
        """.trimIndent()

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#041c1c"))
            setPadding(28, 20, 28, 12)
        }
        val text = TextView(this).apply {
            setTextColor(Color.parseColor("#ffe6cb"))
            textSize = 11f
            this.text = script
        }
        scroll.addView(text)

        AlertDialog.Builder(this)
            .setTitle("Tailscale Setup Notes")
            .setView(scroll)
            .setPositiveButton("Copy") { _, _ ->
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("tailscale_setup_notes", script))
                renderStatusPage("Tailscale setup notes copied. Use only trusted Tailnet endpoints; never public :9119.", emptyList())
                renderConnectionHome()
            }
            .setNegativeButton("Back") { _, _ -> renderConnectionHome() }
            .show()
    }

    private fun handleInternalUrl(url: String): Boolean {
        if (!url.startsWith("hermes://")) return false
        val host = runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault("")
        when (host) {
            "discover" -> bootstrapDashboardConnection()
            "configure-discovery" -> promptForDiscoveryConfig()
            "connect" -> {
                val base = runCatching { URLDecoder.decode(Uri.parse(url).getQueryParameter("base").orEmpty(), "UTF-8") }.getOrDefault("")
                if (base.isNotBlank()) loadDashboardBase(base, persist = true)
            }
            "manual" -> promptForManualEndpoint()
            "saved" -> connectSavedOrManual()
            "script" -> showVpsScriptDialog()
            "menu" -> showHamburgerMenu()
            "textsize" -> showTextSizeDialog()
        }
        return true
    }

    private fun handleNavigationUrl(url: String, isMainFrame: Boolean): Boolean {
        if (handleInternalUrl(url)) return true
        if (isMainFrame && isBlockedMainFrameUrl(url)) {
            renderBlockedUrl(url)
            return true
        }
        return false
    }

    private fun isBlockedMainFrameUrl(url: String): Boolean {
        if (url.isBlank()) return false
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        return !EndpointPolicy.isAllowedDashboardBase(url)
    }

    private fun renderBlockedUrl(url: String) {
        renderStatusPage(
            "Blocked navigation. This hardened build only allows Tailscale MagicDNS (*.ts.net) or Tailscale IPv4 (100.64.0.0/10) dashboard URLs.",
            listOf(url),
        )
        renderConnectionHome()
    }

    private fun htmlEscape(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    private fun showHamburgerMenu() {
        AlertDialog.Builder(this)
            .setTitle("Menu")
            .setItems(arrayOf("Logout")) { _, which ->
                if (which == 0) logoutAndReset()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun logoutAndReset() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        webView.clearHistory()
        webView.clearCache(true)
        renderConnectionHome()
    }

    private fun showTextSizeDialog() {
        val current = getSavedTextZoom()
        val title = TextView(this).apply {
            text = "Text Size: $current%"
            setTextColor(Color.parseColor("#ffe6cb"))
            textSize = 14f
            setPadding(0, 0, 0, 16)
        }
        val slider = SeekBar(this).apply {
            max = 100
            progress = current - 60
        }
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#041c1c"))
            setPadding(36, 28, 36, 20)
            addView(title)
            addView(slider)
        }

        val applyZoom = { zoom: Int ->
            webView.settings.textZoom = zoom
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(PREF_TEXT_ZOOM, zoom)
                .apply()
            triggerTerminalRelayout(webView)
        }

        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            var changedDuringDrag = false
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val zoom = (progress + 60).coerceIn(60, 160)
                title.text = "Text Size: ${zoom}%"
                applyZoom(zoom)
                changedDuringDrag = true
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                changedDuringDrag = false
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (!changedDuringDrag) return
                val url = webView.url.orEmpty()
                if (!showingConnectionHub && (url.startsWith("http://") || url.startsWith("https://"))) {
                    // Fallback: xterm occasionally ignores synthetic resize after text zoom.
                    // Reload guarantees refit without requiring manual portrait<->landscape rotate.
                    webView.postDelayed({ webView.reload() }, 120)
                }
            }
        })

        AlertDialog.Builder(this)
            .setTitle("Terminal Text Size")
            .setView(wrapper)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun injectMobileChrome(view: WebView) {
        if (showingConnectionHub) return
        view.evaluateJavascript(
            """
            (function(){
              if(document.getElementById('hermes-mobile-client-style')) return;
              var style=document.createElement('style');
              style.id='hermes-mobile-client-style';
              style.textContent=[
                'html,body,#root{touch-action:pan-x pan-y;-webkit-overflow-scrolling:touch;}',
                'body,*{overscroll-behavior:auto;}',
                'input,textarea,select{font-size:16px!important;}',
                '#hermes-mobile-power{width:34px;height:34px;border-radius:999px;border:1px solid rgba(255,230,203,.45);background:rgba(4,28,28,.25);color:#ffe6cb;display:inline-flex;align-items:center;justify-content:center;text-decoration:none;backdrop-filter:blur(6px);-webkit-backdrop-filter:blur(6px);font-size:17px;line-height:1;box-sizing:border-box;}',
                '#hermes-mobile-power:hover,#hermes-mobile-power:active{background:rgba(255,215,94,.12);border-color:rgba(255,215,94,.75);color:#ffd75e;}',
                '#hermes-mobile-textsize{width:34px;height:34px;border-radius:999px;border:1px solid rgba(255,230,203,.45);background:rgba(4,28,28,.25);color:#ffe6cb;display:inline-flex;align-items:center;justify-content:center;text-decoration:none;backdrop-filter:blur(6px);-webkit-backdrop-filter:blur(6px);font-size:15px;line-height:1;box-sizing:border-box;}',
                '#hermes-mobile-textsize:hover,#hermes-mobile-textsize:active{background:rgba(255,215,94,.12);border-color:rgba(255,215,94,.75);color:#ffd75e;}'
              ].join('\n');
              document.head.appendChild(style);

              if(document.getElementById('hermes-mobile-power')) return;
              var b=document.createElement('a');
              b.id='hermes-mobile-power';
              b.href='hermes://menu';
              b.setAttribute('aria-label','Power');
              b.setAttribute('title','Power');
              b.innerHTML='<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 2v10"/><path d="M18.4 6.6a9 9 0 1 1-12.8 0"/></svg>';
              var z=document.createElement('a');
              z.id='hermes-mobile-textsize';
              z.href='hermes://textsize';
              z.setAttribute('aria-label','Text Size');
              z.setAttribute('title','Text Size');
              z.textContent='A';

              var walker=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT);
              var brandText=null;
              while(walker.nextNode()){
                var t=(walker.currentNode.nodeValue||'').replace(/\s+/g,' ').trim().toUpperCase();
                if(t.indexOf('HERMES')!==-1 && t.indexOf('AGENT')!==-1){
                  brandText=walker.currentNode.parentElement;
                  break;
                }
              }
              var host=brandText;
              while(host && host!==document.body){
                var r=host.getBoundingClientRect();
                if(r.width>60 && r.height>20) break;
                host=host.parentElement;
              }
              if(host && host!==document.body){
                host.style.display='flex';
                host.style.alignItems='center';
                host.style.justifyContent='space-between';
                host.style.gap='10px';
                var controls=document.createElement('div');
                controls.style.display='inline-flex';
                controls.style.gap='8px';
                controls.appendChild(z);
                controls.appendChild(b);
                host.appendChild(controls);
              }else{
                b.style.position='fixed';
                b.style.top='12px';
                b.style.left='12px';
                b.style.zIndex='99999';
                z.style.position='fixed';
                z.style.top='12px';
                z.style.left='54px';
                z.style.zIndex='99999';
                document.body.appendChild(z);
                document.body.appendChild(b);
              }
            })();
            """.trimIndent(),
            null,
        )
    }

    private fun sendHermesMobileText(text: String) {
        if (text.isEmpty()) return
        webView.post {
            val json = org.json.JSONObject.quote(text)
            webView.evaluateJavascript(
                "window.HermesMobileNativeInput&&window.HermesMobileNativeInput.text($json)",
                null,
            )
        }
    }

    private fun sendHermesMobileKey(key: String) {
        webView.post {
            val json = org.json.JSONObject.quote(key)
            webView.evaluateJavascript(
                "window.HermesMobileNativeInput&&window.HermesMobileNativeInput.key($json)",
                null,
            )
        }
    }

    private fun triggerTerminalRelayout(view: WebView) {
        view.postDelayed({
            view.evaluateJavascript(
                """
                (function(){
                  var root = document.documentElement;
                  var prevWidth = root.style.width;
                  // Force a measurable layout delta so xterm ResizeObserver refits columns.
                  root.style.width = 'calc(100% - 1px)';
                  setTimeout(function(){ root.style.width = prevWidth || ''; }, 90);
                  window.dispatchEvent(new Event('orientationchange'));
                  window.dispatchEvent(new Event('resize'));
                  if (window.visualViewport) {
                    window.visualViewport.dispatchEvent(new Event('resize'));
                  }
                })();
                """.trimIndent(),
                null,
            )
        }, 90)
        view.postDelayed({
            view.evaluateJavascript(
                """
                (function(){
                  window.dispatchEvent(new Event('orientationchange'));
                  window.dispatchEvent(new Event('resize'));
                  if (window.visualViewport) {
                    window.visualViewport.dispatchEvent(new Event('resize'));
                  }
                })();
                """.trimIndent(),
                null,
            )
        }, 260)
    }

    private fun injectMobileInputBridge(view: WebView) {
        if (showingConnectionHub) return
        view.evaluateJavascript(
            """
            (function(){
              if(window.HermesMobileNativeInput) return;

              function terminalTarget(){
                return document.querySelector('.xterm-helper-textarea')
                  || document.querySelector('.xterm textarea')
                  || document.querySelector('.xterm');
              }

              function focusTerminal(){
                var target=terminalTarget();
                if(target && target.focus) target.focus();
                return target;
              }

              function fire(target,type,init){
                var ev;
                try {
                  ev = new InputEvent(type, Object.assign({bubbles:true,cancelable:true,composed:true}, init || {}));
                } catch (_) {
                  ev = document.createEvent('Event');
                  ev.initEvent(type,true,true);
                  Object.assign(ev, init || {});
                }
                target.dispatchEvent(ev);
              }

              function keyEvent(target,type,key,code,keyCode,extra){
                var ev;
                var opts = Object.assign({
                  key:key,
                  code:code,
                  bubbles:true,
                  cancelable:true,
                  composed:true,
                  keyCode:keyCode,
                  which:keyCode
                }, extra || {});
                try {
                  ev = new KeyboardEvent(type, opts);
                } catch (_) {
                  ev = document.createEvent('KeyboardEvent');
                  ev.initKeyboardEvent(type,true,true,window,key,0,false,false,false,false);
                }
                target.dispatchEvent(ev);
              }

              function sendText(text){
                var target=focusTerminal();
                if(!target) return false;
                if(target.tagName === 'TEXTAREA' || target.tagName === 'INPUT'){
                  target.value = text;
                  fire(target,'beforeinput',{inputType:'insertText',data:text});
                  fire(target,'input',{inputType:'insertText',data:text});
                  target.value = '';
                  return true;
                }
                keyEvent(target,'keydown',text,'',text.charCodeAt(0));
                keyEvent(target,'keypress',text,'',text.charCodeAt(0));
                keyEvent(target,'keyup',text,'',text.charCodeAt(0));
                return true;
              }

              function sendKey(key){
                var target=focusTerminal();
                if(!target) return false;
                var spec={
                  backspace:['Backspace','Backspace',8,'deleteContentBackward'],
                  delete:['Delete','Delete',46,'deleteContentForward'],
                  enter:['Enter','Enter',13,'insertLineBreak'],
                  up:['ArrowUp','ArrowUp',38,null],
                  down:['ArrowDown','ArrowDown',40,null],
                  left:['ArrowLeft','ArrowLeft',37,null],
                  right:['ArrowRight','ArrowRight',39,null]
                }[key];
                if(!spec) return false;
                keyEvent(target,'keydown',spec[0],spec[1],spec[2]);
                if(spec[3]) fire(target,'beforeinput',{inputType:spec[3],data:null});
                if(spec[3]) fire(target,'input',{inputType:spec[3],data:null});
                keyEvent(target,'keyup',spec[0],spec[1],spec[2]);
                if(target.value !== undefined) target.value = '';
                return true;
              }

              function dispatchWheel(target,deltaY){
                if(!target) return false;
                var ev;
                try {
                  ev = new WheelEvent('wheel',{
                    deltaY:deltaY,
                    wheelDelta:-deltaY,
                    bubbles:true,
                    cancelable:true,
                    composed:true
                  });
                } catch (_) {
                  ev = document.createEvent('WheelEvent');
                  ev.initEvent('wheel',true,true);
                  ev.deltaY = deltaY;
                  ev.wheelDelta = -deltaY;
                }
                target.dispatchEvent(ev);
                return true;
              }

              function dispatchShiftScroll(target,deltaY){
                if(!target) return false;
                var down = deltaY > 0;
                var key = down ? 'ArrowDown' : 'ArrowUp';
                var code = down ? 'ArrowDown' : 'ArrowUp';
                var keyCode = down ? 40 : 38;
                var steps = Math.max(1, Math.min(8, Math.round(Math.abs(deltaY) / 24)));
                for(var i=0;i<steps;i++){
                  keyEvent(target,'keydown',key,code,keyCode,{shiftKey:true});
                  keyEvent(target,'keyup',key,code,keyCode,{shiftKey:true});
                }
                return true;
              }

              function scrollTerminal(deltaY){
                var targets=[
                  document.querySelector('.xterm-helper-textarea'),
                  document.querySelector('.xterm-screen'),
                  document.querySelector('.xterm-viewport'),
                  document.querySelector('.xterm')
                ].filter(Boolean);
                if(targets.length === 0) return false;
                targets.forEach(function(target){ dispatchWheel(target, deltaY); });
                dispatchShiftScroll(targets[0], deltaY);
                return true;
              }

              function installTerminalTouchScroll(){
                var root = document.querySelector('.xterm');
                if(!root || root.__hermesMobileTouchScrollBound) return !!root;
                root.__hermesMobileTouchScrollBound = true;
                var lastY = null;
                var debt = 0;
                root.addEventListener('touchstart', function(e){
                  if(!e.touches || e.touches.length !== 1) return;
                  lastY = e.touches[0].clientY;
                  debt = 0;
                }, {passive:true});
                root.addEventListener('touchmove', function(e){
                  if(!e.touches || e.touches.length !== 1 || lastY === null) return;
                  var y = e.touches[0].clientY;
                  debt += (lastY - y);
                  lastY = y;
                  if(Math.abs(debt) >= 18){
                    scrollTerminal(debt);
                    debt = 0;
                  }
                }, {passive:true});
                root.addEventListener('touchend', function(){ lastY = null; debt = 0; }, {passive:true});
                root.addEventListener('touchcancel', function(){ lastY = null; debt = 0; }, {passive:true});
                return true;
              }

              installTerminalTouchScroll();
              if(!window.__hermesMobileTerminalScrollObserver){
                window.__hermesMobileTerminalScrollObserver = new MutationObserver(function(){
                  installTerminalTouchScroll();
                });
                window.__hermesMobileTerminalScrollObserver.observe(document.documentElement, {
                  childList:true,
                  subtree:true
                });
              }
              setTimeout(installTerminalTouchScroll, 300);
              setTimeout(installTerminalTouchScroll, 1000);

              window.HermesMobileNativeInput = {
                text: sendText,
                key: sendKey
              };
            })();
            """.trimIndent(),
            null,
        )
    }

}

class HermesWebView(context: Context) : WebView(context) {
    interface MobileInputSink {
        fun sendText(text: String)
        fun sendKey(key: String)
    }

    var mobileInputSink: MobileInputSink? = null

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN

        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val value = text?.toString().orEmpty()
                if (value.isNotEmpty()) mobileInputSink?.sendText(value)
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                val count = beforeLength.coerceAtLeast(1)
                repeat(count) { mobileInputSink?.sendKey("backspace") }
                return true
            }

            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
                return deleteSurroundingText(beforeLength, afterLength)
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action != KeyEvent.ACTION_DOWN) return true
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DEL -> mobileInputSink?.sendKey("backspace")
                    KeyEvent.KEYCODE_FORWARD_DEL -> mobileInputSink?.sendKey("delete")
                    KeyEvent.KEYCODE_ENTER -> mobileInputSink?.sendKey("enter")
                    KeyEvent.KEYCODE_DPAD_UP -> mobileInputSink?.sendKey("up")
                    KeyEvent.KEYCODE_DPAD_DOWN -> mobileInputSink?.sendKey("down")
                    KeyEvent.KEYCODE_DPAD_LEFT -> mobileInputSink?.sendKey("left")
                    KeyEvent.KEYCODE_DPAD_RIGHT -> mobileInputSink?.sendKey("right")
                    else -> event.unicodeChar.takeIf { it > 0 }?.let { mobileInputSink?.sendText(it.toChar().toString()) }
                }
                return true
            }

            override fun performEditorAction(editorAction: Int): Boolean {
                mobileInputSink?.sendKey("enter")
                return true
            }
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean = super.dispatchTouchEvent(event)
}
