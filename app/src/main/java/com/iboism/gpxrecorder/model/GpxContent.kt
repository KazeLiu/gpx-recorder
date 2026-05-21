package com.iboism.gpxrecorder.model

import com.iboism.gpxrecorder.util.DateTimeFormatHelper
import com.iboism.gpxrecorder.util.Distance
import com.iboism.gpxrecorder.util.UUIDHelper
import io.realm.Realm
import io.realm.RealmList
import io.realm.RealmObject
import io.realm.annotations.PrimaryKey
import org.json.JSONObject

/**
 * Created by Brad on 11/19/2017.
 */
open class GpxContent(
    @PrimaryKey var identifier: Long = UUIDHelper.random(),
    var trackList: RealmList<Track> = RealmList(),
    var waypointList: RealmList<Waypoint> = RealmList(),
    var title: String = "",
    var date: String = DateTimeFormatHelper.formatDate()
) : JsonSerializable, XmlSerializable, RealmObject() {

    override fun getXmlString(): String {
        val titleXml = "<name>$title</name>"
        val descXml = "<desc>Recorded with 迹录 for Android</desc>"
        val metaDataXml = "<metadata>$titleXml$descXml</metadata>"
        val contentXml = listOf(trackList)
            .flatten()
            .asSequence()
            .filterIsInstance(XmlSerializable::class.java)
            .map { it.getXmlString() }
            .fold("") { content, entity -> content + entity }

        return "$metaDataXml\n$contentXml"
    }

    override fun getJsonString(): String {
        val map: HashMap<Any?, Any?> = HashMap()
        map["title"] = title
        map["type"] = "FeatureCollection"

        val coordinates = trackList
            .flatMap { it.segments }
            .flatMap { it.points }
            .map { trackPoint ->
                listOf(trackPoint.lon, trackPoint.lat)
            }

        val lineGeometryMap = HashMap<String, Any>()
        lineGeometryMap["type"] = "LineString"
        lineGeometryMap["coordinates"] = coordinates

        val linesMap = HashMap<String, Any>()
        linesMap["type"] = "Feature"
        linesMap["properties"] = HashMap<String, Any>()
        linesMap["geometry"] = lineGeometryMap

        val trackPointNoteFeatures = trackList
            .flatMap { it.segments }
            .flatMap { it.points }
            .filter { it.note.isNotBlank() }
            .map { trackPoint ->
                val pointMap = HashMap<String, Any>()

                val propertiesMap = HashMap<String, Any>()
                propertiesMap["type"] = "trackPoint"
                propertiesMap["note"] = trackPoint.note
                propertiesMap["time"] = trackPoint.time

                val geometryMap = HashMap<String, Any>()
                geometryMap["type"] = "Point"
                geometryMap["coordinates"] = arrayOf(trackPoint.lon, trackPoint.lat)

                pointMap["type"] = "Feature"
                pointMap["properties"] = propertiesMap
                pointMap["geometry"] = geometryMap

                pointMap
            }

        map["features"] = listOf(linesMap) + trackPointNoteFeatures
        return JSONObject(map).toString()
    }

    fun trackPointCount(): Int {
        return trackList.sumOf { track ->
            track.segments.sumOf { segment -> segment.points.size }
        }
    }

    fun totalDistanceKm(): Float {
        var total = 0f
        trackList.forEach { track ->
            track.segments.forEach { segment ->
                segment.points.zipWithNext { previousPoint, point ->
                    total += Distance.haversineKm(previousPoint, point)
                }
            }
        }
        return total
    }

    companion object Keys {
        const val primaryKey = "identifier"

        fun withId(identifier: Long?, realm: Realm): GpxContent? {
            if (identifier == null) return null

            return realm.where(GpxContent::class.java)
                .equalTo(primaryKey, identifier)
                .findFirst()
        }
    }
}
