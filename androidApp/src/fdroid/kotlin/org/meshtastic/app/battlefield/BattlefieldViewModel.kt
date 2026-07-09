package org.meshtastic.app.battlefield

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.koin.core.annotation.Single
import org.meshtastic.core.model.DataPacket
import org.meshtastic.core.model.RadioController
import org.meshtastic.core.prefs.BattlefieldPrefs

import kotlinx.coroutines.flow.asStateFlow

@Single
class BattlefieldViewModel(
    private val prefs: BattlefieldPrefs,
    private val radioController: RadioController,
    private val context: android.content.Context
) : ViewModel() {

    private val scope = CoroutineScope(SupervisorJob())

    val operationId: StateFlow<String> = prefs.operationId
    val missionId: StateFlow<String> = prefs.missionId
    val unitType: StateFlow<String> = prefs.unitTypeCode

    private val _nodeUnitTypes = MutableStateFlow<Map<String, UnitType>>(emptyMap())
    val nodeUnitTypes: StateFlow<Map<String, UnitType>> = _nodeUnitTypes.asStateFlow()

    private var myNodeId: String = ""

    private val sharedPrefs = context.getSharedPreferences("battlefield_alerts", android.content.Context.MODE_PRIVATE)
    private val _droneAlarmEnabled = MutableStateFlow<Boolean>(sharedPrefs.getBoolean("drone_alarm_sound", true))
    val droneAlarmEnabled: StateFlow<Boolean> = _droneAlarmEnabled.asStateFlow()


    fun setDroneAlarmEnabled(enabled: Boolean) {
        _droneAlarmEnabled.value = enabled
        sharedPrefs.edit().putBoolean("drone_alarm_sound", enabled).apply()
        android.util.Log.d("BattlefieldSync", "Drone alarm sound preference updated: $enabled")
    }

    fun setMyNodeId(nodeId: String) {
        android.util.Log.d("MarkerFix", "setMyNodeId called with: $nodeId")
        myNodeId = nodeId
        val currentType = UnitType.fromCode(prefs.unitTypeCode.value)
        android.util.Log.d("MarkerFix", "Setting own unit type: $currentType for nodeId: $nodeId")
        _nodeUnitTypes.value = _nodeUnitTypes.value + (nodeId to currentType)
    }

    fun setOperationId(value: String) = prefs.setOperationId(value)
    fun setMissionId(value: String) = prefs.setMissionId(value)

    fun setUnitTypeCode(code: String) {
        android.util.Log.d("MarkerFix", "setUnitTypeCode called: $code, myNodeId=$myNodeId")
        prefs.setUnitTypeCode(code)
    }

    // Watch unitType prefs — whenever it changes, auto-send LoRa message
    init {
        prefs.unitTypeCode
            .drop(1)
            .distinctUntilChanged()
            .onEach { code ->
                val unitType = UnitType.fromCode(code)
                // Always update our own entry in the map
                if (myNodeId.isNotEmpty()) {
                    _nodeUnitTypes.value = _nodeUnitTypes.value + (myNodeId to unitType)
                    sendUnitTypeMessage(myNodeId, unitType)
                }
            }
            .launchIn(scope)
    }

    fun resetToSoldierOnLaunch(nodeId: String) {
        myNodeId = nodeId
        prefs.resetUnitType()
        sendUnitTypeMessage(nodeId, UnitType.SOLDIER)
    }

    fun resetToSoldierOnClose(nodeId: String) {
        sendUnitTypeMessage(nodeId, UnitType.SOLDIER)
    }

    private fun sendUnitTypeMessage(nodeId: String, unitType: UnitType) {
        val msg = "UT:$nodeId,${unitType.code}"
        android.util.Log.d("BattlefieldSync", "Sending: $msg")
        scope.launch {
            val p = DataPacket(DataPacket.ID_BROADCAST, 0, msg)
            radioController.sendMessage(p)
        }
    }

    fun applyRemoteUnitType(nodeId: String, unitType: UnitType) {
        _nodeUnitTypes.value = _nodeUnitTypes.value + (nodeId to unitType)
        android.util.Log.d("BattlefieldSync", "Node $nodeId is now ${unitType.displayName}")
    }

    fun getUnitTypeForNode(nodeId: String): UnitType =
        _nodeUnitTypes.value[nodeId] ?: UnitType.SOLDIER


    fun sendRemoteWipe(targetNodeId: String) {
        val msg = "WIPE:$targetNodeId"
        android.util.Log.w("BattlefieldSync", "CRITICAL: Broadcasting Remote Wipe Command -> $msg")
        scope.launch {
            val p = DataPacket(DataPacket.ID_BROADCAST, 0, msg)
            radioController.sendMessage(p)
        }
    }




}