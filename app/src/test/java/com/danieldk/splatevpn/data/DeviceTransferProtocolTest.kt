package com.danieldk.splatevpn.data

import org.junit.Assert.*
import org.junit.Test

class DeviceTransferProtocolTest {
    private val key = "a".repeat(43)
    private val token = "b".repeat(43)
    private val endpoint = "http://192.168.1.25:12345/transfer/$token"

    @Test fun pairingRoundTripKeepsEncryptionKeyOutOfHttpUrl() {
        for (direction in DeviceTransferDirection.entries) {
            val invitation = DeviceTransferInvitation(endpoint, key, direction)
            val encoded = invitation.encode()
            assertTrue(DeviceTransferProtocol.isInvitation(encoded))
            assertEquals(invitation, DeviceTransferProtocol.parseInvitation(encoded))
            assertFalse(invitation.url.contains(key))
            assertEquals(key, java.net.URI(encoded).fragment)
        }
    }

    @Test fun rejectsPublicDnsCredentialsAndUnrelatedRequestTargets() {
        val bad = listOf("http://example.com:12345/transfer/$token", "http://127.0.0.1:12345/transfer/$token",
            "http://8.8.8.8:12345/transfer/$token", "http://169.254.169.254:12345/transfer/$token",
            "http://user:pass@192.168.1.25:12345/transfer/$token", "http://192.168.1.25:12345/admin",
            "http://192.168.1.25:12345/transfer/$token?next=admin", "https://192.168.1.25:12345/transfer/$token")
        bad.forEach { url -> assertThrows(Exception::class.java) { DeviceTransferProtocol.parseInvitation(DeviceTransferInvitation(url, key, DeviceTransferDirection.DOWNLOAD).encode()) } }
        assertThrows(Exception::class.java) { DeviceTransferProtocol.parseInvitation(DeviceTransferInvitation(endpoint, "short", DeviceTransferDirection.UPLOAD).encode()) }
    }

    @Test fun acceptsOnlyCanonicalPrivateIpv4Ranges() {
        listOf("10.0.2.15", "172.16.0.1", "172.31.255.254", "192.168.0.1").forEach { assertTrue(it, DeviceTransferProtocol.isPrivateIpv4(it)) }
        listOf("172.15.0.1", "172.32.0.1", "192.169.0.1", "010.0.0.1", "10.0.0.999", "10.0.0", "localhost").forEach { assertFalse(it, DeviceTransferProtocol.isPrivateIpv4(it)) }
    }

    @Test fun invitationGateExpiresAndCannotBeReplayed() {
        val gate = DeviceTransferGate(token, 10_000)
        assertFalse(gate.claim("wrong", 9_000))
        assertTrue(gate.claim(token, 9_001))
        assertFalse(gate.claim(token, 9_002))
        assertTrue(gate.finished(9_003))
        assertFalse(DeviceTransferGate(token, 10_000).claim(token, 10_000))
    }

    @Test fun failedAttemptsClosePairingWithoutConsumingAValidTransferEarly() {
        val gate = DeviceTransferGate(token, 10_000)
        repeat(12) { assertFalse(gate.claim("invalid-$it", 1)) }
        assertTrue(gate.finished(1))
        assertFalse(gate.claim(token, 2))
    }

    @Test fun networkTransfersRejectUnencryptedBackupsAndAuthenticateCiphertext() {
        assertThrows(Exception::class.java) { DeviceTransferProtocol.requireEncrypted("""{"app":"SplateVPN"}""") }
        val password = "a-long-pairing-password".toCharArray()
        val document = BackupCipher.encrypt("""{"secret":"proxy-password"}""", password)
        DeviceTransferProtocol.requireEncrypted(document)
        assertFalse(document.contains("proxy-password"))
        assertThrows(Exception::class.java) { BackupCipher.decryptIfNeeded(document, "another-password".toCharArray()) }
        assertEquals("""{"secret":"proxy-password"}""", BackupCipher.decryptIfNeeded(document, password))
    }
}
