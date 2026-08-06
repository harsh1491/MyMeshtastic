package org.meshtastic.app.gateway

import org.meshtastic.core.data.gateway.GatewayInterceptor
import org.meshtastic.core.model.DataPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Position
import org.meshtastic.proto.Telemetry

class GatewayInterceptorEngine : GatewayInterceptor {

    override fun processIncomingPacket(packet: DataPacket) {
        val text = packet.text.orEmpty()
        val sender = packet.from ?: "UNKNOWN"
        val timestamp = System.currentTimeMillis()

        when (packet.dataType) {
            // ── 1. PERIODIC SOLDIER POSITION PING (POSITION_APP) ──
            PortNum.POSITION_APP.value -> {
                val bytes = packet.bytes ?: return
                val position = try {
                    Position.ADAPTER.decode(bytes)
                } catch (_: Exception) {
                    null
                } ?: return

                val latI = position.latitude_i ?: 0
                val lngI = position.longitude_i ?: 0
                if (latI == 0 && lngI == 0) return // Skip invalid GPS locks

                val lat = latI / 10000000.0
                val lng = lngI / 10000000.0
                val alt = (position.altitude ?: 0).toDouble()

                val event = TacticalEvent(
                    eventType = "SOLDIER_POSITION_PING",
                    senderId = sender,
                    timestampDevice = timestamp,
                    rawPayload = "POS_PING",
                    soldierLocation = SoldierLocation(
                        latitude = lat,
                        longitude = lng,
                        altitude = alt
                    )
                )
                TacticalBridge.sendEvent(event)
            }

            // ── 2. PERIODIC SOLDIER HEALTH PING (TELEMETRY_APP) ──
            PortNum.TELEMETRY_APP.value -> {
                val bytes = packet.bytes ?: return
                val telemetry = try {
                    Telemetry.ADAPTER.decode(bytes)
                } catch (_: Exception) {
                    null
                } ?: return

                val battery = telemetry.device_metrics?.battery_level ?: return

                val event = TacticalEvent(
                    eventType = "SOLDIER_HEALTH_PING",
                    senderId = sender,
                    timestampDevice = timestamp,
                    rawPayload = "HEALTH_PING",
                    soldierLocation = SoldierLocation(batteryLevel = battery)
                )
                TacticalBridge.sendEvent(event)
            }

            // ── 3. MESSAGES, ZONES, & COMMANDS (TEXT_MESSAGE_APP) ──
            PortNum.TEXT_MESSAGE_APP.value -> {
                var targetLoc: TargetLocation? = null

                val eventType = when {
                    // Zone Command: Z:lat,lng,radius,color
                    text.startsWith("Z:", ignoreCase = true) -> {
                        val parts = text.substringAfter(":").split(",")
                        targetLoc = TargetLocation(
                            latitude = parts.getOrNull(0)?.toDoubleOrNull(),
                            longitude = parts.getOrNull(1)?.toDoubleOrNull(),
                            radiusMeters = parts.getOrNull(2)?.toIntOrNull(),
                            color = parts.getOrNull(3)
                        )
                        "ZONE_UPDATE"
                    }

                    // Zone Delete: ZX:lat,lng
                    text.startsWith("ZX:", ignoreCase = true) -> {
                        val parts = text.substringAfter(":").split(",")
                        targetLoc = TargetLocation(
                            latitude = parts.getOrNull(0)?.toDoubleOrNull(),
                            longitude = parts.getOrNull(1)?.toDoubleOrNull()
                        )
                        "ZONE_DELETE"
                    }

                    // Emergency Wipe
                    text.startsWith("WIPE:", ignoreCase = true) -> "KILL_COMM_WIPE"

                    // Interrogate Command or Chat
                    else -> if (text == "INTERROGATE") "INTERROGATION_BURST" else "C2_MESSAGE"
                }

                val event = TacticalEvent(
                    eventType = eventType,
                    senderId = sender,
                    timestampDevice = timestamp,
                    rawPayload = text,
                    targetLocation = targetLoc
                )
                TacticalBridge.sendEvent(event)
            }
        }
    }
}