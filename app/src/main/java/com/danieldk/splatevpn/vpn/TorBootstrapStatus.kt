package com.danieldk.splatevpn.vpn

/** UI labels are local constants. Never display Tor's raw SUMMARY/WARNING or relay identifiers. */
enum class TorBootstrapStage(val tag: String, val ru: String, val en: String) {
    STARTING("starting", "Запуск Tor", "Starting Tor"),
    TRANSPORT_CONNECTING("conn_pt", "Подключение транспорта мостов", "Connecting bridge transport"),
    TRANSPORT_READY("conn_done_pt", "Транспорт мостов подключён", "Bridge transport connected"),
    PROXY_CONNECTING("conn_proxy", "Подключение к прокси", "Connecting to proxy"),
    PROXY_READY("conn_done_proxy", "Прокси подключён", "Proxy connected"),
    RELAY_CONNECTING("conn", "Подключение к мосту или реле", "Connecting to bridge or relay"),
    RELAY_CONNECTED("conn_done", "Соединение с мостом или реле установлено", "Bridge or relay connected"),
    TLS_HANDSHAKE("handshake", "TLS-рукопожатие с мостом или реле", "TLS handshake with bridge or relay"),
    TLS_READY("handshake_done", "TLS-рукопожатие завершено", "TLS handshake complete"),
    DIRECTORY_TUNNEL("onehop_create", "Подготовка защищённого канала каталога", "Preparing directory channel"),
    REQUESTING_DIRECTORY("requesting_status", "Запрос каталога сети Tor", "Requesting Tor directory"),
    LOADING_DIRECTORY("loading_status", "Загрузка каталога сети Tor", "Downloading Tor directory"),
    LOADING_KEYS("loading_keys", "Загрузка ключей каталога", "Downloading directory keys"),
    REQUESTING_RELAYS("requesting_descriptors", "Запрос описаний реле", "Requesting relay descriptions"),
    LOADING_RELAYS("loading_descriptors", "Загрузка описаний реле", "Downloading relay descriptions"),
    DIRECTORY_READY("enough_dirinfo", "Каталог сети готов", "Directory ready"),
    CIRCUIT_TRANSPORT_CONNECTING("ap_conn_pt", "Подключение транспорта для цепочки", "Connecting circuit transport"),
    CIRCUIT_TRANSPORT_READY("ap_conn_done_pt", "Транспорт цепочки готов", "Circuit transport ready"),
    CIRCUIT_PROXY_CONNECTING("ap_conn_proxy", "Подключение прокси для цепочки", "Connecting circuit proxy"),
    CIRCUIT_PROXY_READY("ap_conn_done_proxy", "Прокси цепочки готов", "Circuit proxy ready"),
    CIRCUIT_RELAY_CONNECTING("ap_conn", "Подключение реле для цепочки", "Connecting circuit relay"),
    CIRCUIT_RELAY_READY("ap_conn_done", "Реле цепочки подключено", "Circuit relay connected"),
    CIRCUIT_TLS_HANDSHAKE("ap_handshake", "TLS-рукопожатие для цепочки", "Circuit TLS handshake"),
    CIRCUIT_TLS_READY("ap_handshake_done", "TLS цепочки готов", "Circuit TLS ready"),
    CREATING_CIRCUIT("circuit_create", "Построение цепочки Tor", "Building Tor circuit"),
    READY("done", "Цепочка Tor готова", "Tor circuit ready"),
    UNKNOWN("unknown", "Подключение к сети Tor", "Connecting to Tor network");

    companion object {
        fun fromTag(tag: String?): TorBootstrapStage = entries.firstOrNull { it.tag == tag } ?: UNKNOWN
    }
}

enum class TorBootstrapReason(val code: String, val ru: String, val en: String) {
    REFUSED("CONNECTREFUSED", "Мост или реле отклонили соединение", "Bridge or relay refused the connection"),
    IDENTITY("IDENTITY", "Не удалось проверить подлинность реле", "Relay identity could not be verified"),
    RESET("CONNECTRESET", "Соединение было сброшено", "Connection was reset"),
    TIMEOUT("TIMEOUT", "Мост или реле не ответили вовремя", "Bridge or relay did not respond in time"),
    NO_ROUTE("NOROUTE", "Сеть не может достичь моста или реле", "Network cannot reach bridge or relay"),
    IO_ERROR("IOERROR", "Ошибка связи с мостом или реле", "Bridge or relay communication error"),
    RESOURCES("RESOURCELIMIT", "Tor не хватает локальных ресурсов", "Tor lacks local resources"),
    TRANSPORT_MISSING("PT_MISSING", "Недоступен нужный транспорт мостов", "Required bridge transport is unavailable"),
    CLOSED("DONE", "Соединение с реле закрыто", "Relay connection closed"),
    OTHER("MISC", "Tor сообщил о проблеме соединения", "Tor reported a connection problem"),
    UNKNOWN("UNKNOWN", "Tor сообщил о проблеме соединения", "Tor reported a connection problem");

    companion object {
        fun fromCode(code: String?): TorBootstrapReason? = when {
            code == null -> null
            else -> entries.firstOrNull { it.code == code } ?: UNKNOWN
        }
    }
}

data class TorBootstrapStatus(
    val progress: Int = 0,
    val stage: TorBootstrapStage = TorBootstrapStage.STARTING,
    val warning: Boolean = false,
    val reason: TorBootstrapReason? = null
) {
    companion object {
        // Read only allowlisted machine fields. Quoted text can contain endpoints, keys or paths.
        private val field = Regex("(?:^|\\s)([A-Z_]+)=(\"(?:\\\\.|[^\"\\\\])*\"|[^\\s]+)")

        fun parse(raw: String): TorBootstrapStatus? {
            if (raw.length > 16_384 || !Regex("(?:^|\\s)BOOTSTRAP(?:\\s|$)").containsMatchIn(raw)) return null
            // Tokenize unknown fields too, so quoted SUMMARY text cannot impersonate a TAG.
            val fields = field.findAll(raw).filter { it.groupValues[1] in setOf("PROGRESS", "TAG", "REASON", "WARNING") }
                .associate { it.groupValues[1] to it.groupValues[2].removeSurrounding("\"") }
            val progress = fields["PROGRESS"]?.toIntOrNull()?.takeIf { it in 0..100 } ?: return null
            val warning = raw.substringBefore("BOOTSTRAP").trim().substringAfterLast(' ') == "WARN" || fields.containsKey("WARNING")
            return TorBootstrapStatus(progress, TorBootstrapStage.fromTag(fields["TAG"]), warning,
                TorBootstrapReason.fromCode(fields["REASON"]))
        }
    }
}

/** Tracks real progress with a monotonic clock; retries at lower percentages do not hide a stall. */
class TorBootstrapTracker(private val startedAtMs: Long) {
    var status: TorBootstrapStatus = TorBootstrapStatus()
        private set
    private var highestProgress = 0
    private var highestStage = TorBootstrapStage.STARTING
    private var lastAdvanceAtMs = startedAtMs

    fun update(next: TorBootstrapStatus, nowMs: Long) {
        if (next.progress > highestProgress || next.progress == highestProgress && next.stage != highestStage) {
            highestProgress = maxOf(highestProgress, next.progress)
            highestStage = next.stage
            lastAdvanceAtMs = nowMs
        }
        status = next
    }

    fun elapsedSeconds(nowMs: Long): Long = ((nowMs - startedAtMs).coerceAtLeast(0) / 1000)
    fun stalledSeconds(nowMs: Long): Long = ((nowMs - lastAdvanceAtMs).coerceAtLeast(0) / 1000)
}
