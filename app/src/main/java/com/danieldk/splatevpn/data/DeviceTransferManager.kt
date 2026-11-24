package com.danieldk.splatevpn.data

import android.content.Context
import com.danieldk.splatevpn.vpn.LanShareManager
import com.danieldk.splatevpn.vpn.VpnState
import com.danieldk.splatevpn.vpn.VpnStatusManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.InputStream
import java.util.UUID
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class OutgoingTransfer(val invitation: String, val address: String, val expiresAt: Long, val receiving: Boolean)

@Singleton
class DeviceTransferManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backup: BackupManager,
    private val lan: LanShareManager,
    private val vpn: VpnStatusManager
) {
    private companion object {
        const val TRANSFER_TIMEOUT_MS = 60_000L
        const val MAX_HEADER_BYTES = 16_384
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val setupGate = Mutex()
    private val pendingGate = Mutex()
    private val _outgoing = MutableStateFlow<OutgoingTransfer?>(null)
    val outgoing = _outgoing.asStateFlow()
    private val _pending = MutableStateFlow<TransferPreview?>(null)
    val pending = _pending.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _applied = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val applied = _applied.asSharedFlow()
    private var pendingDocument: String? = null
    private var pendingPassword: CharArray? = null
    private var pendingSendUrl: String? = null
    private var pendingAllowPlaintext = false
    @Volatile private var server: ServerSocket? = null
    private val activeSockets = java.util.concurrent.ConcurrentHashMap<ServerSocket, Socket>()
    private var serverJob: Job? = null
    private var expiryJob: Job? = null

    suspend fun startSending(interfaceName: String = ""): Result<OutgoingTransfer> = start(interfaceName, receiving = false)
    suspend fun startReceiving(interfaceName: String = ""): Result<OutgoingTransfer> = start(interfaceName, receiving = true)

    private suspend fun start(interfaceName: String, receiving: Boolean): Result<OutgoingTransfer> = withContext(Dispatchers.IO) { setupGate.withLock {
        try {
            stopSending()
            val endpoint = lan.getActiveLanEndpoints().firstOrNull { interfaceName.isEmpty() || it.interfaceName == interfaceName }
                ?: error("Connect both devices to the same Wi-Fi or Ethernet network")
            require(DeviceTransferProtocol.isPrivateIpv4(endpoint.ipAddress)) { "A private LAN address is required" }
            val key = DeviceTransferProtocol.newSecret()
            val password = key.toCharArray()
            val document = try { if (receiving) null else backup.exportDocument(password).getOrThrow() } finally { password.fill('\u0000') }
            val socket = ServerSocket(0, 4, InetAddress.getByName(endpoint.ipAddress)).apply { soTimeout = 1000 }
            val token = DeviceTransferProtocol.newSecret()
            val expiresAt = System.currentTimeMillis() + DeviceTransferProtocol.LIFETIME_MS
            val direction = if (receiving) DeviceTransferDirection.UPLOAD else DeviceTransferDirection.DOWNLOAD
            val invitation = DeviceTransferInvitation("http://${endpoint.ipAddress}:${socket.localPort}/transfer/$token", key, direction)
            val outgoing = OutgoingTransfer(invitation.encode(), endpoint.ipAddress, expiresAt, receiving)
            server = socket
            _outgoing.value = outgoing
            _message.value = null
            serverJob = scope.launch { serve(socket, token, key, document, expiresAt, receiving) }
            expiryJob = scope.launch {
                delay(DeviceTransferProtocol.LIFETIME_MS)
                // Close an accepted socket too: per-read timeouts alone cannot stop a slow sender.
                if (server === socket) stopSending()
            }
            Result.success(outgoing)
        } catch (error: CancellationException) { throw error
        } catch (error: Exception) { stopSending(); Result.failure(error) }
    } }

    fun stopSending() {
        expiryJob?.cancel(); expiryJob = null
        serverJob?.cancel(); serverJob = null
        val listener = server
        server = null
        listener?.let { runCatching { activeSockets.remove(it)?.close() }; runCatching { it.close() } }
        _outgoing.value = null
    }

    /** QR/deep links only stage a preview. Sending credentials to another device always needs confirmation. */
    suspend fun receiveInvitation(raw: String): Result<TransferPreview> = withContext(Dispatchers.IO) {
        try {
            val invitation = DeviceTransferProtocol.parseInvitation(raw)
            val password = invitation.secret.toCharArray()
            try {
                if (invitation.direction == DeviceTransferDirection.UPLOAD) {
                    val document = backup.exportDocument(password).getOrThrow()
                    val preview = backup.previewDocument(document, password).getOrThrow()
                        .copy(received = false, source = java.net.URI(invitation.url).host, operationId = UUID.randomUUID())
                    pendingGate.withLock {
                        pendingPassword?.fill('\u0000')
                        pendingDocument = document; pendingPassword = password.copyOf()
                        pendingSendUrl = invitation.url; pendingAllowPlaintext = false; _pending.value = preview
                    }
                    Result.success(preview)
                } else {
                    val document = httpGet(invitation.url)
                    stageDocument(document, password, "LAN")
                }
            } finally { password.fill('\u0000') }
        } catch (error: CancellationException) { throw error
        } catch (error: Exception) { Result.failure(error) }
    }

    suspend fun stageDocument(document: String, password: CharArray, source: String, allowPlaintext: Boolean = false): Result<TransferPreview> = withContext(Dispatchers.IO) {
        try {
            val preview = backup.previewDocument(document, password, allowPlaintext).getOrThrow().copy(source = source, operationId = UUID.randomUUID())
            pendingGate.withLock {
                pendingPassword?.fill('\u0000')
                pendingDocument = document
                pendingPassword = password.copyOf()
                pendingSendUrl = null
                pendingAllowPlaintext = allowPlaintext
                _pending.value = preview
            }
            Result.success(preview)
        } catch (error: CancellationException) { throw error
        } catch (error: Exception) { Result.failure(error) }
    }

    suspend fun applyPending(expected: TransferPreview, mode: BackupManager.ImportMode): Result<Int> = withContext(Dispatchers.IO) { pendingGate.withLock {
        if (expected.operationId == null || _pending.value?.operationId != expected.operationId) {
            return@withLock Result.failure(IllegalStateException("The pending transfer changed. Review it again."))
        }
        val document = pendingDocument ?: return@withLock Result.failure(IllegalStateException("There is no pending transfer"))
        val password = pendingPassword ?: return@withLock Result.failure(IllegalStateException("Pairing key was cleared"))
        val sendUrl = pendingSendUrl
        if (sendUrl != null) {
            try {
                val count = _pending.value?.servers ?: 0
                httpPost(sendUrl, document)
                clearPending()
                _message.value = "Encrypted settings sent. Confirm the import on the receiving device."
                return@withLock Result.success(count)
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) { return@withLock Result.failure(error) }
        }
        if (vpn.state.value !in setOf(VpnState.DISCONNECTED, VpnState.FAILED)) return@withLock Result.failure(IllegalStateException("Disconnect VPN before importing settings"))
        val result = backup.importDocument(document, password, mode, allowPlaintext = pendingAllowPlaintext)
        if (result.isSuccess) { clearPending(); _applied.tryEmit(Unit) }
        result
    } }

    suspend fun dismissPending(expected: TransferPreview? = null) = pendingGate.withLock {
        if (expected == null || _pending.value?.operationId == expected.operationId) clearPending()
    }
    private fun clearPending() {
        pendingPassword?.fill('\u0000'); pendingPassword = null
        pendingDocument = null; pendingSendUrl = null; pendingAllowPlaintext = false; _pending.value = null
    }

    private suspend fun serve(listener: ServerSocket, token: String, key: String, document: String?, expiresAt: Long, receiving: Boolean) {
        val gate = DeviceTransferGate(token, expiresAt)
        try {
            while (currentCoroutineContext().isActive && !gate.finished(System.currentTimeMillis())) {
                val connection = try { listener.accept() } catch (_: SocketTimeoutException) { continue }
                activeSockets[listener] = connection
                if (server !== listener) { connection.close(); activeSockets.remove(listener, connection); break }
                connection.use { socket ->
                    socket.soTimeout = 8_000
                    try {
                        val input = socket.getInputStream().buffered()
                        val first = readHttpLine(input, 4096).split(' ')
                        require(first.size == 3 && first[2] == "HTTP/1.1")
                        val headers = linkedMapOf<String, String>()
                        var total = 0
                        while (true) {
                            val line = readHttpLine(input, 4096)
                            total += line.length
                            require(total <= MAX_HEADER_BYTES)
                            if (line.isEmpty()) break
                            val colon = line.indexOf(':'); require(colon > 0)
                            val name = line.substring(0, colon).lowercase()
                            require(!headers.containsKey(name)); headers[name] = line.substring(colon + 1).trim()
                        }
                        require(socket.inetAddress.isSiteLocalAddress && !headers.containsKey("transfer-encoding"))
                        val method = if (receiving) "POST" else "GET"
                        if (first[0] != method || !first[1].startsWith("/transfer/")) {
                            respond(socket, 400, "Invalid request".toByteArray()); return@use
                        }
                        if (!gate.claim(first[1].removePrefix("/transfer/"), System.currentTimeMillis())) {
                            respond(socket, 403, "Pairing unavailable".toByteArray()); return@use
                        }
                        if (receiving) {
                            val size = headers["content-length"]?.toIntOrNull()
                            require(size != null && size in 1..DeviceTransferProtocol.MAX_DOCUMENT_BYTES)
                            val data = ByteArray(size)
                            var offset = 0
                            while (offset < size) {
                                val count = input.read(data, offset, size - offset); require(count > 0); offset += count
                            }
                            val password = key.toCharArray()
                            val result = try { stageDocument(data.toString(Charsets.UTF_8), password, "LAN") } finally { password.fill('\u0000'); data.fill(0) }
                            if (result.isSuccess) {
                                respond(socket, 200, "Ready for review".toByteArray())
                                _message.value = "Settings received. Review and confirm the import."
                            } else respond(socket, 400, "Invalid encrypted backup".toByteArray())
                        } else {
                            respond(socket, 200, requireNotNull(document).toByteArray(Charsets.UTF_8))
                            _message.value = "Encrypted settings delivered. Confirm the import on the other device."
                        }
                    } catch (cancelled: CancellationException) { throw cancelled
                    } catch (_: Exception) { runCatching { respond(socket, 400, "Transfer failed".toByteArray()) } }
                }
                activeSockets.remove(listener, connection)
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { /* Closed listener or disconnected peer. No configuration is logged. */
        } finally {
            runCatching { listener.close() }
            runCatching { activeSockets.remove(listener)?.close() }
            if (server === listener) { server = null; _outgoing.value = null }
        }
    }

    private fun respond(socket: Socket, code: Int, bytes: ByteArray) {
        val output = socket.getOutputStream()
        output.write("HTTP/1.1 $code Result\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
        output.write(bytes); output.flush()
    }

    suspend fun exportShareFile(password: CharArray? = null): Result<java.io.File> = withContext(Dispatchers.IO) {
        try {
            val dir = java.io.File(context.cacheDir, "share").apply { mkdirs() }
            val file = java.io.File(dir, "splatevpn-config.json")
            backup.exportBackup(android.net.Uri.fromFile(file), password).getOrThrow()
            Result.success(file)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun stageFileUri(uri: android.net.Uri, password: CharArray? = null): Result<TransferPreview> = withContext(Dispatchers.IO) {
        try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    total += read
                    require(total <= DeviceTransferProtocol.MAX_DOCUMENT_BYTES) { "Backup file exceeds maximum size" }
                    out.write(buf, 0, read)
                }
                out.toByteArray()
            } ?: error("Could not read selected file")
            val document = bytes.toString(Charsets.UTF_8)
            val passChars = password ?: CharArray(0)
            try {
                stageDocument(document, passChars, source = "File", allowPlaintext = password == null || password.isEmpty())
            } finally {
                if (password == null) passChars.fill('\u0000')
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private fun findLanNetwork(): android.net.Network? = runCatching {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        cm.allNetworks.firstOrNull { net ->
            val caps = cm.getNetworkCapabilities(net) ?: return@firstOrNull false
            !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) &&
                (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET))
        }
    }.getOrNull()

    private suspend fun <T : Any> withTransferSocket(exchange: (Socket) -> T): T =
        withTimeoutOrNull(TRANSFER_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val socket = Socket()
                runCatching { findLanNetwork()?.bindSocket(socket) }
                val worker = scope.launch {
                    try { continuation.resume(socket.use(exchange)) }
                    catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
                continuation.invokeOnCancellation {
                    runCatching { socket.close() }
                    worker.cancel()
                }
            }
        } ?: throw SocketTimeoutException("LAN transfer timed out")

    private fun connectLanSocket(socket: Socket, host: String, port: Int) {
        try {
            socket.connect(java.net.InetSocketAddress(host, port), 8_000)
        } catch (error: java.io.IOException) {
            throw java.io.IOException(
                "Не удалось подключиться к $host:$port. Брандмауэр ПК или изоляция Wi-Fi блокируют входящее соединение: " +
                    "попробуйте нажать «Отправить настройки» на телефоне и «Принять код» на ПК, либо используйте «Поделиться файлом».",
                error
            )
        }
    }

    private suspend fun httpGet(urlString: String): String {
        val uri = java.net.URI(urlString)
        val host = uri.host ?: error("Invalid host")
        val port = if (uri.port > 0) uri.port else 80
        val path = if (uri.rawPath.isNullOrEmpty()) "/" else uri.rawPath
        return withTransferSocket { socket ->
            connectLanSocket(socket, host, port)
            socket.soTimeout = 30_000
            val out = socket.getOutputStream()
            out.write("GET $path HTTP/1.1\r\nHost: $host:$port\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            out.flush()
            val input = socket.getInputStream().buffered()
            val statusLine = readHttpLine(input, 4096).split(' ')
            require(statusLine.size >= 2 && statusLine[1] == "200") { "The pairing expired or was already used" }
            var contentLength: Int? = null
            var headerBytes = 0
            while (true) {
                val line = readHttpLine(input, 4096)
                headerBytes += line.length + 2
                require(headerBytes <= MAX_HEADER_BYTES) { "Transfer headers exceed limit" }
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0 && line.substring(0, colon).trim().equals("content-length", ignoreCase = true)) {
                    contentLength = line.substring(colon + 1).trim().toIntOrNull()
                }
            }
            val size = contentLength ?: error("Missing Content-Length")
            require(size in 1..DeviceTransferProtocol.MAX_DOCUMENT_BYTES) { "Transfer exceeds limit" }
            val bytes = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val count = input.read(bytes, offset, size - offset)
                require(count > 0) { "Premature end of transfer stream" }
                offset += count
            }
            bytes.toString(Charsets.UTF_8)
        }
    }

    private suspend fun httpPost(urlString: String, document: String) {
        val uri = java.net.URI(urlString)
        val host = uri.host ?: error("Invalid host")
        val port = if (uri.port > 0) uri.port else 80
        val path = if (uri.rawPath.isNullOrEmpty()) "/" else uri.rawPath
        val payload = document.toByteArray(Charsets.UTF_8)
        require(payload.size in 1..DeviceTransferProtocol.MAX_DOCUMENT_BYTES) { "Transfer exceeds limit" }
        withTransferSocket { socket ->
            connectLanSocket(socket, host, port)
            socket.soTimeout = 30_000
            val out = socket.getOutputStream()
            out.write("POST $path HTTP/1.1\r\nHost: $host:$port\r\nContent-Type: application/json\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            out.write(payload)
            out.flush()
            val input = socket.getInputStream().buffered()
            val statusLine = readHttpLine(input, 4096).split(' ')
            require(statusLine.size >= 2 && statusLine[1] == "200") { "The pairing expired or the other device refused the transfer" }
        }
    }

    private fun readHttpLine(input: InputStream, limit: Int): String {
        val output = StringBuilder()
        while (output.length <= limit) {
            val next = input.read()
            require(next >= 0) { "Принимающее устройство разорвало соединение. Проверьте экран ПК." }
            if (next == 10) return output.toString().removeSuffix("\r")
            require(next in 32..126 || next == 13)
            output.append(next.toChar())
        }
        error("HTTP header too large")
    }
}
