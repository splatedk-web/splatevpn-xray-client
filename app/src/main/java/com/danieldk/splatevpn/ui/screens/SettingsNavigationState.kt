package com.danieldk.splatevpn.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** Settings always starts at its catalog when opened from the navigation dock. */
@Stable
internal class SettingsNavigationState {
    var category: SettingsCategory? by mutableStateOf(null)
        private set
    var query by mutableStateOf("")
        private set

    fun open(category: SettingsCategory) {
        this.category = category
        query = ""
    }

    fun search(query: String) {
        category = null
        this.query = query
    }

    fun home() {
        category = null
        query = ""
    }
}

@Composable
internal fun rememberSettingsNavigationState(openRequest: Int, initialCategory: SettingsCategory? = null): SettingsNavigationState =
    // Do not save the subsection with the tab: returning to Settings is an explicit
    // request to see its main menu. The token also handles tapping the active tab.
    remember(openRequest) { SettingsNavigationState().apply { initialCategory?.let(::open) } }
