package com.danieldk.splatevpn.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.danieldk.splatevpn.data.SubscriptionMetadata
import com.danieldk.splatevpn.ui.theme.LocalAppStrings
import com.danieldk.splatevpn.ui.theme.LocalAppThemeColors
import com.danieldk.splatevpn.ui.theme.localized

@Composable
internal fun SubscriptionPublisherDetails(metadataJson: String, compact: Boolean = false, framed: Boolean = true) {
    val metadata = remember(metadataJson) { SubscriptionMetadata.decode(metadataJson) }
    if (metadata.announcement.isBlank() && metadata.website.isBlank() && metadata.support.isBlank() &&
        (compact || metadata.updateHours == 0)) return
    val colors = LocalAppThemeColors.current
    val strings = LocalAppStrings.current
    val uriHandler = LocalUriHandler.current
    var linkError by remember { mutableStateOf(false) }
    val container = Modifier.fillMaxWidth().padding(top = if (framed) {
        if (compact) 6.dp else 12.dp
    } else 0.dp).then(if (framed) Modifier.liquidGlass(
        cornerRadius = 12, backgroundColor = colors.surface.copy(alpha = .97f), innerSheen = false
    ).padding(12.dp) else Modifier)
    CompositionLocalProvider(LocalGlassBackdrop provides null) {
    Column(container, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (metadata.announcement.isNotBlank()) Text(metadata.announcement,
            color = colors.textPrimary, style = MaterialTheme.typography.bodyMedium,
            maxLines = if (compact) 3 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
        if (metadata.updateHours > 0 && !compact) Text(strings.localized("Интервал сервера: %d ч", "Server update interval: %d h").format(metadata.updateHours),
            color = colors.textSecondary, style = MaterialTheme.typography.labelMedium)
        if (metadata.website.isNotBlank() || metadata.support.isNotBlank()) Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Triple(metadata.website, strings.localized("Сайт", "Website"), Icons.Default.Language),
                Triple(metadata.support, strings.localized("Поддержка", "Support"), Icons.Default.SupportAgent))
                .filter { it.first.isNotBlank() }.forEach { (url, label, icon) ->
                    SplateOutlinedButton(onClick = { linkError = runCatching { uriHandler.openUri(url) }.isFailure },
                        modifier = Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(8.dp)) {
                        Icon(icon, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(label, style = MaterialTheme.typography.labelMedium)
                    }
                }
        }
        if (linkError) Text(strings.localized("Не удалось открыть ссылку", "Could not open the link"),
            color = colors.accentCrimson, style = MaterialTheme.typography.bodySmall)
    }
    }
}
