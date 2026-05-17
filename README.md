# Hermes Agent Mobile Client — Hardened Tailscale Fork

This fork customizes the Android Hermes mobile client for a **trusted Tailscale-only** deployment model.

It is a thin Android WebView client for a running Hermes dashboard. The app is intentionally constrained so it can be used to access selected Hermes Agent deployments on your Tailnet without exposing dashboard control surfaces to the public internet.

## Security posture

This fork is designed around these rules:

- Do **not** expose Hermes dashboard port `9119` publicly.
- Do **not** use public VPS IPs, public DNS names, or arbitrary domains as mobile endpoints.
- Use explicit Tailscale MagicDNS names ending in `.ts.net` or Tailscale IPv4 addresses in `100.64.0.0/10`.
- Disable same-Wi-Fi/LAN auto-discovery.
- Allow Tailnet-only auto-discovery from configured MagicDNS hostnames/ports and local Tailscale IPv4 candidates.
- Use Tailscale ACLs so only approved phone/user/device identities can reach Hermes dashboard hosts.

## Hardening changes in this fork

- Removed `RECORD_AUDIO` permission.
- Disabled Android app backup with `android:allowBackup="false"`.
- Changed WebView mixed content policy to `MIXED_CONTENT_NEVER_ALLOW`.
- Added `EndpointPolicy` allowlist logic:
  - Allows `*.ts.net` MagicDNS hostnames.
  - Allows Tailscale IPv4 range `100.64.0.0/10`.
  - Rejects public IPs, LAN IPs, arbitrary domains, and URLs with userinfo.
- Disabled same-Wi-Fi/LAN auto-discovery in the UI flow.
- Replaced public VPS setup guidance with Tailscale-only setup notes.
- Rewrote `scripts/setup-vps-dashboard.sh` to bind Hermes dashboard to the host's Tailscale IP and avoid public firewall exposure.
- Removed stale prebuilt APK artifacts from the fork. Build and sign a fresh APK from this source before installation.

## Tailnet auto-discovery

This fork now includes Tailnet-only auto-discovery. It does **not** perform broad Wi-Fi/LAN probing or mDNS discovery. Instead, it builds a candidate list from trusted Tailnet inputs, probes `/api/status`, and only offers endpoints that still pass the same `EndpointPolicy` allowlist.

Discovery inputs:

- Tailnet suffix, for example `<tailnet>.ts.net`.
- Hermes hostnames, for example `devil,g4-dev,dev01`.
- Dashboard ports, for example `9119,9120,9121,9122,9123` for multiple deployments per host.
- Optional explicit Tailscale IPv4 addresses, if you want to enter `100.x.y.z` endpoints directly.
- Previously saved endpoint, if it is still a valid Tailnet endpoint.

The app does not infer a `/24` from the phone and does not sweep CGNAT/LAN ranges. Every probed candidate comes from the saved endpoint, configured MagicDNS hostnames, or explicit `100.64.0.0/10` IPs you entered.

In the app, tap **Configure Tailnet Discovery**, enter the suffix/hosts/ports once, then tap **Tailnet Auto Discover**. Multiple verified dashboards are shown in a picker.

## Recommended Hermes dashboard setup

On each Hermes host, install and authenticate Tailscale, then find the Tailscale IP:

```bash
tailscale ip -4
```

Start Hermes dashboard bound to that Tailscale IP:

```bash
hermes dashboard --host <tailscale-ip> --port 9119 --no-open --insecure --tui
```

Mobile endpoint examples accepted by this app:

```text
http://devil.<tailnet>.ts.net:9119
http://g4-dev.<tailnet>.ts.net:9119
http://100.x.y.z:9119
```

Avoid:

```text
http://public-vps-ip:9119
http://192.168.x.y:9119
http://10.x.y.z:9119
https://random-domain.example
```

## Persistent Linux host setup

For Linux/VPS hosts that already have Hermes and Tailscale configured:

```bash
cd scripts
chmod +x setup-vps-dashboard.sh
./setup-vps-dashboard.sh
```

The script:

- detects the host's Tailscale IPv4 address,
- refuses to bind to non-Tailscale addresses,
- creates a systemd service for Hermes dashboard,
- binds to the Tailscale IP only,
- allows UFW only from `100.64.0.0/10` if UFW exists,
- prints a Tailnet endpoint for the phone.

Still verify cloud/security-group/router firewalls do **not** expose `9119` publicly.

## Tailscale ACL guidance

Prefer tagging Hermes hosts and allowing only your phone/user/device to reach port `9119`.

Conceptual ACL pattern:

```json
{
  "action": "accept",
  "src": ["user:you@example.com"],
  "dst": ["tag:hermes:9119"]
}
```

Use your real Tailnet users/tags. Do not allow the whole internet or broad untrusted groups to dashboard ports.

## Build

Requires Android Studio or a working Android SDK/JDK 17 environment.

```bash
cd android
./gradlew test
./gradlew assembleDebug
```

Output:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

For a release APK, configure your own private signing key and build/sign from this fork. Do not reuse or trust old prebuilt APK artifacts from upstream for the hardened fork.

## Current limitations

- The app stores a saved endpoint and Tailnet discovery settings. A future iteration could add friendly deployment names/icons for endpoints like `devil`, `g4-Dev`, etc.
- The app still uses HTTP inside the Tailnet. Tailscale encrypts transport, but app-layer auth/TLS would be stronger if Hermes dashboard supports it.
- This fork assumes Tailscale network identity and ACLs are the primary access control. Treat dashboard access as trusted-tailnet-only unless Hermes dashboard auth is enabled.

## Original upstream

Forked from:

```text
https://github.com/areu01or00/Hermes-Agent-Mobile-Client
```
