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

    private val _rfThreat = MutableStateFlow<RfThreat?>(null)
    val rfThreat: StateFlow<RfThreat?> = _rfThreat.asStateFlow()

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

                        // ── DELIMITER-FREE JSON STREAM PARSER ──
                        // Extracts each {...} independently without requiring \n
                        while (true) {
                            val start = accumulator.indexOf('{')
                            if (start == -1) {
                                // No JSON start marker; discard noise if buffer gets too large
                                if (accumulator.length > 2048) {
                                    accumulator = accumulator.takeLast(256)
                                }
                                break
                            }

                            val end = accumulator.indexOf('}', startIndex = start)
                            if (end == -1) {
                                // Waiting for the rest of the JSON frame to arrive over serial
                                break
                            }

                            // Extract the complete JSON frame
                            val jsonStr = accumulator.substring(start, end + 1)
                            parseTelemetryPayload(jsonStr)

                            // Advance accumulator past the extracted JSON
                            accumulator = accumulator.substring(end + 1)
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

    private fun parseTelemetryPayload(jsonStr: String) {
        try {
            val json = JSONObject(jsonStr)

            val frequency = json.optDouble("freq", 0.0)
            val deviceType = json.optString("device_type", "Unknown Target")
            val droneLat = json.optDouble("drone_lat", 0.0)
            val droneLon = json.optDouble("drone_lon", 0.0)
            val height = if (json.has("heigth")) json.optDouble("heigth", 0.0) else json.optDouble("altitude", 0.0)

            if (droneLat != 0.0 && droneLon != 0.0) {
                // DJI Target with GPS
                Log.i("ANT_SDR_TARGET", "🎯 DJI GPS TARGET -> Type: [$deviceType] | RF: ${frequency}MHz | Lat: $droneLat | Lon: $droneLon")
                _droneTarget.value = DroneTarget(
                    deviceType = deviceType,
                    latitude = droneLat,
                    longitude = droneLon,
                    altitude = height,
                    frequency = frequency
                )
            } else if (frequency > 0.0 && deviceType.isNotBlank()) {
                // Non-DJI RF Threat (0.0 GPS)
                Log.w("ANT_SDR_TARGET", "⚠ NON-DJI RF THREAT -> Type: [$deviceType] | RF: ${frequency}MHz")
                _rfThreat.value = RfThreat(
                    deviceType = deviceType,
                    frequency = frequency
                )
            }
        } catch (e: Exception) {
            Log.e("AntSDR_PARSE", "JSON parse error on: $jsonStr | ${e.message}")
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