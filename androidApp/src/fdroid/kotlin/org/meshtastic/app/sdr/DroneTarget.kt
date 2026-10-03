package org.meshtastic.app.sdr

data class DroneTarget(
    val deviceType: String,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val frequency: Double
)

// ── NON-DJI RF THREAT DATA MODELS ──
data class RfThreat(
    val deviceType: String,
    val frequency: Double
)

data class RfThreatZone(
    val centerLat: Double,
    val centerLon: Double,
    val frequency: Double,
    val deviceType: String
)