package com.danieldk.splatevpn.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danieldk.splatevpn.ui.theme.LocalAppStrings
import com.danieldk.splatevpn.ui.theme.LocalAppThemeColors

@Composable
internal fun EgressLocationCard(
    countryFlag: String, countryName: String, address: String, isDetecting: Boolean,
    onRefresh: () -> Unit, modifier: Modifier = Modifier
) {
    val colors = LocalAppThemeColors.current
    val strings = LocalAppStrings.current
    Row(modifier.fillMaxWidth().testTag("egressLocationCard")
        .liquidGlass(cornerRadius = 20, elevated = true, innerSheen = false)
        .padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.size(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(countryFlag.ifBlank { "🌐" }, fontSize = 22.sp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(countryName.ifBlank { if (isDetecting) strings.resolvingLocation else strings.protectedGateway },
                color = colors.textPrimary, style = MaterialTheme.typography.titleSmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("egressCountry"))
            if (address.isNotBlank()) {
                SpoilerAddress(
                    address,
                    Modifier.widthIn(max = 216.dp).fillMaxWidth(),
                    tag = "egressAddressSpoiler",
                    contentPadding = PaddingValues(vertical = 4.dp)
                )
            }
        }
        SplateIconButton(onClick = onRefresh, enabled = !isDetecting,
            modifier = Modifier.size(48.dp).testTag("egressRefresh")) {
            if (isDetecting) CircularProgressIndicator(Modifier.size(18.dp), color = colors.accentCyan, strokeWidth = 2.dp)
            else Icon(Icons.Default.MyLocation, strings.refresh, tint = colors.accentCyan, modifier = Modifier.size(20.dp))
        }
    }
}
