package com.danieldk.splatevpn.data

import com.google.gson.JsonParser
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

internal data class CloudBackupLink(val url: String, val source: String, val yandex: Boolean = false)
internal data class BackupLinkResponse(val code: Int, val text: String, val location: String? = null)

/** Only file-download links; no cloud account password, cookies or OAuth tokens are collected. */
internal object CloudBackupLinks {
    fun https(raw: String): HttpUrl {
        require(raw.length in 1..8192) { "Ссылка слишком длинная или пустая" }
        val url = raw.trim().toHttpUrl()
        require(url.isHttps && url.username.isEmpty() && url.password.isEmpty()) { "Используйте HTTPS-ссылку без логина и пароля в адресе" }
        return url.newBuilder().fragment(null).build()
    }
    fun resolve(raw: String): CloudBackupLink {
        val url = https(raw)
        if (url.host in setOf("disk.yandex.ru", "disk.yandex.com", "yadi.sk")) {
            require(url.pathSegments.firstOrNull() in setOf("d", "i")) { "Нужна публичная ссылка на файл Яндекс Диска" }
            return CloudBackupLink("https://cloud-api.yandex.net/v1/disk/public/resources/download".toHttpUrl()
                .newBuilder().addQueryParameter("public_key", url.toString()).build().toString(), "Яндекс Диск", yandex = true)
        }
        if (url.host in setOf("www.dropbox.com", "dropbox.com")) {
            require(url.pathSegments.firstOrNull() == "s" || url.pathSegments.take(2) == listOf("scl", "fi")) {
                "Нужна ссылка Dropbox на файл, а не папку"
            }
            return CloudBackupLink(url.newBuilder().removeAllQueryParameters("raw").setQueryParameter("dl", "1").build().toString(), "Dropbox")
        }
        if (url.host == "drive.google.com") {
            val segments = url.pathSegments
            val id = if (segments.take(2) == listOf("file", "d")) segments.getOrNull(2)
                else if (segments.firstOrNull() in setOf("open", "uc")) url.queryParameter("id") else null
            require(id != null && id.matches(Regex("[A-Za-z0-9_-]{10,200}"))) { "Нужна ссылка Google Drive на JSON-файл копии, а не папку или документ" }
            val download = "https://drive.google.com/uc".toHttpUrl().newBuilder()
                .addQueryParameter("export", "download").addQueryParameter("id", id)
            url.queryParameter("resourcekey")?.let { download.addQueryParameter("resourcekey", it) }
            return CloudBackupLink(download.build().toString(), "Google Drive")
        }
        return CloudBackupLink(url.toString(), url.host)
    }

    suspend fun load(raw: String, fetch: suspend (String, Int) -> BackupLinkResponse): Pair<String, String> {
        val link = resolve(raw)
        var current = link.url
        if (link.yandex) {
            val metadata = fetch(current, 64 * 1024)
            require(metadata.code in 200..299) { "Не удалось открыть файл Яндекс Диска. Проверьте публичный доступ к файлу." }
            val result = JsonParser.parseString(metadata.text).asJsonObject
            require(!result.has("method") || result["method"].asString == "GET")
            current = https(requireNotNull(result["href"]) { "Облако не вернуло ссылку скачивания" }.asString).toString()
        }
        repeat(6) { attempt ->
            val response = fetch(current, DeviceTransferProtocol.MAX_DOCUMENT_BYTES)
            if (response.code in setOf(301, 302, 303, 307, 308)) {
                require(attempt < 5) { "Слишком много перенаправлений облачной ссылки" }
                val next = requireNotNull(https(current).resolve(requireNotNull(response.location))) { "Некорректное перенаправление" }
                current = https(next.toString()).toString()
            } else {
                require(response.code in 200..299) { "Облако не отдало файл (HTTP ${response.code}). Проверьте доступ по ссылке." }
                val text = response.text.trimStart('\uFEFF', ' ', '\r', '\n', '\t')
                require(text.startsWith('{')) { "По ссылке открывается страница или архив, а не JSON-копия. Откройте доступ к файлу или используйте прямую ссылку скачивания." }
                return text to link.source
            }
        }
        error("Не удалось загрузить копию")
    }
}
