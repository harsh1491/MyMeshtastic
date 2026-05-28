package org.meshtastic.feature.settings.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey

actual fun EntryProviderScope<NavKey>.battlefieldConfigEntry(backStack: NavBackStack<NavKey>) {
    // No-op on desktop/JVM
}