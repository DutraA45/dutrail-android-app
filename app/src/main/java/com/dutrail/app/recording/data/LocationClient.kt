package com.dutrail.app.recording.data

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.dutrail.app.core.common.Clock
import com.dutrail.app.recording.domain.LocationSample
import com.dutrail.app.recording.domain.RecordingConfig
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

sealed interface LocationSettingsState {
    data object Satisfied : LocationSettingsState

    /** Localização desligada, mas o Play Services oferece um diálogo para ligar. */
    data class Resolvable(val resolution: PendingIntent) : LocationSettingsState

    /** Desligada e sem diálogo: só pelas configurações do sistema. */
    data object Unavailable : LocationSettingsState
}

@Singleton
class LocationClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clock: Clock,
) {
    private val fused = LocationServices.getFusedLocationProviderClient(context)
    private val settings = LocationServices.getSettingsClient(context)
    private val locationManager = context.getSystemService(LocationManager::class.java)

    private val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, RecordingConfig.LOCATION_INTERVAL_MILLIS)
        .setMinUpdateIntervalMillis(RecordingConfig.LOCATION_INTERVAL_MILLIS)
        .setWaitForAccurateLocation(false)
        .build()

    fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun isLocationEnabled(): Boolean = LocationManagerCompat.isLocationEnabled(locationManager)

    suspend fun checkSettings(): LocationSettingsState = try {
        settings.checkLocationSettings(LocationSettingsRequest.Builder().addLocationRequest(request).build()).await()
        LocationSettingsState.Satisfied
    } catch (e: ResolvableApiException) {
        LocationSettingsState.Resolvable(e.resolution)
    } catch (e: ApiException) {
        LocationSettingsState.Unavailable
    }

    /** Fluxo de fixes a ~1 Hz, alta precisão. Requer ACCESS_FINE_LOCATION (verificada por quem chama). */
    @SuppressLint("MissingPermission")
    fun updates(): Flow<LocationSample> = callbackFlow {
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { trySend(it.toSample()) }
            }
        }
        fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
            .addOnFailureListener { close(it) }
        awaitClose { fused.removeLocationUpdates(callback) }
    }

    /**
     * Usa o relógio de parede do aparelho (o mesmo dos eventos de pausa), descontando a idade do fix
     * pelo relógio monotônico. Location.time vem do GNSS e pode divergir alguns segundos do sistema.
     */
    private fun Location.toSample(): LocationSample {
        val ageMillis = (SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos) / 1_000_000
        return LocationSample(
            timeMillis = clock.nowMillis() - ageMillis.coerceAtLeast(0),
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = if (hasAccuracy()) accuracy else null,
            altitudeMeters = altitudeMsl(),
            speedMps = if (hasSpeed()) speed else null,
        )
    }

    /** Altitude acima do nível do mar quando o sistema informa (API 34+); senão a elipsoidal do GPS. */
    private fun Location.altitudeMsl(): Double? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && hasMslAltitude() -> mslAltitudeMeters
        hasAltitude() -> altitude
        else -> null
    }
}
