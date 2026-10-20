package com.danieldk.splatevpn.ui.theme

internal val AppStrings.connectionTools get() = ConnectionToolsStrings(this is RussianAppStrings)

internal class ConnectionToolsStrings(private val ru: Boolean) {
    val balancerSettings get() = if (ru) "Настройки балансировщика" else "Balancer settings"
    val balanced get() = if (ru) "Автоматический · по умолчанию" else "Automatic · default"
    val balancedHint get() = if (ru) "Выбирает сервер при подключении, заменяет недоступный и ищет более быстрый при ухудшении связи." else "Selects a server on connect, replaces failed nodes and looks for a faster one when quality drops."
    val failover get() = if (ru) "Только при сбое" else "On failure only"
    val failoverHint get() = if (ru) "Сохраняет рабочий сервер. Переключается, только когда несколько проверок подряд подтвердили сбой." else "Keeps a working server. Switches only after consecutive checks confirm a failure."
    val initial get() = if (ru) "Только при подключении" else "On connect only"
    val initialHint get() = if (ru) "Один раз выбирает сервер при подключении. Во время сеанса не переключает, даже если сервер перестал отвечать." else "Selects a server once on connect. Never switches during the session, even if that server stops responding."
    val healthInterval get() = if (ru) "Интервал проверки связи" else "Connection check interval"
    val failureCount get() = if (ru) "Неудачных проверок до смены" else "Failed checks before switching"
    val optimizationInterval get() = if (ru) "Плановый поиск лучшего сервера" else "Scheduled search for a better server"
    val cooldown get() = if (ru) "Пауза между улучшениями" else "Pause between improvements"
    val improvement get() = if (ru) "Минимальный выигрыш по задержке" else "Minimum latency improvement"
    val reset get() = if (ru) "Вернуть настройки по умолчанию" else "Restore default settings"
    fun seconds(value: Int) = if (ru) "$value с" else "$value s"
    fun minutes(value: Int) = if (ru) "$value мин" else "$value min"
    val disabledHint get() = if (ru) "Параметры применятся, когда включён автовыбор сервера." else "These options apply while auto-select is enabled."
    val speedMethod get() = if (ru) "Загрузка через VPN · 2 потока · 10 с · до 128 МБ" else "VPN download · 2 streams · 10 s · up to 128 MB"
    val speedStart get() = if (ru) "Начать тест скорости" else "Start speed test"
    val speedStop get() = if (ru) "Остановить тест" else "Stop speed test"
    val vpnRequired get() = if (ru) "Сначала подключите VPN" else "Connect the VPN first"
    val vpnChanged get() = if (ru) "Соединение изменилось. Запустите тест заново." else "The connection changed. Run the test again."
    val timeout get() = if (ru) "Сервер теста не ответил вовремя" else "The test server timed out"
    val dns get() = if (ru) "Не удалось определить адрес сервера" else "Could not resolve the test server"
    val network get() = if (ru) "Сервер теста недоступен. Попробуйте другой VPN-узел." else "Test server unavailable. Try another VPN node."
    val invalidSample get() = if (ru) "Недостаточно данных для достоверного замера" else "Not enough data for a reliable measurement"
    val sampleDetails get() = if (ru) "%.1f МБ · %.1f с измерения" else "%.1f MB · %.1f s measured"
    val megabytesPerSecond get() = if (ru) "МБ/с" else "MB/s"
    val megabytes get() = if (ru) "МБ" else "MB"
}
