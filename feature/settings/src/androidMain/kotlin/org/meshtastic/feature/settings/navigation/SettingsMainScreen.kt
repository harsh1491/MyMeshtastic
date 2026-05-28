package org.meshtastic.feature.settings.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import org.meshtastic.core.navigation.Route
import org.meshtastic.core.navigation.SettingsRoute
import org.meshtastic.feature.settings.BattlefieldConfigScreen
import org.meshtastic.feature.settings.SettingsScreen
import org.meshtastic.feature.settings.SettingsViewModel
import org.meshtastic.feature.settings.radio.RadioConfigViewModel

@Composable
actual fun SettingsMainScreen(
    settingsViewModel: SettingsViewModel,
    radioConfigViewModel: RadioConfigViewModel,
    onClickNodeChip: (Int) -> Unit,
    onNavigate: (Route) -> Unit,
    onBack: (() -> Unit)?,
) {
    SettingsScreen(
        settingsViewModel = settingsViewModel,
        viewModel = radioConfigViewModel,
        onClickNodeChip = onClickNodeChip,
        onNavigate = onNavigate,
        onBack = onBack,
    )
}