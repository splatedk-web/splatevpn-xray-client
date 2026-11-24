package com.danieldk.splatevpn.data

import android.content.Context
import android.util.LruCache
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class ReleaseNotesTranslator(private val context: Context) {
    private val cache = LruCache<String, String>(8)

    suspend fun translate(markdown: String, language: String): String {
        val target = TranslateLanguage.fromLanguageTag(language) ?: return markdown
        if (target == TranslateLanguage.RUSSIAN || !markdown.any { it in 'А'..'я' || it == 'ё' || it == 'Ё' }) return markdown
        val hash = MessageDigest.getInstance("SHA-256").digest(markdown.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val key = "$target:$hash"
        cache.get(key)?.let { return it }
        val translator = Translation.getClient(TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.RUSSIAN).setTargetLanguage(target).build())
        try {
            return withTimeout(90_000) {
                val models = setOf(TranslateLanguage.RUSSIAN, target) - TranslateLanguage.ENGLISH
                val modelManager = RemoteModelManager.getInstance()
                var modelsReady = true
                for (languageCode in models) {
                    if (!modelManager.isModelDownloaded(TranslateRemoteModel.Builder(languageCode).build())
                            .awaitTranslationTask()) modelsReady = false
                }
                if (!modelsReady) NetworkAccessPolicy.requireUpdates(context)
                val conditions = DownloadConditions.Builder().apply {
                    if (context.getSharedPreferences("splate_prefs", Context.MODE_PRIVATE)
                            .getBoolean(NetworkAccessPolicy.UPDATES_WIFI_ONLY, false)) requireWifi()
                }.build()
                translator.downloadModelIfNeeded(conditions).awaitTranslationTask()
                translateReleaseMarkdown(markdown) { translator.translate(it).awaitTranslationTask() }
                    .also { cache.put(key, it) }
            }
        } finally {
            translator.close()
        }
    }
}

private suspend fun <T> Task<T>.awaitTranslationTask(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
    addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}

/** Keep code, Markdown markers and link destinations verbatim while translating prose. */
internal suspend fun translateReleaseMarkdown(markdown: String, translate: suspend (String) -> String): String {
    val protected = Regex("""`+[^`]*`+|!?\[[^\]]*\]\([^\s)]*\)|https?://[^\s)]+|<[^>]+>|\*\*|__|~~""")
    val link = Regex("""(!?)\[([^\]]*)\]\(([^)]*)\)""")
    val prefix = Regex("""^\s*(?:(?:#{1,6}|[-+*]|>|\d+\.)\s+)?""")
    var fence: String? = null
    suspend fun prose(text: String): String {
        if (text.none { it in 'А'..'я' || it == 'ё' || it == 'Ё' }) return text
        return text.takeWhile(Char::isWhitespace) + translate(text.trim()) + text.takeLastWhile(Char::isWhitespace)
    }
    val lines = mutableListOf<String>()
    for (line in markdown.split('\n')) {
        val marker = Regex("""^\s{0,3}(`{3,}|~{3,})""").find(line)?.groupValues?.get(1)
        val currentFence = fence
        if (currentFence != null) {
            lines += line
            if (line.trim().startsWith(currentFence) && line.trim().all { it == currentFence[0] }) fence = null
            continue
        }
        if (marker != null) {
            fence = marker
            lines += line
            continue
        }
        val leading = prefix.find(line)!!.value
        val body = line.substring(leading.length)
        val result = StringBuilder(leading)
        var at = 0
        for (match in protected.findAll(body)) {
            result.append(prose(body.substring(at, match.range.first)))
            val linked = link.matchEntire(match.value)
            if (linked == null) result.append(match.value)
            else result.append(linked.groupValues[1]).append('[').append(prose(linked.groupValues[2]))
                .append("](").append(linked.groupValues[3]).append(')')
            at = match.range.last + 1
        }
        result.append(prose(body.substring(at)))
        lines += result.toString()
    }
    return lines.joinToString("\n")
}
