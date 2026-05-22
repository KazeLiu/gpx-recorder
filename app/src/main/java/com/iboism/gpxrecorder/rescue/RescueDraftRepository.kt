package com.iboism.gpxrecorder.rescue

import com.iboism.gpxrecorder.model.GpxContent
import com.iboism.gpxrecorder.model.RescueAnchor
import com.iboism.gpxrecorder.model.RescueAnchorSource
import com.iboism.gpxrecorder.model.RescueDraftStatus
import com.iboism.gpxrecorder.model.RescueDraftStep
import com.iboism.gpxrecorder.model.RescuePlanningStatus
import com.iboism.gpxrecorder.model.RescuePreviewPoint
import com.iboism.gpxrecorder.model.RescueRouteSegment
import com.iboism.gpxrecorder.model.RescueTrackDraft
import com.iboism.gpxrecorder.model.RescueTravelMode
import com.iboism.gpxrecorder.model.Segment
import com.iboism.gpxrecorder.model.Track
import com.iboism.gpxrecorder.model.TrackPoint
import com.iboism.gpxrecorder.util.DateTimeFormatHelper
import io.realm.Realm
import io.realm.RealmList
import io.realm.Sort
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date

data class RescueRouteWaypoint(
    val lat: Double,
    val lon: Double
)

object RescueDraftRepository {
    const val MAX_ROUTE_WAYPOINTS = 10

    fun latestActiveDraftId(): Long? {
        val realm = Realm.getDefaultInstance()
        return try {
            realm.where(RescueTrackDraft::class.java)
                .equalTo("status", RescueDraftStatus.ACTIVE.name)
                .sort("updatedAt", Sort.DESCENDING)
                .findFirst()
                ?.identifier
        } finally {
            realm.close()
        }
    }

    fun createDraft(title: String = "补建轨迹 ${DateTimeFormatHelper.toReadableString(DateTimeFormatHelper.formatDate())}"): Long {
        val realm = Realm.getDefaultInstance()
        return try {
            var draftId = 0L
            realm.executeTransaction {
                val draft = RescueTrackDraft(title = title)
                draftId = draft.identifier
                it.copyToRealm(draft)
            }
            draftId
        } finally {
            realm.close()
        }
    }

    fun createDraftReplacingActive(title: String = "补建轨迹 ${DateTimeFormatHelper.toReadableString(DateTimeFormatHelper.formatDate())}"): Long {
        val realm = Realm.getDefaultInstance()
        return try {
            var draftId = 0L
            realm.executeTransaction {
                deleteActiveDrafts(it)
                val draft = RescueTrackDraft(title = title)
                draftId = draft.identifier
                it.copyToRealm(draft)
            }
            draftId
        } finally {
            realm.close()
        }
    }

    fun copyDraft(draftId: Long): RescueTrackDraft? {
        val realm = Realm.getDefaultInstance()
        return try {
            realm.where(RescueTrackDraft::class.java)
                .equalTo(RescueTrackDraft.primaryKey, draftId)
                .findFirst()
                ?.let { realm.copyFromRealm(it) }
        } finally {
            realm.close()
        }
    }

    fun setTitle(draftId: Long, title: String) {
        updateDraft(draftId) { draft ->
            draft.title = title.ifBlank { "补建轨迹" }
        }
    }

    fun addManualAnchor(draftId: Long, lat: Double, lon: Double, time: String?): Long {
        return addAnchor(
            draftId = draftId,
            anchor = RescueAnchor(
                lat = lat,
                lon = lon,
                time = time,
                source = RescueAnchorSource.MANUAL.name,
                locked = false
            ),
            sortByTime = false
        )
    }

    fun addMediaAnchor(
        draftId: Long,
        lat: Double,
        lon: Double,
        time: String,
        mediaUri: String,
        mediaName: String?
    ): Long {
        return addAnchor(
            draftId = draftId,
            anchor = RescueAnchor(
                lat = lat,
                lon = lon,
                time = time,
                source = RescueAnchorSource.MEDIA.name,
                mediaUri = mediaUri,
                mediaName = mediaName,
                locked = true
            ),
            sortByTime = true
        )
    }

    fun updateAnchorPosition(draftId: Long, anchorId: Long, lat: Double, lon: Double) {
        updateDraft(draftId) { draft ->
            draft.anchors.firstOrNull { it?.identifier == anchorId }?.let { anchor ->
                if (!anchor.locked) {
                    anchor.lat = lat
                    anchor.lon = lon
                }
            }
        }
    }

    fun updateAnchorTime(draftId: Long, anchorId: Long, time: String?) {
        updateDraft(draftId) { draft ->
            draft.anchors.firstOrNull { it?.identifier == anchorId }?.time = time
        }
    }

    fun deleteAnchor(draftId: Long, anchorId: Long) {
        updateDraft(draftId) { draft ->
            val anchor = draft.anchors.firstOrNull { it?.identifier == anchorId } ?: return@updateDraft
            draft.anchors.remove(anchor)
            anchor.deleteFromRealm()
            reorderAnchors(draft)
            draft.generatedPreviewPoints.deleteAllFromRealm()
            draft.currentStep = RescueDraftStep.DRAWING.name
        }
    }

    fun setStep(draftId: Long, step: RescueDraftStep) {
        updateDraft(draftId) { draft -> draft.currentStep = step.name }
    }

    fun clearDownstreamPlanningData(draftId: Long) {
        updateDraft(draftId) { draft ->
            draft.segments.deleteAllFromRealm()
            draft.generatedPreviewPoints.deleteAllFromRealm()
            draft.currentStep = RescueDraftStep.DRAWING.name
        }
    }

    fun preparePlanning(draftId: Long) {
        updateDraft(draftId) { draft ->
            draft.currentStep = RescueDraftStep.PLANNING.name
            rebuildSegments(draft, preserveExisting = true)
        }
    }

    fun updateSegmentMode(draftId: Long, segmentId: Long, mode: RescueTravelMode) {
        updateDraft(draftId) { draft ->
            draft.segments.firstOrNull { it?.identifier == segmentId }?.let { segment ->
                segment.travelMode = mode.name
                if (mode == RescueTravelMode.STRAIGHT) {
                    segment.planningStatus = RescuePlanningStatus.READY.name
                    segment.selectedRoutePolyline = straightPolylineForSegment(draft, segment)
                    segment.routeCandidatesJson = ""
                    segment.errorMessage = ""
                } else {
                    segment.planningStatus = RescuePlanningStatus.NOT_REQUESTED.name
                    segment.selectedRoutePolyline = ""
                    segment.routeCandidatesJson = ""
                    segment.errorMessage = ""
                }
                draft.generatedPreviewPoints.deleteAllFromRealm()
            }
        }
    }

    fun addSegmentWaypoint(draftId: Long, segmentId: Long, lat: Double, lon: Double): Boolean {
        var added = false
        updateDraft(draftId) { draft ->
            val segment = draft.segments.firstOrNull { it?.identifier == segmentId } ?: return@updateDraft
            val waypoints = waypointsFromJson(segment.waypointsJson).toMutableList()
            if (waypoints.size >= MAX_ROUTE_WAYPOINTS) return@updateDraft
            waypoints.add(RescueRouteWaypoint(lat, lon))
            segment.waypointsJson = waypointsToJson(waypoints)
            resetSegmentAfterWaypointChange(draft, segment)
            added = true
        }
        return added
    }

    fun removeSegmentWaypoint(draftId: Long, segmentId: Long, index: Int) {
        updateDraft(draftId) { draft ->
            val segment = draft.segments.firstOrNull { it?.identifier == segmentId } ?: return@updateDraft
            val waypoints = waypointsFromJson(segment.waypointsJson).toMutableList()
            if (index !in waypoints.indices) return@updateDraft
            waypoints.removeAt(index)
            segment.waypointsJson = waypointsToJson(waypoints)
            resetSegmentAfterWaypointChange(draft, segment)
        }
    }

    fun moveSegmentWaypoint(draftId: Long, segmentId: Long, fromIndex: Int, toIndex: Int) {
        updateDraft(draftId) { draft ->
            val segment = draft.segments.firstOrNull { it?.identifier == segmentId } ?: return@updateDraft
            val waypoints = waypointsFromJson(segment.waypointsJson).toMutableList()
            if (fromIndex !in waypoints.indices || toIndex !in waypoints.indices) return@updateDraft
            val item = waypoints.removeAt(fromIndex)
            waypoints.add(toIndex, item)
            segment.waypointsJson = waypointsToJson(waypoints)
            resetSegmentAfterWaypointChange(draft, segment)
        }
    }

    fun setSegmentWaypoints(draftId: Long, segmentId: Long, waypoints: List<RescueRouteWaypoint>) {
        updateDraft(draftId) { draft ->
            val segment = draft.segments.firstOrNull { it?.identifier == segmentId } ?: return@updateDraft
            segment.waypointsJson = waypointsToJson(waypoints.take(MAX_ROUTE_WAYPOINTS))
            resetSegmentAfterWaypointChange(draft, segment)
        }
    }

    fun setAnchorOrder(draftId: Long, anchorIds: List<Long>) {
        updateDraft(draftId) { draft ->
            val anchorsById = draft.anchors.filterNotNull().associateBy { it.identifier }
            if (anchorIds.size != anchorsById.size || anchorIds.any { it !in anchorsById }) return@updateDraft
            anchorIds.forEachIndexed { index, anchorId ->
                anchorsById[anchorId]?.order = index
            }
            draft.generatedPreviewPoints.deleteAllFromRealm()
            draft.currentStep = RescueDraftStep.DRAWING.name
        }
    }

    fun saveRouteCandidates(
        draftId: Long,
        segmentId: Long,
        candidatesJson: String,
        selectedPolyline: String,
        selectedIndex: Int
    ) {
        updateDraft(draftId) { draft ->
            draft.segments.firstOrNull { it?.identifier == segmentId }?.let { segment ->
                segment.routeCandidatesJson = candidatesJson
                segment.selectedRoutePolyline = selectedPolyline
                segment.selectedRouteIndex = selectedIndex
                segment.planningStatus = RescuePlanningStatus.READY.name
                segment.errorMessage = ""
            }
        }
    }

    fun markSegmentPlanningFailed(draftId: Long, segmentId: Long, message: String) {
        updateDraft(draftId) { draft ->
            draft.segments.firstOrNull { it?.identifier == segmentId }?.let { segment ->
                segment.planningStatus = RescuePlanningStatus.FAILED.name
                segment.errorMessage = message
            }
        }
    }

    fun saveGeneratedPreview(draftId: Long, intervalSeconds: Int, points: List<RescueGeneratedPoint>) {
        updateDraft(draftId) { draft ->
            draft.generationIntervalSeconds = intervalSeconds
            draft.generatedPreviewPoints.deleteAllFromRealm()
            points.forEach { point ->
                draft.generatedPreviewPoints.add(
                    RescuePreviewPoint(
                        lat = point.lat,
                        lon = point.lon,
                        time = point.time,
                        sourceAnchorId = point.sourceAnchorId,
                        order = point.order
                    )
                )
            }
            draft.currentStep = RescueDraftStep.GENERATED.name
        }
    }

    fun completeDraft(draftId: Long): Long? {
        val realm = Realm.getDefaultInstance()
        return try {
            var gpxId: Long? = null
            realm.executeTransaction {
                val draft = it.where(RescueTrackDraft::class.java)
                    .equalTo(RescueTrackDraft.primaryKey, draftId)
                    .findFirst() ?: return@executeTransaction
                val points = draft.generatedPreviewPoints.sortedBy { point -> point?.order ?: 0 }
                if (points.size < 2) return@executeTransaction
                val segment = Segment()
                points.forEach { point ->
                    segment.addPoint(
                        TrackPoint(
                            lat = point.lat,
                            lon = point.lon,
                            time = point.time,
                            note = draft.anchors
                                .firstOrNull { anchor -> anchor?.identifier == point.sourceAnchorId }
                                ?.let { anchor -> anchor.mediaName?.let { name -> "媒体锚点：$name" } ?: "" }
                                ?: ""
                        )
                    )
                }
                val gpx = GpxContent(
                    title = draft.title,
                    date = points.first()?.time ?: DateTimeFormatHelper.formatDate(),
                    trackList = RealmList(Track(name = draft.title, segments = RealmList(segment)))
                )
                gpxId = gpx.identifier
                it.copyToRealm(gpx)
                draft.status = RescueDraftStatus.COMPLETED.name
                draft.completedGpxId = gpxId
                draft.touch()
                deleteActiveDrafts(it, exceptDraftId = draft.identifier)
            }
            gpxId
        } finally {
            realm.close()
        }
    }

    fun anchorsForGeneration(draft: RescueTrackDraft): List<RescueAnchorSnapshot> {
        return draft.anchors
            .filterNotNull()
            .sortedBy { it.order }
            .map {
                RescueAnchorSnapshot(
                    id = it.identifier,
                    lat = it.lat,
                    lon = it.lon,
                    time = it.time,
                    source = RescueAnchorSource.valueOf(it.source),
                    order = it.order
                )
            }
    }

    fun segmentsForGeneration(draft: RescueTrackDraft): List<RescueSegmentSnapshot> {
        return draft.segments
            .filterNotNull()
            .sortedBy { it.order }
            .map {
                RescueSegmentSnapshot(
                    id = it.identifier,
                    fromAnchorId = it.fromAnchorId,
                    toAnchorId = it.toAnchorId,
                    order = it.order,
                    travelMode = RescueTravelMode.valueOf(it.travelMode),
                    planningStatus = RescuePlanningStatus.valueOf(it.planningStatus),
                    selectedRoutePolyline = it.selectedRoutePolyline,
                    waypoints = waypointsFromJson(it.waypointsJson)
                )
            }
    }

    fun waypointsFromJson(json: String): List<RescueRouteWaypoint> {
        if (json.isBlank()) return emptyList()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            RescueRouteWaypoint(
                lat = obj.optDouble("lat"),
                lon = obj.optDouble("lon")
            )
        }
    }

    private fun addAnchor(draftId: Long, anchor: RescueAnchor, sortByTime: Boolean): Long {
        val realm = Realm.getDefaultInstance()
        return try {
            var anchorId = 0L
            realm.executeTransaction {
                val draft = it.where(RescueTrackDraft::class.java)
                    .equalTo(RescueTrackDraft.primaryKey, draftId)
                    .findFirst() ?: return@executeTransaction
                anchorId = anchor.identifier
                if (sortByTime) {
                    insertMediaAnchorByTime(draft, anchor)
                } else {
                    draft.anchors.add(anchor)
                }
                reorderAnchors(draft)
                draft.generatedPreviewPoints.deleteAllFromRealm()
                draft.currentStep = RescueDraftStep.DRAWING.name
                draft.touch()
            }
            anchorId
        } finally {
            realm.close()
        }
    }

    private fun updateDraft(draftId: Long, block: (RescueTrackDraft) -> Unit) {
        val realm = Realm.getDefaultInstance()
        try {
            realm.executeTransaction {
                val draft = it.where(RescueTrackDraft::class.java)
                    .equalTo(RescueTrackDraft.primaryKey, draftId)
                    .findFirst() ?: return@executeTransaction
                block(draft)
                draft.touch()
            }
        } finally {
            realm.close()
        }
    }

    private fun deleteActiveDrafts(realm: Realm, exceptDraftId: Long? = null) {
        val activeDrafts = realm.where(RescueTrackDraft::class.java)
            .equalTo("status", RescueDraftStatus.ACTIVE.name)
            .findAll()
            .toList()
            .filter { draft -> draft.identifier != exceptDraftId }
        activeDrafts.forEach { draft ->
            draft.anchors.deleteAllFromRealm()
            draft.segments.deleteAllFromRealm()
            draft.generatedPreviewPoints.deleteAllFromRealm()
            draft.deleteFromRealm()
        }
    }

    private fun reorderAnchors(draft: RescueTrackDraft) {
        draft.anchors.filterNotNull().forEachIndexed { index, anchor ->
            anchor.order = index
        }
    }

    private fun insertMediaAnchorByTime(draft: RescueTrackDraft, anchor: RescueAnchor) {
        val mediaTime = anchor.time?.let { DateTimeFormatHelper.parseDate(it)?.time }
        if (mediaTime == null) {
            draft.anchors.add(anchor)
            return
        }

        val insertIndex = draft.anchors
            .filterNotNull()
            .indexOfFirst { existing ->
                val existingTime = existing.time?.let { DateTimeFormatHelper.parseDate(it)?.time }
                existingTime != null && existingTime > mediaTime
            }
        if (insertIndex >= 0) {
            draft.anchors.add(insertIndex, anchor)
        } else {
            draft.anchors.add(anchor)
        }
    }

    private fun rebuildSegments(draft: RescueTrackDraft, preserveExisting: Boolean) {
        val existing = if (preserveExisting) {
            draft.segments.filterNotNull().associateBy { it.fromAnchorId to it.toAnchorId }
        } else {
            emptyMap()
        }
        draft.segments.clear()
        draft.anchors.filterNotNull().sortedBy { it.order }.zipWithNext { from, to ->
            val segment = existing[from.identifier to to.identifier]
                ?: RescueRouteSegment(
                    fromAnchorId = from.identifier,
                    toAnchorId = to.identifier,
                    travelMode = RescueTravelMode.STRAIGHT.name,
                    planningStatus = RescuePlanningStatus.READY.name
                )
            if (segment.travelMode == RescueTravelMode.STRAIGHT.name) {
                segment.selectedRoutePolyline = straightPolylineForSegment(draft, segment)
            }
            segment.order = draft.segments.size
            draft.segments.add(segment)
        }
    }

    private fun resetSegmentAfterWaypointChange(draft: RescueTrackDraft, segment: RescueRouteSegment) {
        if (segment.travelMode == RescueTravelMode.STRAIGHT.name) {
            segment.planningStatus = RescuePlanningStatus.READY.name
            segment.selectedRoutePolyline = straightPolylineForSegment(draft, segment)
            segment.routeCandidatesJson = ""
            segment.errorMessage = ""
        } else {
            segment.planningStatus = RescuePlanningStatus.NOT_REQUESTED.name
            segment.selectedRoutePolyline = ""
            segment.routeCandidatesJson = ""
            segment.errorMessage = ""
        }
        draft.generatedPreviewPoints.deleteAllFromRealm()
    }

    private fun straightPolylineForSegment(draft: RescueTrackDraft, segment: RescueRouteSegment): String {
        val anchors = draft.anchors.filterNotNull().associateBy { it.identifier }
        val from = anchors[segment.fromAnchorId] ?: return ""
        val to = anchors[segment.toAnchorId] ?: return ""
        val points = buildList {
            add(from.lat to from.lon)
            addAll(waypointsFromJson(segment.waypointsJson).map { it.lat to it.lon })
            add(to.lat to to.lon)
        }
        return RescueTrackGenerator.encodePolyline(points)
    }

    private fun waypointsToJson(waypoints: List<RescueRouteWaypoint>): String {
        val array = JSONArray()
        waypoints.forEach {
            array.put(
                JSONObject()
                    .put("lat", it.lat)
                    .put("lon", it.lon)
            )
        }
        return array.toString()
    }
}
