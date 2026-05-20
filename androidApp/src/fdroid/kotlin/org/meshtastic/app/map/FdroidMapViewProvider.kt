package org.meshtastic.app.map

import android.os.Environment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.annotation.Single
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.meshtastic.core.ui.util.MapViewProvider
import java.io.File

/** MapLibre + offline MBTiles implementation of [MapViewProvider]. */
@Single
class FdroidMapViewProvider : MapViewProvider {
    @Composable
    override fun MapView(modifier: Modifier, navigateToNodeDetails: (Int) -> Unit, waypointId: Int?) {
        val mapViewModel: MapViewModel = koinViewModel()
        LaunchedEffect(waypointId) { mapViewModel.setWaypointId(waypointId) }

        val context = LocalContext.current
        val lifecycle = LocalLifecycleOwner.current.lifecycle

        // Initialize MapLibre engine
        MapLibre.getInstance(context)

        val mapView = remember {
            MapView(context)
        }

        // Handle lifecycle events
        DisposableEffect(lifecycle) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> mapView.onStart()
                    Lifecycle.Event.ON_RESUME -> mapView.onResume()
                    Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                    Lifecycle.Event.ON_STOP -> mapView.onStop()
                    Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                    else -> {}
                }
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }

        AndroidView(
            factory = {
                mapView.apply {
                    // Try multiple locations in order of preference
                    val mbtilesFile = listOf(
                        File(context.getExternalFilesDir(null), "india.mbtiles"),
                        File(Environment.getExternalStorageDirectory(), "offline_maps/india.mbtiles"),
                        File(Environment.getExternalStorageDirectory(), "Download/india.mbtiles")
                    ).firstOrNull { it.exists() && it.canRead() }

// ADD THIS LINE:
                    android.util.Log.d("MapLibre", "MBTiles file: ${mbtilesFile?.absolutePath ?: "NOT FOUND"}")

                    getMapAsync { map ->
                        // Check all possible file locations
                        val mbtilesFile = listOf(
                            File(context.getExternalFilesDir(null), "india.mbtiles"),
                            File(Environment.getExternalStorageDirectory(), "offline_maps/india.mbtiles")
                        ).firstOrNull { it.exists() && it.canRead() }

                        val styleJson = if (mbtilesFile != null) {
                            MapStyleProvider.getOfflineStyleJson(mbtilesFile.absolutePath)
                        } else {
                            // Safe fallback - online OSM raster tiles, no file needed
                            """
        {
          "version": 8,
          "sources": {
            "osm": {
              "type": "raster",
              "tiles": ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
              "tileSize": 256,
              "attribution": "© OpenStreetMap contributors"
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
                            // Style loaded successfully
                        }
                    }
                }
            },
            modifier = modifier,
        )
    }
}