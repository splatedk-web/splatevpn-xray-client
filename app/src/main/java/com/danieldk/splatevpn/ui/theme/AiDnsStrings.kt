package com.danieldk.splatevpn.ui.theme

internal val AppStrings.aiDnsTools get() = AiDnsStrings(this)
internal class AiDnsStrings(private val strings: AppStrings) {
    val autoSwitchTitle get() = strings.localized("Автопереключение SmartDNS", "SmartDNS failover")
    val autoSwitchHint get() = strings.localized("При подключённом VPN проверяет публичные страницы каждые 5 минут в выбранном режиме SmartDNS. При повторных сбоях подтверждает замену и переподключает VPN. CAPTCHA и вход не считаются сбоем.", "While connected, checks public pages every 5 minutes using the selected SmartDNS mode. Repeated failures trigger a verified replacement and a brief VPN reconnect. CAPTCHA and sign-in are not failures.")
    val viaVpn get() = strings.localized("SmartDNS через VPN", "SmartDNS via VPN")
    val viaVpnHint get() = strings.localized("Запросы DNS и подключение к SmartDNS-прокси идут через выбранный VPN-сервер. Автоподбор проверяет этот же путь. Для работы сервиса также важны регион выхода SmartDNS и настройки аккаунта.", "DNS queries and the SmartDNS proxy connection use the selected VPN server. Auto-selection tests this same route. Service access also depends on the SmartDNS exit region and account settings.")
    val title get() = strings.localized("Автоподбор SmartDNS", "SmartDNS auto-selection")
    val hint get() = strings.localized("Проверит провайдеров в выбранном режиме и применит подтверждённый вариант. В прямом режиме проверяется также TLS-фрагментация. Может занять несколько минут.", "Tests providers in the selected mode and applies a confirmed option. Direct mode also tests TLS fragmentation. May take a few minutes.")
    val scope get() = strings.localized("Проверяются DNS, HTTPS и публичные страницы Gemini, ChatGPT и Claude. У финалистов Gemini проверяется после JavaScript. Вход и работа аккаунта не проверяются.", "Checks DNS, HTTPS and public Gemini, ChatGPT and Claude pages. Finalists also load Gemini with JavaScript. Account sign-in and functionality are not checked.")
    val start get() = strings.localized("Подобрать и применить", "Find and apply")
    val stop get() = strings.localized("Остановить подбор", "Stop selection")
    val verifying get() = strings.localized("Повторная проверка", "Confirming result")
    val noMatch get() = strings.localized("Автопроверка не смогла подтвердить Gemini. Это не означает, что он не работает в вашем браузере: вход в аккаунт и ограничения WebView могут мешать проверке. Настройки не изменены. Подробности — ниже.", "The automatic check could not confirm Gemini. It may still work in your browser: sign-in and WebView restrictions can prevent verification. Settings unchanged. Details below.")
    val cancelled get() = strings.localized("Подбор остановлен. Настройки не изменены.", "Selection stopped. Settings unchanged.")
    fun error(code: String?) = when (code) {
        "network_changed" -> strings.localized("Сеть изменилась. Запустите подбор заново.", "Network changed. Start selection again.")
        "network_missing" -> strings.localized("Нет подключения к сети.", "No network connection.")
        else -> strings.localized("Не удалось выполнить проверку. Настройки не изменены.", "Could not complete the check. Settings unchanged.")
    }
    fun checking(done: Int, total: Int) = String.format(strings.localized("Проверено %d из %d", "Checked %d of %d"), done, total)
    fun fragment(enabled: Boolean) = "TLS: " + if (enabled) strings.onUpper else strings.offUpper
    fun applied(label: String, count: Int) = String.format(strings.localized("Применён %s · доступно страниц: %d/3", "Applied %s · accessible pages: %d/3"), label, count)
    fun details(count: Int) = String.format(strings.localized("Результаты (%d)", "Results (%d)"), count)
    fun detail(result: com.danieldk.splatevpn.data.AiDnsTargetResult): String = when (result.detail) {
        com.danieldk.splatevpn.data.AiDnsDetail.DNS_HTTP -> strings.localized("отказ DoH", "DoH rejected")
        com.danieldk.splatevpn.data.AiDnsDetail.DNS_CONNECTION -> strings.localized("не удалось соединиться с DNS", "DNS connection failed")
        com.danieldk.splatevpn.data.AiDnsDetail.DNS_TIMEOUT -> strings.localized("DNS не ответил вовремя", "DNS timed out")
        com.danieldk.splatevpn.data.AiDnsDetail.DNS_INVALID -> strings.localized("DNS не вернул корректный публичный IPv4", "DNS returned no valid public IPv4")
        com.danieldk.splatevpn.data.AiDnsDetail.DNS_CONTENT -> strings.localized("вместо DNS получен другой ответ", "not a DNS response")
        com.danieldk.splatevpn.data.AiDnsDetail.CONNECTION -> strings.localized("ошибка соединения / TLS", "connection / TLS failure")
        com.danieldk.splatevpn.data.AiDnsDetail.CONNECTION_TIMEOUT -> strings.localized("сайт не ответил вовремя", "site timed out")
        com.danieldk.splatevpn.data.AiDnsDetail.TLS_FAILURE -> strings.localized("не удалось установить TLS с сайтом", "site TLS handshake failed")
        com.danieldk.splatevpn.data.AiDnsDetail.HTTP_REPLY -> strings.localized("DNS проверен · ответ сайта", "DNS checked · site response")
        com.danieldk.splatevpn.data.AiDnsDetail.JS_LOADED -> strings.localized("страница загружена с JavaScript; аккаунт не проверен", "JavaScript page loaded; account not checked")
        com.danieldk.splatevpn.data.AiDnsDetail.JS_LIMITED -> strings.localized("JavaScript: вход, CAPTCHA или ошибка страницы", "JavaScript: sign-in, CAPTCHA or page error")
        com.danieldk.splatevpn.data.AiDnsDetail.JS_GEO -> strings.localized("JavaScript: региональный запрет", "JavaScript: region blocked")
        com.danieldk.splatevpn.data.AiDnsDetail.JS_TIMEOUT -> strings.localized("JavaScript: время проверки истекло", "JavaScript: check timed out")
        com.danieldk.splatevpn.data.AiDnsDetail.JS_UNAVAILABLE -> strings.localized("для браузерной проверки нужны Android 9+ и актуальный Android System WebView", "browser check requires Android 9+ and an up-to-date Android System WebView")
        com.danieldk.splatevpn.data.AiDnsDetail.JS_FAILED -> strings.localized("браузерная проверка не завершена", "browser check did not complete")
        null -> ""
    }.let { if (result.httpCode != null) "$it · HTTP ${result.httpCode}" else it }
    fun outcome(value: com.danieldk.splatevpn.data.AiDnsOutcome) = when (value) {
        com.danieldk.splatevpn.data.AiDnsOutcome.ACCESSIBLE -> strings.localized("доступно", "accessible")
        com.danieldk.splatevpn.data.AiDnsOutcome.LIMITED -> strings.localized("ограничено", "limited")
        com.danieldk.splatevpn.data.AiDnsOutcome.GEO_BLOCKED -> strings.localized("регион заблокирован", "region blocked")
        com.danieldk.splatevpn.data.AiDnsOutcome.FAILED -> strings.localized("нет ответа", "failed")
    }
}
