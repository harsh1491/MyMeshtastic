package org.meshtastic.feature.settings.navigation

import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import org.meshtastic.core.navigation.SettingsRoute
import org.meshtastic.feature.settings.BattlefieldConfigScreen

actual fun EntryProviderScope<NavKey>.battlefieldConfigEntry(backStack: NavBackStack<NavKey>) {
    entry<SettingsRoute.BattlefieldConfig> {
        BattlefieldConfigScreen(
            onNavigateUp = dropUnlessResumed { backStack.removeLastOrNull() },
        )
    }
}