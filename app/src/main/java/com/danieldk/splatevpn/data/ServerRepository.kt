package com.danieldk.splatevpn.data

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerRepository @Inject constructor(
    private val serverDao: ServerDao,
    private val subscriptionDao: SubscriptionDao,
    private val settingsManager: SettingsManager
) {
    private val TAG = "ServerRepository"

    /**
     * Permissive SSLSocketFactory strictly for ping/RTT measurement.
     * Prevents false ping failures caused by camouflage SNIs (e.g. dl.google.com)
     * and IP-issued certificates during TLS handshake latency testing.
     */
    private val pingSslSocketFactory: javax.net.ssl.SSLSocketFactory by lazy {
        val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        })
        val sslContext = javax.net.ssl.SSLContext.getInstance("TLS").apply {
            init(null, trustAllCerts, java.security.SecureRandom())
        }
        sslContext.socketFactory
    }

    val allServers: Flow<List<ServerNode>> = serverDao.getAllServers()
    val selectedServer: Flow<ServerNode?> = serverDao.getSelectedServer()
    val allSubscriptions: Flow<List<SubscriptionEntity>> = subscriptionDao.getAllSubscriptions()

    /**
     * Очищает старые встроенные серверы если они остались от предыдущих версий.
     * База данных стартует пустой — пользователь добавляет свои серверы через подписки или вручную.
     */
    suspend fun initDefaultServers() = withContext(Dispatchers.IO) {
        val builtinIds = listOf("node-1", "node-2")
        val existing = serverDao.getAllServersSync()
        val hasOnlyBuiltins = existing.isNotEmpty() && existing.all { it.id in builtinIds }
        if (hasOnlyBuiltins) {
            Log.d(TAG, "Очистка устаревших встроенных серверов")
            deleteSubscription("builtin")
        }
    }

    suspend fun setActiveServer(id: String) = withContext(Dispatchers.IO) {
        serverDao.setActiveServer(id)
    }

    suspend fun getActiveServerSync(): ServerNode? = withContext(Dispatchers.IO) {
        serverDao.getSelectedServerSync() ?: serverDao.getAllServersSync().firstOrNull()
    }

    suspend fun addServerFromLink(link: String): Boolean = withContext(Dispatchers.IO) {
        val node = LinkParser.parse(link, subId = "custom", subName = "Пользовательские")
        if (node != null) {
            val all = serverDao.getAllServersSync()
            val toInsert = if (all.isEmpty()) node.copy(isSelected = true) else node
            serverDao.insertServer(toInsert)
            true
        } else {
            false
        }
    }

    suspend fun deleteServer(id: String) = withContext(Dispatchers.IO) {
        serverDao.deleteServer(id)
    }

    suspend fun updateServer(server: ServerNode) = withContext(Dispatchers.IO) {
        serverDao.updateServer(server)
    }

    suspend fun resetToDefault() = withContext(Dispatchers.IO) {
        serverDao.clearAll()
        subscriptionDao.clearAll()
        initDefaultServers()
    }

    /**
     * TLS Handshake пинг: проверяет реальную доступность VPN-сервера.
     *
     * Для Reality/TLS серверов выполняет полный TLS handshake с SNI —
     * это подтверждает, что сервер реально принимает соединения,
     * а не просто имеет открытый порт.
     *
     * Делает до 3 попыток и берёт лучший (минимальный) RTT.
     * Возвращает -1 если сервер недоступен после всех попыток.
     */
    suspend fun testPing(server: ServerNode): Long = withContext(Dispatchers.IO) {
        val attempts = 3
        val timeout = 5000
        var bestPing = -1L

        for (attempt in 1..attempts) {
            try {
                val startTime = System.currentTimeMillis()
                val security = server.security.lowercase()

                if (security == "reality" || security == "tls") {
                    // TLS Handshake — verifies server actually processes connections
                    Socket().use { rawSocket ->
                        rawSocket.connect(InetSocketAddress(server.address, server.port), timeout)
                        rawSocket.soTimeout = timeout
                        
                        val sslSocket = pingSslSocketFactory.createSocket(
                            rawSocket,
                            server.sni.ifEmpty { server.address },
                            server.port,
                            true
                        ) as javax.net.ssl.SSLSocket
                        
                        sslSocket.use { ssl ->
                            ssl.startHandshake()
                        }
                    }
                } else {
                    // Plain TCP connect for non-TLS servers
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(server.address, server.port), timeout)
                    }
                }

                val rtt = System.currentTimeMillis() - startTime
                if (bestPing == -1L || rtt < bestPing) {
                    bestPing = rtt
                }
                Log.d(TAG, "Ping ${server.name} attempt $attempt: ${rtt}ms (${if (security == "reality" || security == "tls") "TLS handshake" else "TCP connect"} OK)")
            } catch (e: Exception) {
                Log.w(TAG, "Ping ${server.name} attempt $attempt failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        if (bestPing == -1L) {
            Log.w(TAG, "Ping ${server.name}: все $attempts попыток неудачны — сервер недоступен")
        } else {
            Log.d(TAG, "Ping ${server.name}: лучший результат ${bestPing}ms из $attempts попыток")
        }

        serverDao.updatePing(server.id, bestPing)
        bestPing
    }

    suspend fun testAllPings() = withContext(Dispatchers.IO) {
        val servers = serverDao.getAllServersSync()
        servers.map { server ->
            async {
                testPing(server)
            }
        }.awaitAll()
    }

    suspend fun autoSelectFastestServer(): ServerNode? = withContext(Dispatchers.IO) {
        val servers = serverDao.getAllServersSync()
        if (servers.isEmpty()) return@withContext null

        val pingResults = servers.map { server ->
            async {
                val ping = testPing(server)
                server to ping
            }
        }.awaitAll()

        // Берём только доступные серверы (ping > 0)
        val reachable = pingResults.filter { it.second > 0 }

        if (reachable.isEmpty()) {
            Log.w(TAG, "autoSelect: все серверы недоступны, активный сервер не изменён")
            return@withContext null
        }

        // Используем свежий результат пинга, а не stale значение из БД
        val (bestServer, bestPing) = reachable.minByOrNull { it.second }!!
        setActiveServer(bestServer.id)
        Log.d(TAG, "autoSelect: выбран '${bestServer.name}' (пинг: ${bestPing}ms)")
        bestServer
    }

    suspend fun importSubscription(subUrl: String, subTitle: String? = null): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val url = URL(subUrl.trim())
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", "v2rayNG/1.8.5")

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                return@withContext Result.failure(Exception("HTTP Error $responseCode"))
            }

            val rawContent = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }

            // Decode if base64
            val content = try {
                val clean = rawContent.replace("\n", "").replace("\r", "").trim()
                String(Base64.decode(clean, Base64.DEFAULT))
            } catch (e: Exception) {
                rawContent
            }

            val subId = UUID.randomUUID().toString()
            val subName = subTitle?.takeIf { it.isNotBlank() } ?: url.host ?: "Подписка"

            val lines = content.lines().filter { it.isNotBlank() }
            val nodes = mutableListOf<ServerNode>()
            lines.forEach { line ->
                LinkParser.parse(line.trim(), subId = subId, subName = subName)?.let {
                    nodes.add(it)
                }
            }

            if (nodes.isNotEmpty()) {
                serverDao.insertServers(nodes)
                subscriptionDao.insertSubscription(
                    SubscriptionEntity(
                        id = subId,
                        name = subName,
                        url = subUrl.trim(),
                        lastUpdated = System.currentTimeMillis(),
                        nodeCount = nodes.size,
                        autoUpdate = true
                    )
                )
                settingsManager.setSubscriptionUrl(subUrl)
                Result.success(nodes.size)
            } else {
                Result.failure(Exception("В подписке не найдено подходящих узлов"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateSubscription(subId: String): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val sub = subscriptionDao.getSubscriptionById(subId)
                ?: return@withContext Result.failure(Exception("Подписка не найдена"))

            if (sub.url.isBlank()) {
                return@withContext Result.failure(Exception("У подписки нет URL для обновления"))
            }

            val url = URL(sub.url)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", "v2rayNG/1.8.5")

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                return@withContext Result.failure(Exception("HTTP Error $responseCode"))
            }

            val rawContent = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            val content = try {
                val clean = rawContent.replace("\n", "").replace("\r", "").trim()
                String(Base64.decode(clean, Base64.DEFAULT))
            } catch (e: Exception) {
                rawContent
            }

            val lines = content.lines().filter { it.isNotBlank() }
            val nodes = mutableListOf<ServerNode>()
            lines.forEach { line ->
                LinkParser.parse(line.trim(), subId = sub.id, subName = sub.name)?.let {
                    nodes.add(it)
                }
            }

            if (nodes.isNotEmpty()) {
                // Delete old nodes for this sub and insert fresh ones
                serverDao.deleteServersBySubId(sub.id)
                serverDao.insertServers(nodes)
                
                // Ensure a valid server remains selected
                val currentSelected = serverDao.getSelectedServerSync()
                if (currentSelected == null) {
                    nodes.firstOrNull()?.let { serverDao.setActiveServer(it.id) }
                }

                subscriptionDao.insertSubscription(
                    sub.copy(
                        lastUpdated = System.currentTimeMillis(),
                        nodeCount = nodes.size
                    )
                )
                Result.success(nodes.size)
            } else {
                Result.failure(Exception("В обновлении не найдено серверов"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteSubscription(subId: String) = withContext(Dispatchers.IO) {
        serverDao.deleteServersBySubId(subId)
        subscriptionDao.deleteSubscription(subId)
    }

    suspend fun updateAllSubscriptions(): Int = withContext(Dispatchers.IO) {
        val subs = subscriptionDao.getAllSubscriptionsSync().filter { it.autoUpdate && it.url.isNotBlank() }
        var totalUpdated = 0
        subs.forEach { sub ->
            val res = updateSubscription(sub.id)
            if (res.isSuccess) {
                totalUpdated++
            }
        }
        settingsManager.setLastAutoUpdateTime(System.currentTimeMillis())
        totalUpdated
    }
}
