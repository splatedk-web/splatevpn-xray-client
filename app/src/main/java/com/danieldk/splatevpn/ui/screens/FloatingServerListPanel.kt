package com.danieldk.splatevpn.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.danieldk.splatevpn.ui.components.LocalGlassBackdrop
import com.danieldk.splatevpn.ui.components.rememberDockBackdrop

/** The list viewport continues behind the capsule. Only its first item receives a top inset. */
@Composable
internal fun FloatingServerListPanel(
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit,
    content: @Composable (topInset: Dp) -> Unit
) {
    val (backdrop, captureContent) = rememberDockBackdrop()
    SubcomposeLayout(modifier.fillMaxSize()) { constraints ->
        val headerPlaceable = subcompose("header") {
            CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
                Box(Modifier.testTag("serverFloatingTabs")) { header() }
            }
        }.single().measure(constraints.copy(minHeight = 0))
        val topInset = headerPlaceable.height.toDp() + 6.dp
        val listPlaceable = subcompose("list") {
            Box(Modifier.fillMaxSize().then(captureContent).testTag("serverListUnderlay")) { content(topInset) }
        }.single().measure(constraints)
        layout(listPlaceable.width, listPlaceable.height) {
            listPlaceable.place(0, 0)
            headerPlaceable.place(0, 0)
        }
    }
}
