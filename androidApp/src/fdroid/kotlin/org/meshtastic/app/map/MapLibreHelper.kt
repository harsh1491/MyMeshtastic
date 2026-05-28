package org.meshtastic.app.map

import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import kotlin.math.PI
import kotlin.math.cos

import org.meshtastic.app.battlefield.UnitType
import org.meshtastic.app.battlefield.loadBitmapWithoutBlackBackground

object MapLibreHelper {

    // Node annotations list — persists across calls
    private val nodeAnnotations = mutableListOf<org.maplibre.android.annotations.Marker>()

    fun metersToDegreesLon(meters: Double, lat: Double): Double =
        meters / (111320.0 * cos(lat * PI / 180.0))

    fun metersToDegreesLat(meters: Double): Double =
        meters / 110574.0

    fun circleToPolygonPoints(
        centerLat: Double,
        centerLon: Double,
        radiusMeters: Double,
        points: Int = 64
    ): List<List<Double>> {
        val coords = mutableListOf<List<Double>>()
        for (i in 0..points) {
            val angle = 2 * PI * i / points
            val dLat = metersToDegreesLat(radiusMeters) * Math.sin(angle)
            val dLon = metersToDegreesLon(radiusMeters, centerLat) * Math.cos(angle)
            coords.add(listOf(centerLon + dLon, centerLat + dLat))
        }
        return coords
    }

    fun zoneToFeature(zone: MapZone): Feature {
        val coords = circleToPolygonPoints(zone.centerLat, zone.centerLon, zone.radiusMeters)
        val ringCoords = coords.map { Point.fromLngLat(it[0], it[1]) }
        val polygon = org.maplibre.geojson.Polygon.fromLngLats(listOf(ringCoords))
        val feature = Feature.fromGeometry(polygon)
        feature.addStringProperty("id", zone.id)
        feature.addStringProperty("color", zone.color.hex)
        feature.addNumberProperty("alpha", zone.color.alpha)
        feature.addBooleanProperty("isLocal", zone.isLocal)
        return feature
    }

    fun updateZoneLayers(map: MapLibreMap, zones: List<MapZone>) {
        val style = map.style ?: return

        val sourceId = "zones-source"
        val localLayerId = "zones-layer-local"
        val localOutlineId = "zones-outline-local"
        val remoteLayerId = "zones-layer-remote"
        val remoteOutlineId = "zones-outline-remote"

        // Remove all existing zone layers
        try { style.removeLayer(localOutlineId) } catch (_: Exception) {}
        try { style.removeLayer(localLayerId) } catch (_: Exception) {}
        try { style.removeLayer(remoteOutlineId) } catch (_: Exception) {}
        try { style.removeLayer(remoteLayerId) } catch (_: Exception) {}
        try { style.removeSource(sourceId) } catch (_: Exception) {}

        if (zones.isEmpty()) return

        // Add isLocal as a property on each feature
        val features = zones.map { zone ->
            val feature = zoneToFeature(zone)
            feature.addBooleanProperty("isLocal", zone.isLocal)
            feature
        }
        val collection = FeatureCollection.fromFeatures(features)
        style.addSource(GeoJsonSource(sourceId, collection))

        // ── Local zones — colored fill, thin colored outline ──
        val localFill = FillLayer(localLayerId, sourceId).apply {
            setFilter(
                org.maplibre.android.style.expressions.Expression.eq(
                    org.maplibre.android.style.expressions.Expression.get("isLocal"),
                    org.maplibre.android.style.expressions.Expression.literal(true)
                )
            )
            setProperties(
                PropertyFactory.fillColor(
                    org.maplibre.android.style.expressions.Expression.get("color")
                ),
                PropertyFactory.fillOpacity(0.35f)
            )
        }
        style.addLayer(localFill)

        val localOutline = LineLayer(localOutlineId, sourceId).apply {
            setFilter(
                org.maplibre.android.style.expressions.Expression.eq(
                    org.maplibre.android.style.expressions.Expression.get("isLocal"),
                    org.maplibre.android.style.expressions.Expression.literal(true)
                )
            )
            setProperties(
                PropertyFactory.lineColor(
                    org.maplibre.android.style.expressions.Expression.get("color")
                ),
                PropertyFactory.lineWidth(2f)
            )
        }
        style.addLayer(localOutline)

        // ── Remote zones — same colored fill, BLUE outline ──
        val remoteFill = FillLayer(remoteLayerId, sourceId).apply {
            setFilter(
                org.maplibre.android.style.expressions.Expression.eq(
                    org.maplibre.android.style.expressions.Expression.get("isLocal"),
                    org.maplibre.android.style.expressions.Expression.literal(false)
                )
            )
            setProperties(
                PropertyFactory.fillColor(
                    org.maplibre.android.style.expressions.Expression.get("color")
                ),
                PropertyFactory.fillOpacity(0.35f)
            )
        }
        style.addLayer(remoteFill)

        val remoteOutline = LineLayer(remoteOutlineId, sourceId).apply {
            setFilter(
                org.maplibre.android.style.expressions.Expression.eq(
                    org.maplibre.android.style.expressions.Expression.get("isLocal"),
                    org.maplibre.android.style.expressions.Expression.literal(false)
                )
            )
            setProperties(
                PropertyFactory.lineColor("#1565C0"),  // strong blue
                PropertyFactory.lineWidth(3f),          // slightly thicker
                PropertyFactory.lineDasharray(arrayOf(4f, 2f))  // dashed blue border
            )
        }
        style.addLayer(remoteOutline)
    }

    // Uses MapLibre Annotations API — always renders on top
    fun updateNodeMarkers(
        map: MapLibreMap,
        nodes: List<NodeMarkerData>,
        context: android.content.Context,
        myNodeId: String = "",
        getUnitType: (String) -> UnitType = { UnitType.SOLDIER }
    ) {
        nodeAnnotations.forEach { map.removeMarker(it) }
        nodeAnnotations.clear()

        nodes.forEach { node ->
            val unitType = getUnitType(node.id)
            val isMyNode = node.id == myNodeId
            val bitmap = loadUnitBitmap(context, unitType.drawableName, isMyNode)

            val markerOptions = org.maplibre.android.annotations.MarkerOptions()
                .position(org.maplibre.android.geometry.LatLng(node.lat, node.lon))
                .title(node.shortName)
                .snippet("Node: ${node.id}")

            if (bitmap != null) {
                val icon = org.maplibre.android.annotations.IconFactory
                    .getInstance(context)
                    .fromBitmap(bitmap)
                markerOptions.icon(icon)
            }

            val marker = map.addMarker(markerOptions)
            if (marker != null) nodeAnnotations.add(marker)
        }

        android.util.Log.d("MarkerDebug", "Added ${nodeAnnotations.size} annotation markers")
    }

    private fun loadUnitBitmap(
        context: android.content.Context,
        drawableName: String,
        isMyNode: Boolean
    ): android.graphics.Bitmap? {
        return try {
            val resId = context.resources.getIdentifier(
                drawableName, "drawable", context.packageName
            )
            if (resId == 0) return null

            val original = android.graphics.BitmapFactory.decodeResource(
                context.resources, resId
            ) ?: return null

            // Just resize — no background removal, images already have transparency
            val scaled = android.graphics.Bitmap.createScaledBitmap(original, 120, 120, true)

            if (isMyNode) {
                // Add military green circle behind own marker
                val size = 140
                val bordered = android.graphics.Bitmap.createBitmap(
                    size, size, android.graphics.Bitmap.Config.ARGB_8888
                )
                val canvas = android.graphics.Canvas(bordered)
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.parseColor("#4CAF50")
                    style = android.graphics.Paint.Style.FILL
                    isAntiAlias = true
                }
                canvas.drawCircle(70f, 70f, 70f, paint)
                canvas.drawBitmap(scaled, 10f, 10f, null)
                bordered
            } else {
                scaled
            }
        } catch (e: Exception) {
            android.util.Log.e("MapLibreHelper", "Failed to load: $drawableName", e)
            null
        }
    }

    fun drawPreviewZone(map: MapLibreMap, centerLat: Double, centerLon: Double, radiusMeters: Double) {
        val style = map.style ?: return
        val previewSourceId = "preview-zone-source"
        val previewLayerId = "preview-zone-layer"
        val previewOutlineId = "preview-zone-outline"

        val coords = circleToPolygonPoints(centerLat, centerLon, radiusMeters)
        val ringCoords = coords.map { Point.fromLngLat(it[0], it[1]) }
        val polygon = org.maplibre.geojson.Polygon.fromLngLats(listOf(ringCoords))
        val feature = Feature.fromGeometry(polygon)
        val collection = FeatureCollection.fromFeatures(listOf(feature))

        val existingSource = style.getSource(previewSourceId) as? GeoJsonSource
        if (existingSource != null) {
            existingSource.setGeoJson(collection)
        } else {
            style.addSource(GeoJsonSource(previewSourceId, collection))

            val fillLayer = FillLayer(previewLayerId, previewSourceId).apply {
                setProperties(
                    PropertyFactory.fillColor("#888888"),
                    PropertyFactory.fillOpacity(0.3f)
                )
            }
            style.addLayer(fillLayer)

            val lineLayer = LineLayer(previewOutlineId, previewSourceId).apply {
                setProperties(
                    PropertyFactory.lineColor("#333333"),
                    PropertyFactory.lineWidth(2f),
                    PropertyFactory.lineDasharray(arrayOf(2f, 2f))
                )
            }
            style.addLayer(lineLayer)
        }
    }

    fun clearPreviewZone(map: MapLibreMap) {
        val style = map.style ?: return
        try { style.removeLayer("preview-zone-outline") } catch (_: Exception) {}
        try { style.removeLayer("preview-zone-layer") } catch (_: Exception) {}
        try { style.removeSource("preview-zone-source") } catch (_: Exception) {}
    }
}

data class NodeMarkerData(
    val id: String,
    val lat: Double,
    val lon: Double,
    val shortName: String
)