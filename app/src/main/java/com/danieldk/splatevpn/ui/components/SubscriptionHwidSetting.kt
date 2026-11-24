package com.danieldk.splatevpn.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhonelinkLock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.danieldk.splatevpn.R
import com.danieldk.splatevpn.ui.theme.LocalAppResources
import com.danieldk.splatevpn.ui.theme.LocalAppThemeColors

@Composable
internal fun SubscriptionHwidSetting(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    val resources = LocalAppResources.current
    val colors = LocalAppThemeColors.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Default.PhonelinkLock, null, tint = colors.accentAmber, modifier = Modifier.size(20.dp))
            Text(resources.getString(R.string.subscription_hwid_title), Modifier.weight(1f),
                color = colors.textPrimary, style = MaterialTheme.typography.bodyMedium)
            SplateSwitch(checked, onCheckedChange, enabled = enabled,
                modifier = Modifier.testTag("subscription_hwid_switch"))
        }
        Text(resources.getString(R.string.subscription_hwid_hint), color = colors.textSecondary,
            style = MaterialTheme.typography.bodySmall)
    }
}
