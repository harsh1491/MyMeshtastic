package org.meshtastic.core.data.gateway

import org.meshtastic.core.model.DataPacket

interface GatewayInterceptor {
    fun processIncomingPacket(packet: DataPacket)
}