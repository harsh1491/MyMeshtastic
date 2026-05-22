package org.meshtastic.app.map

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.core.annotation.Single

@Single
class ZoneViewModel : ViewModel() {
    private val _zones = MutableStateFlow<List<MapZone>>(emptyList())
    val zones: StateFlow<List<MapZone>> = _zones.asStateFlow()

    fun addZone(zone: MapZone) {
        _zones.value = _zones.value + zone
    }

    // Only called when receiving from remote — marks as not local
    fun addZoneFromRemote(zone: MapZone) {
        _zones.value = _zones.value + zone.copy(isLocal = false)
    }

    // Only allows deletion of locally created zones
    fun deleteZone(zoneId: String): Boolean {
        val zone = _zones.value.find { it.id == zoneId } ?: return false
        if (!zone.isLocal) return false  // cannot delete remote zones
        _zones.value = _zones.value.filter { it.id != zoneId }
        return true
    }

    // Force delete regardless of ownership — used internally for remote delete commands
    fun forceDeleteZone(zoneId: String) {
        _zones.value = _zones.value.filter { it.id != zoneId }
    }

    fun getZoneAtPoint(lat: Double, lon: Double): MapZone? {
        return _zones.value.firstOrNull { zone ->
            distanceMeters(lat, lon, zone.centerLat, zone.centerLon) <= zone.radiusMeters
        }
    }

    // Returns null if zone is not local (can't delete)
    fun getLocalZoneAtPoint(lat: Double, lon: Double): MapZone? {
        return _zones.value.firstOrNull { zone ->
            zone.isLocal &&
                    distanceMeters(lat, lon, zone.centerLat, zone.centerLon) <= zone.radiusMeters
        }
    }

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = Math.toRadians(lat2 - lat1)
        val dLambda = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dPhi / 2) * Math.sin(dPhi / 2) +
                Math.cos(phi1) * Math.cos(phi2) *
                Math.sin(dLambda / 2) * Math.sin(dLambda / 2)
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }
}