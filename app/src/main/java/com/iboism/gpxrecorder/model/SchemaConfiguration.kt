package com.iboism.gpxrecorder.model

import io.realm.FieldAttribute
import io.realm.RealmConfiguration
private const val SCHEMA_VERSION: Long = 15

const val REALM_SHARED_PREFERENCES_NAME = "kRealmSharedPrefs"
const val REALM_INIT_FAILED_KEY = "kRealmInitialized"

class Schema {
    companion object {
        fun configuration(): RealmConfiguration {
            return RealmConfiguration.Builder()
                .allowWritesOnUiThread(true)
                .allowQueriesOnUiThread(true)
                .schemaVersion(SCHEMA_VERSION)
                .migration { realm, oldVersion, newVersion ->
                    var version = oldVersion

                    // Initial version -> 10
                    if (version < 10L && version != 0L) {
                        realm.schema.get("Waypoint")
                            ?.addField("dist", Double::class.java)
                        realm.schema.get("Waypoint")
                            ?.transform { obj ->
                                obj.setDouble("dist", 0.0)
                            }
                        version = 10L
                    }

                    // 10 -> 11
                    if (version == 10L) {
                        version++
                        realm.schema.create("LastLocation")
                            .addField("lat", Double::class.java, FieldAttribute.REQUIRED)
                            .addField("lon", Double::class.java, FieldAttribute.REQUIRED)
                    }

                    // 11 -> 12
                    if (version == 11L) {
                        version++
                        realm.schema.get("TrackPoint")
                            ?.addField("note", String::class.java, FieldAttribute.REQUIRED)
                            ?.transform { obj ->
                                obj.setString("note", "")
                            }
                    }

                    // 12 -> 13
                    if (version == 12L) {
                        version++
                        val rescueAnchor = realm.schema.create("RescueAnchor")
                            .addField("identifier", Long::class.java, FieldAttribute.PRIMARY_KEY)
                            .addField("lat", Double::class.java, FieldAttribute.REQUIRED)
                            .addField("lon", Double::class.java, FieldAttribute.REQUIRED)
                            .addField("time", String::class.java)
                            .addField("source", String::class.java, FieldAttribute.REQUIRED)
                            .addField("mediaUri", String::class.java)
                            .addField("mediaName", String::class.java)
                            .addField("order", Int::class.java, FieldAttribute.REQUIRED)
                            .addField("locked", Boolean::class.java, FieldAttribute.REQUIRED)

                        val rescueRouteSegment = realm.schema.create("RescueRouteSegment")
                            .addField("identifier", Long::class.java, FieldAttribute.PRIMARY_KEY)
                            .addField("fromAnchorId", Long::class.java, FieldAttribute.REQUIRED)
                            .addField("toAnchorId", Long::class.java, FieldAttribute.REQUIRED)
                            .addField("order", Int::class.java, FieldAttribute.REQUIRED)
                            .addField("travelMode", String::class.java, FieldAttribute.REQUIRED)
                            .addField("planningStatus", String::class.java, FieldAttribute.REQUIRED)
                            .addField("selectedRouteIndex", Int::class.java, FieldAttribute.REQUIRED)
                            .addField("selectedRoutePolyline", String::class.java, FieldAttribute.REQUIRED)
                            .addField("routeCandidatesJson", String::class.java, FieldAttribute.REQUIRED)
                            .addField("waypointsJson", String::class.java, FieldAttribute.REQUIRED)
                            .addField("errorMessage", String::class.java, FieldAttribute.REQUIRED)

                        val rescuePreviewPoint = realm.schema.create("RescuePreviewPoint")
                            .addField("identifier", Long::class.java, FieldAttribute.PRIMARY_KEY)
                            .addField("lat", Double::class.java, FieldAttribute.REQUIRED)
                            .addField("lon", Double::class.java, FieldAttribute.REQUIRED)
                            .addField("time", String::class.java, FieldAttribute.REQUIRED)
                            .addField("sourceAnchorId", Long::class.javaObjectType)
                            .addField("order", Int::class.java, FieldAttribute.REQUIRED)

                        realm.schema.create("RescueTrackDraft")
                            .addField("identifier", Long::class.java, FieldAttribute.PRIMARY_KEY)
                            .addField("title", String::class.java, FieldAttribute.REQUIRED)
                            .addField("currentStep", String::class.java, FieldAttribute.REQUIRED)
                            .addField("status", String::class.java, FieldAttribute.REQUIRED)
                            .addField("createdAt", String::class.java, FieldAttribute.REQUIRED)
                            .addField("updatedAt", String::class.java, FieldAttribute.REQUIRED)
                            .addRealmListField("anchors", rescueAnchor)
                            .addRealmListField("segments", rescueRouteSegment)
                            .addField("generationIntervalSeconds", Int::class.java, FieldAttribute.REQUIRED)
                            .addRealmListField("generatedPreviewPoints", rescuePreviewPoint)
                            .addField("completedGpxId", Long::class.javaObjectType)
                    }

                    // 13 -> 14
                    if (version == 13L) {
                        version++
                        runCatching {
                            realm.schema.get("RescuePreviewPoint")
                                ?.setNullable("sourceAnchorId", true)
                        }
                        runCatching {
                            realm.schema.get("RescueTrackDraft")
                                ?.setNullable("completedGpxId", true)
                        }
                    }

                    // 14 -> 15
                    if (version == 14L) {
                        version++
                        realm.schema.get("RescueRouteSegment")?.let { schema ->
                            if (!schema.hasField("waypointsJson")) {
                                schema.addField("waypointsJson", String::class.java, FieldAttribute.REQUIRED)
                                    .transform { obj ->
                                        obj.setString("waypointsJson", "")
                                    }
                            }
                        }
                    }
                }.build()
        }
    }
}
