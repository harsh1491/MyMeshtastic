package org.meshtastic.app.sdr

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import org.json.JSONObject

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AntSdrManager(private val application: Application) {
    private var usbSerialPort: UsbSerialPort? = null
    private val baudRate = 115200
    @Volatile private var isReading = false

    private val usbManager = application.getSystemService(Context.USB_SERVICE) as UsbManager
    private val ACTION_USB_PERMISSION = "org.meshtastic.app.SDR_USB_PERMISSION"

    private val _droneTarget = MutableStateFlow<DroneTarget?>(null)
    val droneTarget: StateFlow<DroneTarget?> = _droneTarget.asStateFlow()

    fun startListening() {
        if (isReading) return // Already running
        stopListening()

        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        Log.d("AntSDR_USB", "--- USB Scan: Found ${availableDrivers.size} potential devices ---")

        if (availableDrivers.isEmpty()) {
            Log.w("AntSDR_USB", "No serial devices detected via Type-C port.")
            return
        }

        // Target Identification: Find the device that isn't your LilyGO/Meshtastic radio
        var targetDriver = availableDrivers.firstOrNull { driver ->
            val vid = driver.device.vendorId
            // Common Meshtastic radio USB-to-UART chip VIDs to skip:
            // 0x10C4 = Silicon Labs (CP210x)
            // 0x1A86 = Qinheng (CH340/CH341)
            vid != 0x10C4 && vid != 0x1A86
        }

        // Fallback: If ABI splitting or custom cables mask the VIDs, default to the first available driver
        if (targetDriver == null) {
            Log.w("AntSDR_USB", "No explicit non-radio device detected. Defaulting to first available USB slot.")
            targetDriver = availableDrivers[0]
        }

        val device = targetDriver.device

        // Request System Authorization
        if (!usbManager.hasPermission(device)) {
            Log.d("AntSDR_USB", "Requesting OS level runtime permission for Device VID: ${device.vendorId}")
            val intent = PendingIntent.getBroadcast(
                application, 0, Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_IMMUTABLE
            )
            usbManager.requestPermission(device, intent)
            return
        }

        val connection = usbManager.openDevice(device) ?: return
        val port = targetDriver.ports[0]

        try {
            port.open(connection)
            port.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            usbSerialPort = port
            Log.i("AntSDR_USB", "✅ ANTSDR COMMUNICATION LINK ESTABLISHED: ${device.deviceName}")
            startBackgroundReadLoop()
        } catch (e: Exception) {
            Log.e("AntSDR_USB", "❌ Failed to open serial interface: ${e.message}")
        }
    }

    private fun startBackgroundReadLoop() {
        isReading = true
        Thread {
            val buffer = ByteArray(4096)
            var accumulator = ""
            Log.d("AntSDR_USB", "Data stream processing thread initialized.")

            while (usbSerialPort != null && isReading) {
                try {
                    val len = usbSerialPort?.read(buffer, 1000) ?: 0
                    if (len > 0) {
                        val rawChunk = String(buffer, 0, len, Charsets.UTF_8)
                        accumulator += rawChunk

                        if (accumulator.contains("\n")) {
                            val lines = accumulator.split("\n")
                            // Process all complete packets (omit the last incomplete fragment)
                            for (i in 0 until lines.size - 1) {
                                val line = lines[i].trim()
                                if (line.isNotEmpty()) {
                                    parseTelemetryPayload(line)
                                }
                            }
                            accumulator = lines.last()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("AntSDR_USB", "Stream exception inside hardware reader: ${e.message}")
                    break
                }
            }
            Log.d("AntSDR_USB", "Data stream processing thread terminated.")
        }.start()
    }

    private fun parseTelemetryPayload(rawLine: String) {
        try {
            // Strip any leading transmission sync markers (like "0Y") by cutting directly to the JSON object root
            val jsonStart = rawLine.indexOf("{")
            if (jsonStart == -1) {
                Log.w("AntSDR_PARSE", "Preamble dropped (Non-JSON frame data): $rawLine")
                return
            }

            val jsonStr = rawLine.substring(jsonStart)
            if (!jsonStr.endsWith("}")) {
                Log.w("AntSDR_PARSE", "Fragment dropped (Incomplete JSON frame segment): $jsonStr")
                return
            }

            val json = JSONObject(jsonStr)

            // Extract core telemetry variables directly from target dictionary keys
            val frequency = json.optDouble("freq", 0.0)
            val deviceType = json.optString("device_type", "Unknown Target")
            val droneLat = json.optDouble("drone_lat", 0.0)
            val droneLon = json.optDouble("drone_lon", 0.0)
            val height = if (json.has("heigth")) json.optDouble("heigth", 0.0) else json.optDouble("altitude", 0.0)

            // Print the parsed target acquisition profile clearly to the Logcat terminal
            Log.i("ANT_SDR_TARGET", "🎯 TARGET ACQUIRED -> Type: [$deviceType] | RF: ${frequency}MHz | Lat: $droneLat | Lon: $droneLon | Alt: ${height}m")

            // ── ADD THIS BLOCK: Emit untrimmed high-precision target data ──
            if (droneLat != 0.0 && droneLon != 0.0) {
                _droneTarget.value = DroneTarget(
                    deviceType = deviceType,
                    latitude = droneLat, // Native Double retains full micro-degree precision
                    longitude = droneLon,
                    altitude = height,
                    frequency = frequency
                )
            }

        } catch (e: Exception) {
            Log.e("AntSDR_PARSE", "Exception thrown while isolating telemetry payload: ${e.message}")
        }
    }

    fun stopListening() {
        isReading = false
        try {
            usbSerialPort?.close()
        } catch (_: Exception) {}
        usbSerialPort = null
        Log.d("AntSDR_USB", "AntSDR Serial port stream disconnected cleanly.")
    }
}