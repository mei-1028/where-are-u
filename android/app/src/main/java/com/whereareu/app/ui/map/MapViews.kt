package com.whereareu.app.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.BitmapDescriptor
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.CircleOptions
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.PolylineOptions
import com.whereareu.app.BuildConfig
import com.whereareu.app.core.net.PlaceDto
import com.whereareu.app.ui.theme.BorderHairline
import com.whereareu.app.ui.theme.CoralPastel
import com.whereareu.app.ui.theme.Ink
import com.whereareu.app.ui.theme.MiroBlue
import com.whereareu.app.ui.theme.OrangePastel
import com.whereareu.app.ui.theme.PinkPastel
import com.whereareu.app.ui.theme.SlateText
import com.whereareu.app.ui.theme.SuccessGreen
import com.whereareu.app.ui.theme.TealPastel
import com.whereareu.app.ui.theme.VioletAccent
import com.whereareu.app.ui.theme.WarningAmber
import com.whereareu.app.ui.theme.White
import com.whereareu.app.ui.theme.memberColor
import kotlin.math.max
import kotlin.math.min

private const val HANGZHOU_LAT = 30.2741
private const val HANGZHOU_LNG = 120.1551

@Composable
fun FamilyMap(
    members: List<MemberUi>,
    myUserId: String?,
    selectedUserId: String?,
    recenterNonce: Int,
    locatingUserId: String?,
    onSelectMember: (String) -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    places: List<PlaceDto> = emptyList(),
    onPlaceClick: (String) -> Unit = {},
    onMapLongPress: (Double, Double) -> Unit = { _, _ -> },
) {
    if (BuildConfig.AMAP_KEY.isNotBlank()) {
        AmapFamilyMap(
            members = members,
            myUserId = myUserId,
            selectedUserId = selectedUserId,
            recenterNonce = recenterNonce,
            locatingUserId = locatingUserId,
            onSelectMember = onSelectMember,
            places = places,
            onPlaceClick = onPlaceClick,
            onMapLongPress = onMapLongPress,
            modifier = modifier,
            active = active,
        )
    } else {
        SchematicFamilyMap(
            members = members,
            myUserId = myUserId,
            selectedUserId = selectedUserId,
            locatingUserId = locatingUserId,
            onSelectMember = onSelectMember,
            modifier = modifier,
        )
    }
}

@Composable
fun TrackMap(
    points: List<Pair<Double, Double>>,
    modifier: Modifier = Modifier,
) {
    if (BuildConfig.AMAP_KEY.isNotBlank()) {
        AmapTrackMap(points = points, modifier = modifier)
    } else {
        SchematicTrackMap(points = points, modifier = modifier)
    }
}

// ---------------------------------------------------------------------------
// AMap implementation (used only when AMAP_KEY is configured)
// ---------------------------------------------------------------------------

private class MapResumeState {
    var isResumed: Boolean = false
}

@Composable
internal fun AmapMapHost(
    modifier: Modifier = Modifier,
    active: Boolean = true,
    onUpdate: (AMap) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val resumeState = remember { MapResumeState() }
    val mapView = remember {
        MapView(context).apply {
            onCreate(null)
            map.uiSettings.setZoomControlsEnabled(false)
            map.uiSettings.setMyLocationButtonEnabled(false)
            map.uiSettings.setRotateGesturesEnabled(false)
            map.uiSettings.setTiltGesturesEnabled(false)
            map.uiSettings.setScaleControlsEnabled(false)
        }
    }

    fun resumeMapIfNeeded() {
        if (!resumeState.isResumed) {
            mapView.onResume()
            resumeState.isResumed = true
        }
    }

    fun pauseMapIfNeeded() {
        if (resumeState.isResumed) {
            mapView.onPause()
            resumeState.isResumed = false
        }
    }

    // Activity lifecycle only. Do NOT call onPause/onResume on bottom-tab
    // switches: on software/headless renderers (e.g. SwiftShader emulators)
    // pausing the map while an opaque overlay covers it can leave a black GL
    // surface on return. Keeping it resumed under the overlay and only
    // disabling input is safer; Activity background still pauses the map.
    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resumeMapIfNeeded()
                Lifecycle.Event.ON_PAUSE -> pauseMapIfNeeded()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            resumeMapIfNeeded()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            pauseMapIfNeeded()
            mapView.onDestroy()
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = { view ->
            // Keep the hidden map alive but ignore touches while covered.
            view.isEnabled = active
            view.map.uiSettings.setAllGesturesEnabled(active)
            onUpdate(view.map)
        },
    )
}

@Composable
private fun AmapFamilyMap(
    members: List<MemberUi>,
    myUserId: String?,
    selectedUserId: String?,
    recenterNonce: Int,
    locatingUserId: String?,
    onSelectMember: (String) -> Unit,
    places: List<PlaceDto>,
    onPlaceClick: (String) -> Unit,
    onMapLongPress: (Double, Double) -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = true,
) {
    val context = LocalContext.current
    val iconCache = remember { mutableMapOf<String, BitmapDescriptor>() }
    var lastCameraKey by remember { mutableStateOf<String?>(null) }

    AmapMapHost(modifier = modifier, active = active) { map ->
        map.clear()

        members.forEachIndexed { index, member ->
            val lat = member.lat ?: return@forEachIndexed
            val lng = member.lng ?: return@forEachIndexed
            val position = LatLng(lat, lng)
            val color = memberColor(index)

            member.accuracy?.takeIf { it > 0f }?.let { accuracy ->
                map.addCircle(
                    CircleOptions()
                        .center(position)
                        .radius(accuracy.toDouble().coerceIn(5.0, 3000.0))
                        .strokeWidth(1.5f)
                        .strokeColor(color.copy(alpha = 0.35f).toArgb())
                        .fillColor(color.copy(alpha = 0.10f).toArgb()),
                )
            }

            if (member.userId == myUserId) {
                val icon = iconCache.getOrPut("me-${member.online}") {
                    buildCircleMarker(
                        context = context,
                        colorArgb = BLUE_DOT.toArgb(),
                        label = "",
                        online = true,
                        selected = false,
                        locating = false,
                    )
                }
                map.addMarker(
                    MarkerOptions()
                        .position(position)
                        .icon(icon)
                        .anchor(0.5f, 0.5f)
                        .title("我的位置"),
                )
            } else {
                val selected = member.userId == selectedUserId
                val locating = member.userId == locatingUserId
                val cacheKey = "${color.toArgb()}-$selected-${member.online}-$locating-${member.nickname.take(1)}"
                val icon = iconCache.getOrPut(cacheKey) {
                    buildCircleMarker(
                        context = context,
                        colorArgb = color.toArgb(),
                        label = member.nickname,
                        online = member.online,
                        selected = selected,
                        locating = locating,
                    )
                }
                val marker = map.addMarker(
                    MarkerOptions()
                        .position(position)
                        .icon(icon)
                        .anchor(0.5f, 0.5f)
                        .title(member.nickname)
                        .snippet(member.userId),
                )
                marker?.setObject(member.userId)
            }
        }

        places.forEach { place ->
            val color = placeColor(place.type)
            map.addCircle(
                CircleOptions()
                    .center(LatLng(place.lat, place.lng))
                    .radius(place.radiusMeters.toDouble())
                    .strokeWidth(1.5f)
                    .strokeColor(color.copy(alpha = 0.5f).toArgb())
                    .fillColor(color.copy(alpha = 0.06f).toArgb()),
            )
            val icon = iconCache.getOrPut("place-${place.type}-${place.name}") {
                buildCircleMarker(
                    context = context,
                    colorArgb = color.toArgb(),
                    label = place.name,
                    online = true,
                    selected = false,
                    locating = false,
                )
            }
            val marker = map.addMarker(
                MarkerOptions()
                    .position(LatLng(place.lat, place.lng))
                    .icon(icon)
                    .anchor(0.5f, 0.5f)
                    .title(place.name),
            )
            marker?.setObject("place:${place.id}")
        }

        map.setOnMarkerClickListener { marker ->
            val payload = marker?.getObject() as? String
            if (active && payload != null) {
                if (payload.startsWith("place:")) {
                    onPlaceClick(payload.removePrefix("place:"))
                } else {
                    onSelectMember(payload)
                }
            }
            true
        }

        map.setOnMapLongClickListener { latLng ->
            if (active) onMapLongPress(latLng.latitude, latLng.longitude)
        }

        val hasData = members.any { it.hasLocation } || places.isNotEmpty()
        val cameraKey = "${selectedUserId ?: "none"}|$recenterNonce"
        if (hasData && cameraKey != lastCameraKey) {
            lastCameraKey = cameraKey
            applyFamilyCamera(
                map = map,
                members = members,
                myUserId = myUserId,
                selectedUserId = selectedUserId,
                focusMe = selectedUserId == null && recenterNonce > 0,
                places = places,
            )
        }
    }
}

private fun placeColor(type: String): Color = when (type) {
    "home" -> SuccessGreen
    "school" -> MiroBlue
    "company" -> WarningAmber
    else -> VioletAccent
}

private fun applyFamilyCamera(
    map: AMap,
    members: List<MemberUi>,
    myUserId: String?,
    selectedUserId: String?,
    focusMe: Boolean,
    places: List<PlaceDto>,
) {
    val selected = members.firstOrNull { it.userId == selectedUserId && it.hasLocation }
    val me = members.firstOrNull { it.userId == myUserId && it.hasLocation }

    if (selected != null) {
        map.moveCamera(
            CameraUpdateFactory.newLatLngZoom(
                LatLng(selected.lat ?: 0.0, selected.lng ?: 0.0),
                16f,
            ),
        )
        return
    }
    if (focusMe && me != null) {
        map.moveCamera(
            CameraUpdateFactory.newLatLngZoom(
                LatLng(me.lat ?: 0.0, me.lng ?: 0.0),
                16f,
            ),
        )
        return
    }

    val located = members.filter { it.hasLocation }
    val lats = located.mapNotNull { it.lat }
    val lngs = located.mapNotNull { it.lng }
    if (lats.isEmpty() || lngs.isEmpty()) {
        if (places.isNotEmpty()) {
            map.moveCamera(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(
                        places.map { it.lat }.average(),
                        places.map { it.lng }.average(),
                    ),
                    14f,
                ),
            )
            return
        }
        map.moveCamera(
            CameraUpdateFactory.newLatLngZoom(LatLng(HANGZHOU_LAT, HANGZHOU_LNG), 12f),
        )
        return
    }
    val centerLat = lats.average()
    val centerLng = lngs.average()
    val latSpan = (lats.maxOrNull() ?: centerLat) - (lats.minOrNull() ?: centerLat)
    val lngSpan = (lngs.maxOrNull() ?: centerLng) - (lngs.minOrNull() ?: centerLng)
    val span = max(latSpan, lngSpan)
    val zoom = when {
        span > 1.0 -> 10f
        span > 0.2 -> 11.5f
        span > 0.05 -> 13f
        span > 0.01 -> 14.5f
        else -> 16f
    }
    map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(centerLat, centerLng), zoom))
}

@Composable
private fun AmapTrackMap(
    points: List<Pair<Double, Double>>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    AmapMapHost(modifier = modifier) { map ->
        map.clear()
        if (points.isNotEmpty()) {
            val latLngs = points.map { LatLng(it.first, it.second) }
            map.addPolyline(
                PolylineOptions()
                    .addAll(latLngs)
                    .width(11f)
                    .color(MiroBlue.toArgb()),
            )
            val startIcon = buildCircleMarker(context, SuccessGreen.toArgb(), "起", true, false, false)
            val endIcon = buildCircleMarker(context, Color(0xFFF04438).toArgb(), "终", true, false, false)
            latLngs.firstOrNull()?.let {
                map.addMarker(MarkerOptions().position(it).icon(startIcon).anchor(0.5f, 0.5f).title("起点"))
            }
            latLngs.lastOrNull()?.let {
                map.addMarker(MarkerOptions().position(it).icon(endIcon).anchor(0.5f, 0.5f).title("终点"))
            }

            val lats = points.map { it.first }
            val lngs = points.map { it.second }
            val centerLat = lats.average()
            val centerLng = lngs.average()
            val latSpan = (lats.maxOrNull() ?: centerLat) - (lats.minOrNull() ?: centerLat)
            val lngSpan = (lngs.maxOrNull() ?: centerLng) - (lngs.minOrNull() ?: centerLng)
            val span = max(latSpan, lngSpan)
            val zoom = when {
                span > 1.0 -> 10f
                span > 0.2 -> 11.5f
                span > 0.05 -> 13f
                span > 0.01 -> 14.5f
                else -> 16f
            }
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(centerLat, centerLng), zoom))
        }
    }
}

// ---------------------------------------------------------------------------
// Compose Canvas fallback map (no key required)
// ---------------------------------------------------------------------------

private data class MarkerPoint(
    val id: String,
    val lat: Double,
    val lng: Double,
    val name: String,
    val color: Color,
    val online: Boolean,
    val isMe: Boolean,
    val selected: Boolean,
    val locating: Boolean,
    val accuracy: Float?,
)

@Composable
private fun SchematicFamilyMap(
    members: List<MemberUi>,
    myUserId: String?,
    selectedUserId: String?,
    locatingUserId: String?,
    onSelectMember: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val paddingPx = with(density) { 56.dp.toPx() }
    val textPaint = remember(density) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = with(density) { 12.sp.toPx() }
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
    }

    val markerPoints = remember(members, myUserId, selectedUserId, locatingUserId) {
        members.mapIndexedNotNull { index, member ->
            val lat = member.lat ?: return@mapIndexedNotNull null
            val lng = member.lng ?: return@mapIndexedNotNull null
            MarkerPoint(
                id = member.userId,
                lat = lat,
                lng = lng,
                name = member.nickname,
                color = memberColor(index),
                online = member.online,
                isMe = member.userId == myUserId,
                selected = member.userId == selectedUserId,
                locating = member.userId == locatingUserId,
                accuracy = member.accuracy,
            )
        }
    }
    val projection = remember(markerPoints, canvasSize, paddingPx) {
        if (canvasSize.width == 0 || canvasSize.height == 0) {
            null
        } else {
            SchematicProjection(
                points = markerPoints.map { it.lat to it.lng },
                width = canvasSize.width.toFloat(),
                height = canvasSize.height.toFloat(),
                padding = paddingPx,
            )
        }
    }

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize = it }
                .pointerInput(projection, markerPoints) {
                    detectTapGestures { tap ->
                        projection?.let { activeProjection ->
                            val threshold = 36.dp.toPx()
                            val hit = markerPoints
                                .asSequence()
                                .filter { !it.isMe }
                                .map { point ->
                                    point to (activeProjection.project(point.lat, point.lng) - tap).getDistance()
                                }
                                .filter { (_, distance) -> distance <= threshold }
                                .minByOrNull { (_, distance) -> distance }
                                ?.first
                            hit?.let { onSelectMember(it.id) }
                        }
                    }
                },
        ) {
            drawSchematicBackdrop()
            val activeProjection = projection
            if (activeProjection != null && markerPoints.isNotEmpty()) {
                markerPoints.filterNot { it.isMe }.forEach { point ->
                    val position = activeProjection.project(point.lat, point.lng)
                    val accuracyRadius = if ((point.accuracy ?: 0f) > 100f) 40.dp.toPx() else 27.dp.toPx()
                    drawCircle(
                        color = point.color.copy(alpha = 0.10f),
                        radius = accuracyRadius,
                        center = position,
                    )
                }

                markerPoints.filterNot { it.isMe }.forEach { point ->
                    val position = activeProjection.project(point.lat, point.lng)
                    if (point.locating) {
                        drawCircle(
                            color = WarningAmber.copy(alpha = 0.28f),
                            radius = 27.dp.toPx(),
                            center = position,
                        )
                    }
                    val outerRadius = if (point.selected) 22.dp.toPx() else 18.dp.toPx()
                    val innerRadius = if (point.selected) 17.dp.toPx() else 14.dp.toPx()
                    val alpha = if (point.online) 1f else 0.5f
                    drawCircle(White.copy(alpha = alpha), outerRadius, position)
                    drawCircle(point.color.copy(alpha = alpha), innerRadius, position)
                    if (point.selected) {
                        drawCircle(
                            color = MiroBlue,
                            radius = outerRadius + 2.dp.toPx(),
                            center = position,
                            style = Stroke(width = 2.dp.toPx()),
                        )
                    }
                    drawContext.canvas.nativeCanvas.drawText(
                        point.name.trim().take(1).ifBlank { "?" },
                        position.x,
                        position.y + with(density) { 4.5.dp.toPx() },
                        textPaint,
                    )
                }

                val me = markerPoints.firstOrNull { it.isMe }
                if (me != null) {
                    val position = activeProjection.project(me.lat, me.lng)
                    drawCircle(BLUE_DOT.copy(alpha = 0.14f), 30.dp.toPx(), position)
                    drawCircle(White, 10.dp.toPx(), position)
                    drawCircle(BLUE_DOT, 7.dp.toPx(), position)
                }
            }
        }
        if (markerPoints.isEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(White.copy(alpha = 0.94f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    text = "等待家人上报位置…",
                    color = SlateText,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SchematicTrackMap(
    points: List<Pair<Double, Double>>,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val paddingPx = with(density) { 48.dp.toPx() }
    val labelPaint = remember(density) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = with(density) { 11.sp.toPx() }
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
    }
    val projection = remember(points, canvasSize, paddingPx) {
        if (canvasSize.width == 0 || canvasSize.height == 0 || points.isEmpty()) {
            null
        } else {
            SchematicProjection(
                points = points,
                width = canvasSize.width.toFloat(),
                height = canvasSize.height.toFloat(),
                padding = paddingPx,
            )
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { canvasSize = it },
    ) {
        drawSchematicBackdrop()
        val activeProjection = projection
        if (activeProjection != null && points.isNotEmpty()) {
            val projected = points.map { activeProjection.project(it.first, it.second) }
            if (projected.size >= 2) {
                val path = Path().apply {
                    moveTo(projected.first().x, projected.first().y)
                    projected.drop(1).forEach { lineTo(it.x, it.y) }
                }
                drawPath(
                    path = path,
                    color = White.copy(alpha = 0.9f),
                    style = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
                drawPath(
                    path = path,
                    color = MiroBlue,
                    style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }

            val start = projected.first()
            drawCircle(White, 15.dp.toPx(), start)
            drawCircle(SuccessGreen, 12.dp.toPx(), start)
            drawContext.canvas.nativeCanvas.drawText(
                "起",
                start.x,
                start.y + with(density) { 4.dp.toPx() },
                labelPaint,
            )

            val end = projected.last()
            drawCircle(White, 15.dp.toPx(), end)
            drawCircle(Color(0xFFF04438), 12.dp.toPx(), end)
            drawContext.canvas.nativeCanvas.drawText(
                "终",
                end.x,
                end.y + with(density) { 4.dp.toPx() },
                labelPaint,
            )
        }
    }
}

private fun DrawScope.drawSchematicBackdrop() {
    val width = size.width
    val height = size.height
    drawRect(Color(0xFFF3F4F7))

    // Pastel city blocks
    drawRoundRect(
        color = TealPastel.copy(alpha = 0.45f),
        topLeft = Offset(width * 0.06f, height * 0.18f),
        size = Size(width * 0.30f, height * 0.16f),
        cornerRadius = CornerRadius(18.dp.toPx()),
    )
    drawRoundRect(
        color = OrangePastel.copy(alpha = 0.45f),
        topLeft = Offset(width * 0.62f, height * 0.24f),
        size = Size(width * 0.28f, height * 0.14f),
        cornerRadius = CornerRadius(18.dp.toPx()),
    )
    drawRoundRect(
        color = PinkPastel.copy(alpha = 0.5f),
        topLeft = Offset(width * 0.16f, height * 0.62f),
        size = Size(width * 0.26f, height * 0.16f),
        cornerRadius = CornerRadius(18.dp.toPx()),
    )
    drawRoundRect(
        color = CoralPastel.copy(alpha = 0.35f),
        topLeft = Offset(width * 0.65f, height * 0.68f),
        size = Size(width * 0.24f, height * 0.13f),
        cornerRadius = CornerRadius(18.dp.toPx()),
    )

    // River
    val river = Path().apply {
        moveTo(-width * 0.1f, height * 0.78f)
        cubicTo(
            width * 0.22f, height * 0.58f,
            width * 0.55f, height * 0.95f,
            width * 1.06f, height * 0.66f,
        )
    }
    drawPath(
        path = river,
        color = Color(0xFFDDEEFF),
        style = Stroke(width = 26.dp.toPx(), cap = StrokeCap.Round),
    )

    // Streets
    for (index in 1..5) {
        val x = width * index / 6f
        drawLine(
            color = White,
            start = Offset(x, 0f),
            end = Offset(x, height),
            strokeWidth = 9.dp.toPx(),
        )
        drawLine(
            color = BorderHairline.copy(alpha = 0.5f),
            start = Offset(x, 0f),
            end = Offset(x, height),
            strokeWidth = 1.dp.toPx(),
        )
    }
    for (index in 1..6) {
        val y = height * index / 7f
        drawLine(
            color = White,
            start = Offset(0f, y),
            end = Offset(width, y),
            strokeWidth = 9.dp.toPx(),
        )
        drawLine(
            color = BorderHairline.copy(alpha = 0.5f),
            start = Offset(0f, y),
            end = Offset(width, y),
            strokeWidth = 1.dp.toPx(),
        )
    }
}

private class SchematicProjection(
    private val points: List<Pair<Double, Double>>,
    private val width: Float,
    private val height: Float,
    padding: Float,
) {
    private val centerLat: Double
    private val centerLng: Double
    private val scale: Float

    init {
        val usableWidth = (width - padding * 2f).coerceAtLeast(1f)
        val usableHeight = (height - padding * 2f).coerceAtLeast(1f)
        if (points.isEmpty()) {
            centerLat = HANGZHOU_LAT
            centerLng = HANGZHOU_LNG
            scale = min(usableWidth, usableHeight) / 0.02f
        } else {
            val lats = points.map { it.first }
            val lngs = points.map { it.second }
            centerLat = lats.average()
            centerLng = lngs.average()
            val latSpan = ((lats.maxOrNull() ?: centerLat) - (lats.minOrNull() ?: centerLat)).coerceAtLeast(0.0025)
            val lngSpan = ((lngs.maxOrNull() ?: centerLng) - (lngs.minOrNull() ?: centerLng)).coerceAtLeast(0.0025)
            scale = min(usableWidth / lngSpan.toFloat(), usableHeight / latSpan.toFloat()) * 0.82f
        }
    }

    fun project(lat: Double, lng: Double): Offset = Offset(
        x = width / 2f + ((lng - centerLng) * scale).toFloat(),
        y = height / 2f - ((lat - centerLat) * scale).toFloat(),
    )
}

// ---------------------------------------------------------------------------
// Shared marker bitmaps
// ---------------------------------------------------------------------------

private val BLUE_DOT = Color(0xFF2E90FA)

private fun buildCircleMarker(
    context: Context,
    colorArgb: Int,
    label: String,
    online: Boolean,
    selected: Boolean,
    locating: Boolean,
): BitmapDescriptor {
    val density = context.resources.displayMetrics.density
    val radiusDp = if (selected) 22f else 18f
    val bitmapSize = ((radiusDp + 12f) * 2f * density).toInt().coerceAtLeast(16)
    val bitmap = Bitmap.createBitmap(bitmapSize, bitmapSize, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    val center = bitmapSize / 2f
    val radiusPx = radiusDp * density
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    if (locating) {
        paint.color = WarningAmber.toArgb()
        paint.alpha = 80
        canvas.drawCircle(center, center, radiusPx + 7f * density, paint)
    }

    paint.alpha = if (online) 255 else 200
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(center, center, radiusPx, paint)

    paint.alpha = if (online) 255 else 150
    paint.color = colorArgb
    canvas.drawCircle(center, center, radiusPx - 3f * density, paint)

    if (selected) {
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * density
            color = MiroBlue.toArgb()
        }
        canvas.drawCircle(center, center, radiusPx + 1.5f * density, ring)
    }

    if (label.isNotBlank()) {
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = android.graphics.Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = 12f * density
            typeface = Typeface.DEFAULT_BOLD
            alpha = if (online) 255 else 200
        }
        val baseline = center - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label.trim().take(1), center, baseline, textPaint)
    }
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}
