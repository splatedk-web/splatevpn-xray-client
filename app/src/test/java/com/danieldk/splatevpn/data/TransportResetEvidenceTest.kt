package com.danieldk.splatevpn.data

import org.junit.Assert.*
import org.junit.Test

class TransportResetEvidenceTest {
    @Test fun ordinaryStreamClosuresDoNotAccuseTheNetwork() {
        listOf("proxy/vless/outbound: write tcp 127.0.0.1:10808->127.0.0.1:45110: write: broken pipe",
            "app/dns: failed to retrieve response > io: read/write on closed pipe",
            "transport/internet/udp: failed to handle UDP input > closed pipe",
            "proxy/freedom: read: connection reset by peer",
            "proxy/vless/outbound: read tcp 127.0.0.1:10808->127.0.0.1:12345: connection reset by peer")
            .forEach { assertFalse(it, isRelevantProxyReset(it)) }
    }
    @Test fun remoteProxyResetIsOnlyEvidenceRequiringAProbe() {
        assertTrue(isRelevantProxyReset("proxy/vless/outbound: read tcp 10.0.0.1:1234->192.0.2.1:443: connection reset by peer"))
    }
}
