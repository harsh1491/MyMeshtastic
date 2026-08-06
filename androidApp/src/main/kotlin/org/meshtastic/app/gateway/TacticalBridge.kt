package org.meshtastic.app.gateway

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.Socket

object TacticalBridge {
    private const val TAG = "TacticalBridge"
    private const val SERVER_IP = "127.0.0.1"
    private const val SERVER_PORT = 9090

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun sendEvent(event: TacticalEvent) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // ── EXPLICITLY PASS TacticalEvent.serializer() ──
                val jsonString = json.encodeToString(TacticalEvent.serializer(), event)

                val socket = Socket(SERVER_IP, SERVER_PORT)
                val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream()), true)
                writer.println(jsonString)
                writer.flush()

                Thread.sleep(100)

                writer.close()
                socket.close()
                Log.d(TAG, "🚀 Transmitted event to laptop: ${event.eventType}")
            } catch (e: Exception) {
                Log.e(TAG, "TCP send failed on port $SERVER_PORT: ${e.message}")
            }
        }
    }
}