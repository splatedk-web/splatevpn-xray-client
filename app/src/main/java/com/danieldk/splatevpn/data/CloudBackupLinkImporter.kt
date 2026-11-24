package com.danieldk.splatevpn.data

import android.content.Context
import com.danieldk.splatevpn.vpn.LocalProxyAccess
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class CloudLinkState(val busy: Boolean = false, val needsPassword: Boolean = false, val error: String? = null)

@Singleton
class CloudBackupLinkImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsManager,
    private val transfer: DeviceTransferManager
) {
    private val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()
    private val gate = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var expiry: Job? = null
    private var cached: Triple<String, String, String>? = null
    private val _state = MutableStateFlow(CloudLinkState())
    val state = _state.asStateFlow()

    suspend fun load(raw: String, password: CharArray): Result<TransferPreview> = withContext(Dispatchers.IO) { gate.withLock {
        _state.value = CloudLinkState(busy = true)
        try {
            val key = CloudBackupLinks.https(raw).toString()
            val document = cached?.takeIf { it.first == key } ?: NetworkAccessPolicy.withUpdates(context) {
                val network = if (settings.isSourceDownloadsViaVpn()) LocalProxyAccess.client(client) else client
                val (text, source) = CloudBackupLinks.load(raw) { url, limit -> fetch(network, url, limit) }
                Triple(key, text, source).also { downloaded ->
                    cached = downloaded; expiry?.cancel()
                    expiry = scope.launch { delay(300_000); gate.withLock { if (cached === downloaded) cached = null } }
                }
            }
            val encrypted = JsonParser.parseString(document.second).asJsonObject["app"]?.asString == "SplateVPN-Encrypted"
            if (encrypted && password.isEmpty()) {
                _state.value = CloudLinkState(needsPassword = true, error = "Копия загружена. Введите пароль её шифрования.")
                return@withLock Result.failure(IllegalArgumentException("Копия загружена. Введите пароль её шифрования."))
            }
            val result = transfer.stageDocument(document.second, password, document.third, allowPlaintext = true)
            _state.value = CloudLinkState(needsPassword = encrypted && result.isFailure, error = result.exceptionOrNull()?.message)
            if (result.isSuccess) { cached = null; expiry?.cancel(); expiry = null }
            result
        } catch (error: CancellationException) { throw error
        } catch (error: Exception) {
            _state.value = CloudLinkState(error = error.message ?: "Не удалось загрузить резервную копию")
            Result.failure(error)
        } finally { _state.value = _state.value.copy(busy = false) }
    } }

    private suspend fun fetch(client: OkHttpClient, url: String, limit: Int): BackupLinkResponse = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(url).header("User-Agent", "SplateVPN-Backup/1.0").get().build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) { continuation.resumeWithException(error) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        if (it.code in setOf(301, 302, 303, 307, 308)) BackupLinkResponse(it.code, "", it.header("Location"))
                        else {
                            val body = requireNotNull(it.body)
                            require(body.contentLength() <= limit) { "Файл копии превышает допустимый размер" }
                            BackupLinkResponse(it.code, body.byteStream().use { stream -> stream.readLimited(limit).toString(Charsets.UTF_8) })
                        }
                    }
                    continuation.resume(result)
                } catch (error: Exception) { continuation.resumeWithException(error) }
            }
        })
    }
}
