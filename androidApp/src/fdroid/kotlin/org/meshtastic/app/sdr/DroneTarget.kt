package org.meshtastic.app.sdr

data class DroneTarget(
    val deviceType: String,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val frequency: Double
)