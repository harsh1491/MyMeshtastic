package org.meshtastic.app.map

import android.view.MotionEvent
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import kotlin.math.sqrt

class ZoneTouchHandler(
    private val mapView: MapView,
    private val map: MapLibreMap,
    private val onZoneReady: (centerLat: Double, centerLon: Double, radiusMeters: Double) -> Unit,
    private val onDrawing: (centerLat: Double, centerLon: Double, radiusMeters: Double) -> Unit,
    private val onCancelled: () -> Unit
) {
    private var startX = 0f
    private var startY = 0f
    private var startLatLng: LatLng? = null
    private var isDrawing = false
    private val MIN_RADIUS = 10.0 // minimum 100 meters

    fun onTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                startLatLng = map.projection.fromScreenLocation(
                    android.graphics.PointF(startX, startY)
                )
                isDrawing = true
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isDrawing) return false
                val center = startLatLng ?: return false
                val currentLatLng = map.projection.fromScreenLocation(
                    android.graphics.PointF(event.x, event.y)
                )
                val radius = calculateRadius(center, currentLatLng)
                if (radius >= MIN_RADIUS) {
                    onDrawing(center.latitude, center.longitude, radius)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!isDrawing) return false
                isDrawing = false
                val center = startLatLng ?: return false
                val currentLatLng = map.projection.fromScreenLocation(
                    android.graphics.PointF(event.x, event.y)
                )
                val radius = calculateRadius(center, currentLatLng)
                if (radius >= MIN_RADIUS) {
                    onZoneReady(center.latitude, center.longitude, radius)
                } else {
                    onCancelled()
                }
                startLatLng = null
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                isDrawing = false
                startLatLng = null
                onCancelled()
                return true
            }
        }
        return false
    }

    private fun calculateRadius(center: LatLng, edge: LatLng): Double {
        // Haversine distance in meters
        val r = 6371000.0
        val phi1 = Math.toRadians(center.latitude)
        val phi2 = Math.toRadians(edge.latitude)
        val dPhi = Math.toRadians(edge.latitude - center.latitude)
        val dLambda = Math.toRadians(edge.longitude - center.longitude)
        val a = Math.sin(dPhi / 2) * Math.sin(dPhi / 2) +
                Math.cos(phi1) * Math.cos(phi2) *
                Math.sin(dLambda / 2) * Math.sin(dLambda / 2)
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }
}