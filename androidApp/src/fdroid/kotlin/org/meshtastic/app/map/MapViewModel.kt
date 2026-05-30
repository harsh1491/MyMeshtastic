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
) : BaseMapViewModel(mapPrefs, nodeRepository, packetRepository, radioController) {

    private val zoneViewModel: ZoneViewModel by lazy {
        org.koin.core.context.GlobalContext.get().get()
    }

    private val battlefieldViewModel: BattlefieldViewModel by lazy {
        org.koin.core.context.GlobalContext.get().get()
    }

    private val _selectedWaypointId = MutableStateFlow(savedStateHandle.get<Int>("waypointId"))
    val selectedWaypointId: StateFlow<Int?> = _selectedWaypointId.asStateFlow()

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
        safeLaunch(context = ioDispatcher, tag = "meshMessageListener") {
            val meshDataHandler: org.meshtastic.core.repository.MeshDataHandler =
                org.koin.core.context.GlobalContext.get().get()

            meshDataHandler.battlefieldMessages.collect { dataPacket ->
                val text = dataPacket.text ?: return@collect
                // Skip our own outgoing messages
                if (dataPacket.from == DataPacket.ID_LOCAL) return@collect
                android.util.Log.d("ZoneSync", "Battlefield message received: $text")
                when {
                    text.startsWith("Z:") || text.startsWith("ZX:") ->
                        parseAndApplyZoneMessage(text)
                    text.startsWith("UT:") ->
                        parseAndApplyUnitTypeMessage(text)
                }
            }
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
}