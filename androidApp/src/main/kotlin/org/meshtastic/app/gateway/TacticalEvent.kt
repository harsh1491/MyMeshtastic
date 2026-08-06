package org.meshtastic.app.gateway

import kotlinx.serialization.Serializable

@Serializable
data class TacticalEvent(
    val eventType: String,             // "SOLDIER_POSITION_PING", "SOLDIER_HEALTH_PING", "ZONE_UPDATE", "C2_MESSAGE", etc.
    val senderId: String,              // Sender Node ID (e.g., "!43568b74")
    val timestampDevice: Long,         // Device epoch timestamp (ms)
    val rawPayload: String,            // Raw message string
    val soldierLocation: SoldierLocation? = null,
    val targetLocation: TargetLocation? = null
)

@Serializable
data class SoldierLocation(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitude: Double? = null,
    val batteryLevel: Int? = null
)

@Serializable
data class TargetLocation(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val radiusMeters: Int? = null,
    val color: String? = null
)