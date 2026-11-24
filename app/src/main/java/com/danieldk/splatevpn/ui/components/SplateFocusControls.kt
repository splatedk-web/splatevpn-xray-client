package com.danieldk.splatevpn.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

@Composable
internal fun SplateTextButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = ButtonDefaults.textShape, colors: ButtonColors = ButtonDefaults.textButtonColors(),
    elevation: ButtonElevation? = null, border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    interactionSource: MutableInteractionSource? = null, content: @Composable RowScope.() -> Unit
) = androidx.compose.material3.TextButton(onClick, modifier.tvFocusOutline(enabled)
    .heightIn(min = if (LocalTvControls.current) 48.dp else 0.dp), enabled, shape, colors,
    elevation, border, contentPadding, interactionSource, content)

@Composable
internal fun SplateIconButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    interactionSource: MutableInteractionSource? = null, content: @Composable () -> Unit
) = androidx.compose.material3.IconButton(onClick, modifier.tvFocusOutline(enabled), enabled, colors, interactionSource, content)

@Composable
internal fun SplateSwitch(
    checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier,
    thumbContent: (@Composable () -> Unit)? = null, enabled: Boolean = true,
    colors: SwitchColors = SwitchDefaults.colors(), interactionSource: MutableInteractionSource? = null
) = androidx.compose.material3.Switch(checked, onCheckedChange, modifier.tvFocusOutline(enabled && onCheckedChange != null),
    thumbContent, enabled, colors, interactionSource)

@Composable
internal fun SplateRadioButton(
    selected: Boolean, onClick: (() -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true,
    colors: RadioButtonColors = RadioButtonDefaults.colors(), interactionSource: MutableInteractionSource? = null
) = androidx.compose.material3.RadioButton(selected, onClick, modifier.tvFocusOutline(enabled && onClick != null), enabled, colors, interactionSource)

@Composable
internal fun SplateCheckbox(
    checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true,
    colors: CheckboxColors = CheckboxDefaults.colors(), interactionSource: MutableInteractionSource? = null
) = androidx.compose.material3.Checkbox(checked, onCheckedChange, modifier.tvFocusOutline(enabled && onCheckedChange != null), enabled, colors, interactionSource)

@Composable
internal fun SplateDropdownMenuItem(
    text: @Composable () -> Unit, onClick: () -> Unit, modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null, trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true, colors: MenuItemColors = MenuDefaults.itemColors(),
    contentPadding: PaddingValues = MenuDefaults.DropdownMenuItemContentPadding,
    interactionSource: MutableInteractionSource? = null
) = androidx.compose.material3.DropdownMenuItem(text, onClick, modifier.tvFocusOutline(enabled), leadingIcon,
    trailingIcon, enabled, colors, contentPadding, interactionSource)
