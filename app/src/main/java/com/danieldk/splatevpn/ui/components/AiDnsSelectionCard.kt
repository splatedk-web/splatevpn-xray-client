package com.danieldk.splatevpn.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danieldk.splatevpn.data.*
import com.danieldk.splatevpn.ui.theme.LocalAppStrings
import com.danieldk.splatevpn.ui.theme.LocalAppThemeColors
import com.danieldk.splatevpn.ui.theme.aiDnsTools

@Composable
internal fun AiDnsSelectionCard(state: AiDnsSelectionState, onStart: () -> Unit, onStop: () -> Unit) {
    val strings = LocalAppStrings.current.aiDnsTools
    val colors = LocalAppThemeColors.current
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().liquidGlass(cornerRadius = 14).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(strings.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
        Text(strings.hint, fontSize = 11.sp, lineHeight = 16.sp, color = colors.textSecondary)
        Text(strings.scope, fontSize = 10.sp, lineHeight = 14.sp, color = colors.textSecondary)
        if (state.running) {
            LinearProgressIndicator(progress = { state.completed.toFloat() / state.total.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth(), color = colors.accentCyan, trackColor = colors.borderSubtle)
            Text(if (state.phase == AiDnsSelectionPhase.VERIFYING) strings.verifying else strings.checking(state.completed, state.total),
                fontSize = 11.sp, color = colors.textPrimary)
            state.current?.let { choice ->
                Text("${SmartDnsProviders.forAi(choice.providerKey).label} · ${strings.fragment(choice.tlsFragment)}", fontSize = 11.sp, color = colors.textSecondary)
            }
        } else {
            val message = when (state.phase) {
                AiDnsSelectionPhase.APPLIED -> state.winner?.let { strings.applied(SmartDnsProviders.forAi(it.choice.providerKey).label, it.accessible) + " · " + strings.fragment(it.choice.tlsFragment) }
                AiDnsSelectionPhase.NO_MATCH -> strings.noMatch
                AiDnsSelectionPhase.CANCELLED -> strings.cancelled
                AiDnsSelectionPhase.ERROR -> strings.error(state.error)
                else -> null
            }
            message?.let { Text(it, fontSize = 11.sp, lineHeight = 16.sp, color = colors.textPrimary) }
        }
        FilledTonalButton(onClick = if (state.running) onStop else onStart, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
            Text(if (state.running) strings.stop else strings.start, fontSize = 12.sp)
        }
        if (state.results.isNotEmpty()) {
            TextButton(onClick = { expanded = !expanded }) { Text(strings.details(state.results.size), fontSize = 11.sp) }
            if (expanded) state.results.sortedWith(aiDnsRanking).forEach { result ->
                HorizontalDivider(color = colors.borderSubtle)
                Text("${SmartDnsProviders.forAi(result.choice.providerKey).label} · ${strings.fragment(result.choice.tlsFragment)}",
                    fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                result.targets.forEach { target ->
                    Text("${target.service}: ${strings.outcome(target.outcome)}" + strings.detail(target).takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                        fontSize = 10.sp, lineHeight = 14.sp, color = colors.textSecondary)
                }
            }
        }
    }
}
