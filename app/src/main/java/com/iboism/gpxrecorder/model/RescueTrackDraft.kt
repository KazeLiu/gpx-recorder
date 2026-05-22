package com.iboism.gpxrecorder.model

import com.iboism.gpxrecorder.util.DateTimeFormatHelper
import com.iboism.gpxrecorder.util.UUIDHelper
import io.realm.RealmList
import io.realm.RealmObject
import io.realm.annotations.PrimaryKey
import java.util.Date

open class RescueTrackDraft(
    @PrimaryKey var identifier: Long = UUIDHelper.random(),
    var title: String = "补建轨迹",
    var currentStep: String = RescueDraftStep.DRAWING.name,
    var status: String = RescueDraftStatus.ACTIVE.name,
    var createdAt: String = DateTimeFormatHelper.formatDate(),
    var updatedAt: String = DateTimeFormatHelper.formatDate(),
    var anchors: RealmList<RescueAnchor> = RealmList(),
    var segments: RealmList<RescueRouteSegment> = RealmList(),
    var generationIntervalSeconds: Int = 5,
    var generatedPreviewPoints: RealmList<RescuePreviewPoint> = RealmList(),
    var completedGpxId: Long? = null
) : RealmObject() {
    fun touch() {
        updatedAt = DateTimeFormatHelper.formatDate(Date())
    }

    companion object Keys {
        const val primaryKey = "identifier"
    }
}

open class RescueAnchor(
    @PrimaryKey var identifier: Long = UUIDHelper.random(),
    var lat: Double = 0.0,
    var lon: Double = 0.0,
    var time: String? = null,
    var source: String = RescueAnchorSource.MANUAL.name,
    var mediaUri: String? = null,
    var mediaName: String? = null,
    var order: Int = 0,
    var locked: Boolean = false
) : RealmObject()

open class RescueRouteSegment(
    @PrimaryKey var identifier: Long = UUIDHelper.random(),
    var fromAnchorId: Long = 0L,
    var toAnchorId: Long = 0L,
    var order: Int = 0,
    var travelMode: String = RescueTravelMode.STRAIGHT.name,
    var planningStatus: String = RescuePlanningStatus.NOT_REQUESTED.name,
    var selectedRouteIndex: Int = 0,
    var selectedRoutePolyline: String = "",
    var routeCandidatesJson: String = "",
    var waypointsJson: String = "",
    var errorMessage: String = ""
) : RealmObject()

open class RescuePreviewPoint(
    @PrimaryKey var identifier: Long = UUIDHelper.random(),
    var lat: Double = 0.0,
    var lon: Double = 0.0,
    var time: String = DateTimeFormatHelper.formatDate(),
    var sourceAnchorId: Long? = null,
    var order: Int = 0
) : RealmObject()

enum class RescueDraftStep {
    DRAWING,
    PLANNING,
    GENERATED
}

enum class RescueDraftStatus {
    ACTIVE,
    COMPLETED,
    ABANDONED
}

enum class RescueAnchorSource {
    MANUAL,
    MEDIA
}

enum class RescueTravelMode {
    DRIVING,
    WALKING,
    RIDING,
    STRAIGHT
}

enum class RescuePlanningStatus {
    NOT_REQUESTED,
    READY,
    FAILED
}
