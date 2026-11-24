package com.danieldk.splatevpn.ui.screens

internal enum class SettingsCategory(val ru: String, val en: String, val hintsRu: String, val hintsEn: String, val keywords: String) {
    CONNECTION("Подключение", "Connection", "Уведомления, разрешения, сеть и балансировщик", "Notifications, permissions, network and balancer", "ping пинг wifi wi-fi вайфай auto автоматизация батарея battery boot плитка tile обновление update уведомления уведомление notifications permissions разрешения автозапуск autostart желательно recommended"),
    TOR("Tor и мосты", "Tor and bridges", "Мосты, подписки GitHub и выбор нод", "Bridges, GitHub subscriptions and node selection", "tor мост мосты bridge bridges onion github ноды nodes"),
    NETWORK("Сеть и DNS", "Network and DNS", "Маршрутизация, общий DNS и проверка серверов", "Routing, general DNS and server checks", "cloudflare google quad9 adguard yandex doh tcp real delay маршрут"),
    ROUTING("Правила и маршруты", "Rules and routing", "AND/OR, сайты, IP, порты и профили сети", "AND/OR, domains, IPs, ports and network profiles", "and or логика составные правила маршруты суффикс keyword domain routing rules"),
    AI("Нейросети", "AI services", "SmartDNS, Gemini, ChatGPT и автоподбор", "SmartDNS, Gemini, ChatGPT and auto-selection", "claude geohide malw zapret dns-ai tls фрагментация обход регион"),
    PROTECTION("Защита и реклама", "Protection and ads", "Kill Switch, IPv6 и блокировщик рекламы", "Kill Switch, IPv6 and ad blocker", "adblock фильтр исключения filter whitelist утечки leak блокировка"),
    APPEARANCE("Оформление и язык", "Appearance and language", "Темы, прозрачность и размытие", "Themes, transparency and blur", "theme темы блюр blur glass стекло dark light светлая winx pony язык language"),
    BACKUP("Резервные копии", "Backup and restore", "Импорт по облачной ссылке, экспорт и восстановление", "Cloud link import, export and restore", "backup cloud drive google link облако ссылка бэкап бекап import импорт export экспорт копия"),
    SYNC("Перенос и синхронизация", "Transfer and sync", "Облачные копии, Android TV, Wi-Fi QR и WebDAV", "Cloud backup, Android TV, Wi-Fi QR and WebDAV", "sync lan transfer webdav cloud drive google облако автокопия приватность privacy синхронизация перенос телевизор tv qr wifi"),
    DIAGNOSTICS("Диагностика", "Diagnostics", "Проверки, журнал и отчёты об ошибках", "Checks, logs and error reports", "лог logs ошибка error трафик traffic speed скорость test тест отчёт отчет reports crash вылет"),
    ADVANCED("Дополнительно", "Advanced", "XHTTP, FakeDNS, QUIC, сниффинг и Mux", "XHTTP, FakeDNS, QUIC, sniffing and Mux", "tls fragment фрагментация mixed sni mtu мту mux multiplexing sniff transport транспорт"),
    SHARING("Прокси и раздача VPN", "Proxy and VPN sharing", "Mixed, авторизация, локальная сеть и модем", "Mixed, authentication, LAN and tethering", "lan wifi hotspot usb tethering раздача прокси mixed socks http username password логин пароль авторизация"),
    ABOUT("О приложении", "About", "Версия ядра и обновления приложения", "Core version and app updates", "xray architecture архитектура версия version обновить apk");

    fun title(russian: Boolean) = if (russian) ru else en
    fun hint(russian: Boolean) = if (russian) hintsRu else hintsEn
    fun matches(query: String): Boolean {
        val haystack = "$ru $en $hintsRu $hintsEn $keywords".lowercase()
        return query.trim().lowercase().split(Regex("\\s+")).all { it in haystack }
    }
}

internal fun visibleSettingsCategories(mode: com.danieldk.splatevpn.data.AppUiMode): List<SettingsCategory> =
    if (mode.isSimple) listOf(
        SettingsCategory.APPEARANCE,
        SettingsCategory.PROTECTION,
        SettingsCategory.ABOUT
    ) else SettingsCategory.entries
