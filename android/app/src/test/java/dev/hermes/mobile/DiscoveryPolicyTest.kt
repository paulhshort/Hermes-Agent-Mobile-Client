package dev.hermes.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryPolicyTest {
    @Test
    fun buildsMagicDnsCandidatesFromConfiguredTailnetHostsAndPorts() {
        val candidates = DiscoveryPolicy.generateDashboardCandidates(
            tailnetSuffix = "example.ts.net",
            hostNames = "devil, g4-dev",
            ports = "9119,9120",
            explicitIpv4Addresses = "",
            savedBase = null,
        )

        assertEquals(
            listOf(
                "http://devil.example.ts.net:9119",
                "http://devil.example.ts.net:9120",
                "http://g4-dev.example.ts.net:9119",
                "http://g4-dev.example.ts.net:9120",
            ),
            candidates,
        )
    }

    @Test
    fun includesSavedEndpointFirstWhenAllowed() {
        val candidates = DiscoveryPolicy.generateDashboardCandidates(
            tailnetSuffix = "example.ts.net",
            hostNames = "devil",
            ports = "9119",
            explicitIpv4Addresses = "",
            savedBase = "http://100.64.2.3:9119/chat",
        )

        assertEquals("http://100.64.2.3:9119", candidates.first())
        assertTrue(candidates.contains("http://devil.example.ts.net:9119"))
    }

    @Test
    fun rejectsInvalidPortsAndNonTailscaleSavedEndpoints() {
        val candidates = DiscoveryPolicy.generateDashboardCandidates(
            tailnetSuffix = "example.ts.net",
            hostNames = "devil",
            ports = "9119,0,abc,70000",
            explicitIpv4Addresses = "",
            savedBase = "http://192.168.1.10:9119",
        )

        assertEquals(listOf("http://devil.example.ts.net:9119"), candidates)
        assertFalse(candidates.contains("http://192.168.1.10:9119"))
    }

    @Test
    fun includesOnlyExplicitTailscaleIpv4CandidatesWithoutNeighborhoodScan() {
        val candidates = DiscoveryPolicy.generateDashboardCandidates(
            tailnetSuffix = "",
            hostNames = "",
            ports = "9119,9120",
            explicitIpv4Addresses = "100.88.12.34,192.168.1.10",
            savedBase = null,
        )

        assertEquals(
            listOf("http://100.88.12.34:9119", "http://100.88.12.34:9120"),
            candidates,
        )
        assertFalse(candidates.contains("http://100.88.12.1:9119"))
        assertFalse(candidates.contains("http://192.168.1.10:9119"))
    }

    @Test
    fun rejectsBareTsNetTailnetSuffix() {
        val candidates = DiscoveryPolicy.generateDashboardCandidates(
            tailnetSuffix = "ts.net",
            hostNames = "devil",
            ports = "9119",
            explicitIpv4Addresses = "",
            savedBase = null,
        )

        assertEquals(emptyList<String>(), candidates)
    }

    @Test
    fun rejectsHostnamesWithUriMetacharacters() {
        val candidates = DiscoveryPolicy.generateDashboardCandidates(
            tailnetSuffix = "example.ts.net",
            hostNames = "devil,evil@devil,bad/path,with:colon,with?query,with#fragment",
            ports = "9119",
            explicitIpv4Addresses = "",
            savedBase = null,
        )

        assertEquals(listOf("http://devil.example.ts.net:9119"), candidates)
    }

    @Test
    fun filtersExplicitIpv4AddressesToTailscaleOnly() {
        assertEquals(
            listOf("100.64.1.2", "100.127.255.254"),
            DiscoveryPolicy.parseTailscaleIpv4Addresses("100.64.1.2,192.168.1.10,8.8.8.8,100.127.255.254,100.128.0.1"),
        )
    }

}
