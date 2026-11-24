package com.danieldk.splatevpn.vpn

import com.danieldk.splatevpn.data.TorSettings
import com.danieldk.splatevpn.data.TorConfigParser
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class TorBridgeDownloadTest {
    @Test fun legacySettingsDefaultToLatencyAndSpeedSelectionSurvivesSerialization() {
        val gson = Gson()
        assertEquals("latency", TorConfigParser.validate(gson.fromJson("{}", TorSettings::class.java)).bridgeSelectionMetric)
        val saved = TorSettings(bridgeSelectionMetric = "speed")
        assertEquals("speed", TorConfigParser.validate(gson.fromJson(gson.toJson(saved), TorSettings::class.java)).bridgeSelectionMetric)
        assertEquals("latency", TorConfigParser.validate(gson.fromJson("{\"bridgeSelectionMetric\":null}", TorSettings::class.java)).bridgeSelectionMetric)
    }

    @Test fun aSpeedMeasurementRequiresRealDataRatherThanHtmlOrTinyErrorBodies() = runBlocking {
        fun client(type: String, size: Int, code: Int = 200) = OkHttpClient.Builder().addInterceptor {
            Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                .header("Content-Type", type).body(ByteArray(size).toResponseBody()).build()
        }.build()
        assertNull(measureTorDownload(client("text/html", 512 * 1024)))
        assertNull(measureTorDownload(client("application/octet-stream", 100)))
        assertNull(measureTorDownload(client("application/octet-stream", 512 * 1024, 403)))
        assertTrue(requireNotNull(measureTorDownload(client("application/octet-stream", 512 * 1024))) > 0)
    }
}
