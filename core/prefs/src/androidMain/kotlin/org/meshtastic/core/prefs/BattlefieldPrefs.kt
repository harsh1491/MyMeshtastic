package org.meshtastic.core.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single
import org.meshtastic.core.di.CoroutineDispatchers

@Single
class BattlefieldPrefs(
    @Named("BattlefieldDataStore") private val dataStore: DataStore<Preferences>,
    dispatchers: CoroutineDispatchers,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.default)

    val operationId: StateFlow<String> =
        dataStore.data.map { it[KEY_OPERATION_ID] ?: "" }
            .stateIn(scope, SharingStarted.Eagerly, "")

    val missionId: StateFlow<String> =
        dataStore.data.map { it[KEY_MISSION_ID] ?: "" }
            .stateIn(scope, SharingStarted.Eagerly, "")

    val unitTypeCode: StateFlow<String> =
        dataStore.data.map { it[KEY_UNIT_TYPE] ?: "S" }
            .stateIn(scope, SharingStarted.Eagerly, "S")

    fun setOperationId(value: String) {
        scope.launch { dataStore.edit { it[KEY_OPERATION_ID] = value } }
    }

    fun setMissionId(value: String) {
        scope.launch { dataStore.edit { it[KEY_MISSION_ID] = value } }
    }

    fun setUnitTypeCode(value: String) {
        scope.launch { dataStore.edit { it[KEY_UNIT_TYPE] = value } }
    }

    fun resetUnitType() {
        scope.launch { dataStore.edit { it[KEY_UNIT_TYPE] = "S" } }
    }

    companion object {
        val KEY_OPERATION_ID = stringPreferencesKey("operation_id")
        val KEY_MISSION_ID = stringPreferencesKey("mission_id")
        val KEY_UNIT_TYPE = stringPreferencesKey("unit_type")
    }
}