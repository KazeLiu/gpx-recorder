package com.iboism.gpxrecorder.util

import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Created by Brad on 11/18/2017.
 */
class DateTimeFormatHelper {
    companion object {
        private const val datePattern = "yyyy-MM-dd'T'HH:mm:ss'Z'"

        private val dateFormatter: SimpleDateFormat
            get() {
                val dateFormatter = SimpleDateFormat(datePattern, Locale.US)
                dateFormatter.timeZone = TimeZone.getTimeZone("UTC")
                return dateFormatter
            }

        fun formatDate(date: Date = Date()): String {
            return dateFormatter.format(date)
        }

        fun parseDate(dateString: String): Date? {
            return dateFormatter.parse(dateString)
        }

        fun toReadableString(dateString: String): String {
            val date = dateFormatter.parse(dateString) ?: return ""
            return SimpleDateFormat
                .getDateTimeInstance(SimpleDateFormat.MEDIUM, SimpleDateFormat.SHORT)
                .format(date)
        }

        fun toReadableString24Hour(dateString: String): String {
            return toReadableStringWithTimePattern(dateString, "HH:mm")
        }

        fun toReadableStringAmPm(dateString: String): String {
            return toReadableStringWithTimePattern(dateString, "a h:mm")
        }

        private fun toReadableStringWithTimePattern(dateString: String, timePattern: String): String {
            val date = dateFormatter.parse(dateString) ?: return ""
            val dateText = DateFormat.getDateInstance(DateFormat.MEDIUM).format(date)
            val timeText = SimpleDateFormat(timePattern, Locale.getDefault()).format(date)
            return "$dateText $timeText"
        }
    }
}

fun Date.toReadableString(): String {
    return DateTimeFormatHelper.toReadableString(
        DateTimeFormatHelper.formatDate(this)
    )
}
