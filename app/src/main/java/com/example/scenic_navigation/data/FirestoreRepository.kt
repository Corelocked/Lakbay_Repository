package com.example.scenic_navigation.data

import com.example.scenic_navigation.models.ActivityType
import com.example.scenic_navigation.models.CurationIntent
import com.example.scenic_navigation.models.SeeingType
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.Query

/**
 * Simple Firestore helper to save/load the user's latest curation intent (selection).
 * Uses a collection per user: collection `users` -> document `{uid}` -> collection `routes`.
 * All functions use simple callbacks so we avoid adding additional coroutine dependencies.
 */
class FirestoreRepository(private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()) {

    init {
        // Enable offline persistence if not already
        try {
            val settings = FirebaseFirestoreSettings.Builder()
                .setPersistenceEnabled(true)
                .build()
            firestore.firestoreSettings = settings
        } catch (_: Exception) {
            // ignore if settings already applied or not supported
        }
    }

    /**
     * Save a selection as a new route document under `users/{userId}/routes`.
     * Also attempts to update a `latestSelection` field on the user document for quick access.
     */
    fun saveSelection(userId: String, intent: CurationIntent, onComplete: (success: Boolean, errorMessage: String?) -> Unit) {
        val routesCol = firestore.collection("users").document(userId).collection("routes")
        val userDocRef = firestore.collection("users").document(userId)

        val routeMap = mapOf(
            "destinationQuery" to intent.destinationQuery,
            "seeing" to intent.seeing.name,
            "activity" to intent.activity.name,
            "timestamp" to FieldValue.serverTimestamp()
        )

        // Add a new document to the user's routes collection (this preserves every travel as a log)
        routesCol.add(routeMap)
            .addOnSuccessListener {
                // Fire and forget: also update latestSelection on the user doc for convenience
                val latestMap = mapOf(
                    "latestSelection" to mapOf(
                        "destinationQuery" to intent.destinationQuery,
                        "seeing" to intent.seeing.name,
                        "activity" to intent.activity.name,
                        "timestamp" to FieldValue.serverTimestamp()
                    )
                )
                userDocRef.set(latestMap, com.google.firebase.firestore.SetOptions.merge())
                    .addOnSuccessListener {
                        // Both route logged and latestSelection updated
                        onComplete(true, null)
                    }
                    .addOnFailureListener {
                        // Route was logged successfully, but updating latestSelection failed. Still consider it success.
                        onComplete(true, null)
                    }
            }
            .addOnFailureListener { ex ->
                onComplete(false, ex.message)
            }
    }

    /**
     * Load the latest saved route for the user by timestamp from `users/{userId}/routes`.
     * Falls back to null if none exists or on error.
     */
    fun loadSelection(userId: String, onComplete: (intent: CurationIntent?) -> Unit) {
        val routesCol = firestore.collection("users").document(userId).collection("routes")
        routesCol.orderBy("timestamp", Query.Direction.DESCENDING).limit(1)
            .get()
            .addOnSuccessListener { querySnap ->
                try {
                    val doc = querySnap.documents.firstOrNull()
                    if (doc != null && doc.exists()) {
                        val destination = (doc.getString("destinationQuery")) ?: ""
                        val seeingStr = (doc.getString("seeing")) ?: SeeingType.OCEANIC.name
                        val activityStr = (doc.getString("activity")) ?: ActivityType.SIGHTSEEING.name

                        val seeing = try { SeeingType.valueOf(seeingStr) } catch (_: Exception) { SeeingType.OCEANIC }
                        val activity = try { ActivityType.valueOf(activityStr) } catch (_: Exception) { ActivityType.SIGHTSEEING }

                        val intent = CurationIntent(destinationQuery = destination, seeing = seeing, activity = activity)
                        onComplete(intent)
                        return@addOnSuccessListener
                    }
                } catch (_: Exception) {
                    // ignore and fall through to onComplete(null)
                }
                onComplete(null)
            }
            .addOnFailureListener {
                onComplete(null)
            }
    }

    /**
     * Optional: load the full history/log of routes for a user.
     */
    fun loadAllRoutes(userId: String, onComplete: (routes: List<CurationIntent>) -> Unit) {
        val routesCol = firestore.collection("users").document(userId).collection("routes")
        routesCol.orderBy("timestamp", Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { snap ->
                val list = mutableListOf<CurationIntent>()
                for (doc in snap.documents) {
                    try {
                        val destination = (doc.getString("destinationQuery")) ?: ""
                        val seeingStr = (doc.getString("seeing")) ?: SeeingType.OCEANIC.name
                        val activityStr = (doc.getString("activity")) ?: ActivityType.SIGHTSEEING.name

                        val seeing = try { SeeingType.valueOf(seeingStr) } catch (_: Exception) { SeeingType.OCEANIC }
                        val activity = try { ActivityType.valueOf(activityStr) } catch (_: Exception) { ActivityType.SIGHTSEEING }

                        list.add(CurationIntent(destinationQuery = destination, seeing = seeing, activity = activity))
                    } catch (_: Exception) {
                        // skip malformed
                    }
                }
                onComplete(list)
            }
            .addOnFailureListener {
                onComplete(emptyList())
            }
    }
}
