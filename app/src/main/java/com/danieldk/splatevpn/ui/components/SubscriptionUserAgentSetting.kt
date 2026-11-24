package com.danieldk.splatevpn.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.danieldk.splatevpn.R
import com.danieldk.splatevpn.data.SubscriptionUserAgent
import com.danieldk.splatevpn.ui.theme.LocalAppResources

@Composable
internal fun SubscriptionUserAgentSetting(
    agent: SubscriptionUserAgent, onSelect: (SubscriptionUserAgent) -> Unit, enabled: Boolean
) {
    val r = LocalAppResources.current
    var open by remember { mutableStateOf(false) }
    fun label(value: SubscriptionUserAgent) =
        if (value == SubscriptionUserAgent.AUTO) r.getString(R.string.subscription_agent_auto) else value.label
    Column(Modifier.fillMaxWidth()) {
        Box {
            SplateTextButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.testTag("subscription_user_agent")) {
                Text(r.getString(R.string.subscription_agent_title, label(agent)))
            }
            SplateDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                SubscriptionUserAgent.entries.forEach { option ->
                    SplateDropdownMenuItem(text = { Text(label(option)) }, onClick = {
                        open = false
                        onSelect(option)
                    })
                }
            }
        }
        Text(r.getString(R.string.subscription_agent_hint), style = MaterialTheme.typography.bodySmall)
    }
}
