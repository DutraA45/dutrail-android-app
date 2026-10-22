package com.dutrail.app.activities.domain

import java.time.Instant

enum class Sport(val apiValue: String) {
    RUNNING("running"),
    CYCLING("cycling"),
    WALKING("walking"),
    HIKING("hiking"),
    SWIMMING("swimming"),
    OTHER("other");

    companion object {
        fun fromApi(value: String): Sport = entries.firstOrNull { it.apiValue == value } ?: OTHER
    }
}

data class Activity(
    val id: String,
    val name: String,
    val sport: Sport,
    val startedAt: Instant,
    /** Tempo total, pausas incluídas. */
    val elapsedTimeSeconds: Long,
    val movingTimeSeconds: Long?,
    val distanceMeters: Double?,
    val elevationGainMeters: Double?,
    val averageHeartRateBpm: Int?,
    val maxHeartRateBpm: Int?,
    val calories: Int?,
) {
    /** Duração exibida: tempo em movimento quando houver, como nos relógios de corrida. */
    val durationSeconds: Long get() = movingTimeSeconds ?: elapsedTimeSeconds

    val averagePaceSecondsPerKm: Double?
        get() = distanceMeters?.takeIf { it > 0 }?.let { durationSeconds / (it / 1000.0) }
}
