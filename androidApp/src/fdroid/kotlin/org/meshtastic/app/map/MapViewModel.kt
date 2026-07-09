package org.meshtastic.app.map

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.KoinViewModel
import org.meshtastic.core.common.BuildConfigProvider
import org.meshtastic.core.common.util.ioDispatcher
import org.meshtastic.core.model.DataPacket
import org.meshtastic.core.model.RadioController
import org.meshtastic.core.repository.MapPrefs
import org.meshtastic.core.repository.NodeRepository
import org.meshtastic.core.repository.PacketRepository
import org.meshtastic.core.repository.RadioConfigRepository
import org.meshtastic.core.ui.viewmodel.safeLaunch
import org.meshtastic.core.ui.viewmodel.stateInWhileSubscribed
import org.meshtastic.feature.map.BaseMapViewModel
import org.meshtastic.proto.LocalConfig
import org.meshtastic.app.battlefield.BattlefieldViewModel
import org.meshtastic.app.battlefield.UnitType

@Suppress("LongParameterList")
@KoinViewModel
class MapViewModel(
    mapPrefs: MapPrefs,
    packetRepository: PacketRepository,
    nodeRepository: NodeRepository,
    private val radioController: RadioController,
    radioConfigRepository: RadioConfigRepository,
    buildConfigProvider: BuildConfigProvider,
    savedStateHandle: SavedStateHandle,
    private val context: android.content.Context
) : BaseMapViewModel(mapPrefs, nodeRepository, packetRepository, radioController) {

    private val zoneViewModel: ZoneViewModel by lazy {
        org.koin.core.context.GlobalContext.get().get()
    }

    private val battlefieldViewModel: BattlefieldViewModel by lazy {
        org.koin.core.context.GlobalContext.get().get()
    }

    private val _selectedWaypointId = MutableStateFlow(savedStateHandle.get<Int>("waypointId"))
    val selectedWaypointId: StateFlow<Int?> = _selectedWaypointId.asStateFlow()

    // ── Lazy-link directly into the active AntSdr global context ──
    private val antSdrManager: org.meshtastic.app.sdr.AntSdrManager by lazy {
        org.koin.core.context.GlobalContext.get().get()
    }

    // Expose the high-accuracy data thread directly to the Map layout
    // Change your old droneTarget variable line to this:
    private val _unifiedDroneTarget = MutableStateFlow<org.meshtastic.app.sdr.DroneTarget?>(null)
    val droneTarget: StateFlow<org.meshtastic.app.sdr.DroneTarget?> = _unifiedDroneTarget.asStateFlow()

    private var lastAlarmTimestamp = 0L // Cooldown tracking variable to prevent audio spam
    private var lastDroneSeenTimestamp = 0L

    fun setWaypointId(id: Int?) {
        if (_selectedWaypointId.value != id) {
            _selectedWaypointId.value = id
        }
    }

    var mapStyleId: Int
        get() = mapPrefs.mapStyle.value
        set(value) { mapPrefs.setMapStyle(value) }

    val localConfig = radioConfigRepository.localConfigFlow
        .stateInWhileSubscribed(initialValue = LocalConfig())

    val config get() = localConfig.value

    val applicationId = buildConfigProvider.applicationId

    fun sendZone(zone: MapZone) {
        val colorChar = zone.color.name[0]
        val lat = "%.6f".format(zone.centerLat)
        val lon = "%.6f".format(zone.centerLon)
        val radius = zone.radiusMeters.toInt()
        val msg = "Z:$lat,$lon,$radius,$colorChar"
        android.util.Log.d("ZoneSync", "Sending zone: $msg")
        sendRawMessage(msg)
    }

    fun sendZoneDelete(zone: MapZone) {
        val lat = "%.6f".format(zone.centerLat)
        val lon = "%.6f".format(zone.centerLon)
        val msg = "ZX:$lat,$lon"
        android.util.Log.d("ZoneSync", "Sending zone delete: $msg")
        sendRawMessage(msg)
    }

    private fun sendRawMessage(text: String) {
        safeLaunch(context = ioDispatcher, tag = "sendRawMessage") {
            val p = DataPacket(DataPacket.ID_BROADCAST, 0, text)
            radioController.sendMessage(p)
        }
    }

    init {

        // ── ADD THIS ENGINE: 5-Second Drone Inactivity Timeout Sweeper ──
        safeLaunch(context = ioDispatcher, tag = "droneTimeoutSweeper") {
            while (true) {
                kotlinx.coroutines.delay(1000) // Check the clock once per second
                if (_unifiedDroneTarget.value != null && (System.currentTimeMillis() - lastDroneSeenTimestamp) > 5000) {
                    android.util.Log.w("DroneTrack", "⏱ Inactivity Timeout: No drone telemetry received for 5s. Clearing target.")
                    _unifiedDroneTarget.value = null // Clears the flow state completely
                }
            }
        }


        // Pipeline A: Monitor the LOCAL physical AntSDR USB-C port thread
        safeLaunch(context = ioDispatcher, tag = "localSdrListener") {
            antSdrManager.droneTarget.collect { target ->
                if (target != null) {
                    lastDroneSeenTimestamp = System.currentTimeMillis() // ── RESET TIMEOUT ──
                    _unifiedDroneTarget.value = target
                    triggerAlarmAudioNotification()

                    val payload = "DRONE:${target.latitude},${target.longitude},${target.deviceType},${target.altitude},${target.frequency}"
                    sendRawMessage(payload)
                }
            }
        }

        // Pipeline B: Monitor incoming mesh network messages from REMOTE teammates
        safeLaunch(context = ioDispatcher, tag = "meshMessageListener") {
            val meshDataHandler: org.meshtastic.core.repository.MeshDataHandler =
                org.koin.core.context.GlobalContext.get().get()

            meshDataHandler.battlefieldMessages.collect { dataPacket ->
                val text = dataPacket.text ?: return@collect
                if (dataPacket.from == DataPacket.ID_LOCAL) return@collect

                when {
                    text.startsWith("Z:") || text.startsWith("ZX:") ->
                        parseAndApplyZoneMessage(text)
                    text.startsWith("UT:") ->
                        parseAndApplyUnitTypeMessage(text)
                    text.startsWith("WIPE:") ->
                        parseAndApplyWipeMessage(text)
                    text.startsWith("DRONE:") -> {
                        lastDroneSeenTimestamp = System.currentTimeMillis() // ── RESET TIMEOUT ──
                        parseAndApplyMeshDroneTarget(text)
                    }
                }
            }
        }
    }

    // Add this INSIDE MapViewModel class (alongside your sendZone functions)
    fun sendDirectMessage(targetNodeId: String, text: String) {
        safeLaunch(context = ioDispatcher, tag = "sendDirectMessage") {
            // Convert the decimal string (e.g., "12345678") back to a number
            val num = targetNodeId.toLongOrNull()
            if (num == null) {
                android.util.Log.e("MapViewModel", "Failed to send DM: Invalid node ID '$targetNodeId'")
                return@safeLaunch
            }

            // Format the number into the standard Meshtastic Hex ID (e.g., "!00bc614e")
            val hexDestId = String.format("!%08x", num)
            android.util.Log.d("MapViewModel", "Sending quick DM to $hexDestId: $text")

            // Create and send the packet using the properly formatted destination
            val p = org.meshtastic.core.model.DataPacket(
                to = hexDestId,
                channel = 0,
                text = text
            )
            radioController.sendMessage(p)
        }
    }

    private fun parseAndApplyZoneMessage(text: String) {
        try {
            when {
                text.startsWith("Z:") -> {
                    val parts = text.removePrefix("Z:").split(",")
                    if (parts.size < 4) return
                    val lat = parts[0].toDouble()
                    val lon = parts[1].toDouble()
                    val radius = parts[2].toDouble()
                    val color = when (parts[3].uppercase()) {
                        "R" -> ZoneColor.RED
                        "Y" -> ZoneColor.YELLOW
                        "G" -> ZoneColor.GREEN
                        else -> ZoneColor.RED
                    }
                    val zone = MapZone(centerLat = lat, centerLon = lon, radiusMeters = radius, color = color)
                    val exists = zoneViewModel.zones.value.any { existing ->
                        zoneViewModel.distanceMeters(existing.centerLat, existing.centerLon, lat, lon) < 10.0
                    }
                    if (!exists) {
                        zoneViewModel.addZoneFromRemote(zone)
                        android.util.Log.d("ZoneSync", "Zone added from remote: $lat,$lon")
                    }
                }
                text.startsWith("ZX:") -> {
                    val parts = text.removePrefix("ZX:").split(",")
                    if (parts.size < 2) return
                    val lat = parts[0].toDouble()
                    val lon = parts[1].toDouble()
                    val zoneToDelete = zoneViewModel.zones.value.firstOrNull { zone ->
                        zoneViewModel.distanceMeters(zone.centerLat, zone.centerLon, lat, lon) < 10.0
                    }
                    zoneToDelete?.let {
                        zoneViewModel.forceDeleteZone(it.id)
                        android.util.Log.d("ZoneSync", "Zone deleted from remote: $lat,$lon")
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ZoneSync", "Failed to parse zone message: $text", e)
        }
    }

    private fun parseAndApplyUnitTypeMessage(text: String) {
        try {
            // UT:NODEID,S
            val parts = text.removePrefix("UT:").split(",")
            if (parts.size < 2) return
            val nodeId = parts[0].trim()
            val unitType = UnitType.fromCode(parts[1].trim().uppercase())
            battlefieldViewModel.applyRemoteUnitType(nodeId, unitType)
        } catch (e: Exception) {
            android.util.Log.e("BattlefieldSync", "Failed to parse UT message: $text", e)
        }
    }



    private fun parseAndApplyWipeMessage(text: String) {
        try {
            val targetNodeId = text.removePrefix("WIPE:").trim()
            val myNodeNum = myNodeInfo.value?.myNodeNum?.toString()

            if (targetNodeId == myNodeNum) {
                android.util.Log.w("EmergencyWipe", "CRITICAL: Verified remote hardware wipe command matches this node!")

                // Fetch the interface implementation via Koin context configuration
                val wipeManager = org.koin.core.context.GlobalContext.get()
                    .get<org.meshtastic.feature.settings.EmergencyWipeHandler>()

                // Execute on IO thread dispatcher
                safeLaunch(context = ioDispatcher, tag = "remoteWipeExecution") {
                    wipeManager.executeEmergencyWipe()
                }
            } else {
                android.util.Log.d("EmergencyWipe", "Remote wipe command ignored. Targeted node ($targetNodeId) is not us.")
            }
        } catch (e: Exception) {
            android.util.Log.e("EmergencyWipe", "Failed to process remote wipe message payload: $text", e)
        }
    }



    private fun parseAndApplyMeshDroneTarget(text: String) {
        try {
            // Format: DRONE:lat,lon,deviceType,altitude,frequency
            val parts = text.removePrefix("DRONE:").split(",")
            if (parts.size < 5) return

            val lat = parts[0].toDouble() // Preserves un-truncated floating point accuracy
            val lon = parts[1].toDouble()
            val deviceType = parts[2].trim()
            val altitude = parts[3].toDouble()
            val frequency = parts[4].toDouble()

            // Push target data to the map UI collector thread
            _unifiedDroneTarget.value = org.meshtastic.app.sdr.DroneTarget(
                deviceType = deviceType,
                latitude = lat,
                longitude = lon,
                altitude = altitude,
                frequency = frequency
            )

            // Trigger audio notification sequence for teammates
            triggerAlarmAudioNotification()

        } catch (e: Exception) {
            android.util.Log.e("MeshDroneParser", "Failed parsing mesh telemetry packet: $text", e)
        }
    }

    private fun triggerAlarmAudioNotification() {
        if (!battlefieldViewModel.droneAlarmEnabled.value) return

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastAlarmTimestamp > 10000) {
            lastAlarmTimestamp = currentTime

            safeLaunch(context = kotlinx.coroutines.Dispatchers.Main, tag = "sirenPlayback") {
                try {
                    val resId = context.resources.getIdentifier("drone_alarm", "raw", context.packageName)
                    if (resId != 0) {
                        val mediaPlayer = android.media.MediaPlayer.create(context, resId)

                        // ── ADD SAFE CALLS (?.) TO PREVENT NULL COMPILATION ERRORS ──
                        mediaPlayer?.setOnCompletionListener { mp -> mp.release() }
                        mediaPlayer?.start()

                        android.util.Log.i("AlertEngine", "⚠ Threat Siren Executed Successfully.")
                    } else {
                        android.util.Log.w("AlertEngine", "Audio resource file 'drone_alarm' missing from res/raw folder.")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("AlertEngine", "MediaPlayer execution fault: ${e.message}")
                }
            }
        }
    }






}