package com.iboism.gpxrecorder.records.details

import com.iboism.gpxrecorder.model.Segment
import com.iboism.gpxrecorder.model.TrackPoint
import com.iboism.gpxrecorder.util.DateTimeFormatHelper
import io.realm.Realm

internal object TrackPointEditor {
    data class TrackPointSnapshot(
        val identifier: Long,
        val lat: Double,
        val lon: Double,
        val ele: Double?,
        val time: String,
        val note: String
    )

    fun updateNote(pointId: Long, note: String) {
        val realm = Realm.getDefaultInstance()
        realm.executeTransaction {
            pointWithId(pointId, it)?.note = note
        }
        realm.close()
    }

    fun movePoint(pointId: Long, lat: Double, lon: Double) {
        val realm = Realm.getDefaultInstance()
        realm.executeTransaction {
            val point = pointWithId(pointId, it) ?: return@executeTransaction
            point.lat = lat
            point.lon = lon
            containingSegment(pointId, it)?.recalculateDistance()
        }
        realm.close()
    }

    fun deletePoint(pointId: Long) {
        val realm = Realm.getDefaultInstance()
        realm.executeTransaction {
            val point = pointWithId(pointId, it) ?: return@executeTransaction
            val segment = containingSegment(pointId, it) ?: return@executeTransaction
            segment.points.remove(point)
            segment.recalculateDistance()
            point.deleteFromRealm()
        }
        realm.close()
    }

    fun insertPointAfter(previousPointId: Long, lat: Double, lon: Double): Long? {
        val realm = Realm.getDefaultInstance()
        var insertedPointId: Long? = null
        realm.executeTransaction {
            val segment = containingSegment(previousPointId, it) ?: return@executeTransaction
            val previousIndex = segment.points.indexOfFirst { point -> point.identifier == previousPointId }
            if (previousIndex < 0) return@executeTransaction

            val previousPoint = segment.points[previousIndex] ?: return@executeTransaction
            val nextPoint = segment.points.getOrNull(previousIndex + 1)
            val point = TrackPoint(
                lat = lat,
                lon = lon,
                ele = interpolateElevation(previousPoint, nextPoint),
                time = midpointTime(previousPoint, nextPoint),
                note = ""
            )
            it.copyToRealm(point)
            segment.points.add(previousIndex + 1, point)
            segment.recalculateDistance()
            insertedPointId = point.identifier
        }
        realm.close()
        return insertedPointId
    }

    fun snapshot(pointId: Long): TrackPointSnapshot? {
        val realm = Realm.getDefaultInstance()
        val snapshot = pointWithId(pointId, realm)?.let {
            TrackPointSnapshot(
                identifier = it.identifier,
                lat = it.lat,
                lon = it.lon,
                ele = it.ele,
                time = it.time,
                note = it.note
            )
        }
        realm.close()
        return snapshot
    }

    private fun pointWithId(pointId: Long, realm: Realm): TrackPoint? {
        return realm.where(TrackPoint::class.java)
            .equalTo(TrackPoint.primaryKey, pointId)
            .findFirst()
    }

    private fun containingSegment(pointId: Long, realm: Realm): Segment? {
        return realm.where(Segment::class.java)
            .findAll()
            .firstOrNull { segment ->
                segment.points.any { point -> point.identifier == pointId }
            }
    }

    private fun interpolateElevation(previousPoint: TrackPoint, nextPoint: TrackPoint?): Double? {
        val previousElevation = previousPoint.ele ?: return null
        val nextElevation = nextPoint?.ele ?: return previousElevation
        return (previousElevation + nextElevation) / 2.0
    }

    private fun midpointTime(previousPoint: TrackPoint, nextPoint: TrackPoint?): String {
        val previousDate = DateTimeFormatHelper.parseDate(previousPoint.time) ?: return previousPoint.time
        val nextDate = nextPoint?.time?.let { DateTimeFormatHelper.parseDate(it) } ?: return previousPoint.time
        val midpointMillis = previousDate.time + ((nextDate.time - previousDate.time) / 2)
        return DateTimeFormatHelper.formatDate(java.util.Date(midpointMillis))
    }
}
