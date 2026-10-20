package com.danieldk.splatevpn.ui.screens

import com.danieldk.splatevpn.SplateConstants
import com.danieldk.splatevpn.ui.theme.AppStrings

internal fun subscriptionDisplayName(id: String, name: String, strings: AppStrings): String = when {
    id == SplateConstants.PRESET_SUB_HARVESTER_ID && name == SplateConstants.PRESET_SUB_HARVESTER_NAME -> strings.uiPresetHarvester
    id == SplateConstants.PRESET_SUB_CIDR_ID && name == SplateConstants.PRESET_SUB_CIDR_NAME -> strings.uiPresetCidr
    id == SplateConstants.PRESET_SUB_UNIVERSAL_ID && name == SplateConstants.PRESET_SUB_UNIVERSAL_NAME -> strings.uiPresetUniversal
    else -> name
}
