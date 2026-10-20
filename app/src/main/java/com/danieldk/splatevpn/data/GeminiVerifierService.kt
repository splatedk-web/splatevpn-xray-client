package com.danieldk.splatevpn.data

import android.app.Service
import android.content.*
import android.os.*
import kotlinx.coroutines.*
import kotlin.coroutines.resume

/** The WebView proxy and storage never affect speed tests or other WebViews. */
class GeminiVerifierService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what == 1) {
            job?.cancel()
            val reply = message.replyTo
            val port = message.arg1
            job = scope.launch {
                val result = GeminiWebViewCheck(this@GeminiVerifierService).check(port)
                runCatching { reply.send(Message.obtain(null, 1).apply { data = Bundle().apply {
                    putString("outcome", result.outcome.name); putString("detail", result.detail?.name); putLong("elapsed", result.elapsedMs)
                } }) }
            }
        }
        true
    })
    override fun onBind(intent: Intent): IBinder = messenger.binder
    override fun onUnbind(intent: Intent): Boolean { job?.cancel(); return false }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

internal suspend fun verifyGeminiPage(context: Context, port: Int): AiDnsTargetResult = withContext(Dispatchers.Main.immediate) {
    try {
        withTimeout(32_000) {
            suspendCancellableCoroutine { continuation ->
                var bound = false
                var released = false
                lateinit var connection: ServiceConnection
                fun release() {
                    if (released) return
                    released = true
                    if (bound) runCatching { context.unbindService(connection) }
                }
                fun finish(result: AiDnsTargetResult) { release(); if (continuation.isActive) continuation.resume(result) }
                val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
                    val result = runCatching {
                        AiDnsTargetResult("Gemini", AiDnsOutcome.valueOf(requireNotNull(message.data.getString("outcome"))),
                            message.data.getLong("elapsed"), message.data.getString("detail")?.let(AiDnsDetail::valueOf))
                    }.getOrElse { AiDnsTargetResult("Gemini", AiDnsOutcome.FAILED, detail = AiDnsDetail.JS_FAILED) }
                    finish(result); true
                })
                connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                        if (!continuation.isActive) { release(); return }
                        runCatching { Messenger(binder).send(Message.obtain(null, 1, port, 0).apply { replyTo = reply }) }
                            .onFailure { finish(AiDnsTargetResult("Gemini", AiDnsOutcome.FAILED, detail = AiDnsDetail.JS_FAILED)) }
                    }
                    override fun onServiceDisconnected(name: ComponentName) { finish(AiDnsTargetResult("Gemini", AiDnsOutcome.FAILED, detail = AiDnsDetail.JS_FAILED)) }
                    override fun onNullBinding(name: ComponentName) = onServiceDisconnected(name)
                    override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
                }
                bound = context.bindService(Intent(context, GeminiVerifierService::class.java), connection, Context.BIND_AUTO_CREATE)
                if (!bound) finish(AiDnsTargetResult("Gemini", AiDnsOutcome.FAILED, detail = AiDnsDetail.JS_UNAVAILABLE))
                continuation.invokeOnCancellation { Handler(Looper.getMainLooper()).post { release() } }
            }
        }
    } catch (_: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); AiDnsTargetResult("Gemini", AiDnsOutcome.FAILED, detail = AiDnsDetail.JS_TIMEOUT) }
}
