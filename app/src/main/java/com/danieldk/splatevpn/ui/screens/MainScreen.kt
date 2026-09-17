package com.danieldk.splatevpn.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danieldk.splatevpn.ui.MainViewModel
import com.danieldk.splatevpn.ui.theme.*

enum class AppTab(val icon: ImageVector) {
    HOME(Icons.Default.Shield),
    SERVERS(Icons.Default.Dns),
    APPS(Icons.Default.Apps),
    SETTINGS(Icons.Default.Tune);

    fun getTitle(strings: AppStrings): String = when (this) {
        HOME -> strings.tabControl
        SERVERS -> strings.tabNodes
        APPS -> strings.tabRouting
        SETTINGS -> strings.tabConfig
    }
}

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onToggleConnect: () -> Unit
) {
    var currentTab by remember { mutableStateOf(AppTab.HOME) }
    val strings = LocalAppStrings.current
    val colors = LocalAppThemeColors.current

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        bottomBar = {
            // High-Tech Floating Dock Navigation Bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.surface.copy(alpha = 0.96f))
                    .border(
                        1.dp,
                        Brush.verticalGradient(
                            listOf(
                                colors.borderMedium.copy(alpha = 0.8f),
                                colors.borderSubtle.copy(alpha = 0.4f)
                            )
                        ),
                        RoundedCornerShape(20.dp)
                    )
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppTab.entries.forEach { tab ->
                        val isSelected = currentTab == tab
                        val iconColor by animateColorAsState(
                            targetValue = if (isSelected) colors.accentCyan else colors.textTertiary,
                            label = "nav_icon"
                        )
                        val textColor by animateColorAsState(
                            targetValue = if (isSelected) colors.accentCyan else colors.textTertiary,
                            label = "nav_text"
                        )

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) colors.accentCyan.copy(alpha = 0.1f) else Color.Transparent)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { currentTab = tab }
                                .padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.getTitle(strings),
                                tint = iconColor,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = tab.getTitle(strings),
                                color = textColor,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                letterSpacing = 0.8.sp
                            )
                        }
                    }
                }
            }
        },
        containerColor = colors.backgroundDeep
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(bottom = paddingValues.calculateBottomPadding())
        ) {
            when (currentTab) {
                AppTab.HOME -> HomeScreen(
                    viewModel = viewModel,
                    onToggleConnect = onToggleConnect,
                    onNavigateToServers = { currentTab = AppTab.SERVERS }
                )
                AppTab.SERVERS -> ServersScreen(viewModel = viewModel)
                AppTab.APPS -> PerAppScreen(viewModel = viewModel)
                AppTab.SETTINGS -> SettingsScreen(viewModel = viewModel)
            }
        }
    }
}
