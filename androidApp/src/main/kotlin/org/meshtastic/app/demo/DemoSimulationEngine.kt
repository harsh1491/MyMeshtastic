package org.meshtastic.app.demo

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.meshtastic.app.sdr.RfThreatZone
import kotlin.math.cos
import kotlin.math.sin

data class SimulatedDrone(
    val id: String,
    val callsign: String,
    val isFriend: Boolean,
    var lat: Double,
    var lon: Double,
    val baseLat: Double,
    val baseLon: Double,
    val altitudeMeters: Double,
    val speedKmh: Double,
    val headingDeg: Double,
    var isPassiveMode: Boolean = true
)

data class SimulatedStation(
    val id: String,
    val label: String,
    val lat: Double,
    val lon: Double,
    val type: StationType
)

enum class StationType { COMMANDER, DETECTOR_DJI, DETECTOR_RF }

object DemoSimulationEngine {
    const val DEMO_ENABLED = true

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var simulationJob: Job? = null

    const val BASE_LAT = 30.3670641
    const val BASE_LON = 76.8660838
    const val anchorLat = BASE_LAT
    const val anchorLon = BASE_LON

    // ── Tightened positions (~200m spread) ──
    val stations = listOf(
        SimulatedStation("STN-CMD", "commander", BASE_LAT, BASE_LON, StationType.COMMANDER),
        SimulatedStation("STN-DET1", "detector-1", BASE_LAT + 0.0004, BASE_LON - 0.0024, StationType.DETECTOR_DJI), // West
        SimulatedStation("STN-DET2", "detector-2", BASE_LAT - 0.0003, BASE_LON + 0.0024, StationType.DETECTOR_RF)   // East
    )

    // HUD Counters
    private val _totalDrones = MutableStateFlow(0)
    val totalDrones: StateFlow<Int> = _totalDrones.asStateFlow()

    private val _friendDrones = MutableStateFlow(0)
    val friendDrones: StateFlow<Int> = _friendDrones.asStateFlow()

    private val _enemyDrones = MutableStateFlow(0)
    val enemyDrones: StateFlow<Int> = _enemyDrones.asStateFlow()

    private val _activeDrones = MutableStateFlow<List<SimulatedDrone>>(emptyList())
    val activeDrones: StateFlow<List<SimulatedDrone>> = _activeDrones.asStateFlow()

    private val _demoRfThreatZone = MutableStateFlow<RfThreatZone?>(null)
    val demoRfThreatZone: StateFlow<RfThreatZone?> = _demoRfThreatZone.asStateFlow()

    // Active/Passive Mode state dictionary for Nodes tab
    val nodeModeStates = MutableStateFlow(
        mutableMapOf(
            10101 to true,  // commander
            10102 to true,  // detector-1
            10103 to true,  // detector-2
            10104 to true,  // drone1
            10105 to false  // drone2
        )
    )

    fun toggleModeForNode(nodeNum: Int) {
        val current = nodeModeStates.value.toMutableMap()
        current[nodeNum] = !(current[nodeNum] ?: false)
        nodeModeStates.value = current
    }

    fun startSimulation() {
        if (!DEMO_ENABLED) return

        simulationJob?.cancel()
        simulationJob = scope.launch {
            // T = 0s: Reset to ONLY 3 base stations, 0 drones, NO threat zone
            _totalDrones.value = 0
            _friendDrones.value = 0
            _enemyDrones.value = 0
            _activeDrones.value = emptyList()
            _demoRfThreatZone.value = null

            val det1 = stations[1]
            val det2 = stations[2]

            // ── T = 4s: FRIEND DRONE 1 APPEARS ──
            delay(4000)
            val friend1 = SimulatedDrone(
                id = "DRN-FR-01",
                callsign = "drone1",
                isFriend = true,
                lat = det1.lat + 0.0008,
                lon = det1.lon - 0.0006,
                baseLat = det1.lat + 0.0008,
                baseLon = det1.lon - 0.0006,
                altitudeMeters = 95.0,
                speedKmh = 38.4,
                headingDeg = 45.0,
                isPassiveMode = true
            )
            _activeDrones.value = listOf(friend1)
            _totalDrones.value = 1
            _friendDrones.value = 1
            _enemyDrones.value = 0

            // ── T = 8s: FRIEND DRONE 2 APPEARS ──
            delay(4000)
            val friend2 = SimulatedDrone(
                id = "DRN-FR-02",
                callsign = "drone2",
                isFriend = true,
                lat = det1.lat - 0.0007,
                lon = det1.lon - 0.0009,
                baseLat = det1.lat - 0.0007,
                baseLon = det1.lon - 0.0009,
                altitudeMeters = 120.0,
                speedKmh = 42.1,
                headingDeg = 130.0,
                isPassiveMode = false
            )
            _activeDrones.value = listOf(friend1, friend2)
            _totalDrones.value = 2
            _friendDrones.value = 2
            _enemyDrones.value = 0

            // Motion patrol loop
            var tick = 0
            val movementJob = launch {
                while (isActive) {
                    delay(1000)
                    tick++
                    _activeDrones.value = _activeDrones.value.mapIndexed { idx, d ->
                        val radius = 0.0006 + (idx * 0.0002)
                        val angle = (tick * 0.15) + (idx * 2.09)
                        d.copy(
                            lat = d.baseLat + (radius * sin(angle)),
                            lon = d.baseLon + (radius * cos(angle))
                        )
                    }
                }
            }

            // ── T = 13s: ENEMY DRONE APPEARS ──
            delay(5000)
            val enemy = SimulatedDrone(
                id = "DRN-FOE-99",
                callsign = "Red-Raven (DJI)",
                isFriend = false,
                lat = det1.lat + 0.0013,
                lon = det1.lon + 0.0006,
                baseLat = det1.lat + 0.0013,
                baseLon = det1.lon + 0.0006,
                altitudeMeters = 145.0,
                speedKmh = 56.8,
                headingDeg = 220.0,
                isPassiveMode = false
            )
            _activeDrones.value = listOf(friend1, friend2, enemy)
            _totalDrones.value = 3
            _friendDrones.value = 2
            _enemyDrones.value = 1

            // ── T = 20s: DETECTOR-2 1KM THREAT ZONE (RIGHT SIDE) ──
            delay(7000)
            while (isActive) {
                _demoRfThreatZone.value = RfThreatZone(
                    centerLat = det2.lat,
                    centerLon = det2.lon,
                    frequency = 2442.37,
                    deviceType = "2.4G OFDM video link"
                )
                delay(4500)
                _demoRfThreatZone.value = null
                delay(1200)
            }

            movementJob.cancel()
        }
    }

    fun stopSimulation() {
        simulationJob?.cancel()
        _totalDrones.value = 0
        _activeDrones.value = emptyList()
        _demoRfThreatZone.value = null
    }
}