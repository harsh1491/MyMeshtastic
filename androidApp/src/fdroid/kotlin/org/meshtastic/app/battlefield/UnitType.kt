package org.meshtastic.app.battlefield

enum class UnitType(val code: String, val displayName: String, val drawableName: String) {
    SOLDIER("S", "Soldier", "soldier_marker"),
    TANK("T", "Tank", "marker_tank"),
    DRONE("D", "Drone", "marker_drone"),
    HELI("H", "Heli", "marker_heli");

    companion object {
        fun fromCode(code: String): UnitType =
            entries.find { it.code == code } ?: SOLDIER
    }
}