package com.example.scenic_navigation.services

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import org.osmdroid.util.GeoPoint
import kotlin.coroutines.resume

/**
 * Service for getting device location
 */
class LocationService(private val context: Context) {
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private var locationCallback: LocationCallback? = null
    private var isTrackingLocation = false

    /**
     * Get the current device location
     * Returns null if location permission is not granted or location unavailable
     */
    suspend fun getCurrentLocation(): GeoPoint? {
        // Check if location permission is granted
        if (!hasLocationPermission()) {
            Log.w("LocationService", "Location permission not granted")
            return null
        }

        return try {
            suspendCancellableCoroutine { continuation ->
                val cancellationTokenSource = CancellationTokenSource()

                fusedLocationClient.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    cancellationTokenSource.token
                ).addOnSuccessListener { location: Location? ->
                    if (location != null) {
                        Log.d("LocationService", "Got location: ${location.latitude}, ${location.longitude}")
                        continuation.resume(GeoPoint(location.latitude, location.longitude))
                    } else {
                        Log.w("LocationService", "Location is null, trying last known location")
                        getLastKnownLocation { lastLocation ->
                            continuation.resume(lastLocation)
                        }
                    }
                }.addOnFailureListener { exception ->
                    Log.e("LocationService", "Failed to get location: ${exception.message}")
                    // Try last known location as fallback
                    getLastKnownLocation { lastLocation ->
                        continuation.resume(lastLocation)
                    }
                }

                continuation.invokeOnCancellation {
                    cancellationTokenSource.cancel()
                }
            }
        } catch (e: Exception) {
            Log.e("LocationService", "Error getting current location: ${e.message}")
            null
        }
    }

    /**
     * Get last known location as fallback
     */
    private fun getLastKnownLocation(callback: (GeoPoint?) -> Unit) {
        if (!hasLocationPermission()) {
            callback(null)
            return
        }

        try {
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location: Location? ->
                    if (location != null) {
                        Log.d("LocationService", "Got last known location: ${location.latitude}, ${location.longitude}")
                        callback(GeoPoint(location.latitude, location.longitude))
                    } else {
                        Log.w("LocationService", "Last known location is null")
                        callback(null)
                    }
                }
                .addOnFailureListener { exception ->
                    Log.e("LocationService", "Failed to get last known location: ${exception.message}")
                    callback(null)
                }
        } catch (e: SecurityException) {
            Log.e("LocationService", "Security exception getting last location: ${e.message}")
            callback(null)
        }
    }

    /**
     * Check if location permission is granted
     */
    fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Start continuous location tracking
     * @param onLocationUpdate Callback invoked when location updates
     * @param intervalMillis Update interval in milliseconds (default 5 seconds)
     */
    fun startLocationTracking(
        onLocationUpdate: (Location) -> Unit,
        intervalMillis: Long = 5000L
    ) {
        if (!hasLocationPermission()) {
            Log.w("LocationService", "Cannot start tracking: Location permission not granted")
            return
        }

        if (isTrackingLocation) {
            Log.w("LocationService", "Location tracking already started")
            return
        }

        try {
            val locationRequest = LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                intervalMillis
            ).apply {
                setMinUpdateIntervalMillis(intervalMillis / 2)
                setMaxUpdateDelayMillis(intervalMillis * 2)
            }.build()

            locationCallback = object : LocationCallback() {
                override fun onLocationResult(locationResult: LocationResult) {
                    locationResult.lastLocation?.let { location ->
                        Log.d("LocationService", "Location update: ${location.latitude}, ${location.longitude}")
                        onLocationUpdate(location)
                    }
                }
            }

            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )

            isTrackingLocation = true
            Log.d("LocationService", "Started location tracking with ${intervalMillis}ms interval")
        } catch (e: SecurityException) {
            Log.e("LocationService", "Security exception starting location tracking: ${e.message}")
        }
    }

    /**
     * Stop continuous location tracking
     */
    fun stopLocationTracking() {
        if (!isTrackingLocation) {
            return
        }

        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
            Log.d("LocationService", "Stopped location tracking")
        }
        locationCallback = null
        isTrackingLocation = false
    }

    /**
     * Check if currently tracking location
     */
    fun isTracking(): Boolean = isTrackingLocation
}

