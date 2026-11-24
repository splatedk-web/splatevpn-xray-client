package com.danieldk.splatevpn.data

import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class SmartDnsBootstrapTest {
    @Test fun physicalBootstrapCannotImportLocalFakeDnsOrCarrierNatAddresses() {
        val values = listOf("1.1.1.1", "127.0.0.1", "192.168.1.1", "10.0.0.1", "169.254.0.1", "198.18.0.1", "100.64.1.2", "0.0.0.0", "224.0.0.1", "::1", "1.1.1.1", "8.8.8.8")
        assertEquals(listOf("1.1.1.1", "8.8.8.8"), SmartDnsBootstrap.publicIpv4(values.map(InetAddress::getByName)))
    }
}
