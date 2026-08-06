package org.meshtastic.app.gateway

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.OutputStream
import java.net.Socket

class GatewaySocketManager {

    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    @Volatile private var isConnected = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun connect(host: String = "127.0.0.1", port: Int = 8080) {
        scope.launch {
            try {
                if (isConnected) return@launch

                Log.i("GatewaySocket", "Attempting USB WebSocket connection to $host:$port...")
                val newSocket = Socket(host, port)
                val out = newSocket.getOutputStream()
                val input = newSocket.getInputStream()

                // Perform standard WebSocket HTTP Upgrade Handshake over TCP
                val handshake = "GET /stream HTTP/1.1\r\n" +
                        "Host: $host:$port\r\n" +
                        "Upgrade: websocket\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                        "Sec-WebSocket-Version: 13\r\n\r\n"

                out.write(handshake.toByteArray(Charsets.UTF_8))
                out.flush()

                // Verify server 101 response
                val buffer = ByteArray(1024)
                val bytesRead = input.read(buffer)
                if (bytesRead > 0) {
                    val response = String(buffer, 0, bytesRead, Charsets.UTF_8)
                    if (response.contains("101")) {
                        socket = newSocket
                        outputStream = out
                        isConnected = true
                        Log.i("GatewaySocket", "✅ Connected to Laptop Debrief Server over USB.")

                        // Keep-alive reader loop: Handles incoming Ping frames from Python
                        scope.launch {
                            try {
                                val readBuffer = ByteArray(2048)
                                while (isConnected && !newSocket.isClosed) {
                                    val read = input.read(readBuffer)
                                    if (read == -1) break // Connection closed by server

                                    // Respond to Ping frame (opcode 0x09) with Pong (opcode 0x0A)
                                    if (read > 0 && (readBuffer[0].toInt() and 0x0F) == 0x09) {
                                        synchronized(this@GatewaySocketManager) {
                                            out.write(0x8A)
                                            out.write(0x80)
                                            out.write(0); out.write(0); out.write(0); out.write(0)
                                            out.flush()
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("GatewaySocket", "Reader loop fault: ${e.message}")
                            } finally {
                                isConnected = false
                                socket?.close()
                                scheduleReconnect(host, port)
                            }
                        }
                        return@launch
                    }
                }

                newSocket.close()
                scheduleReconnect(host, port)
            } catch (e: Exception) {
                isConnected = false
                Log.e("GatewaySocket", "Connection failed: ${e.message}. Retrying in 5s...")
                scheduleReconnect(host, port)
            }
        }
    }

    private fun scheduleReconnect(host: String, port: Int) {
        scope.launch {
            delay(5000)
            connect(host, port)
        }
    }

    fun sendEvent(event: TacticalEvent) {
        if (!isConnected || outputStream == null) {
            Log.w("GatewaySocket", "Skipped packet sending — Socket disconnected!")
            return
        }
        scope.launch {
            try {
                val jsonPayload = Json.encodeToString(event)
                synchronized(this@GatewaySocketManager) {
                    outputStream?.let {
                        writeWebSocketFrame(it, jsonPayload)
                        Log.i("GatewaySocket", "🚀 Piped event over USB: ${event.eventType}")
                    }
                }
            } catch (e: Exception) {
                Log.e("GatewaySocket", "Error sending gateway event", e)
                isConnected = false
            }
        }
    }

    private fun writeWebSocketFrame(out: OutputStream, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val len = bytes.size
        out.write(0x81) // FIN bit + Text opcode
        when {
            len <= 125 -> {
                out.write(0x80 or len)
            }
            len <= 65535 -> {
                out.write(0x80 or 126)
                out.write((len shr 8) and 0xFF)
                out.write(len and 0xFF)
            }
            else -> {
                out.write(0x80 or 127)
                for (i in 7 downTo 0) {
                    out.write(((len.toLong() shr (i * 8)) and 0xFF).toInt())
                }
            }
        }
        // Client-to-Server Masking Key (4 zero bytes)
        out.write(0); out.write(0); out.write(0); out.write(0)
        out.write(bytes)
        out.flush()
    }

    fun disconnect() {
        try {
            isConnected = false
            socket?.close()
            socket = null
            outputStream = null
        } catch (_: Exception) {}
    }
}