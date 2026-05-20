package com.iboism.gpxrecorder.model

import com.iboism.gpxrecorder.extensions.toElevationString
import com.iboism.gpxrecorder.util.DateTimeFormatHelper
import com.iboism.gpxrecorder.util.UUIDHelper
import io.realm.RealmObject
import io.realm.annotations.PrimaryKey

/**
 * Created by Brad on 11/18/2017.
 */

open class TrackPoint(
    @PrimaryKey var identifier: Long = UUIDHelper.random(),
    var lat: Double = 0.0,
    var lon: Double = 0.0,
    var ele: Double? = null,
    var time: String = DateTimeFormatHelper.formatDate(),
    var note: String = ""
) : RealmObject(), XmlSerializable {
    override fun getXmlString(): String {
        val eleXml = ele?.let { return@let "<ele>${it.toElevationString()}</ele>" } ?: ""
        val timeXml = "<time>$time</time>"
        val noteXml = note.takeIf { it.isNotBlank() }?.let { "<desc>${it.toXmlEscapedString()}</desc>" } ?: ""
        return "<trkpt lat=\"$lat\" lon=\"$lon\">$eleXml$timeXml$noteXml</trkpt>"
    }

    private fun String.toXmlEscapedString(): String {
        return this
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    companion object Keys {
        const val primaryKey = "identifier"
    }
}
