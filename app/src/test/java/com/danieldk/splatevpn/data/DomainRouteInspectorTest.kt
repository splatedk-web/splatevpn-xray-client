package com.danieldk.splatevpn.data

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class DomainRouteInspectorTest {
    @Test fun respectsDomainBoundariesAndOrderedCustomRules() {
        val root = Files.createTempDirectory("route-inspector").toFile()
        try {
            val rules = """[{"outboundTag":"proxy","domain":["full:example.com"]},{"outboundTag":"direct","domain":["domain:example.com"]}]"""
            assertEquals("proxy", DomainRouteInspector.inspect("example.com", rules, root)?.outbound)
            assertEquals("direct", DomainRouteInspector.inspect("sub.example.com", rules, root)?.outbound)
            assertNull(DomainRouteInspector.inspect("notexample.com", rules, root))
        } finally { root.deleteRecursively() }
    }
    @Test fun readsCompiledGeositeAndSkipsUdpOnlyRules() {
        val root = Files.createTempDirectory("route-inspector").toFile()
        try {
            java.io.File(root, "splate-ads.dat").writeBytes(AdFilterCompiler.encode(listOf("ad.example.com")))
            val rules = """[{"network":"udp","outboundTag":"proxy","domain":["domain:example.com"]},{"outboundTag":"block","domain":["ext:splate-ads.dat:ads"]}]"""
            assertEquals("block", DomainRouteInspector.inspect("ad.example.com", rules, root)?.outbound)
            assertNull(DomainRouteInspector.inspect("example.com", rules, root))
        } finally { root.deleteRecursively() }
    }
}
