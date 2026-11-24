package com.danieldk.splatevpn

/**
 * Централизованные константы приложения SplateVPN.
 * Все магические числа и строковые литералы, используемые в нескольких модулях,
 * должны объявляться здесь для единообразия и упрощения конфигурации.
 */
object SplateConstants {
    const val REPOSITORY_URL = "https://github.com/splatedk-web/splatevpn-xray-client"
    const val XRAY_VERSION = BuildConfig.XRAY_VERSION
    const val GEO_DATA_REVISION = "202610222206"

    /** Порт локального SOCKS5 прокси Xray Core */
    const val XRAY_SOCKS_PORT = 10808
    /** Private SOCKS listener for DNS tests; bypasses TUN DNS interception. */
    const val DNS_CHECK_SOCKS_PORT = 10818

    /** Адрес loopback для привязки SOCKS5 прокси */
    const val XRAY_SOCKS_ADDRESS = "127.0.0.1"

    /** Virtual DNS endpoint intercepted by Xray inside the TUN subnet. */
    const val TUN_DNS_ADDRESS = "172.19.0.2"

    /** Предустановленная подписка White Lists CIDR RU */
    const val PRESET_SUB_CIDR_ID = "preset_white_cidr"
    const val PRESET_SUB_CIDR_NAME = "Белый список (CIDR RU)"
    const val PRESET_SUB_CIDR_URL = "https://translate.yandex.ru/translate?url=https://white.igareck.net/WHITE-CIDR-RU-all.txt&lang=de-de"
    const val PRESET_SUB_CIDR_FALLBACK_URL = "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/refs/heads/main/WHITE-CIDR-RU-all.txt"

    /** Предустановленная подписка Harvester */
    const val PRESET_SUB_HARVESTER_ID = "preset_harvester"
    const val PRESET_SUB_HARVESTER_NAME = "Harvester (Рабочие узлы)"
    const val PRESET_SUB_HARVESTER_URL = "https://raw.githubusercontent.com/luxxuria/harvester/refs/heads/main/ping_tested.txt"

    /** Дополнительный белый список VLESS Universal */
    const val PRESET_SUB_UNIVERSAL_ID = "preset_white_universal"
    const val PRESET_SUB_UNIVERSAL_NAME = "Белый список (Universal)"
    const val PRESET_SUB_UNIVERSAL_URL = "https://raw.githubusercontent.com/zieng2/wl/refs/heads/main/vless_universal.txt"

    const val PRESET_SUB_LTE_ID = "preset_white_lte_checked"
    const val PRESET_SUB_LTE_NAME = "Белый список (LTE Checked)"
    const val PRESET_SUB_LTE_URL = "https://raw.githubusercontent.com/hussaroff/lte-universal-checked/main/whitelist.txt"

    const val PRESET_SUB_SPONSOR_ID = "preset_sponsor"
    const val PRESET_SUB_SPONSOR_NAME = "🌐 WTPN 🔐"
    val PRESET_SUB_SPONSOR_URL = BuildConfig.PRIVATE_SUBSCRIPTION_URL

    /** Черный список VLESS (Black VLESS RUS) */
    const val PRESET_SUB_RKP_WL_ID = "preset_rkp_wl"
    const val PRESET_SUB_RKP_BL_ID = "preset_rkp_bl"
    const val PRESET_SUB_BLACK_ID = "preset_black_vless"
    const val PRESET_SUB_BLACK_NAME = "Черный список (Black VLESS)"
    const val PRESET_SUB_BLACK_URL = "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/refs/heads/main/BLACK_VLESS_RUS.txt"
    const val PRESET_SUB_BLACK_FALLBACK_URL = "https://gitlab.com/igareck/vpn-configs-for-russia/-/raw/main/BLACK_VLESS_RUS.txt"

    /** Порт локального LAN HTTP прокси для раздачи по Wi-Fi / USB */
    const val XRAY_LAN_HTTP_PORT = 10809

    /** Порт локального LAN SOCKS5 прокси для раздачи по Wi-Fi / USB */
    const val XRAY_LAN_SOCKS_PORT = 10808

    val PRESET_SUB_IDS = setOf(PRESET_SUB_CIDR_ID, PRESET_SUB_HARVESTER_ID, PRESET_SUB_UNIVERSAL_ID,
        PRESET_SUB_LTE_ID, PRESET_SUB_SPONSOR_ID, PRESET_SUB_BLACK_ID, PRESET_SUB_RKP_WL_ID, PRESET_SUB_RKP_BL_ID)
}
