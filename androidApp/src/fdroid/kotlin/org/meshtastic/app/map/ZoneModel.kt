package org.meshtastic.app.map

import java.util.UUID

enum class ZoneColor(val hex: String, val alpha: Float) {
    RED("#FF0000", 0.35f),
    YELLOW("#FFFF00", 0.35f),
    GREEN("#00FF00", 0.35f)
}

data class MapZone(
    val id: String = UUID.randomUUID().toString(),
    val centerLat: Double,
    val centerLon: Double,
    val radiusMeters: Double,
    val color: ZoneColor,
    val isLocal: Boolean = true  // true = created on this device, false = received
)