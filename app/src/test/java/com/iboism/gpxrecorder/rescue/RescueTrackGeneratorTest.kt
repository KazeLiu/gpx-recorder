package com.iboism.gpxrecorder.rescue

import com.iboism.gpxrecorder.model.RescueAnchorSource
import com.iboism.gpxrecorder.model.RescuePlanningStatus
import com.iboism.gpxrecorder.model.RescueTravelMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RescueTrackGeneratorTest {
    @Test
    fun validateAnchorsRejectsMissingEndTime() {
        val anchors = listOf(
            RescueAnchorSnapshot(1L, 30.0, 120.0, "2026-05-22T01:00:00Z", RescueAnchorSource.MANUAL, 0),
            RescueAnchorSnapshot(2L, 30.1, 120.1, null, RescueAnchorSource.MANUAL, 1)
        )

        assertEquals("终点必须设置时间。", RescueTrackGenerator.validateAnchors(anchors))
    }

    @Test
    fun generateKeepsTimedAnchorsAndInterpolatesUntimedAnchor() {
        val anchors = listOf(
            RescueAnchorSnapshot(1L, 30.0, 120.0, "2026-05-22T01:00:00Z", RescueAnchorSource.MANUAL, 0),
            RescueAnchorSnapshot(2L, 30.1, 120.1, null, RescueAnchorSource.MANUAL, 1),
            RescueAnchorSnapshot(3L, 30.2, 120.2, "2026-05-22T01:10:00Z", RescueAnchorSource.MEDIA, 2)
        )
        val segments = listOf(
            RescueSegmentSnapshot(11L, 1L, 2L, 0, RescueTravelMode.STRAIGHT, RescuePlanningStatus.READY, ""),
            RescueSegmentSnapshot(12L, 2L, 3L, 1, RescueTravelMode.STRAIGHT, RescuePlanningStatus.READY, "")
        )

        val result = RescueTrackGenerator.generate(anchors, segments, 300)

        assertTrue(result.points.any { it.sourceAnchorId == 1L && it.time == "2026-05-22T01:00:00Z" })
        assertTrue(result.points.any { it.sourceAnchorId == 3L && it.time == "2026-05-22T01:10:00Z" })
        assertTrue(result.points.any { it.lat == 30.1 && it.lon == 120.1 })
        assertNull(RescueTrackGenerator.validateAnchors(anchors))
    }
}
