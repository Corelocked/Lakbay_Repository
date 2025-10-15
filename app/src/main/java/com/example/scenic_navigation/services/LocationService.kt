package com.example.scenic_navigation.services

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
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
}

