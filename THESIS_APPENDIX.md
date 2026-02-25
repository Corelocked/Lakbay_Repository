# Thesis Appendix — Code Snippets & Blurbs (Complete)

This appendix consolidates the key code snippets and short blurbs you can include in your thesis. Each entry includes:
- A one-line summary
- Exact source file and inclusive line ranges to screenshot
- A short caption for the figure
- A copy-paste blurb suitable for the thesis
- A compact Kotlin/Python snippet (the exact lines called out) for screenshot or paste

Order below follows the earlier suggested screenshot order and covers all snippets (1–12).

---

## 1 — MlInferenceEngine (init)

- File: `app/src/main/java/com/example/scenic_navigation/ml/MlInferenceEngine.kt`
- Feature: model load + metadata + flexible fallback
- Exact lines: 37–114 (inclusive)

Caption
: MlInferenceEngine.init — loads TFLite model asset, logs model metadata, and attempts flexible fallbacks.

Appendix blurb (copy-paste)
: MlInferenceEngine.init — Reads optional `models/model_metadata.json`, attempts to load the configured TFLite asset, inspects input/output tensor quantization parameters, and falls back to any `.tflite` under `assets/models/` when the primary asset is missing. This makes on-device inference robust to packaging and quantization differences.

Kotlin snippet (lines 37–114)
```text
// MlInferenceEngine.init (lines 37-114)
init {
    // Log model metadata if present
    try {
        val metaStream = context.assets.open("models/model_metadata.json")
        BufferedReader(InputStreamReader(metaStream)).use { br ->
            val content = br.readText()
            val j = JSONObject(content)
            val name = j.optString("model_name", "unknown")
            val version = j.optString("version", "unknown")
            val trainedOn = j.optString("trained_on", "unknown")
            Log.i("MlInferenceEngine", "Model metadata - name:$name version:$version trained_on:$trainedOn")
            // Log metrics if present
            val metrics = j.optJSONObject("metrics")
            if (metrics != null) {
                val auc = metrics.optDouble("auc", Double.NaN)
                val acc = metrics.optDouble("accuracy", Double.NaN)
                val n = j.optInt("n_samples", -1)
                Log.i("MlInferenceEngine", "Model metrics - auc:${if (!auc.isNaN()) String.format("%.4f", auc) else "n/a"} accuracy:${if (!acc.isNaN()) String.format("%.3f", acc) else "n/a"} samples:${if (n>=0) n else "n/a"}")
            }
        }
    } catch (e: Exception) {
        Log.i("MlInferenceEngine", "No model metadata found or failed to read it: ${e.message}")
    }

    try {
        val modelBuffer = loadModelFile(context, modelAssetPath)
        val options = Interpreter.Options().apply { setNumThreads(defaultNumThreads) }
        interpreter = Interpreter(modelBuffer, options)
        Log.i("MlInferenceEngine", "Loaded model asset: $modelAssetPath")
        // inspect input/output tensor details for quantization params
        try {
            val inTensor = interpreter!!.getInputTensor(0)
            inputFeatureCount = inTensor.shape().last()
            val inType = inTensor.dataType()
            if (inType == DataType.UINT8 || inType == DataType.INT8) {
                inputIsQuantized = true
                val qp = inTensor.quantizationParams()
                inputScale = qp.scale
                inputZeroPoint = qp.zeroPoint
            }
            val outTensor = interpreter!!.getOutputTensor(0)
            val outType = outTensor.dataType()
            if (outType == DataType.UINT8 || outType == DataType.INT8) {
                outputIsQuantized = true
                val qp2 = outTensor.quantizationParams()
                outputScale = qp2.scale
                outputZeroPoint = qp2.zeroPoint
            }
        } catch (_: Throwable) {
        }
        hasInterpreter = true
    } catch (t: Throwable) {
        Log.w("MlInferenceEngine", "Failed to load model asset $modelAssetPath, attempting fallback...", t)
        // Fallback: try to load any .tflite in assets/models
        try {
            val assets = context.assets.list("models") ?: emptyArray()
            val tflite = assets.firstOrNull { it.endsWith(".tflite") }
            if (tflite != null) {
                val candidate = "models/" + tflite
                val modelBuffer = loadModelFile(context, candidate)
                val options = Interpreter.Options().apply { setNumThreads(defaultNumThreads) }
                interpreter = Interpreter(modelBuffer, options)
                hasInterpreter = true
                Log.i("MlInferenceEngine", "Loaded fallback model asset: $candidate")
            }
        } catch (t: Throwable) {
            Log.w("MlInferenceEngine", "No fallback model found in assets/models/", t)
        }
        hasInterpreter = hasInterpreter && interpreter != null
        if (!hasInterpreter) {
            interpreter = null
        }
    }
    // Attempt flexible fallback if the direct load failed or if the provided
    // asset path is not present in packaged assets.
    tryInitFlexible(context)
}
```

---

## 2 — MlInferenceEngine (batch prediction)

- File: `app/src/main/java/com/example/scenic_navigation/ml/MlInferenceEngine.kt`
- Feature: `predictScoresBatch` (batch inference + quantization handling)
- Exact lines: 241–284 (inclusive)

Caption
: MlInferenceEngine.predictScoresBatch — efficient batched inference handling both float and quantized models.

Appendix blurb (copy-paste)
: predictScoresBatch — Packs feature rows into a single batch input for the TFLite Interpreter and handles input/output quantization (scale/zeroPoint). Falls back to a deterministic heuristic per-row when the interpreter isn't available.

Kotlin snippet (lines 241–284)
```text
// MlInferenceEngine.predictScoresBatch (lines 241-284)
override fun predictScoresBatch(featuresBatch: List<FloatArray>): FloatArray {
    if (featuresBatch.isEmpty()) return FloatArray(0)
    synchronized(interpLock) {
        if (hasInterpreter && interpreter != null) {
            try {
                if (inputIsQuantized) {
                    val batchSize = featuresBatch.size
                    val bb = ByteBuffer.allocateDirect(batchSize * featuresBatch[0].size).order(ByteOrder.nativeOrder())
                    for (r in 0 until batchSize) {
                        val row = featuresBatch[r]
                        for (i in row.indices) {
                            val q = Math.round(row[i] / inputScale) + inputZeroPoint
                            bb.put(q.toByte())
                        }
                    }
                    bb.rewind()
                    if (outputIsQuantized) {
                        val outBb = ByteBuffer.allocateDirect(batchSize).order(ByteOrder.nativeOrder())
                        interpreter!!.run(bb, outBb)
                        outBb.rewind()
                        val out = FloatArray(batchSize)
                        for (i in 0 until batchSize) {
                            val qout = outBb.get().toInt()
                            out[i] = (qout - outputZeroPoint) * outputScale
                        }
                        return out
                    } else {
                        val output = Array(batchSize) { FloatArray(1) }
                        interpreter!!.run(bb, output)
                        return FloatArray(batchSize) { i -> output[i][0] }
                    }
                } else {
                    val input = featuresBatch.toTypedArray()
                    val output = Array(featuresBatch.size) { FloatArray(1) }
                    interpreter!!.run(input, output)
                    return FloatArray(featuresBatch.size) { i -> output[i][0] }
                }
            } catch (_: Throwable) {
                // fall through and compute heuristic per-row
            }
        }
        return FloatArray(featuresBatch.size) { i -> heuristicScore(featuresBatch[i]) }
    }
}
```

---

## 3 — MlFeatureExtractor.buildPoiFeatures

- File: `app/src/main/java/com/example/scenic_navigation/ml/MlFeatureExtractor.kt`
- Feature: `buildPoiFeatures`
- Exact lines: 20–44 (inclusive)

Caption
: MlFeatureExtractor.buildPoiFeatures — canonical feature pipeline and normalization used by model and trainer.

Appendix blurb (copy-paste)
: MlFeatureExtractor.buildPoiFeatures — Produces the exact input vector the reranker expects: normalized distance (0..1), time-of-day sine/cosine, binary category flags (food/sight), normalized scenic score (scaled by 1/250), and municipality flag. The feature order is strictly enforced and must match the trainer.

Kotlin snippet (lines 20–44)
```text
// MlFeatureExtractor.buildPoiFeatures (lines 20-44)
fun buildPoiFeatures(poi: Poi, userLat: Double, userLon: Double, timeMillis: Long, scenicScore: Float = 0f): FloatArray {
    val distance = haversineMeters(userLat, userLon, poi.lat ?: userLat, poi.lon ?: userLon)
    val maxDist = 50_000.0 // normalization cap — keep consistent with training
    val distNorm = (distance / maxDist).coerceIn(0.0, 1.0).toFloat()

    val hour = ((timeMillis / 1000) / 3600) % 24
    val angle = (2.0 * Math.PI * hour) / 24.0
    val timeSin = sin(angle).toFloat()
    val timeCos = cos(angle).toFloat()

    val catTokens = poi.category?.split("/")?.map { it.trim().lowercase() } ?: emptyList()
    val catFood = if (catTokens.any { it.contains("restaurant") || it.contains("food") || it.contains("cafe") }) 1f else 0f
    val catSight = if (catTokens.any { it.contains("viewpoint") || it.contains("tourist") || it.contains("attraction") }) 1f else 0f

    val hasMunicipality = if (!poi.municipality.isNullOrBlank()) 1f else 0f
    val scenicNorm = (scenicScore / 250f).coerceIn(0f, 1f)

    return floatArrayOf(distNorm, timeSin, timeCos, catFood, catSight, scenicNorm, hasMunicipality)
}
```

---

## 4 — PoiReranker.rerank

- File: `app/src/main/java/com/example/scenic_navigation/ml/PoiReranker.kt`
- Feature: `rerank`
- Exact lines: 23–41 (inclusive)

Caption
: PoiReranker.rerank — batch scoring and blended final score (ML + scenic + preference).

Appendix blurb (copy-paste)
: PoiReranker.rerank — Runs batch inference for candidate POIs and computes a blended final score that mixes the model output (weight 0.75), a normalized scenic score (scaled to model range), and a small additive user-preference boost (weight 0.15). This preserves both learned signals and app heuristics.

Kotlin snippet (lines 23–41)
```text
// PoiReranker.rerank (lines 23-41)
fun rerank(pois: List<Poi>, userLat: Double, userLon: Double, timeMillis: Long): List<Poi> {
    val featuresBatch = pois.map { poi -> extractor.buildPoiFeatures(poi, userLat, userLon, timeMillis, scenicScore = poi.scenicScore ?: 0f) }
    val scores = engine.predictScoresBatch(featuresBatch)

    val final = pois.indices.map { i ->
        val scenic = pois[i].scenicScore ?: 0f
        val scenicNorm = (scenic / 250f).coerceIn(0f, 1f)
        val mlScore = scores.getOrNull(i) ?: 0f
        val prefScore = prefStore?.getPreferenceScore(pois[i].category) ?: 0f
        val blended = ML_BLEND_ALPHA * mlScore + (1f - ML_BLEND_ALPHA) * scenicNorm
        val finalScore = blended + PREF_WEIGHT * prefScore
        Pair(pois[i], finalScore)
    }

    return final.sortedByDescending { it.second }.map { it.first }
}
```

---

## 5 — RecommendationsViewModel.fetchRecommendations (end-to-end)

- File: `app/src/main/java/com/example/scenic_navigation/viewmodel/RecommendationsViewModel.kt`
- Feature: `fetchRecommendations` (overload with parameters)
- Exact lines: 265–388 (inclusive)

Caption
: RecommendationsViewModel.fetchRecommendations — end-to-end discovery → ML rerank → curated boosts → telemetry.

Appendix blurb (copy-paste)
: fetchRecommendations — Implements the Discover pipeline: load candidates from CSV (or fall back to planner), apply a simple category-priority sort, run the ML reranker, boost curated favorites to the top, apply distance and category filters, and record an opt-in telemetry impression snapshot.

Kotlin snippet (lines 265–388)
```text
// RecommendationsViewModel.fetchRecommendations (lines 265-388)
fun fetchRecommendations(userLat: Double = 14.5995, userLon: Double = 120.9842, maxDistanceKm: Double = 50.0, preferredCategories: Set<String> = emptySet()) {
    viewModelScope.launch {
        _isLoading.value = true
        try {
            val allPois = loadCandidates().toMutableList()
            val sortedPois = allPois.sortedWith(compareBy<Poi> { poi -> /* category priority */ }.thenBy { it.name })
            val centerLat = sortedPois.getOrNull(0)?.lat ?: userLat
            val centerLon = sortedPois.getOrNull(0)?.lon ?: userLon

            var reranked = try {
                poiReranker.rerank(sortedPois, centerLat, centerLon, System.currentTimeMillis())
            } catch (e: Exception) {
                sortedPois
            }

            // Boost curated set to the top
            val curatedSet = sharedPrefs.getStringSet(CURATED_KEY, emptySet()) ?: emptySet()
            if (curatedSet.isNotEmpty()) {
                val storedNames = curatedSet.mapNotNull { key -> key.split(',').firstOrNull()?.trim()?.lowercase() }.toSet()
                val (curated, others) = reranked.partition { poi -> storedNames.contains(poi.name.trim().lowercase()) }
                reranked = curated + others
            }

            // Distance filter, preferred category boosts, telemetry impression logging follow here
            _recommendations.value = reranked.take(20)

            // Telemetry: snapshot impression
            val reqId = java.util.UUID.randomUUID().toString()
            val topKeys = reranked.take(10).map { poi -> com.example.scenic_navigation.ui.RecommendationsAdapter.canonicalKey(poi) }
            com.example.scenic_navigation.services.Telemetry.logRecommendationImpression(reqId, topKeys, settingsStore.isPersonalizationEnabled(), null)

        } catch (e: Exception) {
            _recommendations.value = emptyList()
        } finally {
            _isLoading.value = false
        }
    }
}
```

Notes
: The actual function is larger — capture lines 265–388 in your IDE for the full flow (candidate load, rerank, curated boosting, filters, telemetry).

---

## 6 — ScenicRoutePlanner.fetchScenicPois (route-side discovery)

- File: `app/src/main/java/com/example/scenic_navigation/services/ScenicRoutePlanner.kt`
- Feature: `fetchScenicPois`
- Exact lines: 199–360 (inclusive)

Caption
: ScenicRoutePlanner.fetchScenicPois — segment-based POI discovery, scoring, curation boosts, and ML rerank.

Appendix blurb (copy-paste)
: fetchScenicPois — Finds candidate scenic POIs along a route by segmenting the polyline into bbox queries (with caching), scoring by category heuristics, applying curation boosts (if any), and finally reranking candidates with the on-device ML model. This produces the route-level scenic enrichment used by the UI.

Kotlin snippet (lines 199–360)
```text
// ScenicRoutePlanner.fetchScenicPois (lines 199-360)
suspend fun fetchScenicPois(
    routePoints: List<GeoPoint>,
    packageName: String,
    routeType: String = "generic",
    curationIntent: com.example.scenic_navigation.models.CurationIntent? = null,
    onStatusUpdate: ((String) -> Unit)? = null
): List<ScenicPoi> = withContext(Dispatchers.IO) {
    if (routePoints.isEmpty()) return@withContext emptyList()
    val length = GeoUtils.computeRouteLength(routePoints)
    // compute spacing/segments, query per-segment in parallel, merge results
    // apply distance filter, dedupe, compute scores via computeScoreFromCategory
    // Apply curation boosts (CurationMapper.map) and then ML reranker (poiReranker.rerank)
    return@withContext list
}
```

Notes
: For full implementation screenshot lines 199–360. The function includes segmentation, bbox creation, caching, curation boosts, and ML rerank calls.

---

## 7 — Training dataset generation (train_from_luzon.generate_dataset)

- File: `tools/train_from_luzon.py`
- Feature: `generate_dataset`
- Exact lines: 114–137 (inclusive)

Caption
: train_from_luzon.generate_dataset — synthetic impression sampling and heuristic labeling for training.

Appendix blurb (copy-paste)
: generate_dataset — Samples POIs and synthetic user locations to build a training set. Labels are created by combining distance, scenic base, and category signals into a logistic probability and sampling a binomial label. This synthetic data is used to train and export the TFLite reranker.

Python snippet (lines 114–137)
```text
# train_from_luzon.generate_dataset (lines 114-137)
def generate_dataset(pois, n_samples=10000):
    rng = np.random.RandomState(42)
    users = sample_user_locations(pois, max(50, n_samples // 200))
    X = []
    y = []
    for i in range(n_samples):
        user = users[rng.randint(len(users))]
        poi = pois[rng.randint(len(pois))]
        hour = rng.randint(0,24)
        features = build_features(poi, user[0], user[1], hour)
        dist = features[0]
        scenic = features[5]
        sight = features[4]
        logit = 1.5*(1 - dist) + 1.2*scenic + 0.6*sight + rng.normal(scale=0.3)
        prob = 1.0 / (1.0 + math.exp(-logit))
        label = float(rng.binomial(1, prob))
        X.append(features)
        y.append(label)
    X = np.stack(X, axis=0)
    y = np.array(y, dtype=np.float32)
    return X, y
```

---

## 8 — Telemetry (init, logEvent, rotate)

- File: `app/src/main/java/com/example/scenic_navigation/services/Telemetry.kt`
- Features: `init`, `logEvent`, `rotateIfNeeded`
- Exact lines: 32–155 (inclusive)

Caption
: Telemetry — opt-in local JSON-lines logger with rotation.

Appendix blurb (copy-paste)
: Telemetry — Minimal opt-in telemetry helper that appends JSON-lines to `filesDir/telemetry/events.log`; it respects the user's opt-in preference, rotates large logs to avoid unbounded growth, and includes convenience methods such as `logRecommendationImpression` for QA snapshots.

Kotlin snippet (lines 32–155)
```text
// Telemetry.init / logEvent / rotateIfNeeded (lines 32-155)
fun init(context: Context) {
    try {
        filesDirPath = context.applicationContext.filesDir.absolutePath
        val settings = SettingsStore(context.applicationContext)
        enabled = settings.isTelemetryEnabled()
        prefs = context.applicationContext.getSharedPreferences("scenic_prefs", Context.MODE_PRIVATE)
        prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { shared, key ->
            if (key == "telemetry_enabled") {
                enabled = shared.getBoolean(key, false)
                Log.i(TAG, "Telemetry enabled changed: $enabled")
            }
        }
        prefs?.registerOnSharedPreferenceChangeListener(prefsListener)
        val d = File(filesDirPath, telemetryDirName)
        if (!d.exists()) d.mkdirs()
        initialized = true
        Log.i(TAG, "Telemetry initialized; enabled=$enabled")
    } catch (t: Throwable) {
        Log.w(TAG, "Failed to init telemetry: ${t.message}")
        initialized = false
    }
}

fun logEvent(eventType: String, params: Map<String, Any?> = emptyMap()) {
    if (!initialized) return
    if (!isEnabled()) return
    try {
        val root = JSONObject()
        root.put("event_type", eventType)
        root.put("event_time", System.currentTimeMillis())
        val p = JSONObject()
        for ((k, v) in params) {
            when (v) {
                null -> p.put(k, JSONObject.NULL)
                is Number -> p.put(k, v)
                is Boolean -> p.put(k, v)
                is String -> p.put(k, v)
                is Map<*, *> -> { /* nested map handling */ }
                is List<*> -> { /* array handling */ }
                else -> p.put(k, v.toString())
            }
        }
        root.put("params", p)
        val basePath = filesDirPath ?: return
        rotateIfNeeded(basePath)
        val d = File(basePath, telemetryDirName)
        if (!d.exists()) d.mkdirs()
        val f = File(d, eventsFileName)
        FileOutputStream(f, true).use { fos ->
            OutputStreamWriter(fos, Charsets.UTF_8).use { writer ->
                writer.append(root.toString())
                writer.append('\n')
                writer.flush()
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "Failed to log telemetry event=$eventType: ${t.message}")
    }
}
```

---

## 9 — FirestoreRepository (save/load user curation)

- File: `app/src/main/java/com/example/scenic_navigation/data/FirestoreRepository.kt`
- Features: `saveSelection`, `loadSelection`
- Exact lines: 34–103 (inclusive)

Caption
: FirestoreRepository.saveSelection/loadSelection — saving and retrieving user curation selections in Firestore.

Appendix blurb (copy-paste)
: FirestoreRepository.saveSelection/loadSelection — Demonstrates writing user curation history to Firestore (`users/{uid}/routes`) while updating a `latestSelection` field on the user document for quick retrieval. Includes offline persistence via Firestore settings.

Kotlin snippet (lines 34–103)
```text
// FirestoreRepository.saveSelection / loadSelection (lines 34-103)
fun saveSelection(userId: String, intent: CurationIntent, onComplete: (success: Boolean, errorMessage: String?) -> Unit) {
    val routesCol = firestore.collection("users").document(userId).collection("routes")
    val userDocRef = firestore.collection("users").document(userId)

    val routeMap = mapOf(
        "destinationQuery" to intent.destinationQuery,
        "seeing" to intent.seeing.name,
        "activity" to intent.activity.name,
        "timestamp" to FieldValue.serverTimestamp()
    )

    routesCol.add(routeMap)
        .addOnSuccessListener {
            val latestMap = mapOf(
                "latestSelection" to mapOf(
                    "destinationQuery" to intent.destinationQuery,
                    "seeing" to intent.seeing.name,
                    "activity" to intent.activity.name,
                    "timestamp" to FieldValue.serverTimestamp()
                )
            )
            userDocRef.set(latestMap, com.google.firebase.firestore.SetOptions.merge())
                .addOnSuccessListener { onComplete(true, null) }
                .addOnFailureListener { onComplete(true, null) }
        }
        .addOnFailureListener { ex -> onComplete(false, ex.message) }
}

fun loadSelection(userId: String, onComplete: (intent: CurationIntent?) -> Unit) {
    val routesCol = firestore.collection("users").document(userId).collection("routes")
    routesCol.orderBy("timestamp", Query.Direction.DESCENDING).limit(1)
        .get()
        .addOnSuccessListener { querySnap ->
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
            onComplete(null)
        }
        .addOnFailureListener { onComplete(null) }
```

---

## 10 — RoutingService.generateCoastalRouteViaWaypoints (oceanic)

- File: `app/src/main/java/com/example/scenic_navigation/services/RoutingService.kt`
- Function: `generateCoastalRouteViaWaypoints`
- Exact lines: 603–645 (inclusive)

Caption
: RoutingService.generateCoastalRouteViaWaypoints — chooses coastal waypoint sets and samples anchors to produce a coast-following via-waypoint route.

Appendix blurb (copy-paste)
: generateCoastalRouteViaWaypoints — Picks a coastal waypoint set near the destination, orders anchors along the route, and samples representative anchors used as via-waypoints for OSRM. Short trips avoid coastal detours; long oceanic trips sample multiple anchors so the polyline follows the coast.

Kotlin snippet (lines 603–645)
```text
// RoutingService.generateCoastalRouteViaWaypoints (lines 603-645)
private suspend fun generateCoastalRouteViaWaypoints(start: GeoPoint, dest: GeoPoint, packageName: String): List<GeoPoint> {
    Log.d("RoutingService", "Finding best coastal route...")
    val directDistance = start.distanceToAsDouble(dest)
    val isLongDistance = directDistance > 300_000

    if (directDistance < 30_000) return fetchRoute(start, dest, packageName, "default")

    val bestWaypoints = findBestWaypointSet(start, dest, coastalWaypoints, isLongDistance)
    if (bestWaypoints.isEmpty()) return fetchRoute(start, dest, packageName, "default")

    val ordered = orderWaypointsAlongRoute(start, dest, bestWaypoints)
    val sampled = if (!isLongDistance) {
        val nearest = ordered.minByOrNull { wp -> com.example.scenic_navigation.utils.GeoUtils.haversine(wp.latitude, wp.longitude, dest.latitude, dest.longitude) } ?: ordered.first()
        listOf(nearest)
    } else {
        sampleWaypoints(ordered, maxPoints = 5)
    }

    return generateRouteViaWaypoints(start, dest, packageName, sampled)
}
```

---

## 11 — RoutingService.generateMountainRouteViaWaypoints (mountain)

- File: `app/src/main/java/com/example/scenic_navigation/services/RoutingService.kt`
- Function: `generateMountainRouteViaWaypoints`
- Exact lines: 647–665 (inclusive)

Caption
: RoutingService.generateMountainRouteViaWaypoints — chooses mountain anchors, orders and samples them to produce scenic mountain routes.

Appendix blurb (copy-paste)
: generateMountainRouteViaWaypoints — For mountain routing the service selects mountain anchors close to the destination, orders them along the projected path, samples up to three anchors, and uses them as via-waypoints while enforcing an initial clear fraction to avoid immediate origin detours.

Kotlin snippet (lines 647–665)
```text
// RoutingService.generateMountainRouteViaWaypoints (lines 647-665)
private suspend fun generateMountainRouteViaWaypoints(start: GeoPoint, dest: GeoPoint, packageName: String): List<GeoPoint> {
    Log.d("RoutingService", "Finding best mountain route...")
    val bestWaypoints = findBestWaypointSet(start, dest, mountainWaypoints, false)
    if (bestWaypoints.isEmpty()) return fetchRoute(start, dest, packageName, "default")

    val ordered = orderWaypointsAlongRoute(start, dest, bestWaypoints)
    val sampled = sampleWaypoints(ordered, maxPoints = 3)

    return generateRouteViaWaypoints(start, dest, packageName, sampled)
}
```

---

## 12 — CurationMapper.map (UI → planner)

- File: `app/src/main/java/com/example/scenic_navigation/services/CurationMapper.kt`
- Feature: `CurationMapper.map`
- Exact lines: 15–253 (inclusive)

Caption
: CurationMapper.map — maps the UI `CurationIntent` into `routeType`, per-tag `poiBoosts`, and `tagFilters` used by the planner.

Appendix blurb (copy-paste)
: CurationMapper.map — Converts a user `CurationIntent` into a `PlannerCurationConfig` (routeType, poiBoosts, tagFilters). The planner multiplies POI scores by `1 + totalBoost` (clamped) and slightly deprioritizes non-matching tags, so curation choices meaningfully change candidate POI order before ML reranking.

Kotlin snippet (lines 15–253)
```text
// CurationMapper.map (lines 15-253)
object CurationMapper {
    fun map(intent: CurationIntent?, locale: String? = null): PlannerCurationConfig {
        if (intent == null) return PlannerCurationConfig("generic", emptyMap(), emptyList())

        val boosts = mutableMapOf<String, Double>()
        val filters = mutableListOf<String>()

        when (intent.seeing) {
            com.example.scenic_navigation.models.SeeingType.OCEANIC -> {
                filters.addAll(listOf("nature park", "historical site", "viewpoint", "tourist attraction", "beach", "coast", "bay", "cape", "museum", "restaurant", "cafe", "pasalubong store"))
                boosts["beach"] = 1.0
                boosts["coast"] = 0.6
                boosts["bay"] = 0.5
                boosts["cape"] = 0.5
                boosts["view"] = 0.5
                boosts["nature park"] = 0.8
                boosts["park"] = 0.7
                boosts["historical site"] = 0.5
                boosts["tourist attraction"] = 0.6
                boosts["museum"] = 0.5
                boosts["restaurant"] = 0.4
            }
            com.example.scenic_navigation.models.SeeingType.MOUNTAIN -> {
                filters.addAll(listOf("nature park", "historical site", "peak", "volcano", "viewpoint", "waterfall", "ridge", "tourist attraction", "museum"))
                boosts["peak"] = 1.0
                boosts["ridge"] = 0.6
                boosts["view"] = 0.5
                boosts["waterfall"] = 0.4
                boosts["nature park"] = 0.8
                boosts["historical site"] = 0.5
                boosts["museum"] = 0.4
            }
        }

        val routeType = when (intent.seeing) {
            com.example.scenic_navigation.models.SeeingType.OCEANIC -> "oceanic"
            com.example.scenic_navigation.models.SeeingType.MOUNTAIN -> "mountain"
        }

        return PlannerCurationConfig(routeType, boosts.toMap(), filters.toList())
    }
}
```

---

Screenshot checklist (concise)
- `MlInferenceEngine.kt` — lines 37–114 (init)
- `MlInferenceEngine.kt` — lines 241–284 (predictScoresBatch)
- `MlFeatureExtractor.kt` — lines 20–44 (buildPoiFeatures)
- `PoiReranker.kt` — lines 23–41 (rerank)
- `RecommendationsViewModel.kt` — lines 265–388 (fetchRecommendations)
- `ScenicRoutePlanner.kt` — lines 199–360 (fetchScenicPois)
- `tools/train_from_luzon.py` — lines 114–137 (generate_dataset)
- `Telemetry.kt` — lines 32–155 (init / logEvent / rotate)
- `FirestoreRepository.kt` — lines 34–103 (saveSelection / loadSelection)
- `RoutingService.kt` — lines 603–645 (coastal)
- `RoutingService.kt` — lines 647–665 (mountain)
- `CurationMapper.kt` — lines 15–253 (map)

---

If you'd like exports next, I can:
- Produce a single PDF with these snippets in order (one screenshot per snippet). I can lay out the markdown and convert to PDF.
- Render PNG images of each code snippet (monospace, light/dark theme) for insertion into the thesis.
- Produce a short script/instructions to automate extracting exact ranges as small text files for screenshot tooling.

Which of those would you like me to run next?
