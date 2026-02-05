package com.example.scenic_navigation.data

import com.example.scenic_navigation.models.ActivityType
import com.example.scenic_navigation.models.CurationIntent
import com.example.scenic_navigation.models.SeeingType
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings

/**
 * Simple Firestore helper to save/load the user's latest curation intent (selection).
 * Uses a single document per user: collection `users` -> document `{uid}` with field `latestSelection`.
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

    fun saveSelection(userId: String, intent: CurationIntent, onComplete: (success: Boolean, errorMessage: String?) -> Unit) {
        val docRef = firestore.collection("users").document(userId)
        val map = mapOf(
            "latestSelection" to mapOf(
                "destinationQuery" to intent.destinationQuery,
                "seeing" to intent.seeing.name,
                "activity" to intent.activity.name,
                "timestamp" to FieldValue.serverTimestamp()
            )
        )
        docRef.set(map, com.google.firebase.firestore.SetOptions.merge())
            .addOnSuccessListener {
                onComplete(true, null)
            }
            .addOnFailureListener { ex ->
                onComplete(false, ex.message)
            }
    }

    fun loadSelection(userId: String, onComplete: (intent: CurationIntent?) -> Unit) {
        val docRef = firestore.collection("users").document(userId)
        docRef.get()
            .addOnSuccessListener { doc ->
                if (doc != null && doc.exists()) {
                    val map = doc.get("latestSelection") as? Map<*, *>
                    if (map != null) {
                        val destination = (map["destinationQuery"] as? String) ?: ""
                        val seeingStr = (map["seeing"] as? String) ?: SeeingType.OCEANIC.name
                        val activityStr = (map["activity"] as? String) ?: ActivityType.SIGHTSEEING.name

                        val seeing = try { SeeingType.valueOf(seeingStr) } catch (_: Exception) { SeeingType.OCEANIC }
                        val activity = try { ActivityType.valueOf(activityStr) } catch (_: Exception) { ActivityType.SIGHTSEEING }

                        val intent = CurationIntent(destinationQuery = destination, seeing = seeing, activity = activity)
                        onComplete(intent)
                        return@addOnSuccessListener
                    }
                }
                onComplete(null)
            }
            .addOnFailureListener {
                onComplete(null)
            }
    }
}
