package org.meshtastic.feature.settings

interface EmergencyWipeHandler {
    suspend fun executeEmergencyWipe()
}