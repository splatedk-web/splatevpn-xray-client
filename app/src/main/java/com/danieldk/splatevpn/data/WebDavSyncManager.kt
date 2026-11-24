package com.danieldk.splatevpn.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.security.KeyStore
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class WebDavSettings(val endpoint: String = "", val username: String = "", val password: String = "")
data class WebDavSyncState(val busy: Boolean = false, val error: String? = null, val completed: Boolean = false)

internal object WebDavProtocol {
    const val FILE_NAME = "splate-settings.v1.json"
    fun documentUrl(endpoint: String): String {
        val url = endpoint.trim().toHttpUrl()
        require(url.isHttps && url.host.isNotBlank() && url.username.isEmpty() && url.password.isEmpty() && url.fragment == null && url.query == null) {
            "Use an HTTPS WebDAV folder URL without credentials, query or fragment"
        }
        return url.newBuilder().apply {
            if (!url.encodedPath.endsWith('/')) addPathSegment("")
            addPathSegment(FILE_NAME)
        }.build().toString()
    }
    fun snapshotUrl(endpoint: String, name: String): String {
        require(name.matches(Regex("splate-[a-f0-9]{12}-[0-9]{1,19}-[a-f0-9]{8}\\.json")))
        return documentUrl(endpoint).toHttpUrl().newBuilder().setPathSegment(
            documentUrl(endpoint).toHttpUrl().pathSegments.lastIndex, name).build().toString()
    }
}

/** Explicit encrypted snapshot push/pull. WebDAV credentials stay in a device-bound private store. */
@Singleton
class WebDavSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backup: BackupManager,
    private val transfer: DeviceTransferManager,
    private val settingsManager: SettingsManager
) {
    private val operation = Mutex()
    private val credentialGate = Mutex()
    private val _settings = MutableStateFlow(WebDavSettings())
    val settings = _settings.asStateFlow()
    private val _state = MutableStateFlow(WebDavSyncState())
    val state = _state.asStateFlow()
    private val store = AtomicFile(File(context.noBackupFilesDir, "webdav-credentials.json"))
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .proxy(java.net.Proxy.NO_PROXY)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()
    private var loaded = false
    private val revisions = DeviceSecretStore(context, "splate-webdav-revisions-v1")
    private fun metadata(): JsonObject = revisions.read()?.let { JsonParser.parseString(it).asJsonObject } ?: JsonObject()
    private fun revisionKey(url: String) = java.security.MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
        .joinToString("") { "%02x".format(it) }
    private fun networkClient() = if (settingsManager.isSourceDownloadsViaVpn())
        com.danieldk.splatevpn.vpn.LocalProxyAccess.client(client) else client
    private fun rememberRevision(url: String, etag: String?) {
        val data = metadata()
        if (etag.isNullOrBlank() || etag.startsWith("W/")) data.remove(revisionKey(url))
        else data.addProperty(revisionKey(url), etag)
        revisions.write(data.toString())
    }
    private suspend fun <T> sendRequest(client: OkHttpClient, request: Request, consume: (okhttp3.Response) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, error: java.io.IOException) { continuation.resumeWithException(error) }
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    try { continuation.resume(response.use(consume)) }
                    catch (error: Exception) { continuation.resumeWithException(error) }
                }
            })
        }
    private suspend fun send(client: OkHttpClient, value: Request) = sendRequest(client, value) { WebDavReply(it.code, it.header("ETag")) }

    init { scope.launch { load() } }

    suspend fun load(): WebDavSettings = withContext(Dispatchers.IO) { credentialGate.withLock {
        if (!loaded) {
            loaded = true
            try {
                if (store.baseFile.exists() || File(store.baseFile.path + ".bak").exists()) {
                    val encoded = store.openRead().use { it.readLimited(64 * 1024).toString(Charsets.UTF_8) }
                    _settings.value = gson.fromJson(decryptCredentials(encoded), WebDavSettings::class.java)
                }
            } catch (_: Exception) { _state.value = WebDavSyncState(error = "Saved WebDAV credentials could not be read. Enter and save them again.") }
        }
        _settings.value
    } }

    suspend fun saveSettings(value: WebDavSettings): Result<Unit> = withContext(Dispatchers.IO) { credentialGate.withLock {
        try {
            require(value.username.length <= 1024 && value.password.length <= 4096 && value.endpoint.length <= 2048) { "WebDAV settings are too long" }
            if (value.endpoint.isNotBlank()) WebDavProtocol.documentUrl(value.endpoint)
            val safe = value.copy(endpoint = value.endpoint.trim())
            val encrypted = encryptCredentials(gson.toJson(safe)).toByteArray(Charsets.UTF_8)
            val output = store.startWrite()
            try { output.write(encrypted); store.finishWrite(output) }
            catch (error: Exception) { store.failWrite(output); throw error }
            loaded = true; _settings.value = safe; _state.value = WebDavSyncState()
            Result.success(Unit)
        } catch (error: CancellationException) { throw error
        } catch (error: Exception) { Result.failure(error) }
    } }

    suspend fun upload(password: CharArray): Result<Unit> = execute {
        val credentials = load()
        val url = WebDavProtocol.documentUrl(credentials.endpoint)
        val document = backup.exportDocument(password).getOrThrow()
        val request = authenticated(Request.Builder().url(url), credentials)
            .put(document.toRequestBody("application/json".toMediaType())).build()
        val response = send(networkClient(), request)
        require(response.successful) { "WebDAV upload failed (HTTP ${response.code}). Check the folder and account permissions." }
        rememberRevision(url, response.etag)
    }

    /** Immutable history plus conditional publication: another device's newer copy is never overwritten silently. */
    suspend fun uploadScheduled(password: CharArray, name: String): Result<Unit> = execute {
        val credentials = load()
        val url = WebDavProtocol.documentUrl(credentials.endpoint)
        val archive = WebDavProtocol.snapshotUrl(credentials.endpoint, name)
        val document = backup.exportDocument(password).getOrThrow()
        val body = document.toRequestBody("application/json".toMediaType())
        val network = networkClient()
        val meta = metadata()
        val historyKey = revisionKey(url) + "-history"
        val initial = WebDavPublicationState(meta[revisionKey(url)]?.asString,
            (meta[historyKey] as? com.google.gson.JsonArray)?.map { it.asString }.orEmpty())
        require(initial.archives.size < 100) { "Не удаётся очистить историю WebDAV. Проверьте разрешение удаления старых снимков." }
        WebDavSnapshotPublisher.publish(initial, archive, persist = { state ->
            meta.add(historyKey, com.google.gson.JsonArray().apply { state.archives.forEach { add(it) } })
            if (state.etag == null) meta.remove(revisionKey(url)) else meta.addProperty(revisionKey(url), state.etag)
            revisions.write(meta.toString())
        }, uploadArchive = {
            send(network, authenticated(Request.Builder().url(archive), credentials).header("If-None-Match", "*").put(body).build())
        }, uploadLatest = { previous ->
            send(network, authenticated(Request.Builder().url(url), credentials).apply {
                if (previous.isNullOrBlank()) header("If-None-Match", "*") else header("If-Match", previous)
            }.put(body).build())
        }, deleteArchive = { old ->
            require(old.startsWith(url.substringBeforeLast('/') + "/splate-"))
            val response = send(network, authenticated(Request.Builder().url(old), credentials).delete().build())
            response.successful || response.code == 404
        })
    }

    suspend fun download(password: CharArray): Result<TransferPreview> = execute {
        val credentials = load()
        val request = authenticated(Request.Builder().url(WebDavProtocol.documentUrl(credentials.endpoint)), credentials).get().build()
        val document = sendRequest(networkClient(), request) { response ->
            require(response.isSuccessful) { "WebDAV download failed (HTTP ${response.code})" }
            val body = requireNotNull(response.body)
            require(body.contentLength() <= DeviceTransferProtocol.MAX_DOCUMENT_BYTES) { "Backup exceeds 32 MB" }
            body.byteStream().use { it.readLimited(DeviceTransferProtocol.MAX_DOCUMENT_BYTES).toString(Charsets.UTF_8) }
        }
        // Previewing (or cancelling a preview) never authorizes overwriting that remote revision.
        transfer.stageDocument(document, password, "WebDAV").getOrThrow()
    }

    private suspend fun <T> execute(action: suspend () -> T): Result<T> = withContext(Dispatchers.IO) { operation.withLock {
        _state.value = WebDavSyncState(busy = true)
        try { Result.success(NetworkAccessPolicy.withUpdates(context) { action() }).also { _state.value = WebDavSyncState(completed = true) }
        } catch (error: CancellationException) { _state.value = WebDavSyncState(); throw error
        } catch (error: Exception) {
            _state.value = WebDavSyncState(error = error.message)
            Result.failure(error)
        }
    } }

    private fun authenticated(builder: Request.Builder, credentials: WebDavSettings): Request.Builder = builder.apply {
        if (credentials.username.isNotEmpty() || credentials.password.isNotEmpty())
            header("Authorization", Credentials.basic(credentials.username, credentials.password, Charsets.UTF_8))
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }

    private fun encryptCredentials(plaintext: String): String = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, key())
        updateAAD(KEY_ALIAS.toByteArray(Charsets.UTF_8))
        JsonObject().apply {
            addProperty("version", 1)
            addProperty("iv", Base64.getEncoder().encodeToString(iv))
            addProperty("ciphertext", Base64.getEncoder().encodeToString(doFinal(plaintext.toByteArray(Charsets.UTF_8))))
        }.toString()
    }

    private fun decryptCredentials(document: String): String {
        val obj = JsonParser.parseString(document).asJsonObject
        require(obj["version"].asInt == 1)
        val nonce = Base64.getDecoder().decode(obj["iv"].asString)
        require(nonce.size == 12)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, nonce))
            updateAAD(KEY_ALIAS.toByteArray(Charsets.UTF_8))
            doFinal(Base64.getDecoder().decode(obj["ciphertext"].asString)).toString(Charsets.UTF_8)
        }
    }

    companion object { private const val KEY_ALIAS = "splate-webdav-credentials-v1" }
}
