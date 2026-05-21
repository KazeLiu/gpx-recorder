package com.iboism.gpxrecorder.recording.location

import org.junit.Assert.assertEquals
import org.junit.Test

class AmapLocationErrorFormatterTest {
    @Test
    fun formatsErrorCodeBeforeSdkMessageAndDetail() {
        val message = AmapLocationErrorFormatter.format(
            errorCode = 10,
            errorInfo = "Location service startup failed",
            locationDetail = "Check whether APSService is declared in AndroidManifest.xml"
        )

        assertEquals(
            "Error code 10: Location service startup failed\nCheck whether APSService is declared in AndroidManifest.xml",
            message
        )
    }

    @Test
    fun doesNotRepeatDetailWhenSdkInfoAlreadyContainsIt() {
        val message = AmapLocationErrorFormatter.format(
            errorCode = 13,
            errorInfo = "Location failed",
            locationDetail = "Location failed"
        )

        assertEquals("Error code 13: Location failed", message)
    }
}
