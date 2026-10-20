package com.danieldk.splatevpn.ui.screens

internal enum class SettingsCategory(val ru: String, val en: String, val hintsRu: String, val hintsEn: String, val keywords: String) {
    CONNECTION("Подключение", "Connection", "Автовыбор, балансировщик, запуск и подписки", "Auto-select, balancer, startup and subscriptions", "ping пинг auto автоматизация батарея battery boot плитка tile обновление update"),
    NETWORK("Сеть и DNS", "Network and DNS", "Маршрутизация, общий DNS и проверка серверов", "Routing, general DNS and server checks", "cloudflare google quad9 adguard yandex doh tcp real delay маршрут"),
    AI("Нейросети", "AI services", "SmartDNS, Gemini, ChatGPT и автоподбор", "SmartDNS, Gemini, ChatGPT and auto-selection", "claude geohide malw comss zapret dns-ai tls фрагментация обход регион"),
    PROTECTION("Защита и реклама", "Protection and ads", "Kill Switch, IPv6 и блокировщик рекламы", "Kill Switch, IPv6 and ad blocker", "adblock фильтр исключения filter whitelist утечки leak блокировка"),
    APPEARANCE("Оформление и язык", "Appearance and language", "Темы, прозрачность и размытие", "Themes, transparency and blur", "theme темы блюр blur glass стекло dark light светлая winx pony язык language"),
    BACKUP("Резервные копии", "Backup and restore", "Экспорт, восстановление и автокопия", "Export, restore and automatic snapshot", "backup бэкап бекап import импорт export экспорт копия"),
    DIAGNOSTICS("Диагностика", "Diagnostics", "Проверки подключения, статистика и журнал", "Connection checks, statistics and logs", "лог logs ошибка error трафик traffic speed скорость test тест"),
    ADVANCED("Дополнительно", "Advanced", "XHTTP, FakeDNS, QUIC, сниффинг и свои маршруты", "XHTTP, FakeDNS, QUIC, sniffing and custom routes", "tls fragment фрагментация mux multiplexing sniff transport транспорт домен domain"),
    SHARING("Раздача VPN", "VPN sharing", "Прокси в локальной сети и модем", "LAN proxy and tethering", "lan wifi hotspot usb tethering раздача прокси"),
    ABOUT("О приложении", "About", "Версия ядра и обновления приложения", "Core version and app updates", "xray architecture архитектура версия version обновить apk");

    fun title(russian: Boolean) = if (russian) ru else en
    fun hint(russian: Boolean) = if (russian) hintsRu else hintsEn
    fun matches(query: String): Boolean {
        val haystack = "$ru $en $hintsRu $hintsEn $keywords".lowercase()
        return query.trim().lowercase().split(Regex("\\s+")).all { it in haystack }
    }
}
