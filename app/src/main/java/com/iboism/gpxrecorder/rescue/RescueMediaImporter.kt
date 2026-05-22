package com.iboism.gpxrecorder.rescue

import android.content.Context
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.iboism.gpxrecorder.util.DateTimeFormatHelper
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class RescueMediaAnchorCandidate(
    val uri: Uri,
    val displayName: String?,
    val lat: Double,
    val lon: Double,
    val time: String
)

data class RescueMediaImportResult(
    val importable: List<RescueMediaAnchorCandidate>,
    val rejectedCount: Int
)

class RescueMediaImporter(private val context: Context) {
    fun read(uris: List<Uri>): RescueMediaImportResult {
        val importable = mutableListOf<RescueMediaAnchorCandidate>()
        var rejected = 0
        uris.forEach { uri ->
            val candidate = readImage(uri) ?: readVideo(uri)
            if (candidate != null) importable.add(candidate) else rejected += 1
        }
        return RescueMediaImportResult(importable, rejected)
    }

    private fun readImage(uri: Uri): RescueMediaAnchorCandidate? {
        return try {
            val resolver = context.contentResolver
            val exif = resolver.openInputStream(uri)?.use { ExifInterface(it) } ?: return null
            val latLong = FloatArray(2)
            if (!exif.getLatLong(latLong)) return null
            val time = exif.dateTimeOriginal() ?: return null
            RescueMediaAnchorCandidate(
                uri = uri,
                displayName = displayName(uri),
                lat = latLong[0].toDouble(),
                lon = latLong[1].toDouble(),
                time = time
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun readVideo(uri: Uri): RescueMediaAnchorCandidate? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val location = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)
            val date = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
            val latLon = location?.parseIso6709Location() ?: return null
            val time = date?.parseVideoDate() ?: return null
            RescueMediaAnchorCandidate(
                uri = uri,
                displayName = displayName(uri),
                lat = latLon.first,
                lon = latLon.second,
                time = time
            )
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    private fun ExifInterface.dateTimeOriginal(): String? {
        val value = getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: getAttribute(ExifInterface.TAG_DATETIME)
            ?: return null
        val parser = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
        val parsed = parser.parse(value) ?: return null
        return DateTimeFormatHelper.formatDate(parsed)
    }

    private fun String.parseVideoDate(): String? {
        val patterns = listOf(
            "yyyyMMdd'T'HHmmss.SSS'Z'",
            "yyyyMMdd'T'HHmmss'Z'",
            "yyyyMMdd'T'HHmmss.SSSZ",
            "yyyyMMdd'T'HHmmssZ"
        )
        patterns.forEach { pattern ->
            val formatter = SimpleDateFormat(pattern, Locale.US).apply {
                if (pattern.endsWith("'Z'")) timeZone = TimeZone.getTimeZone("UTC")
            }
            runCatching { formatter.parse(this) }.getOrNull()?.let {
                return DateTimeFormatHelper.formatDate(it)
            }
        }
        return null
    }

    private fun String.parseIso6709Location(): Pair<Double, Double>? {
        val match = Regex("([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?)").find(this) ?: return null
        val lat = match.groupValues.getOrNull(1)?.toDoubleOrNull() ?: return null
        val lon = match.groupValues.getOrNull(2)?.toDoubleOrNull() ?: return null
        return lat to lon
    }

    private fun displayName(uri: Uri): String? {
        val resolver = context.contentResolver
        return resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            cursor.getString(0)
        }
    }
}
