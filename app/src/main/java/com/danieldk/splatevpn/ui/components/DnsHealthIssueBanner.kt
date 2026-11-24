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
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danieldk.splatevpn.data.DnsHealthIssue
import com.danieldk.splatevpn.ui.theme.AppThemeColors
import com.danieldk.splatevpn.ui.theme.LocalAppStrings
import com.danieldk.splatevpn.ui.theme.localized

@Composable
fun DnsHealthIssueBanner(
    issue: DnsHealthIssue,
    onApplyDns: () -> Unit,
    onEnableFakeDns: () -> Unit,
    onDismiss: () -> Unit,
    colors: AppThemeColors,
    modifier: Modifier = Modifier
) {
    val strings = LocalAppStrings.current
    val isResolved = issue.candidate != null
    val accentColor = if (isResolved) colors.accentEmerald else colors.accentAmber
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
                    imageVector = when {
                        isResolved -> Icons.Default.Dns
                        issue.allFailed -> Icons.Default.WarningAmber
                        else -> Icons.Default.WarningAmber
                    },
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = when {
                        isResolved -> String.format(strings.localized("Рабочий DNS: %s", "Available DNS: %s"), issue.candidate?.label)
                        issue.allFailed -> strings.localized("Проверки DNS не прошли", "DNS checks failed")
                        else -> strings.localized("Проверка DNS-серверов…", "Checking DNS servers…")
                    },
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
                        contentDescription = strings.close,
                        tint = colors.textSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            val descriptionText = when {
                issue.isChecking -> {
                    String.format(strings.localized("%s не отвечает через VPN. Проверяются другие DoH-серверы…",
                        "%s is not responding through the VPN. Checking other DoH servers…"), issue.failedDnsLabel)
                }
                isResolved -> {
                    val latency = issue.candidateLatencyMs?.let { String.format(strings.localized(" (%d мс)", " (%d ms)"), it) }.orEmpty()
                    String.format(strings.localized("%s не ответил через VPN. %s отвечает%s.",
                        "%s did not respond through the VPN. %s responds%s."), issue.failedDnsLabel, issue.candidate?.label, latency)
                }
                issue.allFailed -> {
                    strings.localized("Проверенные DoH-серверы не ответили через VPN. Попробуйте другой сервер или DNS.",
                        "The tested DoH servers did not respond through the VPN. Try another server or DNS provider.")
                }
                else -> ""
            }

            Text(
                text = descriptionText,
                color = colors.textSecondary,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )

            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isResolved) {
                    Button(
                        onClick = onApplyDns,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.accentEmerald
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
                            text = String.format(strings.localized("Выбрать %s", "Switch to %s"), issue.candidate?.label),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                if (issue.isFakeDnsOff) {
                    OutlinedButton(
                        onClick = onEnableFakeDns,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = colors.textPrimary
                        ),
                        border = BorderStroke(1.dp, colors.accentCyan.copy(alpha = 0.7f)),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.defaultMinSize(minHeight = 34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = colors.accentCyan,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = strings.localized("Включить FakeDNS", "Enable FakeDNS"),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                if (issue.isChecking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = colors.accentAmber
                    )
                }
            }
        }
    }
}
