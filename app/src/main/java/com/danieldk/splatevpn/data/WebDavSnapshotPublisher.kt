package com.danieldk.splatevpn.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class WebDavPublicationState(val etag: String? = null, val archives: List<String> = emptyList())
internal data class WebDavReply(val code: Int, val etag: String? = null) { val successful get() = code in 200..299 }

/** The durable archive intent precedes network writes, so retries can clean up interrupted uploads. */
internal object WebDavSnapshotPublisher {
    suspend fun publish(initial: WebDavPublicationState, archive: String,
        persist: (WebDavPublicationState) -> Unit,
        uploadArchive: suspend () -> WebDavReply,
        uploadLatest: suspend (String?) -> WebDavReply,
        deleteArchive: suspend (String) -> Boolean
    ) {
        var state = initial.copy(archives = (initial.archives + archive).distinct())
        persist(state)
        currentCoroutineContext().ensureActive()
        require(uploadArchive().successful) { "WebDAV snapshot upload failed" }
        currentCoroutineContext().ensureActive()
        val response = uploadLatest(initial.etag)
        currentCoroutineContext().ensureActive()
        if (response.successful) {
            state = state.copy(etag = response.etag?.takeIf { it.isNotBlank() && !it.startsWith("W/") })
            persist(state)
        }
        val removed = mutableSetOf<String>()
        for (old in state.archives.dropLast(CloudBackupPolicy.HISTORY_LIMIT)) {
            currentCoroutineContext().ensureActive()
            if (deleteArchive(old)) removed += old
        }
        if (removed.isNotEmpty()) { state = state.copy(archives = state.archives.filterNot { it in removed }); persist(state) }
        require(response.code != 412) {
            "Снимок сохранён, но общая копия изменена другим устройством. Получите и проверьте её. Для продолжения опубликуйте выбранную копию вручную."
        }
        require(response.successful) { "Снимок сохранён, но общая копия не обновлена (HTTP ${response.code})" }
    }
}
