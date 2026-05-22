package com.iboism.gpxrecorder.rescue

import com.iboism.gpxrecorder.model.RescueAnchorSource
import com.iboism.gpxrecorder.model.RescuePlanningStatus
import com.iboism.gpxrecorder.model.RescueTravelMode
import com.iboism.gpxrecorder.util.DateTimeFormatHelper
import java.util.Date
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class RescueAnchorSnapshot(
    val id: Long,
    val lat: Double,
    val lon: Double,
    val time: String?,
    val source: RescueAnchorSource,
    val order: Int
)

data class RescueSegmentSnapshot(
    val id: Long,
    val fromAnchorId: Long,
    val toAnchorId: Long,
    val order: Int,
    val travelMode: RescueTravelMode,
    val planningStatus: RescuePlanningStatus,
    val selectedRoutePolyline: String,
    val waypoints: List<RescueRouteWaypoint> = emptyList()
)

data class RescueGeneratedPoint(
    val lat: Double,
    val lon: Double,
    val time: String,
    val sourceAnchorId: Long?,
    val order: Int
)

data class RescueGenerationResult(
    val points: List<RescueGeneratedPoint>,
    val warnings: List<String>
)

object RescueTrackGenerator {
    private const val MAX_REASONABLE_SPEED_KMH = 160.0

    fun generate(
        anchors: List<RescueAnchorSnapshot>,
        segments: List<RescueSegmentSnapshot>,
        intervalSeconds: Int
    ): RescueGenerationResult {
        val orderedAnchors = anchors.sortedBy { it.order }
        val anchorTimeMap = interpolateAnchorTimes(orderedAnchors)
        val generated = mutableListOf<RescueGeneratedPoint>()
        val warnings = mutableListOf<String>()
        var order = 0

        segments.sortedBy { it.order }.forEach { segment ->
            val from = orderedAnchors.firstOrNull { it.id == segment.fromAnchorId } ?: return@forEach
            val to = orderedAnchors.firstOrNull { it.id == segment.toAnchorId } ?: return@forEach
            val fromTime = anchorTimeMap[from.id] ?: return@forEach
            val toTime = anchorTimeMap[to.id] ?: return@forEach
            val durationMillis = toTime.time - fromTime.time
            if (durationMillis <= 0L) return@forEach

            val route = routePointsFor(segment, from, to)
            val distanceKm = route.zipWithNext { a, b -> haversineKm(a, b) }.sum()
            val speedKmh = distanceKm / (durationMillis / 3_600_000.0)
            if (speedKmh > MAX_REASONABLE_SPEED_KMH) {
                warnings.add("第 ${segment.order + 1} 段速度约 ${speedKmh.toInt()} km/h，可能异常。")
            }

            val steps = max(1, (durationMillis / (intervalSeconds * 1000L)).toInt())
            for (index in 0..steps) {
                if (generated.isNotEmpty() && index == 0) continue
                val fraction = index.toDouble() / steps.toDouble()
                val point = pointAtDistanceFraction(route, fraction)
                val time = Date(fromTime.time + (durationMillis * fraction).toLong())
                generated.add(
                    RescueGeneratedPoint(
                        lat = point.lat,
                        lon = point.lon,
                        time = DateTimeFormatHelper.formatDate(time),
                        sourceAnchorId = null,
                        order = order++
                    )
                )
            }

            listOf(from, to)
                .filter { it.time != null || it.source == RescueAnchorSource.MEDIA }
                .forEach { anchor ->
                    val anchorTime = anchorTimeMap[anchor.id] ?: return@forEach
                    val existingIndex = generated.indexOfFirst { it.time == DateTimeFormatHelper.formatDate(anchorTime) }
                    val anchorPoint = RescueGeneratedPoint(
                        lat = anchor.lat,
                        lon = anchor.lon,
                        time = DateTimeFormatHelper.formatDate(anchorTime),
                        sourceAnchorId = anchor.id,
                        order = 0
                    )
                    if (existingIndex >= 0) {
                        generated[existingIndex] = anchorPoint
                    } else {
                        generated.add(anchorPoint)
                    }
                }
        }

        val sorted = generated
            .distinctBy { it.time to it.sourceAnchorId }
            .sortedBy { DateTimeFormatHelper.parseDate(it.time)?.time ?: 0L }
            .mapIndexed { index, point -> point.copy(order = index) }

        return RescueGenerationResult(sorted, warnings)
    }

    fun validateAnchors(anchors: List<RescueAnchorSnapshot>): String? {
        val ordered = anchors.sortedBy { it.order }
        if (ordered.size < 2) return "至少需要两个锚点。"
        if (ordered.first().time == null) return "起点必须设置时间。"
        if (ordered.last().time == null) return "终点必须设置时间。"
        val timedAnchors = ordered.mapNotNull { anchor ->
            val time = anchor.time?.let { DateTimeFormatHelper.parseDate(it) } ?: return@mapNotNull null
            anchor to time
        }
        timedAnchors.zipWithNext { previous, next ->
            if (!next.second.after(previous.second)) {
                return "有时间的锚点必须按路线顺序递增。"
            }
        }
        return null
    }

    private fun interpolateAnchorTimes(anchors: List<RescueAnchorSnapshot>): Map<Long, Date> {
        val result = mutableMapOf<Long, Date>()
        val timedIndexes = anchors.mapIndexedNotNull { index, anchor ->
            val date = anchor.time?.let { DateTimeFormatHelper.parseDate(it) } ?: return@mapIndexedNotNull null
            index to date
        }
        timedIndexes.forEach { (index, date) -> result[anchors[index].id] = date }

        timedIndexes.zipWithNext { previous, next ->
            val startIndex = previous.first
            val endIndex = next.first
            val span = endIndex - startIndex
            if (span <= 1) return@zipWithNext
            val startTime = previous.second.time
            val endTime = next.second.time
            for (index in (startIndex + 1) until endIndex) {
                val fraction = (index - startIndex).toDouble() / span.toDouble()
                result[anchors[index].id] = Date(startTime + ((endTime - startTime) * fraction).toLong())
            }
        }
        return result
    }

    private fun routePointsFor(
        segment: RescueSegmentSnapshot,
        from: RescueAnchorSnapshot,
        to: RescueAnchorSnapshot
    ): List<RoutePoint> {
        val planned = segment.selectedRoutePolyline
            .takeIf { it.isNotBlank() }
            ?.let { decodePolyline(it) }
            .orEmpty()
        if (planned.size >= 2) return planned
        return buildList {
            add(RoutePoint(from.lat, from.lon))
            addAll(segment.waypoints.map { RoutePoint(it.lat, it.lon) })
            add(RoutePoint(to.lat, to.lon))
        }
    }

    fun encodePolyline(points: List<Pair<Double, Double>>): String {
        return points.joinToString(";") { "${it.second},${it.first}" }
    }

    fun decodePolyline(polyline: String): List<RoutePoint> {
        return polyline.split(";")
            .mapNotNull { token ->
                val parts = token.split(",")
                val lon = parts.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
                val lat = parts.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
                RoutePoint(lat, lon)
            }
    }

    private fun pointAtDistanceFraction(points: List<RoutePoint>, fraction: Double): RoutePoint {
        if (points.size <= 1) return points.firstOrNull() ?: RoutePoint(0.0, 0.0)
        val segmentDistances = points.zipWithNext { a, b -> haversineKm(a, b) }
        val totalDistance = segmentDistances.sum()
        if (totalDistance <= 0.0) return points.first()
        val targetDistance = totalDistance * fraction.coerceIn(0.0, 1.0)
        var walked = 0.0
        segmentDistances.forEachIndexed { index, distance ->
            if (walked + distance >= targetDistance) {
                val localFraction = if (distance == 0.0) 0.0 else (targetDistance - walked) / distance
                val from = points[index]
                val to = points[index + 1]
                return RoutePoint(
                    lat = from.lat + (to.lat - from.lat) * localFraction,
                    lon = from.lon + (to.lon - from.lon) * localFraction
                )
            }
            walked += distance
        }
        return points.last()
    }

    private fun haversineKm(a: RoutePoint, b: RoutePoint): Double {
        val earthRadiusKm = 6371.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val h = sin(dLat / 2).pow(2.0) + sin(dLon / 2).pow(2.0) * cos(lat1) * cos(lat2)
        return earthRadiusKm * 2 * atan2(sqrt(h), sqrt(1 - h))
    }
}

data class RoutePoint(val lat: Double, val lon: Double)
