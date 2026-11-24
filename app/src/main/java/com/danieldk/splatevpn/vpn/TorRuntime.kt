package com.danieldk.splatevpn.vpn

import android.content.Context
import com.danieldk.splatevpn.data.TorConfigParser
import com.danieldk.splatevpn.data.TorSettings
import com.danieldk.splatevpn.data.AppLogManager
import com.danieldk.splatevpn.data.torBridgeTransport
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.freehaven.tor.control.TorControlConnection
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

enum class TorPhase { STOPPED, STARTING, RUNNING, FAILED }
data class TorRuntimeState(
    val phase: TorPhase = TorPhase.STOPPED,
    val progress: Int = 0,
    val error: String? = null,
    val bootstrap: TorBootstrapStatus = TorBootstrapStatus(),
    val elapsedSeconds: Long = 0,
    val stalledSeconds: Long = 0,
    val attemptNumber: Int = 0,
    val attemptCount: Int = 0,
    val bridgeTransport: String = "",
    val activeBridgeIndex: Int = -1,
    val autoSelecting: Boolean = false
) {
    val isStalled: Boolean get() = phase == TorPhase.STARTING && stalledSeconds >= 30
}
private class TorShutdownException : IllegalStateException("Tor is still stopping. Close and reopen the app before retrying.")
private class TorBridgeStalledException : IllegalStateException("The bridge did not advance past the initial handshake")

/** Official Tor runs as an executable child process. Its JNI is never loaded into the app. */
@Singleton
class TorRuntime @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appLogManager: AppLogManager? = null,
    private val torBridgeProbeRepository: com.danieldk.splatevpn.data.TorBridgeProbeRepository? = null,
    private val torBridgeBenchmark: TorBridgeBenchmark? = null
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(TorRuntimeState())
    val state = _state.asStateFlow()
    private val generationLock = Any()
    private var generation = 0L
    @Volatile private var startup: Job? = null
    @Volatile private var torProcess: Process? = null
    @Volatile private var processId: Int? = null
    private var control: TorControlConnection? = null
    private var controlSocket: Socket? = null
    private var activeSettings: TorSettings? = null
    private var socksPort = 0
    @Volatile private var socksRelay: TorSocksAuthRelay? = null
    val socksCredentials: LocalProxyCredentials? get() = socksRelay?.credentials
    private var transportLease: TorTransportPool.Lease? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val controlRequests = TorControlRequests(scope)
    private var monitor: Job? = null
    private var speedConfirmation: Job? = null
    private var outputReader: Job? = null

    fun generationToken(): Long = synchronized(generationLock) { generation }
    internal fun processIdForTest(): Int? = processId
    internal fun hasRunningProcess(): Boolean = torProcess?.isAlive == true

    internal var bridgeTimings = TorBridgeTimings()
    private var lastSuccessfulBridge: String? = null
    private val bridgePreferences by lazy { context.getSharedPreferences("tor_bridge_preference", android.content.Context.MODE_PRIVATE) }
    private var bridgeRotation = 0
    private data class FailedBridge(val bridge: String, val networkKey: String, val atMs: Long)
    @Volatile private var failedBridge: FailedBridge? = null

    internal suspend fun reportTrafficFailure() = mutex.withLock {
        if (_state.value.phase != TorPhase.RUNNING) return@withLock
        lastSuccessfulBridge?.let { failedBridge = FailedBridge(it, currentNetworkKey(), monotonicMs()) }
        _state.value = _state.value.copy(phase = TorPhase.FAILED, error = "Tor tunnel stopped passing HTTPS traffic")
    }

    private fun retryableBridgeFailure(error: Throwable): Boolean =
        error is Exception && error !is TorShutdownException && error !is TorTransportInitializationException &&
            (error !is CancellationException || error is TimeoutCancellationException)

    private fun currentNetworkKey(): String =
        com.danieldk.splatevpn.data.NetworkAccessPolicy.physicalNetworkKey(context)?.toString() ?: "none"

    suspend fun start(settings: TorSettings, initialHandshakeProbe: Boolean = false): Int = coroutineScope {
        mutex.withLock {
            val job = currentCoroutineContext()[Job]
            synchronized(generationLock) { generation++; startup = job }
            try {
                currentCoroutineContext().ensureActive()
                val safe = TorConfigParser.validate(settings)
                val existing = torProcess
                if (activeSettings == safe && existing?.isAlive == true && _state.value.phase == TorPhase.RUNNING) {
                    monitor(existing, generationToken(), lastSuccessfulBridge.takeIf { safe.useBridges })
                    return@withLock socksPort
                }
                val netKey = currentNetworkKey()
                val savedNetwork = bridgePreferences.getString("successful_bridge_network", null)
                val savedAt = bridgePreferences.getLong("successful_bridge_at", 0L)
                val recentSuccess = savedNetwork == netKey && System.currentTimeMillis() - savedAt in 0..600_000L
                val savedHash = runCatching { bridgePreferences.getString("successful_bridge_hash", null) }.getOrNull()
                val previous = safe.bridges.firstOrNull { bridge ->
                    recentSuccess && (bridge == lastSuccessfulBridge || (savedHash != null && TorBridgeSelection.fingerprint(bridge) == savedHash))
                }
                if (lastSuccessfulBridge != null && previous == null) {
                    lastSuccessfulBridge = null
                    runCatching { bridgePreferences.edit().remove("successful_bridge_hash").apply() }
                }
                val recentlyFailed = failedBridge?.takeIf {
                    it.networkKey == netKey && monotonicMs() - it.atMs < 60_000L
                }?.bridge
                val delays = if (com.danieldk.splatevpn.data.NetworkAccessPolicy.checksAllowed(context) && safe.useBridges && safe.autoSelectFastestBridge && safe.selectedBridge.isBlank() && safe.bridges.size > 1 && torBridgeProbeRepository != null) {
                    if (torBridgeProbeRepository.hasFreshProbes(networkKey = netKey, bridges = safe.bridges, transport = safe.bridgeTransport)) {
                        torBridgeProbeRepository.bridgeLatencies.value
                    } else if (previous != null && previous != recentlyFailed) {
                        emptyMap()
                    } else {
                        _state.value = TorRuntimeState(phase = TorPhase.STARTING, autoSelecting = true)
                        appLogManager?.i("Tor", "Probing " + safe.bridges.size + " bridges for lowest latency and reachability...")
                        torBridgeProbeRepository.probeBridges(safe.bridges, timeoutMs = 1500L, networkKey = netKey, transport = safe.bridgeTransport,
                            totalBudgetMs = 1800L)
                    }
                } else emptyMap()
                val speeds = if (com.danieldk.splatevpn.data.NetworkAccessPolicy.checksAllowed(context) && safe.useBridges &&
                    safe.autoSelectFastestBridge && safe.selectedBridge.isBlank() &&
                    com.danieldk.splatevpn.data.TorBridgeMetric.fromId(safe.bridgeSelectionMetric) == com.danieldk.splatevpn.data.TorBridgeMetric.SPEED &&
                    torBridgeBenchmark != null) {
                    val cached = torBridgeProbeRepository?.speedsFor(safe, netKey).orEmpty()
                    if (cached.isNotEmpty()) {
                        cached
                    } else if (previous != null && previous != recentlyFailed) {
                        emptyMap()
                    } else {
                        _state.value = TorRuntimeState(phase = TorPhase.STARTING, autoSelecting = true)
                        torBridgeBenchmark.measure(safe, netKey, delays)
                    }
                } else emptyMap()
                val plan = TorBridgeSelection.plan(safe, previous, bridgeRotation++, delays, recentlyFailed, speeds)
                val planBridges = plan.candidates.mapNotNull { it.bridge }.toSet()
                val advancedFallbacks = if (safe.useBridges && plan.automatic &&
                    (safe.bridgeTransport == "auto" || safe.bridgeTransport in setOf("webtunnel", "snowflake"))) {
                    safe.bridges.mapIndexed { index, bridge -> TorBridgeCandidate(index, bridge) }
                        .filter { it.transport in setOf("webtunnel", "snowflake") && it.bridge !in planBridges }
                        .take(3)
                } else emptyList()

                try {
                    tryTorBridgeCandidates(plan, ::retryableBridgeFailure) { candidate, number, hasNext ->
                        _state.value = TorRuntimeState(phase = TorPhase.STARTING,
                            attemptNumber = number, attemptCount = plan.candidates.size + advancedFallbacks.size,
                            bridgeTransport = candidate.transport, activeBridgeIndex = candidate.index,
                            autoSelecting = plan.automatic)
                        appLogManager?.i("Tor", "Bridge attempt " + number + "/" + plan.candidates.size +
                            "; transport=" + candidate.transport)
                        val budget = if (plan.automatic) bridgeTimings.autoAttemptMillis else bridgeTimings.manualAttemptMillis
                        try {
                            val port = startSingleAttempt(
                                settings = candidate.settings(safe),
                                attemptBudgetMillis = budget,
                                retryAfterFailure = ((hasNext || advancedFallbacks.isNotEmpty()) && plan.automatic) || initialHandshakeProbe,
                                candidateTransport = candidate.transport,
                                isPreviousSuccess = (candidate.bridge != null && candidate.bridge == previous)
                            )
                            activeSettings = safe
                            if (candidate.bridge != null) {
                                lastSuccessfulBridge = candidate.bridge
                                runCatching { bridgePreferences.edit().putString("successful_bridge_hash", TorBridgeSelection.fingerprint(candidate.bridge))
                                    .putString("successful_bridge_network", netKey).putLong("successful_bridge_at", System.currentTimeMillis()).apply() }
                            }
                            confirmSpeed(safe, netKey, candidate.bridge, port)
                            port
                        } catch (attemptError: Throwable) {
                            if (candidate.bridge == previous) {
                                lastSuccessfulBridge = null
                                runCatching { bridgePreferences.edit().remove("successful_bridge_hash").apply() }
                                if (safe.bridges.size > 1 && torBridgeProbeRepository != null && !torBridgeProbeRepository.hasFreshProbes(netKey, safe.bridges, safe.bridgeTransport)) {
                                    scope.launch {
                                        runCatching { torBridgeProbeRepository.probeBridges(safe.bridges, timeoutMs = 1500L, networkKey = netKey, transport = safe.bridgeTransport) }
                                    }
                                }
                            }
                            throw attemptError
                        }
                    }
                } catch (primaryError: Throwable) {
                    if (advancedFallbacks.isNotEmpty() && retryableBridgeFailure(primaryError)) {
                        appLogManager?.w("Tor", "All TCP-probed bridge candidates failed; falling back to WebTunnel/Snowflake candidates for real Tor bootstrap...")
                        val fallbackPlan = TorBridgePlan(advancedFallbacks, automatic = true)
                        tryTorBridgeCandidates(fallbackPlan, ::retryableBridgeFailure) { candidate, number, hasNext ->
                            val currentAttempt = plan.candidates.size + number
                            val totalAttempts = plan.candidates.size + fallbackPlan.candidates.size
                            _state.value = TorRuntimeState(phase = TorPhase.STARTING,
                                attemptNumber = currentAttempt, attemptCount = totalAttempts,
                                bridgeTransport = candidate.transport, activeBridgeIndex = candidate.index,
                                autoSelecting = true)
                            appLogManager?.i("Tor", "Fallback bridge attempt " + number + "/" + fallbackPlan.candidates.size +
                                "; transport=" + candidate.transport)
                            val budget = bridgeTimings.autoAttemptMillis
                            val port = startSingleAttempt(
                                settings = candidate.settings(safe),
                                attemptBudgetMillis = budget,
                                retryAfterFailure = hasNext || initialHandshakeProbe,
                                candidateTransport = candidate.transport,
                                isPreviousSuccess = false
                            )
                            activeSettings = safe
                            if (candidate.bridge != null) {
                                lastSuccessfulBridge = candidate.bridge
                                runCatching { bridgePreferences.edit().putString("successful_bridge_hash", TorBridgeSelection.fingerprint(candidate.bridge))
                                    .putString("successful_bridge_network", netKey).putLong("successful_bridge_at", System.currentTimeMillis()).apply() }
                            }
                            confirmSpeed(safe, netKey, candidate.bridge, port)
                            port
                        }
                    } else {
                        throw primaryError
                    }
                }
            } catch (error: CancellationException) {
                if (error !is TimeoutCancellationException) _state.value = TorRuntimeState()
                throw error
            } catch (error: LinkageError) {
                throw IllegalStateException("Tor bridge transport library could not load", error)
            } catch (error: Exception) {
                lastSuccessfulBridge = null
                runCatching { bridgePreferences.edit().remove("successful_bridge_hash").apply() }
                if (_state.value.phase != TorPhase.FAILED) _state.value = _state.value.copy(
                    phase = TorPhase.FAILED, error = "Tor could not start. Check the saved bridge and relay settings.")
                throw error
            } finally { synchronized(generationLock) { startup = null } }
        }
    }

    private suspend fun startSingleAttempt(
        settings: TorSettings,
        attemptBudgetMillis: Long,
        retryAfterFailure: Boolean,
        candidateTransport: String = "vanilla",
        isPreviousSuccess: Boolean = false
    ): Int {
        var bootstrapTracker: TorBootstrapTracker? = null
        return try {
            val safe = TorConfigParser.validate(settings)
            if (!stopLocked()) throw TorShutdownException()

            val tracker = TorBootstrapTracker(monotonicMs()).also { bootstrapTracker = it }
            publishBootstrap(tracker, monotonicMs(), forceLog = true)
            withTimeout(attemptBudgetMillis) {
                val ports = withContext(Dispatchers.IO) {
                    val names = if (safe.useBridges) safe.bridges.map { it.substringBefore(' ') }
                        .filter { it in TorConfigParser.supportedTransports }.toSet() else emptySet()
                    TorTransportPool.acquire(context, names).also { transportLease = it }.ports
                }
                val directory = File(context.noBackupFilesDir, "tor")
                val controlFile = File(directory, "control-port")
                val cookie = File(directory, "control-cookie")
                val pidFile = File(directory, "tor.pid")
                val socksSocketFile = File(directory, "socks.sock")
                val process = withContext(Dispatchers.IO) {
                    check(directory.isDirectory || directory.mkdirs()) { "Tor private directory could not be created" }
                    val data = File(directory, "data").apply { mkdirs() }
                    controlFile.delete(); cookie.delete(); pidFile.delete(); socksSocketFile.delete()
                    val torrc = File(directory, "torrc").apply {
                        writeText(TorConfigParser.buildTorrc(safe, ports, socksUnixPath = socksSocketFile.absolutePath))
                    }
                    val binary = File(context.applicationInfo.nativeLibraryDir, "libtor.so")
                    check(binary.isFile && binary.canExecute()) { "Tor executable is unavailable" }
                    ProcessBuilder(binary.absolutePath, "-f", torrc.absolutePath,
                        "--DataDirectory", data.absolutePath, "--RunAsDaemon", "0",
                        "--ControlPort", "127.0.0.1:auto", "--ControlPortWriteToFile", controlFile.absolutePath,
                        "--CookieAuthentication", "1", "--CookieAuthFile", cookie.absolutePath,
                        "--CookieAuthFileGroupReadable", "0", "--PidFile", pidFile.absolutePath,
                        "--__OwningControllerProcess", android.os.Process.myPid().toString(),
                        "--Log", "notice stdout")
                        .directory(directory).redirectErrorStream(true).start().also { torProcess = it }
                }
                // Drain logs so a full pipe cannot block Tor, but keep a ring buffer of startup messages for diagnostics.
                val startupOutput = java.util.concurrent.ConcurrentLinkedDeque<String>()
                outputReader = scope.launch {
                    runCatching {
                        process.inputStream.bufferedReader().use { reader ->
                            while (true) {
                                val line = reader.readLine() ?: break
                                startupOutput.addLast(line)
                                while (startupOutput.size > 25) startupOutput.pollFirst()
                            }
                        }
                    }
                }
                while (control == null) {
                    if (!process.isAlive) {
                        val exitCode = runCatching { process.exitValue() }.getOrDefault(-1)
                        val errSnippet = startupOutput.filter { it.isNotBlank() }.joinToString(" | ")
                        appLogManager?.e("Tor", "Tor process exited immediately (code $exitCode): $errSnippet")
                        error("Tor executable stopped during startup (code $exitCode): ${errSnippet.takeLast(250)}")
                    }
                    withContext(Dispatchers.IO) {
                        if (controlFile.isFile && cookie.isFile && cookie.length() == 32L) {
                            val port = controlFile.readLines().firstNotNullOfOrNull { line ->
                                Regex("PORT=127\\.0\\.0\\.1:(\\d+)").matchEntire(line.trim())?.groupValues?.get(1)?.toIntOrNull()
                            }
                            if (port != null && port in 1..65535) {
                                val socket = Socket()
                                try {
                                    socket.connect(InetSocketAddress("127.0.0.1", port), 2_000)
                                    controlSocket = socket
                                    val connection = TorControlConnection(socket)
                                    val secret = cookie.readBytes()
                                    try { controlRequest { connection.authenticate(secret) } } finally { secret.fill(0) }
                                    controlRequest { connection.takeOwnership() }
                                    control = connection
                                    processId = controlRequest { connection.getInfo("process/pid") }.toIntOrNull()
                                        ?: pidFile.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
                                } catch (error: Throwable) {
                                    runCatching { socket.close() }
                                    controlSocket = null
                                    throw error
                                }
                            }
                        }
                    }
                    if (control == null) {
                        publishBootstrap(tracker, monotonicMs())
                        delay(100)
                    }
                }
                val connection = requireNotNull(control)
                while (true) {
                    check(process.isAlive) { "Tor executable stopped during bootstrap" }
                    val phase = controlRequest { connection.getInfo("status/bootstrap-phase") }.orEmpty()
                    TorBootstrapStatus.parse(phase)?.let { tracker.update(it, monotonicMs()) }
                    publishBootstrap(tracker, monotonicMs())
                    if (retryAfterFailure && bridgeTimings.shouldLeaveEarly(
                            progress = tracker.status.progress,
                            stalledSeconds = tracker.stalledSeconds(monotonicMs()),
                            warning = tracker.status.warning,
                            reason = tracker.status.reason,
                            transport = candidateTransport,
                            isPreviousSuccess = isPreviousSuccess
                        )) {
                        throw TorBridgeStalledException()
                    }
                    if (controlRequest { connection.getInfo("status/circuit-established") } == "1") {
                        val listeners = controlRequest { connection.getInfo("net/listeners/socks") }.orEmpty()
                        if (listeners.contains("unix:") && socksSocketFile.exists()) {
                            runCatching { socksRelay?.close() }
                            val relay = TorSocksAuthRelay.forUnixSocket(socksSocketFile.absolutePath)
                            socksRelay = relay
                            socksPort = relay.port
                            break
                        }
                        val port = Regex("127\\.0\\.0\\.1:(\\d+)").find(listeners)?.groupValues?.get(1)?.toIntOrNull()
                        if (port != null && port in 1..65535) { socksPort = port; break }
                    }
                    delay(250)
                }

                val ready = TorBootstrapStatus(100, TorBootstrapStage.READY)
                tracker.update(ready, monotonicMs())
                _state.value = _state.value.copy(phase = TorPhase.RUNNING, progress = 100, error = null,
                    bootstrap = ready, elapsedSeconds = tracker.elapsedSeconds(monotonicMs()), stalledSeconds = 0)
                logBootstrap(ready, tracker.elapsedSeconds(monotonicMs()))
                monitor(process, generationToken(), safe.selectedBridge.takeIf { safe.useBridges && it.isNotBlank() })
                socksPort
            }
        } catch (error: Throwable) {
            val callerContext = currentCoroutineContext()
            val failedAtMs = monotonicMs()
            val diagnosticState = _state.value
            val failure = try {
                callerContext.ensureActive()
                error
            } catch (cancelled: CancellationException) { cancelled }
            val cleaned = if (error !is TorShutdownException) withContext(NonCancellable) { stopLocked() } else false
            // A stop may arrive during cleanup too. Preserve the original caller's Job,
            // rather than consulting the temporary NonCancellable cleanup context.
            val finalFailure = try {
                callerContext.ensureActive()
                failure
            } catch (cancelled: CancellationException) { cancelled }
            val tracker = bootstrapTracker
            val failedState = diagnosticState.copy(
                phase = TorPhase.FAILED,
                bootstrap = tracker?.status ?: diagnosticState.bootstrap,
                elapsedSeconds = tracker?.elapsedSeconds(failedAtMs) ?: diagnosticState.elapsedSeconds,
                stalledSeconds = tracker?.stalledSeconds(failedAtMs) ?: diagnosticState.stalledSeconds)
            _state.value = if (!cleaned) failedState.copy(error = TorShutdownException().message) else if (retryAfterFailure && retryableBridgeFailure(finalFailure)) {
                appLogManager?.w("Tor", "Bridge attempt did not connect; trying the next saved bridge")
                failedState.copy(phase = TorPhase.STARTING, error = null)
            } else when (finalFailure) {
                is TimeoutCancellationException -> failedState.copy(error =
                    "Tor startup timed out at ${failedState.bootstrap.stage.en} (${failedState.progress}%). " +
                        "Elapsed ${failedState.elapsedSeconds}s; no progress ${failedState.stalledSeconds}s. " +
                        "Reason: ${failedState.bootstrap.reason?.code ?: "unknown"}. Try another bridge or WebTunnel.")
                is CancellationException -> TorRuntimeState()
                else -> failedState.copy(error = "Tor could not start. Check the bridge and relay settings.")
            }
            if (_state.value.phase == TorPhase.FAILED) appLogManager?.w("Tor",
                "Bootstrap failed: phase=${failedState.bootstrap.stage.tag}; progress=${failedState.progress}%; " +
                    "reason=${failedState.bootstrap.reason?.code ?: "none"}; elapsed=${failedState.elapsedSeconds}s")

            throw finalFailure
        }
    }

    private suspend fun <T> controlRequest(request: () -> T): T =
        controlRequests.execute(controlSocket, request)

    suspend fun newIdentity(): Boolean = mutex.withLock {
        val connection = control ?: return false
        try {
            controlRequest { connection.signal("NEWNYM") }
            appLogManager?.i("Tor", "Tor SIGNAL NEWNYM sent: new clean identity and circuits requested")
            true
        } catch (e: Exception) {
            appLogManager?.w("Tor", "Failed to send NEWNYM signal: ${e.message}")
            false
        }
    }

    suspend fun stop(expectedGeneration: Long? = null) {
        val request = synchronized(generationLock) {
            if (expectedGeneration != null && expectedGeneration != generation) return
            generation++
            Pair(generation, startup)
        }
        request.second?.cancel()
        withContext(NonCancellable) { mutex.withLock {
            if (generationToken() != request.first) return@withLock
            val failure = _state.value.takeIf { it.phase == TorPhase.FAILED }
            val stopped = stopLocked()
            _state.value = if (stopped) failure ?: TorRuntimeState()
                else _state.value.copy(phase = TorPhase.FAILED, error = TorShutdownException().message)
        } }
    }

    private fun monotonicMs(): Long = System.nanoTime() / 1_000_000L

    private fun publishBootstrap(tracker: TorBootstrapTracker, nowMs: Long, forceLog: Boolean = false) {
        val previous = _state.value
        val next = previous.copy(phase = TorPhase.STARTING, progress = tracker.status.progress, error = null,
            bootstrap = tracker.status, elapsedSeconds = tracker.elapsedSeconds(nowMs), stalledSeconds = tracker.stalledSeconds(nowMs))
        _state.value = next
        if (forceLog || previous.bootstrap != next.bootstrap) logBootstrap(next.bootstrap, next.elapsedSeconds)
        if (!previous.isStalled && next.isStalled) appLogManager?.w("Tor",
            "Bootstrap stalled: phase=${next.bootstrap.stage.tag}; progress=${next.progress}%; " +
                "reason=${next.bootstrap.reason?.code ?: "none"}; elapsed=${next.elapsedSeconds}s; no_progress=${next.stalledSeconds}s")
    }

    private fun logBootstrap(status: TorBootstrapStatus, elapsedSeconds: Long) {
        appLogManager?.i("Tor", "Bootstrap: phase=${status.stage.tag}; progress=${status.progress}%; " +
            "reason=${status.reason?.code ?: "none"}; elapsed=${elapsedSeconds}s")
    }

    private fun confirmSpeed(settings: TorSettings, network: String, bridge: String?, port: Int) {
        speedConfirmation?.cancel()
        if (bridge == null || settings.bridgeSelectionMetric != "speed" || !settings.autoSelectFastestBridge ||
            settings.selectedBridge.isNotBlank() || torBridgeBenchmark == null) return
        val token = generationToken()
        val creds = socksCredentials
        speedConfirmation = scope.launch {
            // Let the fresh Tor circuit and initial health check settle before running a speed sample.
            delay(15_000)
            fun current() = generationToken() == token && currentNetworkKey() == network &&
                _state.value.phase == TorPhase.RUNNING && activeSettings == settings
            try {
                torBridgeBenchmark.confirmConnection(settings, network, bridge, port, ::current, credentials = creds)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.w("TorBenchmark", "Connected speed sample unavailable: ${error.javaClass.simpleName}")
            }
        }
    }

    private suspend fun stopLocked(): Boolean {
        speedConfirmation?.cancelAndJoin(); speedConfirmation = null
        monitor?.cancel(); monitor = null
        activeSettings = null
        val process = torProcess
        val connection = control
        control = null
        if (process?.isAlive == true && connection != null) runCatching { controlRequest { connection.shutdownTor("HALT") } }
        runCatching { controlSocket?.close() }
        controlSocket = null
        val stopped = withContext(Dispatchers.IO) {
            if (process != null && !process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroy()
                if (!process.waitFor(1, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(1, TimeUnit.SECONDS)
                }
            }
            process?.isAlive != true
        }
        outputReader?.cancel(); outputReader = null
        if (stopped) {
            runCatching { process?.inputStream?.close() }
            runCatching { process?.outputStream?.close() }
            runCatching { process?.errorStream?.close() }
            torProcess = null; processId = null; socksPort = 0
        }
        runCatching { socksRelay?.close() }
        socksRelay = null
        runCatching { File(File(context.noBackupFilesDir, "tor"), "socks.sock").delete() }
        withContext(Dispatchers.IO) {
            transportLease?.close()
            transportLease = null
        }
        return stopped
    }

    private fun monitor(process: Process, token: Long, bridge: String?) {
        monitor?.cancel()
        monitor = scope.launch {
            var controlFailures = 0
            var circuitFailures = 0
            while (isActive && generationToken() == token && torProcess === process) {
                delay(2_000)
                if (!process.isAlive) break
                val established = try {
                    val connection = checkNotNull(control)
                    controlRequest { connection.getInfo("status/circuit-established") }
                } catch (error: Exception) {
                    currentCoroutineContext().ensureActive()
                    if (error is CancellationException && error !is TimeoutCancellationException) throw error
                    null
                }
                controlFailures = if (established in setOf("0", "1")) 0 else controlFailures + 1
                circuitFailures = when (established) {
                    "0" -> circuitFailures + 1
                    "1" -> 0
                    else -> circuitFailures
                }
                if (controlFailures >= 3 || circuitFailures >= 30) break
            }
            currentCoroutineContext().ensureActive()
            if (generationToken() == token && torProcess === process) {
                if (bridge != null) failedBridge = FailedBridge(bridge, currentNetworkKey(), monotonicMs())
                _state.value = _state.value.copy(phase = TorPhase.FAILED, error = "Tor connection was lost")
            }
        }
    }
}
