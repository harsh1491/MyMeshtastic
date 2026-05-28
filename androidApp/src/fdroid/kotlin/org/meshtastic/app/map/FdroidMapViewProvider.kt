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

        val zones by zoneViewModel.zones.collectAsStateWithLifecycle()
        val nodes by mapViewModel.nodes.collectAsStateWithLifecycle()

        // ── Update zones ──
        LaunchedEffect(zones, styleLoaded) {
            if (styleLoaded) {
                mapLibreMap?.let { MapLibreHelper.updateZoneLayers(it, zones) }
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

            val markerData = mutableListOf<NodeMarkerData>()

            // Add other nodes that have LoRa GPS
            nodes.filter { it.validPosition != null && it.num != myNodeNum }.forEach { node ->
                markerData.add(
                    NodeMarkerData(
                        id = node.num.toString(),
                        lat = node.latitude,
                        lon = node.longitude,
                        shortName = node.user.short_name ?: "?"
                    )
                )
            }

            // Add MY node — LoRa GPS first, phone GPS fallback
            if (myNodeNum != null) {
                val myNode = nodes.firstOrNull { it.num == myNodeNum }
                val myLoraLocation = if (myNode?.validPosition != null) {
                    Pair(myNode.latitude, myNode.longitude)
                } else null

                val myPhoneLocation = getPhoneLocation(context)

                when {
                    myLoraLocation != null -> {
                        android.util.Log.d("MarkerFix", "MY MARKER: Using LoRa GPS → ${myLoraLocation.first}, ${myLoraLocation.second}")
                        markerData.add(
                            NodeMarkerData(
                                id = myNodeNum.toString(),
                                lat = myLoraLocation.first,
                                lon = myLoraLocation.second,
                                shortName = myNode?.user?.short_name ?: "Me"
                            )
                        )
                    }
                    myPhoneLocation != null -> {
                        android.util.Log.d("MarkerFix", "MY MARKER: Using Phone GPS → ${myPhoneLocation.latitude}, ${myPhoneLocation.longitude}")
                        markerData.add(
                            NodeMarkerData(
                                id = myNodeNum.toString(),
                                lat = myPhoneLocation.latitude,
                                lon = myPhoneLocation.longitude,
                                shortName = myNode?.user?.short_name ?: "Me"
                            )
                        )
                    }
                    else -> {
                        android.util.Log.d("MarkerFix", "MY MARKER: No GPS available — marker not shown")
                    }
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
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FloatingActionButton(
                    onClick = { mapLibreMap?.animateCamera(CameraUpdateFactory.zoomIn()) },
                    shape = CircleShape,
                    containerColor = Color.White
                ) {
                    Text("+", fontSize = 24.sp, color = Color(0xFF1E88E5))
                }

                FloatingActionButton(
                    onClick = { mapLibreMap?.animateCamera(CameraUpdateFactory.zoomOut()) },
                    shape = CircleShape,
                    containerColor = Color.White
                ) {
                    Text("−", fontSize = 24.sp, color = Color(0xFF1E88E5))
                }

                FloatingActionButton(
                    onClick = {
                        val map = mapLibreMap ?: return@FloatingActionButton
                        val myNodeNum = mapViewModel.myNodeInfo.value?.myNodeNum
                        val myNode = mapViewModel.nodes.value.firstOrNull { it.num == myNodeNum }

                        val lat: Double?
                        val lon: Double?

                        if (myNode?.validPosition != null) {
                            // Use LoRa GPS
                            lat = myNode.latitude
                            lon = myNode.longitude
                        } else {
                            // Use phone GPS
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
    }

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
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = locationManager.getProviders(true)
            var bestLocation: android.location.Location? = null
            for (provider in providers) {
                val loc = locationManager.getLastKnownLocation(provider) ?: continue
                if (bestLocation == null || loc.accuracy < bestLocation.accuracy) {
                    bestLocation = loc
                }
            }
            bestLocation
        } catch (e: Exception) {
            android.util.Log.e("MarkerFix", "Failed to get phone location: ${e.message}")
            null
        }
    }



}