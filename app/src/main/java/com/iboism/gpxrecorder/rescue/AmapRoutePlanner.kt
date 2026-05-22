package com.iboism.gpxrecorder.rescue

import android.util.Log
import com.iboism.gpxrecorder.model.RescueTravelMode
import com.iboism.gpxrecorder.util.AmapCoordinateConverter
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class AmapRouteCandidate(
    val title: String,
    val distanceMeters: Int,
    val durationSeconds: Int,
    val polyline: String
)

data class AmapPlaceCandidate(
    val name: String,
    val lat: Double,
    val lon: Double
)

class AmapRoutePlanner(private val apiKey: String) {
    fun searchPlace(keyword: String): AmapPlaceCandidate? {
        if (keyword.isBlank()) return null
        val url = "https://restapi.amap.com/v3/place/text?key=${apiKey.encoded()}&keywords=${keyword.encoded()}&city=全国&offset=1&page=1"
        val json = JSONObject(readUrl(url))
        json.requireAmapSuccess("地点搜索")
        val first = json.optJSONArray("pois")?.optJSONObject(0) ?: return null
        val location = first.optString("location").split(",")
        val gcjLon = location.getOrNull(0)?.toDoubleOrNull() ?: return null
        val gcjLat = location.getOrNull(1)?.toDoubleOrNull() ?: return null
        val (wgsLat, wgsLon) = AmapCoordinateConverter.gcjToWgs(gcjLat, gcjLon)
        return AmapPlaceCandidate(first.optString("name", keyword), wgsLat, wgsLon)
    }

    fun planRoute(
        mode: RescueTravelMode,
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        waypoints: List<RescueRouteWaypoint> = emptyList()
    ): List<AmapRouteCandidate> = withAmapRetry {
        planRouteOnce(mode, fromLat, fromLon, toLat, toLon, waypoints)
    }

    private fun planRouteOnce(
        mode: RescueTravelMode,
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        waypoints: List<RescueRouteWaypoint>
    ): List<AmapRouteCandidate> {
        if (mode == RescueTravelMode.STRAIGHT) return emptyList()
        if (mode != RescueTravelMode.DRIVING && waypoints.isNotEmpty()) {
            return planMultiLegRoute(mode, fromLat, fromLon, toLat, toLon, waypoints)
        }
        val (fromGcjLat, fromGcjLon) = AmapCoordinateConverter.wgsToGcj(fromLat, fromLon)
        val (toGcjLat, toGcjLon) = AmapCoordinateConverter.wgsToGcj(toLat, toLon)
        val origin = "${fromGcjLon},${fromGcjLat}".encoded()
        val destination = "${toGcjLon},${toGcjLat}".encoded()
        val waypointText = waypoints
            .takeIf { it.isNotEmpty() && mode == RescueTravelMode.DRIVING }
            ?.joinToString(";") {
                val (gcjLat, gcjLon) = AmapCoordinateConverter.wgsToGcj(it.lat, it.lon)
                "${gcjLon},${gcjLat}"
            }
            ?.encoded()
            ?.let { "&waypoints=$it" }
            .orEmpty()
        val url = when (mode) {
            RescueTravelMode.DRIVING -> "https://restapi.amap.com/v3/direction/driving?key=${apiKey.encoded()}&origin=$origin&destination=$destination&extensions=all&strategy=10$waypointText"
            RescueTravelMode.WALKING -> "https://restapi.amap.com/v5/direction/walking?key=${apiKey.encoded()}&origin=$origin&destination=$destination&show_fields=cost,polyline&alternative_route=3"
            RescueTravelMode.RIDING -> "https://restapi.amap.com/v5/direction/bicycling?key=${apiKey.encoded()}&origin=$origin&destination=$destination&show_fields=cost,polyline&alternative_route=3"
            RescueTravelMode.STRAIGHT -> return emptyList()
        }
        val json = JSONObject(readUrl(url))
        json.requireAmapSuccess("路线规划")
        return parseRoutes(mode, json)
    }

    private fun withAmapRetry(block: () -> List<AmapRouteCandidate>): List<AmapRouteCandidate> {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (error: AmapServiceException) {
                attempt += 1
                if (error.infoCode != AMAP_QPS_LIMIT_INFO_CODE || attempt >= MAX_QPS_RETRY_COUNT) {
                    throw error
                }
                Thread.sleep(QPS_RETRY_DELAYS_MS.getOrElse(attempt - 1) { QPS_RETRY_DELAYS_MS.last() })
            }
        }
    }

    private fun planMultiLegRoute(
        mode: RescueTravelMode,
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        waypoints: List<RescueRouteWaypoint>
    ): List<AmapRouteCandidate> {
        val points = buildList {
            add(RescueRouteWaypoint(fromLat, fromLon))
            addAll(waypoints)
            add(RescueRouteWaypoint(toLat, toLon))
        }
        val legCandidates = points.zipWithNext().mapIndexed { index, (from, to) ->
            if (index > 0) Thread.sleep(MULTI_LEG_ROUTE_THROTTLE_MS)
            withAmapRetry {
                planRouteOnce(mode, from.lat, from.lon, to.lat, to.lon, emptyList())
            }
        }
        if (legCandidates.any { it.isEmpty() }) return emptyList()
        val candidateCount = legCandidates.minOf { it.size }.coerceAtMost(3)
        return (0 until candidateCount).map { index ->
            val legs = legCandidates.map { it[index] }
            AmapRouteCandidate(
                title = "方案 ${index + 1}",
                distanceMeters = legs.sumOf { it.distanceMeters },
                durationSeconds = legs.sumOf { it.durationSeconds },
                polyline = legs.joinToString(";") { it.polyline }.trim(';')
            )
        }
    }

    fun candidatesToJson(candidates: List<AmapRouteCandidate>): String {
        val array = JSONArray()
        candidates.forEach { candidate ->
            array.put(
                JSONObject()
                    .put("title", candidate.title)
                    .put("distanceMeters", candidate.distanceMeters)
                    .put("durationSeconds", candidate.durationSeconds)
                    .put("polyline", candidate.polyline)
            )
        }
        return array.toString()
    }

    fun candidatesFromJson(json: String): List<AmapRouteCandidate> {
        if (json.isBlank()) return emptyList()
        val array = JSONArray(json)
        return (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            AmapRouteCandidate(
                title = obj.optString("title"),
                distanceMeters = obj.optInt("distanceMeters"),
                durationSeconds = obj.optInt("durationSeconds"),
                polyline = obj.optString("polyline")
            )
        }
    }

    private fun parseRoutes(mode: RescueTravelMode, json: JSONObject): List<AmapRouteCandidate> {
        val paths = json.optJSONObject("route")?.optJSONArray("paths")
            ?: json.optJSONObject("data")?.optJSONArray("paths")
            ?: return emptyList()

        return (0 until paths.length()).mapNotNull { index ->
            val path = paths.optJSONObject(index) ?: return@mapNotNull null
            val polyline = path.optJSONArray("steps").decodeWgsPolyline()
            if (polyline.isBlank()) return@mapNotNull null
            val cost = path.optJSONObject("cost")
            AmapRouteCandidate(
                title = "方案 ${index + 1}",
                distanceMeters = path.optString("distance").toIntOrNull() ?: path.optInt("distance"),
                durationSeconds = path.optString("duration").toIntOrNull()
                    ?: path.optInt("duration").takeIf { it > 0 }
                    ?: cost?.optString("duration")?.toIntOrNull()
                    ?: cost?.optInt("duration")
                    ?: 0,
                polyline = polyline
            )
        }
    }

    private fun JSONArray?.decodeWgsPolyline(): String {
        val points = mutableListOf<Pair<Double, Double>>()
        if (this == null) return ""
        for (index in 0 until length()) {
            val step = optJSONObject(index) ?: continue
            step.optString("polyline").split(";").forEach { token ->
                val parts = token.split(",")
                val gcjLon = parts.getOrNull(0)?.toDoubleOrNull() ?: return@forEach
                val gcjLat = parts.getOrNull(1)?.toDoubleOrNull() ?: return@forEach
                points.add(AmapCoordinateConverter.gcjToWgs(gcjLat, gcjLon))
            }
        }
        return RescueTrackGenerator.encodePolyline(points)
    }

    private fun readUrl(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = "GET"
        }
        return try {
            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            BufferedReader(InputStreamReader(stream)).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONObject.requireAmapSuccess(action: String) {
        val status = optString("status")
        val errCode = optString("errcode")
        val isFailed = status == "0" || (errCode.isNotBlank() && errCode != "0")
        if (!isFailed) return

        val infoCode = optString("infocode").ifBlank { errCode }
        val info = optString("info").ifBlank { optString("errmsg").ifBlank { "未知错误" } }
        Log.w(TAG, "$action failed: infocode=$infoCode info=$info")
        throw AmapServiceException(infoCode, info)
    }

    private fun String.encoded(): String = URLEncoder.encode(this, Charsets.UTF_8.name())

    companion object {
        private const val TAG = "AmapRoutePlanner"
        private const val AMAP_QPS_LIMIT_INFO_CODE = "10021"
        private const val MAX_QPS_RETRY_COUNT = 4
        private const val MULTI_LEG_ROUTE_THROTTLE_MS = 450L
        private val QPS_RETRY_DELAYS_MS = longArrayOf(900L, 1_600L, 2_600L)
    }
}

class AmapServiceException(
    val infoCode: String,
    val info: String
) : Exception("高德服务错误 $infoCode：$info")
