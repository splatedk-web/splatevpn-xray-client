package com.danieldk.splatevpn.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.danieldk.splatevpn.ui.theme.LocalAppStrings
import com.danieldk.splatevpn.ui.theme.LocalAppThemeColors
import com.danieldk.splatevpn.ui.theme.localized

/** A remote-friendly one-time choice. It does not send or apply any configuration. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun TvTransferOfferDialog(
    busy: Boolean,
    error: String?,
    onReceive: () -> Unit,
    onLater: () -> Unit
) {
    val colors = LocalAppThemeColors.current
    val strings = LocalAppStrings.current
    val receiveFocus = remember { FocusRequester() }
    val inputMode = LocalInputModeManager.current
    Dialog(onDismissRequest = { if (!busy) onLater() }, properties = DialogProperties(
        usePlatformDefaultWidth = false, dismissOnClickOutside = false
    )) {
        com.danieldk.splatevpn.ui.components.rememberAndApplyPopupWindowBlur(blurBehind = true, backgroundBlur = false)
        Surface(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(24.dp).testTag("tvTransferOffer"),
            shape = RoundedCornerShape(24.dp), color = colors.surface) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text(strings.localized("Перенести настройки с телефона?", "Transfer settings from your phone?"),
                    color = colors.textPrimary, fontSize = 27.sp, lineHeight = 35.sp, fontWeight = FontWeight.Bold)
                Text(strings.localized(
                    "Подключите телефон и телевизор к одной Wi-Fi или Ethernet-сети. Телевизор покажет QR-код: отсканируйте его в SplateVPN на телефоне. Перед применением вы увидите состав настроек.",
                    "Connect your phone and TV to the same Wi-Fi or Ethernet network. The TV will show a QR code to scan in SplateVPN on your phone. You can review the settings before applying them."
                ), color = colors.textSecondary, fontSize = 18.sp, lineHeight = 26.sp)
                error?.let { Text(it, color = colors.accentCrimson, fontSize = 17.sp) }
                TvActionButton(strings.localized("Получить по QR-коду", "Receive using QR code"), onReceive,
                    modifier = Modifier.focusRequester(receiveFocus), enabled = !busy, tag = "tvTransferOffer:receive", prominent = true)
                TvActionButton(strings.localized("Настроить позже", "Set up later"), onLater,
                    enabled = !busy, tag = "tvTransferOffer:later")
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        LaunchedEffect(receiveFocus, inputMode) {
            inputMode.requestInputMode(InputMode.Keyboard)
            withFrameNanos { }
            receiveFocus.requestFocus()
        }
    }
}
