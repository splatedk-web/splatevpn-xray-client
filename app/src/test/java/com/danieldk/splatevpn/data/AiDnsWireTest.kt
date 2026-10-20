package com.danieldk.splatevpn.data

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class AiDnsWireTest {
    private val query = AiDnsWire.query("gemini.google.com")
    private fun answer(ip: ByteArray = byteArrayOf(8, 8, 8, 8)) = query.copyOf().apply { this[2] = 0x81.toByte(); this[3] = 0x80.toByte(); this[7] = 1 } +
        byteArrayOf(0xc0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4) + ip
    private fun rejects(data: ByteArray) { try { AiDnsWire.addresses(data, query); fail("Invalid DNS accepted") } catch (_: IOException) {} }
    @Test fun matchingAnswerReturnsPublicIpv4() { assertEquals(listOf("8.8.8.8"), AiDnsWire.addresses(answer(), query)) }
    @Test fun nxdomainTruncationAndWrongTransactionAreRejected() {
        rejects(answer().apply { this[3] = 0x83.toByte() }); rejects(answer().apply { this[2] = 0x83.toByte() })
        rejects(answer().apply { this[0] = (this[0].toInt() xor 1).toByte() }); rejects(answer().dropLast(1).toByteArray())
    }
    @Test fun emptyOrUnrelatedQuestionIsRejected() {
        rejects(query.copyOf().apply { this[2] = 0x81.toByte(); this[3] = 0x80.toByte() })
        rejects(answer().apply { this[13] = 'x'.code.toByte() })
    }
    @Test fun privateBlockingAndFakeDnsAnswersAreRejected() {
        listOf(byteArrayOf(0,0,0,0),byteArrayOf(127,0,0,1),byteArrayOf(10,0,0,1),byteArrayOf(192.toByte(),168.toByte(),1,1),
            byteArrayOf(198.toByte(),18,0,1), byteArrayOf(100,64,0,1)).forEach { rejects(answer(it)) }
    }
    @Test fun malformedCompressionAndOversizeResponseAreRejected() {
        rejects(answer().apply { this[query.size] = 0xff.toByte(); this[query.size+1] = 0xff.toByte() })
        rejects(answer() + ByteArray(65536))
    }
}
