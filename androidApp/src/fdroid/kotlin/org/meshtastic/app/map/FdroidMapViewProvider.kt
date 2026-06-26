package org.meshtastic.app.map

import android.annotation.SuppressLint
import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.annotation.Single
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.meshtastic.core.ui.icon.MeshtasticIcons
import org.meshtastic.core.ui.icon.MyLocation
import org.meshtastic.core.ui.util.MapViewProvider
import java.io.File

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height

import androidx.compose.material3.OutlinedTextField

import android.location.LocationManager
import android.content.Context

enum class MapInteractionMode { NONE, DRAW_ZONE, DELETE_ZONE }

@Single
class FdroidMapViewProvider : MapViewProvider {
    @Composable
    override fun MapView(
        modifier: Modifier,
        navigateToNodeDetails: (Int) -> Unit,
        waypointId: Int?
    ) {
        val mapViewModel: MapViewModel = koinViewModel()
        val zoneViewModel: ZoneViewModel = koinViewModel()
        LaunchedEffect(waypointId) { mapViewModel.setWaypointId(waypointId) }

        val context = LocalContext.current
        val lifecycle = LocalLifecycleOwner.current.lifecycle

        MapLibre.getInstance(context)

        // ── Persistent state (survives page switches) ──
        var savedZoom by rememberSaveable { mutableStateOf(17.0) }
        var savedLat by rememberSaveable { mutableStateOf<Double?>(null) }
        var savedLon by rememberSaveable { mutableStateOf<Double?>(null) }
        var hasMovedToLocation by rememberSaveable { mutableStateOf(false) }

        // ── Ephemeral state ──
        var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
        var interactionMode by remember { mutableStateOf(MapInteractionMode.NONE) }
        var showColorPicker by remember { mutableStateOf(false) }
        var pendingZoneCenter by remember { mutableStateOf<LatLng?>(null) }
        var pendingZoneRadius by remember { mutableStateOf(0.0) }
        var showDeleteConfirm by remember { mutableStateOf(false) }
        var zoneToDelete by remember { mutableStateOf<MapZone?>(null) }
        var styleLoaded by remember { mutableStateOf(false) }
        var isDrawingZone by remember { mutableStateOf(false) }
        var zoneTouchHandler by remember { mutableStateOf<ZoneTouchHandler?>(null) }

        // Add these for the Quick Message feature:
        var selectedRemoteNodeId by remember { mutableStateOf<String?>(null) }
        var selectedRemoteNodeName by remember { mutableStateOf("") }
        var quickMessageText by remember { mutableStateOf("") }

        var showActionMenu by remember { mutableStateOf(false) }

        var isInterrogationDialog by remember { mutableStateOf(false) }

        var showKillConfirmDialog by remember { mutableStateOf(false) }

        // ── Memory for entry/exit detection ──
        val previousZonePresence = remember { mutableMapOf<String, Set<String>>() }
        var isFirstZoneCheck by remember { mutableStateOf(true) }

        val zones by zoneViewModel.zones.collectAsStateWithLifecycle()
        val nodes by mapViewModel.nodes.collectAsStateWithLifecycle()

        // ── Update zones ──
        // ── Update zones and dynamically count units inside them ──
        // ── Update zones and dynamically count units inside them ──
        // ── Update zones and dynamically track unit entries/exits ──
        LaunchedEffect(zones, nodes, styleLoaded) {
            if (styleLoaded) {
                val zoneCounts = mutableMapOf<String, Int>()


                // 15 minute timeout (900 seconds)
                val TIMEOUT_SECONDS = 120
                val currentTimeSecs = System.currentTimeMillis() / 1000

                // Only count nodes that have GPS AND have pinged us recently
                val validNodes = nodes.filter { node ->
                    val lastHeardSecs = node.lastHeard?.toLong() ?: 0L
                    val isOnline = (currentTimeSecs - lastHeardSecs) <= TIMEOUT_SECONDS

                    node.validPosition != null && isOnline
                }


                val currentZonePresence = mutableMapOf<String, Set<String>>()

                for (zone in zones) {
                    val nodesInsideThisZone = mutableSetOf<String>()

                    // 1. Find everyone currently inside the zone
                    for (node in validNodes) {
                        val distance = MapLibreHelper.calculateDistance(
                            lat1 = zone.centerLat,
                            lon1 = zone.centerLon,
                            lat2 = node.latitude,
                            lon2 = node.longitude
                        )
                        if (distance <= zone.radiusMeters) {
                            val nodeName = node.user?.short_name ?: node.num.toString()
                            nodesInsideThisZone.add(nodeName)
                        }
                    }

                    currentZonePresence[zone.id] = nodesInsideThisZone
                    zoneCounts[zone.id] = nodesInsideThisZone.size

                    // 2. Compare with previous state to detect Entries and Exits
                    // We only block notifications if it's the very first time the map loads.
                    // If it's NOT the first load, we alert for any new zone captures!
                    if (!isFirstZoneCheck) {
                        // If it's a new zone, previousNodes is empty, so EVERYONE triggers an 'entered' alert.
                        val previousNodes = previousZonePresence[zone.id] ?: emptySet()
                        val entered = nodesInsideThisZone - previousNodes
                        val exited = previousNodes - nodesInsideThisZone

                        entered.forEach { nodeName ->
                            android.widget.Toast.makeText(
                                context,
                                "⚠️ $nodeName ENTERED Zone",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }

                        exited.forEach { nodeName ->
                            android.widget.Toast.makeText(
                                context,
                                "ℹ️ $nodeName EXITED Zone",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }

                // 3. Update the memory and toggle the first-run flag
                isFirstZoneCheck = false
                previousZonePresence.clear()
                previousZonePresence.putAll(currentZonePresence)

                // 4. Draw the zones and the text boxes
                mapLibreMap?.let { map ->
                    MapLibreHelper.updateZoneLayers(map, zones)
                    MapLibreHelper.updateZoneCountLabels(map, zones, zoneCounts, context)
                }
            }
        }

        // ── Update node markers ──
        val battlefieldVm: org.meshtastic.app.battlefield.BattlefieldViewModel =
            remember { org.koin.core.context.GlobalContext.get().get() }
        val nodeUnitTypes by battlefieldVm.nodeUnitTypes.collectAsStateWithLifecycle()
        val myUnitType by battlefieldVm.unitType.collectAsStateWithLifecycle()

        LaunchedEffect(nodes, styleLoaded, mapLibreMap, nodeUnitTypes, myUnitType) {
            if (!styleLoaded) return@LaunchedEffect
            val map = mapLibreMap ?: return@LaunchedEffect

            kotlinx.coroutines.delay(300)

            val myNodeNum = mapViewModel.myNodeInfo.value?.myNodeNum

            if (myNodeNum != null) {
                battlefieldVm.setMyNodeId(myNodeNum.toString())
            }

            // Add other nodes that have LoRa GPS
            val markerData = mutableListOf<NodeMarkerData>()

            // Define how long a node can be silent before it disappears (in seconds)
            // 900 seconds = 15 minutes. Adjust this to whatever fits your mission profile!
            val TIMEOUT_SECONDS = 120
            val currentTimeSecs = System.currentTimeMillis() / 1000

            // Filter for nodes with valid GPS, NOT our own node, AND heard from recently
            nodes.filter { node ->
                val hasPosition = node.validPosition != null
                val isNotMe = node.num != myNodeNum

                // Meshtastic stores lastHeard in seconds since epoch
                val lastHeardSecs = node.lastHeard?.toLong() ?: 0L
                val isOnline = (currentTimeSecs - lastHeardSecs) <= TIMEOUT_SECONDS

                hasPosition && isNotMe && isOnline
            }.forEach { node ->
                markerData.add(
                    NodeMarkerData(
                        id = node.num.toString(),
                        lat = node.latitude,
                        lon = node.longitude,
                        shortName = node.user?.short_name ?: "?"
                    )
                )
            }

            // Add MY node — LoRa GPS first, phone GPS fallback
            // Add MY node — LoRa GPS first, phone GPS fallback with retry
            // Add MY node — LoRa GPS first, then active phone GPS request
            if (myNodeNum != null) {
                val myNode = nodes.firstOrNull { it.num == myNodeNum }
                val myLoraLocation = if (myNode?.validPosition != null) {
                    Pair(myNode.latitude, myNode.longitude)
                } else null

                val lat: Double?
                val lon: Double?

                if (myLoraLocation != null) {
                    lat = myLoraLocation.first
                    lon = myLoraLocation.second
                    android.util.Log.d("MarkerFix", "MY MARKER: Using LoRa GPS")
                } else {
                    // Try cached first, then actively request fresh location
                    val cached = getPhoneLocation(context)
                    val location = cached ?: requestFreshLocation(context)
                    lat = location?.latitude
                    lon = location?.longitude
                    android.util.Log.d("MarkerFix", "MY MARKER: ${if (location != null) "Using Phone GPS ${lat},${lon}" else "No GPS"}")
                }

                if (lat != null && lon != null) {
                    markerData.add(NodeMarkerData(
                        id = myNodeNum.toString(),
                        lat = lat,
                        lon = lon,
                        shortName = myNode?.user?.short_name ?: "Me"
                    ))
                }
            }

            MapLibreHelper.updateNodeMarkers(
                map = map,
                nodes = markerData,
                context = context,
                myNodeId = myNodeNum?.toString() ?: "",
                getUnitType = { nodeId -> battlefieldVm.getUnitTypeForNode(nodeId) }
            )
        }

        // One-time GPS acquisition on first load
        LaunchedEffect(styleLoaded) {
            if (!styleLoaded) return@LaunchedEffect

            // Try cached location first for immediate marker
            val cachedLoc = getPhoneLocation(context)
            val myNodeNum = mapViewModel.myNodeInfo.value?.myNodeNum

            if (cachedLoc != null && myNodeNum != null) {
                val map = mapLibreMap ?: return@LaunchedEffect
                val currentNodes = mapViewModel.nodes.value
                val markerData = mutableListOf<NodeMarkerData>()

                currentNodes.filter { it.validPosition != null && it.num != myNodeNum }
                    .forEach { node ->
                        markerData.add(NodeMarkerData(
                            id = node.num.toString(),
                            lat = node.latitude,
                            lon = node.longitude,
                            shortName = node.user.short_name ?: "?"
                        ))
                    }

                val myNode = currentNodes.firstOrNull { it.num == myNodeNum }
                val myLat = myNode?.takeIf { it.validPosition != null }?.latitude ?: cachedLoc.latitude
                val myLon = myNode?.takeIf { it.validPosition != null }?.longitude ?: cachedLoc.longitude

                markerData.add(NodeMarkerData(
                    id = myNodeNum.toString(),
                    lat = myLat,
                    lon = myLon,
                    shortName = myNode?.user?.short_name ?: "Me"
                ))

                battlefieldVm.setMyNodeId(myNodeNum.toString())
                MapLibreHelper.updateNodeMarkers(
                    map = map,
                    nodes = markerData,
                    context = context,
                    myNodeId = myNodeNum.toString(),
                    getUnitType = { nodeId -> battlefieldVm.getUnitTypeForNode(nodeId) }
                )
            }

            // Then try fresh GPS and update again
            kotlinx.coroutines.delay(500)
            val freshLoc = requestFreshLocation(context)
            if (freshLoc != null && myNodeNum != null) {
                val map = mapLibreMap ?: return@LaunchedEffect
                val currentNodes = mapViewModel.nodes.value
                val markerData = mutableListOf<NodeMarkerData>()

                currentNodes.filter { it.validPosition != null && it.num != myNodeNum }
                    .forEach { node ->
                        markerData.add(NodeMarkerData(
                            id = node.num.toString(),
                            lat = node.latitude,
                            lon = node.longitude,
                            shortName = node.user.short_name ?: "?"
                        ))
                    }

                val myNode = currentNodes.firstOrNull { it.num == myNodeNum }
                val myLat = myNode?.takeIf { it.validPosition != null }?.latitude ?: freshLoc.latitude
                val myLon = myNode?.takeIf { it.validPosition != null }?.longitude ?: freshLoc.longitude

                markerData.add(NodeMarkerData(
                    id = myNodeNum.toString(),
                    lat = myLat,
                    lon = myLon,
                    shortName = myNode?.user?.short_name ?: "Me"
                ))

                battlefieldVm.setMyNodeId(myNodeNum.toString())
                MapLibreHelper.updateNodeMarkers(
                    map = map,
                    nodes = markerData,
                    context = context,
                    myNodeId = myNodeNum.toString(),
                    getUnitType = { nodeId -> battlefieldVm.getUnitTypeForNode(nodeId) }
                )
                android.util.Log.d("MarkerFix", "Fresh GPS marker placed: ${freshLoc.latitude}, ${freshLoc.longitude}")
            }
        }

        // ── Auto move to my location once ──
        LaunchedEffect(styleLoaded) {
            if (styleLoaded && !hasMovedToLocation) {
                val map = mapLibreMap ?: return@LaunchedEffect
                // Wait a moment for location component to get a fix
                kotlinx.coroutines.delay(1000)

                // THIS MUST BE HERE — sets myNodeId in BattlefieldViewModel
                val myNodeNum = mapViewModel.myNodeInfo.value?.myNodeNum
                if (myNodeNum != null) {
                    battlefieldVm.setMyNodeId(myNodeNum.toString())
                }

                val loc = getPhoneLocation(context)
                if (loc != null) {
                    map.moveCamera(
                        CameraUpdateFactory.newLatLngZoom(
                            LatLng(loc.latitude, loc.longitude), 17.0
                        )
                    )
                    savedLat = loc.latitude
                    savedLon = loc.longitude
                    savedZoom = 17.0
                    hasMovedToLocation = true
                } else {
                    val lat = savedLat ?: 20.5937
                    val lon = savedLon ?: 78.9629
                    val zoom = if (savedLat != null) savedZoom else 5.0
                    map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), zoom))
                }
            }
        }

        val mapView = remember { MapView(context) }

        // ── Lifecycle + save camera on pause ──
        DisposableEffect(lifecycle) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> mapView.onStart()
                    Lifecycle.Event.ON_RESUME -> mapView.onResume()
                    Lifecycle.Event.ON_PAUSE -> {
                        mapLibreMap?.cameraPosition?.let { pos ->
                            savedLat = pos.target?.latitude
                            savedLon = pos.target?.longitude
                            savedZoom = pos.zoom
                        }
                        mapView.onPause()
                    }
                    Lifecycle.Event.ON_STOP -> mapView.onStop()
                    Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                    else -> {}
                }
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }

        Box(modifier = modifier.fillMaxSize()) {

            AndroidView(
                factory = {
                    mapView.apply {

                        val mbtilesFile = listOf(
                            File(context.getExternalFilesDir(null), "india.mbtiles"),
                            File(
                                Environment.getExternalStorageDirectory(),
                                "offline_maps/india.mbtiles"
                            ),
                            File(
                                Environment.getExternalStorageDirectory(),
                                "Download/india.mbtiles"
                            )
                        ).firstOrNull { it.exists() && it.canRead() }

                        android.util.Log.d(
                            "MapDebug",
                            "MBTiles: ${mbtilesFile?.absolutePath ?: "NOT FOUND"}"
                        )

                        getMapAsync { map ->
                            mapLibreMap = map

                            val styleJson = if (mbtilesFile != null) {
                                MapStyleProvider.getOfflineStyleJson(mbtilesFile.absolutePath)
                            } else {
                                """
                                {
                                  "version": 8,
                                  "sources": {
                                    "osm": {
                                      "type": "raster",
                                      "tiles": ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
                                      "tileSize": 256
                                    }
                                  },
                                  "layers": [{
                                    "id": "osm",
                                    "type": "raster",
                                    "source": "osm"
                                  }]
                                }
                                """.trimIndent()
                            }

                            map.setStyle(Style.Builder().fromJson(styleJson)) { _ ->
                                styleLoaded = true
                                enableLocationComponent(map, context)
                            }

                            // Restore saved camera position
                            if (savedLat != null && savedLon != null) {
                                map.moveCamera(
                                    CameraUpdateFactory.newLatLngZoom(
                                        LatLng(savedLat!!, savedLon!!), savedZoom
                                    )
                                )
                            }

                            // Save camera on every move
                            map.addOnCameraMoveListener {
                                map.cameraPosition.target?.let { target ->
                                    savedLat = target.latitude
                                    savedLon = target.longitude
                                    savedZoom = map.cameraPosition.zoom
                                }
                            }

                            // NEW: Marker Click Listener for Quick Messaging
                            // Marker Click Listener for Action Selection Menu
                            map.setOnMarkerClickListener { marker ->
                                val nodeId = marker.snippet?.replace("Node: ", "") ?: return@setOnMarkerClickListener false
                                val myNodeNum = mapViewModel.myNodeInfo.value?.myNodeNum?.toString()

                                if (nodeId != myNodeNum) {
                                    selectedRemoteNodeId = nodeId
                                    selectedRemoteNodeName = marker.title ?: "Unknown Unit"
                                    quickMessageText = ""
                                    showActionMenu = true // <-- Launch the intermediate selection pop-up first
                                    true
                                } else {
                                    false
                                }
                            }

                            // Click listener for DELETE mode
                            map.addOnMapClickListener { latLng ->
                                when (interactionMode) {
                                    MapInteractionMode.DELETE_ZONE -> {
                                        // Only get local zones — can't delete received zones
                                        val zone = zoneViewModel.getLocalZoneAtPoint(
                                            latLng.latitude, latLng.longitude
                                        )
                                        if (zone != null) {
                                            zoneToDelete = zone
                                            showDeleteConfirm = true
                                        } else {
                                            // Tapped a remote zone or empty area
                                            val remoteZone = zoneViewModel.getZoneAtPoint(
                                                latLng.latitude, latLng.longitude
                                            )
                                            if (remoteZone != null) {
                                                // Show message that this zone can't be deleted
                                                android.widget.Toast.makeText(
                                                    context,
                                                    "You can only delete zones you created",
                                                    android.widget.Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                        true
                                    }
                                    else -> false
                                }
                            }

                            // Zone touch handler
                            val handler = ZoneTouchHandler(
                                mapView = this,
                                map = map,
                                onDrawing = { lat, lon, radius ->
                                    isDrawingZone = true
                                    MapLibreHelper.drawPreviewZone(map, lat, lon, radius)
                                },
                                onZoneReady = { lat, lon, radius ->
                                    MapLibreHelper.clearPreviewZone(map)
                                    isDrawingZone = false
                                    pendingZoneCenter = LatLng(lat, lon)
                                    pendingZoneRadius = radius
                                    showColorPicker = true
                                },
                                onCancelled = {
                                    MapLibreHelper.clearPreviewZone(map)
                                    isDrawingZone = false
                                }
                            )
                            zoneTouchHandler = handler
                        }

                        // Touch interceptor for DRAW mode
                        setOnTouchListener { _, event ->
                            if (interactionMode == MapInteractionMode.DRAW_ZONE) {
                                zoneTouchHandler?.onTouch(event) ?: false
                            } else {
                                false
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // ── Bottom Right: Zoom + Location buttons ──
            // ── Bottom Right: Zoom + Location buttons ──
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.End // Ensures + and - stay aligned to the far right edge
            ) {
                // Zoom In
                FloatingActionButton(
                    onClick = { mapLibreMap?.animateCamera(CameraUpdateFactory.zoomIn()) },
                    shape = CircleShape,
                    containerColor = Color.White
                ) {
                    Text("+", fontSize = 24.sp, color = Color(0xFF1E88E5))
                }

                // Zoom Out
                FloatingActionButton(
                    onClick = { mapLibreMap?.animateCamera(CameraUpdateFactory.zoomOut()) },
                    shape = CircleShape,
                    containerColor = Color.White
                ) {
                    Text("−", fontSize = 24.sp, color = Color(0xFF1E88E5))
                }

                // Row holding the Logo (Left) and Location Button (Right)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp) // Space between logo and button
                ) {
                    // Company Logo
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(
                            id = org.meshtastic.app.R.drawable.company_logo // MAKE SURE THIS MATCHES YOUR FILENAME
                        ),
                        contentDescription = "Company Logo",
                        modifier = Modifier.height(56.dp) // Adjust this size (e.g. 48.dp, 64.dp) to fit perfectly
                    )

                    // My Location Button
                    FloatingActionButton(
                        onClick = {
                            val map = mapLibreMap ?: return@FloatingActionButton
                            val myNodeNum = mapViewModel.myNodeInfo.value?.myNodeNum
                            val myNode = mapViewModel.nodes.value.firstOrNull { it.num == myNodeNum }

                            val lat: Double?
                            val lon: Double?

                            if (myNode?.validPosition != null) {
                                lat = myNode.latitude
                                lon = myNode.longitude
                            } else {
                                val loc = getPhoneLocation(context)
                                lat = loc?.latitude
                                lon = loc?.longitude
                            }

                            if (lat != null && lon != null) {
                                map.animateCamera(
                                    CameraUpdateFactory.newLatLngZoom(
                                        LatLng(lat, lon), 17.0
                                    )
                                )
                                savedLat = lat
                                savedLon = lon
                                savedZoom = 17.0
                            }
                        },
                        shape = CircleShape,
                        containerColor = Color.White
                    ) {
                        Icon(
                            imageVector = MeshtasticIcons.MyLocation,
                            contentDescription = "My Location",
                            tint = Color(0xFF1E88E5)
                        )
                    }
                }
            }

            // ── Bottom Left: Zone buttons ──
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FloatingActionButton(
                    onClick = {
                        interactionMode =
                            if (interactionMode == MapInteractionMode.DELETE_ZONE)
                                MapInteractionMode.NONE
                            else MapInteractionMode.DELETE_ZONE
                    },
                    shape = CircleShape,
                    containerColor = if (interactionMode == MapInteractionMode.DELETE_ZONE)
                        Color(0xFFE53935) else Color.White
                ) {
                    Text("🗑", fontSize = 20.sp)
                }

                FloatingActionButton(
                    onClick = {
                        interactionMode =
                            if (interactionMode == MapInteractionMode.DRAW_ZONE)
                                MapInteractionMode.NONE
                            else MapInteractionMode.DRAW_ZONE
                    },
                    shape = CircleShape,
                    containerColor = if (interactionMode == MapInteractionMode.DRAW_ZONE)
                        Color(0xFF43A047) else Color.White
                ) {
                    Text(
                        "⬤",
                        fontSize = 20.sp,
                        color = if (interactionMode == MapInteractionMode.DRAW_ZONE)
                            Color.White else Color(0xFF43A047)
                    )
                }
            }

            // ── Mode indicator banner ──
            if (interactionMode != MapInteractionMode.NONE) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 72.dp)
                        .background(
                            color = if (interactionMode == MapInteractionMode.DRAW_ZONE)
                                Color(0xFF43A047) else Color(0xFFE53935),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
                        )
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (interactionMode == MapInteractionMode.DRAW_ZONE)
                            "Press and drag to draw zone"
                        else "Tap a zone to delete it",
                        color = Color.White,
                        fontSize = 13.sp
                    )
                }
            }
        }

        // ── Color Picker Dialog ──
        if (showColorPicker) {
            AlertDialog(
                onDismissRequest = { showColorPicker = false },
                title = { Text("Choose Zone Color") },
                text = {
                    Row(
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        ZoneColor.entries.forEach { color ->
                            val bgColor = when (color) {
                                ZoneColor.RED -> Color(0xFFE53935)
                                ZoneColor.YELLOW -> Color(0xFFFDD835)
                                ZoneColor.GREEN -> Color(0xFF43A047)
                            }
                            Button(
                                onClick = {
                                    pendingZoneCenter?.let { center ->
                                        val newZone = MapZone(
                                            centerLat = center.latitude,
                                            centerLon = center.longitude,
                                            radiusMeters = pendingZoneRadius,
                                            color = color
                                        )
                                        zoneViewModel.addZone(newZone)
                                        // Send zone to all mesh nodes
                                        mapViewModel.sendZone(newZone)
                                    }
                                    showColorPicker = false
                                    interactionMode = MapInteractionMode.NONE
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = bgColor)
                            ) {
                                Text(color.name, color = Color.White)
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showColorPicker = false }) { Text("Cancel") }
                }
            )
        }

        // ── Delete Confirmation Dialog ──
        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text("Delete Zone?") },
                text = { Text("Are you sure you want to delete this zone?") },
                confirmButton = {
                    Button(
                        onClick = {
                            val zone = zoneToDelete
                            if (zone != null) {
                                zoneViewModel.deleteZone(zone.id)
                                mapViewModel.sendZoneDelete(zone)
                            }
                            showDeleteConfirm = false
                            zoneToDelete = null
                            interactionMode = MapInteractionMode.NONE
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFE53935)
                        )
                    ) { Text("Delete") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showDeleteConfirm = false
                        zoneToDelete = null
                    }) { Text("Cancel") }
                }
            )
        }

        // ── Tactical Action Selection Menu ──
        if (showActionMenu && selectedRemoteNodeId != null) {
            androidx.compose.ui.window.Dialog(onDismissRequest = { showActionMenu = false }) {
                androidx.compose.material3.Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                    color = Color(0xFF121A16),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF4CAF50)),
                    tonalElevation = 8.dp
                ) {
                    Column(
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "TACTICAL INTERACTION: $selectedRemoteNodeName",
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            fontSize = 14.sp,
                            color = Color(0xFF4CAF50),
                            letterSpacing = 1.sp
                        )

                        androidx.compose.material3.HorizontalDivider(color = Color(0xFF2E4035), thickness = 1.dp)

                        // Option 1: Message
                        Button(
                            onClick = {
                                isInterrogationDialog = false // Route to standard messaging
                                showActionMenu = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF37474F)),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(2.dp)
                        ) {
                            Text("1. MESSAGE UNIT", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = Color.White)
                        }

                        // Option 2: Interrogate
                        Button(
                            onClick = {
                                isInterrogationDialog = true // Route to verification challenge panel
                                showActionMenu = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B0000)),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(2.dp)
                        ) {
                            Text("2. INTERROGATE UNIT", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = Color.White)
                        }


                        // ── ADD THIS BLOCK: Option 3: Kill Comm ──
                        Button(
                            onClick = {
                                showActionMenu = false
                                showKillConfirmDialog = true // Launch the confirmation alert
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C)), // Pure Danger Red
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(2.dp)
                        ) {
                            Text("3. KILL COMM. UNIT", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = Color.White)
                        }



                        TextButton(onClick = { showActionMenu = false }) {
                            Text("ABORT OPERATION", color = Color(0xFFE53935), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // ── Unified Tactical Dialog Engine ──
        if (selectedRemoteNodeId != null && !showActionMenu) {
            androidx.compose.ui.window.Dialog(onDismissRequest = { selectedRemoteNodeId = null }) {
                androidx.compose.material3.Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                    color = Color(0xFF121A16),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF4CAF50)),
                    tonalElevation = 8.dp
                ) {
                    Column(
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Title Header updates context based on branch route selection
                        Text(
                            text = if (isInterrogationDialog) "INTERROGATION LINK: $selectedRemoteNodeName" else "COMMS LINK: $selectedRemoteNodeName",
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            fontSize = 16.sp,
                            color = Color(0xFF4CAF50),
                            letterSpacing = 1.sp
                        )

                        androidx.compose.material3.HorizontalDivider(color = Color(0xFF2E4035), thickness = 1.dp)

                        // ── BRANCH A: STANDARD MESSAGING INTERFACE ──
                        if (!isInterrogationDialog) {
                            Text(
                                text = "QUICK TRANSMIT STATUS:",
                                fontSize = 11.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                color = Color(0xFFA0B2A6),
                                letterSpacing = 0.5.sp
                            )

                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    Button(
                                        onClick = {
                                            mapViewModel.sendDirectMessage(selectedRemoteNodeId!!, "Need Assistance!")
                                            selectedRemoteNodeId = null
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B0000)),
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(2.dp),
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)
                                    ) {
                                        Text("NEED ASSIST", fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 1)
                                    }

                                    Button(
                                        onClick = {
                                            mapViewModel.sendDirectMessage(selectedRemoteNodeId!!, "Area Clear.")
                                            selectedRemoteNodeId = null
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B5E20)),
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(2.dp),
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)
                                    ) {
                                        Text("AREA CLEAR", fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 1)
                                    }
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    Button(
                                        onClick = {
                                            mapViewModel.sendDirectMessage(selectedRemoteNodeId!!, "Moving to Position.")
                                            selectedRemoteNodeId = null
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF37474F)),
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(2.dp),
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)
                                    ) {
                                        Text("MOVING TO POS", fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 1)
                                    }

                                    Button(
                                        onClick = {
                                            mapViewModel.sendDirectMessage(selectedRemoteNodeId!!, "Target Spotted!")
                                            selectedRemoteNodeId = null
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF57F17)),
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(2.dp),
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)
                                    ) {
                                        Text("TARGET SPOTTED", fontSize = 11.sp, color = Color.Black, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 1)
                                    }
                                }
                            }

                            Text(
                                text = "CUSTOM TRANSMISSION:",
                                fontSize = 11.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                color = Color(0xFFA0B2A6),
                                modifier = Modifier.padding(top = 4.dp),
                                letterSpacing = 0.5.sp
                            )

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                androidx.compose.material3.OutlinedTextField(
                                    value = quickMessageText,
                                    onValueChange = { quickMessageText = it },
                                    placeholder = { Text("Enter payload...", color = Color(0xFF5A7062)) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(2.dp),
                                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color(0xFFE0E0E0),
                                        unfocusedTextColor = Color(0xFFE0E0E0),
                                        focusedBorderColor = Color(0xFF4CAF50),
                                        unfocusedBorderColor = Color(0xFF2E4035),
                                        cursorColor = Color(0xFF4CAF50),
                                        focusedContainerColor = Color(0xFF0A0F0D),
                                        unfocusedContainerColor = Color(0xFF0A0F0D)
                                    )
                                )

                                Button(
                                    onClick = {
                                        if (quickMessageText.isNotBlank()) {
                                            mapViewModel.sendDirectMessage(selectedRemoteNodeId!!, quickMessageText)
                                            quickMessageText = ""
                                            selectedRemoteNodeId = null
                                        }
                                    },
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(2.dp),
                                    modifier = Modifier.size(52.dp),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                                ) {
                                    Text("SEND", color = Color.Black, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 16.sp)
                                }
                            }
                        }

                        // ── BRANCH B: INTERROGATION PANEL ──
                        else {
                            Text(
                                text = "INTERROGATION:",
                                fontSize = 11.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                color = Color(0xFFE53935), // Warn Color Alert
                                modifier = Modifier.padding(top = 4.dp),
                                letterSpacing = 0.5.sp
                            )

                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    Button(
                                        onClick = {
                                            mapViewModel.sendDirectMessage(selectedRemoteNodeId!!, "AUTHENTICATE: FALCON-6")
                                            selectedRemoteNodeId = null
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A237E)),
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(2.dp),
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)
                                    ) {
                                        Text("CHALLENGE: FALCON", fontSize = 10.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 1)
                                    }

                                    Button(
                                        onClick = {
                                            mapViewModel.sendDirectMessage(selectedRemoteNodeId!!, "STATUS CHECK: VIPER")
                                            selectedRemoteNodeId = null
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A237E)),
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(2.dp),
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)
                                    ) {
                                        Text("CHALLENGE: VIPER", fontSize = 10.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 1)
                                    }
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    Button(
                                        onClick = {
                                            mapViewModel.sendDirectMessage(selectedRemoteNodeId!!, "EXECUTE: PROTOCOL PHANTOM")
                                            selectedRemoteNodeId = null
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A148C)),
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(2.dp),
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)
                                    ) {
                                        Text("CODE: PHANTOM", fontSize = 10.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 1)
                                    }

                                    Button(
                                        onClick = {
                                            mapViewModel.sendDirectMessage(selectedRemoteNodeId!!, "FALLBACK TO RECON ZONE")
                                            selectedRemoteNodeId = null
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF006064)),
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(2.dp),
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)
                                    ) {
                                        Text("CODE: FALLBACK", fontSize = 10.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 1)
                                    }
                                }
                            }
                        }

                        // Shared exit node controller at the bottom of panel
                        TextButton(
                            onClick = { selectedRemoteNodeId = null },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("ABORT", color = Color(0xFFE53935), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        }
                    }
                }
            }
        }


        // ── Remote Kill Comm. Confirmation Dialog ──
        if (showKillConfirmDialog && selectedRemoteNodeId != null) {
            AlertDialog(
                onDismissRequest = { showKillConfirmDialog = false },
                title = {
                    Text(
                        "⚠ REMOTE DESTROY COMMAND",
                        color = Color(0xFFE53935),
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        "Are you absolutely certain you want to kill communications for $selectedRemoteNodeName?\n\n" +
                                "This will transmit an over-the-air payload instruction. The target device will immediately clear local mission data tables, flush secure parameters, and completely lock execution authorization.",
                        color = Color.White
                    )
                },
                containerColor = Color(0xFF121A16), // Tactical dark green background
                confirmButton = {
                    Button(
                        onClick = {
                            showKillConfirmDialog = false
                            battlefieldVm.sendRemoteWipe(selectedRemoteNodeId!!) // Broadcast wipe packet
                            selectedRemoteNodeId = null // Terminate target sequence
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C)) // Solid Danger Red
                    ) {
                        Text("EXECUTE WIPE", color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showKillConfirmDialog = false
                        selectedRemoteNodeId = null
                    }) {
                        Text("CANCEL", color = Color.Gray)
                    }
                }
            )
        }



    } // This closes Box wrapper
} // This closes MapView function // This closes the MapView function scope safely




    @SuppressLint("MissingPermission")
    private fun enableLocationComponent(map: MapLibreMap, context: android.content.Context) {
        // We draw our own location marker, so we disable the built-in dot
        // but still enable the engine to get GPS coordinates
        val style = map.style ?: return
        try {
            val locationComponent = map.locationComponent
            val options = LocationComponentActivationOptions
                .builder(context, style)
                .useDefaultLocationEngine(true)
                .build()
            locationComponent.activateLocationComponent(options)
            locationComponent.isLocationComponentEnabled = false // ← disabled visually
            locationComponent.cameraMode = CameraMode.NONE
            locationComponent.renderMode = RenderMode.NORMAL
        } catch (e: Exception) {
            android.util.Log.e("MapLibre", "Location component error: ${e.message}")
        }
    }


    @SuppressLint("MissingPermission")
    private fun getPhoneLocation(context: android.content.Context): android.location.Location? {
        return try {
            val locationManager = context.getSystemService(
                android.content.Context.LOCATION_SERVICE
            ) as android.location.LocationManager

            val providers = locationManager.getProviders(true)
            var bestLocation: android.location.Location? = null

            for (provider in providers) {
                val loc = locationManager.getLastKnownLocation(provider) ?: continue
                if (bestLocation == null || loc.accuracy < bestLocation.accuracy) {
                    bestLocation = loc
                }
            }
            bestLocation
        } catch (e: Exception) { null }
    }


    @SuppressLint("MissingPermission")
    private suspend fun requestFreshLocation(context: android.content.Context): android.location.Location? {
        return try {
            val locationManager = context.getSystemService(
                android.content.Context.LOCATION_SERVICE
            ) as android.location.LocationManager

            val providers = listOf(
                android.location.LocationManager.GPS_PROVIDER,
                android.location.LocationManager.NETWORK_PROVIDER,
                android.location.LocationManager.FUSED_PROVIDER
            ).filter {
                try { locationManager.isProviderEnabled(it) } catch (_: Exception) { false }
            }

            if (providers.isEmpty()) return getPhoneLocation(context)

            kotlinx.coroutines.withTimeoutOrNull(10000) {
                kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                    val listener = object : android.location.LocationListener {
                        override fun onLocationChanged(location: android.location.Location) {
                            if (cont.isActive) cont.resumeWith(Result.success(location))
                            try { locationManager.removeUpdates(this) } catch (_: Exception) {}
                        }
                        override fun onProviderDisabled(provider: String) {}
                        override fun onProviderEnabled(provider: String) {}
                    }

                    var registered = false
                    for (provider in providers) {
                        try {
                            locationManager.requestLocationUpdates(
                                provider, 0L, 0f, listener,
                                android.os.Looper.getMainLooper()
                            )
                            registered = true
                            break
                        } catch (_: Exception) {}
                    }

                    if (!registered) {
                        cont.resumeWith(Result.success(null))
                        return@suspendCancellableCoroutine
                    }

                    cont.invokeOnCancellation {
                        try { locationManager.removeUpdates(listener) } catch (_: Exception) {}
                    }
                }
            } ?: getPhoneLocation(context) // fallback to cached if timeout

        } catch (e: Exception) {
            android.util.Log.e("MarkerFix", "requestFreshLocation error: ${e.message}")
            getPhoneLocation(context)
        }
    }



