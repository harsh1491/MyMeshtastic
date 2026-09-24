package org.meshtastic.app.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.location.Location
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import org.meshtastic.app.battlefield.UnitType
import org.meshtastic.app.battlefield.loadBitmapWithoutBlackBackground
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

object MapLibreHelper {

    // Node & Zone Count annotations list — persists across updates
    private val nodeAnnotations = mutableListOf<Marker>()
    private val zoneLabelAnnotations = mutableListOf<Marker>()

    // ── 1. GEODETIC & DISTANCE UTILITIES ──

    fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0].toDouble()
    }

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
            val dLat = metersToDegreesLat(radiusMeters) * sin(angle)
            val dLon = metersToDegreesLon(radiusMeters, centerLat) * cos(angle)
            coords.add(listOf(centerLon + dLon, centerLat + dLat))
        }
        return coords
    }

    fun zoneToFeature(zone: MapZone): Feature {
        val coords = circleToPolygonPoints(zone.centerLat, zone.centerLon, zone.radiusMeters)
        val ringCoords = coords.map { Point.fromLngLat(it[0], it[1]) }
        val polygon = Polygon.fromLngLats(listOf(ringCoords))
        val feature = Feature.fromGeometry(polygon)
        feature.addStringProperty("id", zone.id)
        feature.addStringProperty("color", zone.color.hex)
        feature.addNumberProperty("alpha", zone.color.alpha)
        feature.addBooleanProperty("isLocal", zone.isLocal)
        return feature
    }

    // ── 2. TACTICAL ZONE RENDERING ──

    fun updateZoneLayers(map: MapLibreMap, zones: List<MapZone>) {
        val style = map.style ?: return

        val sourceId = "zones-source"
        val localLayerId = "zones-layer-local"
        val localOutlineId = "zones-outline-local"
        val remoteLayerId = "zones-layer-remote"
        val remoteOutlineId = "zones-outline-remote"

        try { style.removeLayer(localOutlineId) } catch (_: Exception) {}
        try { style.removeLayer(localLayerId) } catch (_: Exception) {}
        try { style.removeLayer(remoteOutlineId) } catch (_: Exception) {}
        try { style.removeLayer(remoteLayerId) } catch (_: Exception) {}
        try { style.removeSource(sourceId) } catch (_: Exception) {}

        if (zones.isEmpty()) return

        val features = zones.map { zone ->
            val feature = zoneToFeature(zone)
            feature.addBooleanProperty("isLocal", zone.isLocal)
            feature
        }
        val collection = FeatureCollection.fromFeatures(features)
        style.addSource(GeoJsonSource(sourceId, collection))

        // Local zones: colored fill, thin colored outline
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

        // Remote zones: same colored fill, dashed blue outline
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
                PropertyFactory.lineColor("#1565C0"),
                PropertyFactory.lineWidth(3f),
                PropertyFactory.lineDasharray(arrayOf(4f, 2f))
            )
        }
        style.addLayer(remoteOutline)
    }

    // ── 3. ZONE COUNT BADGE LABELS ──

    fun updateZoneCountLabels(
        map: MapLibreMap,
        zones: List<MapZone>,
        zoneCounts: Map<String, Int>,
        context: Context
    ) {
        zoneLabelAnnotations.forEach { map.removeMarker(it) }
        zoneLabelAnnotations.clear()

        zones.forEach { zone ->
            val count = zoneCounts[zone.id] ?: 0
            val badgeBitmap = createZoneBadgeBitmap(count, zone.color.hex)
            val icon = IconFactory.getInstance(context).fromBitmap(badgeBitmap)

            val marker = map.addMarker(
                MarkerOptions()
                    .position(LatLng(zone.centerLat, zone.centerLon))
                    .title("Zone Units: $count")
                    .icon(icon)
            )
            if (marker != null) zoneLabelAnnotations.add(marker)
        }
    }

    private fun createZoneBadgeBitmap(count: Int, colorHex: String): Bitmap {
        val width = 160
        val height = 54
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val bgPaint = Paint().apply {
            color = Color.parseColor("#E6121A16")
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        val borderPaint = Paint().apply {
            color = try { Color.parseColor(colorHex) } catch (_: Exception) { Color.GREEN }
            style = Paint.Style.STROKE
            strokeWidth = 3f
            isAntiAlias = true
        }

        val rect = RectF(4f, 4f, width - 4f, height - 4f)
        canvas.drawRoundRect(rect, 27f, 27f, bgPaint)
        canvas.drawRoundRect(rect, 27f, 27f, borderPaint)

        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 24f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }

        val text = "UNITS: $count"
        val yPos = (height / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
        canvas.drawText(text, width / 2f, yPos, textPaint)

        return bitmap
    }

    // ── 4. HIGH-CONTRAST NODE MARKERS ──

    fun updateNodeMarkers(
        map: MapLibreMap,
        nodes: List<NodeMarkerData>,
        context: Context,
        myNodeId: String = "",
        getUnitType: (String) -> UnitType = { UnitType.SOLDIER }
    ) {
        nodeAnnotations.forEach { map.removeMarker(it) }
        nodeAnnotations.clear()

        nodes.forEach { node ->
            val unitType = getUnitType(node.id)
            val isMyNode = node.id == myNodeId
            val bitmap = loadUnitBitmap(context, unitType.drawableName, isMyNode)

            val markerOptions = MarkerOptions()
                .position(LatLng(node.lat, node.lon))
                .title(node.shortName)
                .snippet("Node: ${node.id}")

            if (bitmap != null) {
                val icon = IconFactory.getInstance(context).fromBitmap(bitmap)
                markerOptions.icon(icon)
            }

            val marker = map.addMarker(markerOptions)
            if (marker != null) nodeAnnotations.add(marker)
        }

        android.util.Log.d("MarkerDebug", "Added ${nodeAnnotations.size} annotation markers")
    }

    // ── HIGH-CONTRAST TACTICAL CIRCULAR TOKEN ──
    private fun loadUnitBitmap(
        context: Context,
        drawableName: String,
        isMyNode: Boolean
    ): Bitmap? {
        return try {
            val resId = context.resources.getIdentifier(
                drawableName, "drawable", context.packageName
            )
            if (resId == 0) return null

            // 1. Decode original transparent PNG directly (no pixel/color alteration)
            val original = BitmapFactory.decodeResource(context.resources, resId) ?: return null

            // 2. Token dimensions
            val tokenSize = 130
            val composite = Bitmap.createBitmap(tokenSize, tokenSize, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(composite)
            val center = tokenSize / 2f
            val baseRadius = 56f

            // Layer A: Subtle dark outer edge (keeps white disc visible against bright terrain)
            val outerShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#44000000")
                style = Paint.Style.STROKE
                strokeWidth = 3f
            }
            canvas.drawCircle(center, center, baseRadius + 1f, outerShadowPaint)

            // Layer B: Pure Solid White Disc
            val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.FILL
            }
            canvas.drawCircle(center, center, baseRadius, discPaint)

            // Layer C: Vibrant Tactical Border Ring
            // Vibrant Green for "Me", Vibrant Blue for squad members
            val ringColor = if (isMyNode) Color.parseColor("#00C853") else Color.parseColor("#0091EA")
            val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ringColor
                style = Paint.Style.STROKE
                strokeWidth = 7f
            }
            canvas.drawCircle(center, center, baseRadius - 2f, ringPaint)

            // Layer D: Draw original transparent marker PNG cleanly inside
            // Sized with breathing room so it sits neatly inside the white circle
            val iconSize = 78
            val scaledIcon = Bitmap.createScaledBitmap(original, iconSize, iconSize, true)
            val offset = (tokenSize - iconSize) / 2f

            val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(scaledIcon, offset, offset, iconPaint)

            composite
        } catch (e: Exception) {
            android.util.Log.e("MapLibreHelper", "Failed to load: $drawableName", e)
            null
        }
    }

    /**
     * Extracts the alpha silhouette of the transparent PNG and creates:
     * - A 2px crisp white inner contour (so dark uniforms/rifles never blend into satellite terrain)
     * - A 2px outer tactical glow (Green for Me, Cyan for squad mates)
     * - Leaves the surrounding area 100% transparent (NO white disc or box)
     */
    private fun addTacticalSilhouetteHalo(src: Bitmap, isMyNode: Boolean): Bitmap {
        val haloRadius = 4
        val outWidth = src.width + haloRadius * 2
        val outHeight = src.height + haloRadius * 2
        val output = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val alphaMask = src.extractAlpha()

        // 1. Outer Team Tactical Aura (Emerald Green for "Me", Cyan Blue for squad)
        val teamColor = if (isMyNode) Color.parseColor("#00E676") else Color.parseColor("#00B0FF")
        val teamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = teamColor
        }
        val outerRadius = haloRadius
        for (dx in -outerRadius..outerRadius) {
            for (dy in -outerRadius..outerRadius) {
                if (dx * dx + dy * dy <= outerRadius * outerRadius) {
                    canvas.drawBitmap(alphaMask, (haloRadius + dx).toFloat(), (haloRadius + dy).toFloat(), teamPaint)
                }
            }
        }

        // 2. Inner Crisp White Contour (separates dark pixels from dark satellite terrain)
        val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
        }
        val innerRadius = 2
        for (dx in -innerRadius..innerRadius) {
            for (dy in -innerRadius..innerRadius) {
                if (dx * dx + dy * dy <= innerRadius * innerRadius) {
                    canvas.drawBitmap(alphaMask, (haloRadius + dx).toFloat(), (haloRadius + dy).toFloat(), whitePaint)
                }
            }
        }

        // 3. Draw the original transparent soldier PNG cleanly on top
        canvas.drawBitmap(src, haloRadius.toFloat(), haloRadius.toFloat(), null)

        return output
    }

    // ── 5. PREVIEW ZONE ENGINE ──

    fun drawPreviewZone(map: MapLibreMap, centerLat: Double, centerLon: Double, radiusMeters: Double) {
        val style = map.style ?: return
        val previewSourceId = "preview-zone-source"
        val previewLayerId = "preview-zone-layer"
        val previewOutlineId = "preview-zone-outline"

        val coords = circleToPolygonPoints(centerLat, centerLon, radiusMeters)
        val ringCoords = coords.map { Point.fromLngLat(it[0], it[1]) }
        val polygon = Polygon.fromLngLats(listOf(ringCoords))
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