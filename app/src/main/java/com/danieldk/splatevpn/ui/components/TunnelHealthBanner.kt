package com.danieldk.splatevpn.ui.components

import com.danieldk.splatevpn.ui.components.SplateIconButton as IconButton

import com.danieldk.splatevpn.ui.components.SplateButton as Button
import com.danieldk.splatevpn.ui.components.SplateOutlinedButton as OutlinedButton

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danieldk.splatevpn.data.HealthIssueKind
import com.danieldk.splatevpn.data.TunnelHealthIssue
import com.danieldk.splatevpn.ui.theme.AppThemeColors
import com.danieldk.splatevpn.ui.theme.LocalAppStrings
import com.danieldk.splatevpn.ui.theme.localized

@Composable
fun TunnelHealthBanner(
    issue: TunnelHealthIssue,
    onPrimaryAction: () -> Unit,
    onSecondaryAction: () -> Unit,
    onDismiss: () -> Unit,
    colors: AppThemeColors,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current

    val accentColor = when (issue.kind) {
        HealthIssueKind.NETWORK_UNAVAILABLE -> colors.accentAmber
        HealthIssueKind.CONNECTIVITY_UNVERIFIED -> colors.accentAmber
        HealthIssueKind.DNS_BLOCKED -> if (issue.dnsIssue?.candidate != null) colors.accentEmerald else colors.accentAmber
        HealthIssueKind.SILENT_BLACKHOLE -> colors.accentAmber
        HealthIssueKind.REALITY_TLS_FAILURE -> colors.accentAmber
        HealthIssueKind.CAPTIVE_PORTAL -> colors.accentCyan
        HealthIssueKind.MTU_FRAGMENTATION -> colors.accentViolet
        HealthIssueKind.TLS_FRAGMENTATION -> colors.accentViolet
        HealthIssueKind.GEO_BLOCKED_DOMAIN -> colors.accentEmerald
        HealthIssueKind.TIME_DESYNC -> colors.accentAmber
        HealthIssueKind.ROUTING_LOOP -> colors.accentEmerald
    }
    val borderColor = accentColor.copy(alpha = 0.50f)

    Surface(
        modifier = modifier.fillMaxWidth().liquidGlass(cornerRadius = 16, accentBorder = accentColor),
        color = androidx.compose.ui.graphics.Color.Transparent,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = when (issue.kind) {
                        HealthIssueKind.NETWORK_UNAVAILABLE -> Icons.Default.Wifi
                        HealthIssueKind.CONNECTIVITY_UNVERIFIED -> Icons.Default.WarningAmber
                        HealthIssueKind.TLS_FRAGMENTATION -> Icons.Default.Tune
                        HealthIssueKind.DNS_BLOCKED -> Icons.Default.Dns
                        HealthIssueKind.SILENT_BLACKHOLE -> Icons.Default.WarningAmber
                        HealthIssueKind.REALITY_TLS_FAILURE -> Icons.Default.VpnKey
                        HealthIssueKind.CAPTIVE_PORTAL -> Icons.Default.Wifi
                        HealthIssueKind.MTU_FRAGMENTATION -> Icons.Default.Tune
                        HealthIssueKind.GEO_BLOCKED_DOMAIN -> Icons.Default.Public
                        HealthIssueKind.TIME_DESYNC -> Icons.Default.Schedule
                        HealthIssueKind.ROUTING_LOOP -> Icons.Default.Loop
                    },
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = issue.title,
                    color = colors.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = strings.localized("Закрыть", "Dismiss"),
                        tint = colors.textSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Text(
                text = issue.description,
                color = colors.textSecondary,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )

            if (!issue.primaryActionLabel.isNullOrBlank() || !issue.secondaryActionLabel.isNullOrBlank()) {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!issue.primaryActionLabel.isNullOrBlank()) {
                        Button(
                            onClick = onPrimaryAction,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = accentColor
                            ),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.defaultMinSize(minHeight = 34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = issue.primaryActionLabel,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (!issue.secondaryActionLabel.isNullOrBlank()) {
                        OutlinedButton(
                            onClick = onSecondaryAction,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = colors.textPrimary
                            ),
                            border = BorderStroke(1.dp, colors.borderSubtle),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.defaultMinSize(minHeight = 34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = colors.textSecondary
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = issue.secondaryActionLabel,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}
