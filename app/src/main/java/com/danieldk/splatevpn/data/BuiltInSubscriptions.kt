package com.danieldk.splatevpn.data

import com.danieldk.splatevpn.SplateConstants as C

internal enum class PresetGroup { LEGACY, UNIVERSAL, EXPANDED_WHITELISTS, SPONSOR, BLACK_LISTS, RKP }

internal data class BuiltInSubscription(
    val id: String, val name: String, val url: String, val asset: String, val group: PresetGroup
)

/** Different publishers may aggregate some of the same public servers. */
internal val builtInSubscriptions = listOf(
    BuiltInSubscription(C.PRESET_SUB_CIDR_ID, C.PRESET_SUB_CIDR_NAME, C.PRESET_SUB_CIDR_URL,
        "preset_white_cidr.txt", PresetGroup.LEGACY),
    BuiltInSubscription(C.PRESET_SUB_HARVESTER_ID, C.PRESET_SUB_HARVESTER_NAME, C.PRESET_SUB_HARVESTER_URL,
        "preset_harvester.txt", PresetGroup.LEGACY),
    BuiltInSubscription(C.PRESET_SUB_UNIVERSAL_ID, C.PRESET_SUB_UNIVERSAL_NAME, C.PRESET_SUB_UNIVERSAL_URL,
        "preset_white_universal.txt", PresetGroup.UNIVERSAL),
    BuiltInSubscription(C.PRESET_SUB_LTE_ID, C.PRESET_SUB_LTE_NAME, C.PRESET_SUB_LTE_URL,
        "preset_white_lte_checked.txt", PresetGroup.EXPANDED_WHITELISTS),
    BuiltInSubscription(C.PRESET_SUB_BLACK_ID, C.PRESET_SUB_BLACK_NAME, C.PRESET_SUB_BLACK_URL,
        "preset_black_vless.txt", PresetGroup.BLACK_LISTS),
    BuiltInSubscription(C.PRESET_SUB_RKP_WL_ID, "РКП · Белый список",
        "https://hub.mos.ru/rkp/sub-roskompozor/raw/main/wl", "", PresetGroup.RKP),
    BuiltInSubscription(C.PRESET_SUB_RKP_BL_ID, "РКП · Чёрный список",
        "https://hub.mos.ru/rkp/sub-roskompozor/raw/main/bl", "", PresetGroup.RKP)
) + listOfNotNull(C.PRESET_SUB_SPONSOR_URL.takeIf { it.isNotEmpty() }?.let { url ->
    BuiltInSubscription(C.PRESET_SUB_SPONSOR_ID, C.PRESET_SUB_SPONSOR_NAME, url, "", PresetGroup.SPONSOR)
})


internal fun isProtectedWtpn(id: String, url: String? = null): Boolean =
    id == C.PRESET_SUB_SPONSOR_ID || url?.let {
        SubscriptionHwidPolicy.canonicalUrl(it)?.toString() == C.PRESET_SUB_SPONSOR_URL
    } == true
