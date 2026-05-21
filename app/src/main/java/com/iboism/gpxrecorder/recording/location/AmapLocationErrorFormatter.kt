package com.iboism.gpxrecorder.recording.location

internal object AmapLocationErrorFormatter {
    fun format(errorCode: Int, errorInfo: String?, locationDetail: String?): String {
        val info = errorInfo.orEmpty().trim()
        val detail = locationDetail.orEmpty().trim()
        val primaryMessage = info.ifEmpty { detail.ifEmpty { "Unknown AMap location error" } }

        return buildString {
            append("Error code ")
            append(errorCode)
            append(": ")
            append(primaryMessage)

            if (detail.isNotEmpty() && detail != primaryMessage) {
                append('\n')
                append(detail)
            }
        }
    }
}
