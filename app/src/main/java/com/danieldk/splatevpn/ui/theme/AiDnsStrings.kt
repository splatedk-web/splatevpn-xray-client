package com.danieldk.splatevpn.ui.theme

internal val AppStrings.aiDnsTools get() = AiDnsStrings(this is RussianAppStrings)
internal class AiDnsStrings(private val ru: Boolean) {
    val title get() = if (ru) "Автоподбор SmartDNS" else "SmartDNS auto-selection"
    val hint get() = if (ru) "Проверит провайдеров с TLS-фрагментацией и без неё и применит лучший подтверждённый вариант. Может занять несколько минут." else "Tests providers with and without TLS fragmentation and applies the best confirmed option. May take a few minutes."
    val scope get() = if (ru) "Проверяются DNS, HTTPS и публичные страницы Gemini, ChatGPT и Claude. У финалистов Gemini проверяется после JavaScript. Вход и работа аккаунта не проверяются." else "Checks DNS, HTTPS and public Gemini, ChatGPT and Claude pages. Finalists also load Gemini with JavaScript. Account sign-in and functionality are not checked."
    val start get() = if (ru) "Подобрать и применить" else "Find and apply"
    val stop get() = if (ru) "Остановить подбор" else "Stop selection"
    val verifying get() = if (ru) "Повторная проверка" else "Confirming result"
    val noMatch get() = if (ru) "Автопроверка не смогла подтвердить Gemini. Это не означает, что он не работает в вашем браузере: вход в аккаунт и ограничения WebView могут мешать проверке. Настройки не изменены. Подробности — ниже." else "The automatic check could not confirm Gemini. It may still work in your browser: sign-in and WebView restrictions can prevent verification. Settings unchanged. Details below."
    val cancelled get() = if (ru) "Подбор остановлен. Настройки не изменены." else "Selection stopped. Settings unchanged."
    fun error(code: String?) = when (code) {
        "network_changed" -> if (ru) "Сеть изменилась. Запустите подбор заново." else "Network changed. Start selection again."
        "network_missing" -> if (ru) "Нет подключения к сети." else "No network connection."
        else -> if (ru) "Не удалось выполнить проверку. Настройки не изменены." else "Could not complete the check. Settings unchanged."
    }
    fun checking(done: Int, total: Int) = if (ru) "Проверено $done из $total" else "Checked $done of $total"
    fun fragment(enabled: Boolean) = if (ru) "TLS: ${if (enabled) "вкл" else "выкл"}" else "TLS: ${if (enabled) "on" else "off"}"
    fun applied(label: String, count: Int) = if (ru) "Применён $label · доступно страниц: $count/3" else "Applied $label · accessible pages: $count/3"
    fun details(count: Int) = if (ru) "Результаты ($count)" else "Results ($count)"
    fun detail(result: com.danieldk.splatevpn.data.AiDnsTargetResult): String = when (result.detail) {
        com.danieldk.splatevpn.data.AiDnsDetail.DNS_HTTP -> if (ru) "отказ DoH" else "DoH rejected"
        com.danieldk.splatevpn.data.AiDnsDetail.DNS_CONNECTION -> if (ru) "не удалось соединиться с DNS" else "DNS connection failed"
        com.danieldk.splatevpn.data.AiDnsDetail.DNS_TIMEOUT -> if (ru) "DNS не ответил вовремя" else "DNS timed out"
        com.danieldk.splatevpn.data.AiDnsDetail.DNS_INVALID -> if (ru) "DNS не вернул корректный публичный IPv4" else "DNS returned no valid public IPv4"
        com.danieldk.splatevpn.data.AiDnsDetail.DNS_CONTENT -> if (ru) "вместо DNS получен другой ответ" else "not a DNS response"
        com.danieldk.splatevpn.data.AiDnsDetail.CONNECTION -> if (ru) "ошибка соединения / TLS" else "connection / TLS failure"
        com.danieldk.splatevpn.data.AiDnsDetail.CONNECTION_TIMEOUT -> if (ru) "сайт не ответил вовремя" else "site timed out"
        com.danieldk.splatevpn.data.AiDnsDetail.TLS_FAILURE -> if (ru) "не удалось установить TLS с сайтом" else "site TLS handshake failed"
        com.danieldk.splatevpn.data.AiDnsDetail.HTTP_REPLY -> if (ru) "DNS проверен · ответ сайта" else "DNS checked · site response"
        com.danieldk.splatevpn.data.AiDnsDetail.JS_LOADED -> if (ru) "страница загружена с JavaScript; аккаунт не проверен" else "JavaScript page loaded; account not checked"
        com.danieldk.splatevpn.data.AiDnsDetail.JS_LIMITED -> if (ru) "JavaScript: вход, CAPTCHA или ошибка страницы" else "JavaScript: sign-in, CAPTCHA or page error"
        com.danieldk.splatevpn.data.AiDnsDetail.JS_GEO -> if (ru) "JavaScript: региональный запрет" else "JavaScript: region blocked"
        com.danieldk.splatevpn.data.AiDnsDetail.JS_TIMEOUT -> if (ru) "JavaScript: время проверки истекло" else "JavaScript: check timed out"
        com.danieldk.splatevpn.data.AiDnsDetail.JS_UNAVAILABLE -> if (ru) "для браузерной проверки нужны Android 9+ и актуальный Android System WebView" else "browser check requires Android 9+ and an up-to-date Android System WebView"
        com.danieldk.splatevpn.data.AiDnsDetail.JS_FAILED -> if (ru) "браузерная проверка не завершена" else "browser check did not complete"
        null -> ""
    }.let { if (result.httpCode != null) "$it · HTTP ${result.httpCode}" else it }
    fun outcome(value: com.danieldk.splatevpn.data.AiDnsOutcome) = when (value) {
        com.danieldk.splatevpn.data.AiDnsOutcome.ACCESSIBLE -> if (ru) "доступно" else "accessible"
        com.danieldk.splatevpn.data.AiDnsOutcome.LIMITED -> if (ru) "ограничено" else "limited"
        com.danieldk.splatevpn.data.AiDnsOutcome.GEO_BLOCKED -> if (ru) "регион заблокирован" else "region blocked"
        com.danieldk.splatevpn.data.AiDnsOutcome.FAILED -> if (ru) "нет ответа" else "failed"
    }
}
