package dev.hermes.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointPolicyTest {
    @Test
    fun normalizesBareTailscaleMagicDnsHostToHttpBase() {
        assertEquals(
            "http://devil.example.ts.net:9119",
            EndpointPolicy.normalizeDashboardBase("devil.example.ts.net:9119/chat"),
        )
    }

    @Test
    fun allowsTailscaleIpv4Range() {
        assertTrue(EndpointPolicy.isAllowedDashboardBase("http://100.64.0.1:9119"))
        assertTrue(EndpointPolicy.isAllowedDashboardBase("http://100.127.255.254:9119"))
    }

    @Test
    fun rejectsNonTailscalePublicAndLanAddresses() {
        assertFalse(EndpointPolicy.isAllowedDashboardBase("http://203.0.113.10:9119"))
        assertFalse(EndpointPolicy.isAllowedDashboardBase("http://192.168.1.25:9119"))
        assertFalse(EndpointPolicy.isAllowedDashboardBase("http://10.0.0.15:9119"))
    }

    @Test
    fun rejectsArbitraryDomainsAndUserInfo() {
        assertFalse(EndpointPolicy.isAllowedDashboardBase("https://example.com:9119"))
        assertFalse(EndpointPolicy.isAllowedDashboardBase("http://user:pass@devil.example.ts.net:9119"))
    }

    @Test
    fun allowsTailscaleMagicDnsHosts() {
        assertTrue(EndpointPolicy.isAllowedDashboardBase("http://devil.tailnet-name.ts.net:9119"))
        assertTrue(EndpointPolicy.isAllowedDashboardBase("https://g4-dev.tailnet-name.ts.net:9119"))
    }

    @Test
    fun rejectsTailscaleApexAndMalformedIpv4LikeHosts() {
        assertFalse(EndpointPolicy.isAllowedDashboardBase("https://ts.net:9119"))
        assertFalse(EndpointPolicy.isAllowedDashboardBase("http://100.64.evil:9119"))
        assertFalse(EndpointPolicy.isAllowedDashboardBase("http://100.128.0.1:9119"))
    }
}
