package com.dutrail.app.navigation

import kotlinx.serialization.Serializable

@Serializable
data object LoginRoute

@Serializable
data object SignupRoute

@Serializable
data object ActivityListRoute

@Serializable
data class ActivityDetailRoute(val id: String)

/** @param resumeRecordingId gravação interrompida a retomar; null para uma nova. */
@Serializable
data class RecordingSetupRoute(val resumeRecordingId: Long? = null)

@Serializable
data object RecordingRoute

@Serializable
data object RouteMapRoute

@Serializable
data class RecordingResultRoute(val recordingId: Long)
