package com.danieldk.splatevpn.data

import com.google.gson.GsonBuilder

/** An allowlist, rather than redaction of exception messages or application logs. */
data class AnonymousErrorReport(
    val schema: Int = 1,
    val appVersion: String,
    val category: String,
    val exceptions: List<AnonymousException> = emptyList()
)

data class AnonymousException(val type: String, val frames: List<AnonymousFrame>)
data class AnonymousFrame(val className: String, val method: String, val line: Int)

object AnonymousErrorReports {
    val categories = listOf("QR", "TOR", "SETTINGS", "TRANSFER", "CONNECTION", "OTHER")
    private val name = Regex("[A-Za-z0-9_.$<>-]{1,220}")
    private val packages = listOf("com.danieldk.splatevpn.", "com.google.zxing.",
        "com.journeyapps.", "androidx.compose.", "IPtProxy.", "net.freehaven.tor.")

    fun create(version: String, category: String, error: Throwable? = null): AnonymousErrorReport {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        val errors = mutableListOf<AnonymousException>()
        var current = error
        while (current != null && errors.size < 4 && seen.add(current)) {
            val frames = current.stackTrace.asSequence().filter { frame ->
                packages.any { frame.className.startsWith(it) } && name.matches(frame.className) && name.matches(frame.methodName)
            }.take(24).map { AnonymousFrame(it.className, it.methodName, it.lineNumber.coerceIn(-1, 1_000_000)) }.toList()
            errors += AnonymousException(current.javaClass.name.takeIf { name.matches(it) } ?: "Throwable", frames)
            current = current.cause
        }
        return AnonymousErrorReport(appVersion = version.takeIf { Regex("[A-Za-z0-9.+_-]{1,64}").matches(it) } ?: "unknown",
            category = category.takeIf { it in categories } ?: "OTHER", exceptions = errors)
    }

    fun json(report: AnonymousErrorReport): String = GsonBuilder().setPrettyPrinting().create().toJson(report)
}
