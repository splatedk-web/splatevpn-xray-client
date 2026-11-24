package com.danieldk.splatevpn.ui.screens

import com.danieldk.splatevpn.SplateConstants
import com.danieldk.splatevpn.ui.theme.AppStrings
import com.danieldk.splatevpn.ui.theme.localized

internal fun subscriptionDisplayName(id: String, name: String, strings: AppStrings): String = when {
    id == SplateConstants.PRESET_SUB_HARVESTER_ID && name == SplateConstants.PRESET_SUB_HARVESTER_NAME -> strings.uiPresetHarvester
    id == SplateConstants.PRESET_SUB_CIDR_ID && name == SplateConstants.PRESET_SUB_CIDR_NAME -> strings.uiPresetCidr
    id == SplateConstants.PRESET_SUB_UNIVERSAL_ID && name == SplateConstants.PRESET_SUB_UNIVERSAL_NAME -> strings.uiPresetUniversal
    id == SplateConstants.PRESET_SUB_LTE_ID && name == SplateConstants.PRESET_SUB_LTE_NAME ->
        if (strings is com.danieldk.splatevpn.ui.theme.ResourceAppStrings)
            strings.resources.getString(com.danieldk.splatevpn.R.string.preset_lte_name)
        else strings.localized(name, "Allowlist (LTE Checked)")
    id == SplateConstants.PRESET_SUB_BLACK_ID && name == SplateConstants.PRESET_SUB_BLACK_NAME ->
        if (strings is com.danieldk.splatevpn.ui.theme.ResourceAppStrings)
            strings.resources.getString(com.danieldk.splatevpn.R.string.preset_black_name)
        else strings.localized(name, "Blacklist (Black VLESS)")
    else -> name
}
