# LAKBAY — Scenic Navigation (Developer README)

This README documents the LAKBAY (scenic_navigation) Android app for developers: architecture, the main APIs and SDKs used, how routing and POI discovery work, how the POI reranker and recommendation page function, and how the included machine-learning pieces are trained and consumed.

If you want a short orientation, start by reading:
- UI & routing: `app/src/main/java/com/example/scenic_navigation/ui/RouteFragment.kt`
- Routing & POI planner: `app/src/main/java/com/example/scenic_navigation/services/ScenicRoutePlanner.kt`
- App ViewModels: `app/src/main/java/com/example/scenic_navigation/viewmodel/RouteViewModel.kt`, `RecommendationsViewModel.kt`, `SharedRouteViewModel.kt`
- ML inference and reranker: `app/src/main/java/com/example/scenic_navigation/ml/MlInferenceEngine.kt`, `MlFeatureExtractor.kt`, `PoiReranker.kt`
- Training scripts and synthetic datasets: `tools/train_poi_reranker.py`, `tools/train_from_luzon.py`, `tools/luzon_dataset.csv`

---

Table of contents
- Project overview
- Dependencies & SDKs
- High-level architecture
- Routing flow (how routes are generated & selected)
- POI discovery & clustering
- POI reranker (ML) — inference and integration
- Discover/Recommendation page
- Machine learning: model, training, inference, and personalization (expanded)
- Data shapes / API contracts
- Build, test, and run instructions (developer)
- Retraining and deploying models
- Troubleshooting & notes
- Next steps and suggestions

---

Project overview

LAKBAY is an Android app that prioritizes scenic routes and points-of-interest (POIs) over the shortest/fastest path. It:
- Requests routing alternatives (OSRM-style) and scores them by counting and evaluating scenic POIs along each route.
- Uses a local dataset (CSV) and dynamic Overpass/POI queries to discover candidate POIs.
- Optionally reranks POIs using an on-device TensorFlow Lite model (poi_reranker.tflite).

The codebase is Kotlin-first and organized into UI, services, viewmodels, models, and an `ml` package for inference.

Dependencies & SDKs (where to verify)
- Version catalog: `gradle/libs.versions.toml` — shows pinned versions used by app module.
- App module dependencies: `app/build.gradle.kts`.

Key libraries (as found in the project files):
- AndroidX (core-ktx, appcompat, lifecycle, fragment, recyclerview)
- osmdroid (map display) — referenced in `RouteFragment.kt` and `app/build.gradle.kts`
- OkHttp (networking) — used in `ScenicRoutePlanner.kt` for API requests
- Google Play Services Location & Auth (GPS + optional Google Sign-In) — declared in `app/build.gradle.kts`
- Firebase (Auth and Firestore) — used for saving/reading user curation (`FirestoreRepository`) and referenced in Gradle BoM
- TensorFlow Lite (`org.tensorflow:tensorflow-lite`) — used in `MlInferenceEngine.kt` to run the reranker model
- Kotlin coroutines (background processing)

High-level architecture

- UI layer
  - `RouteFragment.kt` drives the primary map screen, handles inputs (curation dropdowns), shows route polylines and POI markers, and reacts to ViewModel state.
  - Additional UI components: `POIDetailBottomSheet`, preview adapters (horizontal RecyclerView), settings activity.

- ViewModels
  - `RouteViewModel` orchestrates planning: geocoding, route fetch (RoutingService), scenic POI fetching (ScenicRoutePlanner), and ML reranking via `PoiReranker`.
  - `RecommendationsViewModel` provides the Discover/Recommendations flow: loads candidate POIs (CSV + planner fallbacks), applies ML reranking and personalization, and exposes LiveData for UI.
  - `SharedRouteViewModel` shares route and recommendations state across fragments.

- Services
  - `ScenicRoutePlanner` implements POI discovery & scoring along a route. It queries local datasets, composes bbox queries per segment, caches Overpass results, applies category-based heuristics and curation boosts, then optionally runs the ML reranker.
  - `PoiService` reads local dataset CSV (used as descriptions / fallback). See `ScenicRoutePlanner` and `RecommendationsViewModel` for usage.
  - `RoutingService` (used by `RouteViewModel`) fetches actual route geometry (OSRM or similar). The app delegates route generation to a routing API and then scores alternatives.

Routing flow (how routes are generated and selected)

1. User requests a route from `RouteFragment` (UI) via `RouteViewModel.planRoute` or the curated shortcut `planRouteCurated`.
2. `RouteViewModel` performs geocoding (if needed) and invokes `RoutingService.fetchRoute(start, dest, packageName, routingMode, waypoints)` which returns a list of GeoPoints describing the route polyline.
   - `routingMode` can include scenic hints like "oceanic" or "mountain".
3. Once route alternatives or the chosen route are available, `RouteViewModel` calls `ScenicRoutePlanner.fetchScenicPois(route, packageName, routeType, curationIntent)` to find scenic POIs nearby the route.
4. `ScenicRoutePlanner` splits the route into segments, computes bbox queries per-segment, queries its local dataset (and/or Overpass if implemented), scores POIs by category heuristics, filters/deduplicates, and returns a ranked list.
5. `RouteViewModel` then applies a ML reranker `PoiReranker` to reorder candidates, stores final POIs in `_routePois` LiveData and updates route scoring metadata (distance, duration, scenic score).
6. UI (`RouteFragment`) listens to LiveData to draw the polyline, add start/destination markers, and add POI markers / clusters. The `RouteFragment` implements clustering (greedy centroid-based) and a decluster behavior at high zoom levels.

POI discovery & clustering

- Source data:
  - Primary: local dataset CSV `datasets/luzon_dataset.csv` loaded from assets (code expects an asset under `app/src/main/assets/datasets/luzon_dataset.csv`). The training scripts also reference `tools/luzon_dataset.csv`.
  - ScenicRoutePlanner can fetch POIs in bounding boxes and will cache results in an LRU cache keyed by bbox.

- Scoring & filtering:
  - `ScenicRoutePlanner.computeScoreFromCategory` maps categories and route type (oceanic/mountain/generic) to integer scenic scores.
  - Minimum scenic score threshold: `MIN_SCENIC_SCORE = 45`.
  - Curation boosts: `CurationMapper` is applied (if a curation intent exists) to increase scores for certain tags.

- Clustering:
  - `RouteFragment.updateMarkers` implements a simple greedy clustering algorithm: group POIs whose centroids are within `epsMeters` (configurable via preferences) and produce cluster marker bitmaps using `createClusterIcon(count, avgScore)`.
  - Declustering is applied when zoomed-in (zoom >= 15) to show individual POIs and a horizontal preview list.

POI reranker (ML) — inference and integration

- Files:
  - In-app inference: `app/src/main/java/com/example/scenic_navigation/ml/MlInferenceEngine.kt`.
  - Feature extraction: `app/src/main/java/com/example/scenic_navigation/ml/MlFeatureExtractor.kt`.
  - Reranker wrapper: `app/src/main/java/com/example/scenic_navigation/ml/PoiReranker.kt`.

- Feature order
  - The model expects a feature vector in this exact order (constant in code):
    ["distNorm", "timeSin", "timeCos", "cat_food", "cat_sight", "scenicScore", "hasMunicipality"]
  - This order is enforced in the trainer scripts and `MlFeatureExtractor`.

- MlInferenceEngine responsibilities
  - Loads a TFLite model from assets (default `models/poi_reranker_from_luzon.tflite`) with fallback discovery of any `.tflite` under `assets/models/`.
  - Inspects input/output tensors for quantization parameters and handles both floating and quantized models.
  - Methods provided: `predictScore(features: FloatArray): Float`, `predictScoreAsync`, and `predictScoresBatch` (batch inference to reduce overhead).
  - If the TFLite interpreter cannot be initialized, a deterministic `heuristicScore` fallback is used so the app still behaves sensibly without a model.

- PoiReranker
  - Calls `MlFeatureExtractor.buildPoiFeatures` for each POI (computes normalized distance, time-of-day sin/cos, category flags, normalized scenic score, municipality flag).
  - Runs batch inference via `MlInferenceEngine.predictScoresBatch` and blends the ML score with the app's handcrafted scenic score. The blend factor (ML vs scenic) is controlled by a constant `ML_BLEND_ALPHA` in `PoiReranker`.
  - Optionally includes a user preference boost via `UserPreferenceStore` when provided.

Discover/Recommendation page (how it works)

- `RecommendationsViewModel` is the central logic for Discover/Recommendations UI:
  - Loads candidate POIs once using `loadCandidates()` which reads `datasets/luzon_dataset.csv` from assets. If the CSV is missing or empty, it falls back to `ScenicRoutePlanner.fetchScenicPois` seeded near the user's current location.
  - Sorts candidates by simple category priorities and then calls `PoiReranker.rerank(...)` to produce a personalized ordering.
  - Applies curated POI boosts (a persisted curated set in shared preferences) so user-curated favorites surface higher.
  - Provides filters (distance radius, preferred categories) and will relax constraints if necessary.


# Machine learning: model, training, inference, and personalization (expanded)

This section explains in detail how the reranker model is trained and exported, what features the app builds for inference, how the app runs the model, and precisely how personalization and user interactions affect POI boosting.

Files and scripts to inspect
- Inference and feature extraction:
  - `app/src/main/java/com/example/scenic_navigation/ml/MlInferenceEngine.kt`
  - `app/src/main/java/com/example/scenic_navigation/ml/MlFeatureExtractor.kt`
  - `app/src/main/java/com/example/scenic_navigation/ml/PoiReranker.kt`
- Trainers and data:
  - `tools/train_poi_reranker.py` (synthetic trainer)
  - `tools/train_from_luzon.py` (CSV-seeded trainer)
  - `tools/luzon_dataset.csv` (seed POI data)
- Personalization stores and UI hooks:
  - `app/src/main/java/com/example/scenic_navigation/services/UserPreferenceStore.kt`
  - `app/src/main/java/com/example/scenic_navigation/FavoriteStore.kt`
  - `app/src/main/java/com/example/scenic_navigation/services/CurationMapper.kt`
  - `app/src/main/java/com/example/scenic_navigation/viewmodel/RecommendationsViewModel.kt`

Feature pipeline (exact transforms)

The model expects a single-row float vector with the features in this exact order:
1. distNorm — normalized distance from user to POI (distance_meters / 50_000, clamped to 0..1)
2. timeSin — sin(2π * hour_of_day / 24)
3. timeCos — cos(2π * hour_of_day / 24)
4. cat_food — binary flag (1 if POI category tokens indicate a food place)
5. cat_sight — binary flag (1 if POI category tokens indicate sightseeing)
6. scenicScore — scenic score normalized to [0,1] in the feature vector (app divides stored scenic score by 250f)
7. hasMunicipality — binary flag (1 if the POI record contains a municipality)

App-side code: `MlFeatureExtractor.buildPoiFeatures` performs these transforms. Important implementation details:
- Distances are computed with haversine and capped at 50,000 meters (50 km).
- Time is computed from `System.currentTimeMillis()` and mapped to sine/cosine for periodic representation.
- Category flags are tokenized and checked for common keywords.
- scenicScore is normalized by dividing by 250f and then clamped to [0,1]. This mirrors the synthetic trainer where scenic values are in [0,1].

Training & exported artifacts

- `tools/train_poi_reranker.py` generates synthetic training data and trains an MLP (configurable hidden sizes). It writes:
  - `poi_reranker.tflite` (TFLite model)
  - `feature_stats.json` (per-feature min/max/mean/std used for diagnostics)

- `tools/train_from_luzon.py` seeds the synthetic impression generation from `tools/luzon_dataset.csv`, constructs the same feature order as above, trains an MLP, and writes:
  - `poi_reranker_from_luzon.tflite`
  - `feature_stats_from_luzon.json`

- Both scripts support exporting a Keras `.keras` model for debugging and optional post-training quantization using a representative dataset.

- The project build includes a `copyModelAssets` task in `app/build.gradle.kts` that copies model files from a tools location into `app/src/main/assets/models/` during preBuild. This is convenient for CI.

MlInferenceEngine runtime behavior

- Loads the TFLite model from `assets/models/<model>.tflite` (default path: `models/poi_reranker_from_luzon.tflite`). If that path doesn't exist, it will scan `assets/models/` for any `.tflite` and use the first one.
- Inspects input/output tensor types and quantization parameters (scale and zeroPoint) so it can run both float and quantized (int8/uint8) models.
- Exposes these APIs:
  - `predictScore(features: FloatArray): Float` — synchronous single-row inference with fallback.
  - `predictScoreAsync(features: FloatArray): Float` — suspend wrapper for coroutine use.
  - `predictScoresBatch(featuresBatch: List<FloatArray>): FloatArray` — batch inference to reduce interpreter overhead when scoring many POIs.
- If model loading or inference fails the engine falls back to `heuristicScore` (deterministic linear rule combining distance, scenic, and category flags) so that the app continues to function even without a working model.

PoiReranker blending math (exact code)

`PoiReranker.rerank(...)` performs these steps (literal behavior):
1. Build feature batch using `MlFeatureExtractor.buildPoiFeatures` (scenic normalized inside the features as described).
2. Run `MlInferenceEngine.predictScoresBatch(featuresBatch)` to get ML scores (floating values expected around 0..1 for this setup).
3. Compute a blended score per POI:
   - ml = model score (0..1)
   - scenic = `poi.scenicScore` (note: this is the stored app scenic score; see caveat below)
   - prefScore = `UserPreferenceStore.getPreferenceScore(category)` (0..1)
   - blended = ML_BLEND_ALPHA * ml + (1 - ML_BLEND_ALPHA) * scenic
   - finalScore = blended + PREF_WEIGHT * prefScore
4. Sort POIs by finalScore descending and return.

Constants in code:
- ML_BLEND_ALPHA = 0.75f (weight given to ML model vs scenic signal)
- PREF_WEIGHT = 0.15f (additive weight for user preference score)

Status: scenic normalization is already implemented in code

The codebase already normalizes the stored `poi.scenicScore` consistently for both training and inference. `MlFeatureExtractor.buildPoiFeatures` divides the app's stored scenic score by 250f to produce the model input, and `PoiReranker` also applies the same normalization when blending the ML output with the scenic signal. Concretely `PoiReranker` does:

  val scenic = poi.scenicScore ?: 0f
  val scenicNorm = (scenic / 250f).coerceIn(0f, 1f) // normalized to model scale
  val blended = ML_BLEND_ALPHA * mlScore + (1f - ML_BLEND_ALPHA) * scenicNorm

So the numeric worked example below already reflects a correct normalized blend; no immediate code change is required here. If you later change the training transforms (for example, standardizing by mean/std from `feature_stats.json`), ensure the same transforms are applied at inference time.

Personalization: how user interactions affect ranking

There are three persistent interaction channels and several immediate UI-driven boosts:

1) Explicit curation intent (UI selection of Seeing + Activity)
- `CurationMapper.map(intent)` converts user choices into:
  - `routeType` ("oceanic" / "mountain" / "generic")
  - `poiBoosts` (map of tag -> boost value, e.g. "beach" -> 1.0)
  - `tagFilters` (list of tags to prefer)
- `ScenicRoutePlanner` applies these by multiplying the base POI score by `multiplier = (1.0 + totalBoost).coerceAtMost(3.0) * (matchesFilter ? 1.0 : 0.85)` — in practice a boost of 1.0 doubles the POI score before ML reranking.

2) Curated favorites (hard priority)
- `RecommendationsViewModel` persists a curated set in shared preferences (CURATED_KEY). When producing recommendations it partitions reranked items into curated + others and places `curated` first, preserving internal order. This is a hard UI-level priority.
- `FavoriteStore` persists the actual favorite POI JSON for the Favorites UI.

3) Implicit preference counts (soft personalization)
- `UserPreferenceStore.incrementCategory(category)` is called when the user likes/curates a POI; counts are stored in `user_preferences` SharedPreferences.
- `UserPreferenceStore.getPreferenceScore(category, k=5f)` returns `count / (count + k)` producing a smooth score in [0,1]. Example: count=5 -> 0.5.
- `PoiReranker` adds `PREF_WEIGHT * prefScore` to the final score (PREF_WEIGHT = 0.15). So even a maxed prefScore (close to 1.0) yields only a small additive boost (~0.15) — enough to bias but not dominate.

UI / short-term boosts
- Category chips and discovery filters (Recommendations UI) are translated into `poiBoosts` and `tagFilters` via `mapCategoriesToPlannerFilters(...)` and re-applied at recommendation time to boost and partition the results.

Worked numeric example (before and after recommended fix)

Inputs (example):
- POI A: distance = 2,000 m → distNorm = 0.04
- scenicScore stored in app = 120
- category contains sightseeing → cat_sight = 1
- ML predicts mlScore = 0.8
- User pref count = 5 -> prefScore = 5/(5+5) = 0.5

Current code (inconsistent, scenic is unnormalized):
- blended = 0.75 * 0.8 + 0.25 * 120 = 0.6 + 30 = 30.6
- finalScore = 30.6 + 0.15 * 0.5 = 30.675
=> scenic overwhelms the ML signal.

After applying the recommended normalization (scenicNorm = scenicScore / 250 = 0.48):
- blended = 0.75 * 0.8 + 0.25 * 0.48 = 0.6 + 0.12 = 0.72
- finalScore = 0.72 + 0.15 * 0.5 = 0.795
=> ML, scenic and preferences all meaningfully contribute.

Retraining and validating a new model (commands)

1) Create a Python venv and install trainer dependencies (PowerShell):

```powershell
py -3.11 -m venv .venv; .\.venv\Scripts\Activate.ps1
python -m pip install --upgrade pip
pip install -r tools\requirements.txt
```

2) Train a model (synthetic):

```powershell
python tools\train_poi_reranker.py --out-assets app\src\main\assets\models --epochs 10 --samples 20000
```

3) Or train from the Luzon CSV seed:

```powershell
python tools\train_from_luzon.py --out-assets app\src\main\assets\models --samples 10000 --epochs 6 --quantize
```

4) Smoke-test assets:

```powershell
python tools\smoke_tflite.py
```

5) Build the Android app (Gradle will include assets):

```powershell
./gradlew assembleDebug
```

Unit testing and sanity checks
- Add a unit test for `MlFeatureExtractor` that constructs a deterministic POI/time and asserts the expected feature vector numeric values.
- Add a unit test for `PoiReranker` to verify blending behavior: craft a fake MlInference implementation that returns fixed ML scores, and assert that final ordering and numeric finalScore follow the blending formula.

Suggested code improvements (prioritized)
1. Fix scenic normalization in `PoiReranker` (minimal, high impact): divide `poi.scenicScore` by 250f before blending.
2. Optionally read `feature_stats.json` and apply standardization (mean/std) if you switch to training with standardized features; keep the same transforms in training and inference.
3. Consider time-decayed preference counts (recent likes > older ones) so personalization adapts.
4. Add telemetry (opt-in) to collect impressions and chosen POIs to create a real label set for retraining.


# Telemetry (local-only)

This project includes a minimal, opt-in telemetry helper that currently stores events locally on the device for debugging and QA. The telemetry implementation intentionally keeps data on-device (no automatic upload) and includes a small rotation policy so logs don't grow without bound.

Key points
- Implementation: `app/src/main/java/com/example/scenic_navigation/services/Telemetry.kt`.
- Initialization: Telemetry is initialized at app startup in `ScenicNavigationApplication.onCreate()` so it is available throughout the app.
- Opt-in: Telemetry respects the settings toggle (`SettingsStore` key `telemetry_enabled`). Events are only written when the user enables telemetry in the app Settings.
- Local storage path (on device):
  - `filesDir/telemetry/events.log` — full path on the device is typically:
    `/data/data/com.example.scenic_navigation/files/telemetry/events.log`
- Rotation policy:
  - The logger rotates when `events.log` exceeds 5 MB (default). Rotated files are kept as `events.log.1`, `events.log.2`, `events.log.3` (default keeps the 3 most recent archives).
  - Rotation prevents unbounded disk growth while preserving recent history for debugging.

Events currently logged
- `recommendation_impression` (when recommendations are produced) — includes a `request_id` and top candidate keys.
- `poi_detail_open` (when the POI detail bottom sheet is opened) — includes POI name/category/municipality.
- `favorite_added` / `favorite_removed` (when a user saves or unsaves a POI).

Sample event (one JSON object per line):

```json
{ "event_type": "poi_detail_open", "event_time": 1708872000000, "params": { "poi_name": "Mount Example", "category": "viewpoint", "municipality": "ExampleTown" } }
```

How to retrieve logs (developer / QA)
- For debug builds you can pull the file from a connected device or emulator using `adb` (PowerShell example):

```powershell
# Pull current events.log to the host
adb exec-out run-as com.example.scenic_navigation cat files/telemetry/events.log > events.log

# Pull rotated files if present (copy to /sdcard first when run-as unavailable):
adb shell "run-as com.example.scenic_navigation cp files/telemetry/events.log.1 /sdcard/events.log.1"
adb pull /sdcard/events.log.1 .
adb shell "run-as com.example.scenic_navigation rm /sdcard/events.log.1"
```

- If `run-as` fails (non-debuggable build or device restrictions), use Android Studio's Device File Explorer to browse `data/data/com.example.scenic_navigation/files/telemetry/` and download `events.log` (and rotated files) manually.

Privacy and operational notes
- Telemetry is opt-in: do not collect or upload telemetry from users who have not enabled it.
- Avoid logging high-precision location or personally-identifying data unless you have explicit consent. Consider hashing or pseudonymizing user identifiers and bucketing/coarsening locations for privacy.
- Rotated logs are still local-only; if you later implement a flush/upload path, be mindful of consent, security, and rate-limiting.

Next steps (optional)
- Add an "Export telemetry" action in Settings that zips and shares the local `telemetry` folder for QA.
- Implement a secure, batched uploader (client → server) that only sends data with user consent. Prefer a server-side gateway (Cloud Function) to validate, sample, and write events to your analytics backend (Firestore/BigQuery).
- Extend telemetry to include per-candidate feature snapshots (the model input) if you intend to use logs for offline ML training; that simplifies building training rows later.

---

If you want, I can now:
- Apply the recommended normalization fix to `PoiReranker` and add a unit test (I can implement and run it),
- Run a quick validation build of the Android app,
- Or add a minimal telemetry/event schema for collecting impressions.

Tell me which you want me to do next.

Included code snippets (oceanic / mountain routing + curation)

Below are compact, highlighted excerpts of the key functions you asked to include in the README. These are the exact locations in the codebase where the behavior is implemented:
- Oceanic (coastal) and mountain route logic: `app/src/main/java/com/example/scenic_navigation/services/RoutingService.kt`
- Curation mapping: `app/src/main/java/com/example/scenic_navigation/services/CurationMapper.kt`

1) Oceanic / Coastal route selection (excerpt)

```kotlin
// Source: RoutingService.generateCoastalRouteViaWaypoints
private suspend fun generateCoastalRouteViaWaypoints(start: GeoPoint, dest: GeoPoint, packageName: String): List<GeoPoint> {
    Log.d("RoutingService", "Finding best coastal route...")
    val directDistance = start.distanceToAsDouble(dest)
    val isLongDistance = directDistance > 300_000

    // For very short routes (<30km), don't use coastal waypoints to avoid unnecessary detours
    if (directDistance < 30_000) {
        Log.d("RoutingService", "Route is too short (${"%.0f".format(directDistance)}m) for coastal waypoints. Using direct route.")
        return fetchRoute(start, dest, packageName, "default")
    }

    // Pick waypoint set based primarily on proximity to the destination
    val bestWaypoints = findBestWaypointSet(start, dest, coastalWaypoints, isLongDistance)

    if (bestWaypoints.isEmpty()) {
        Log.w("RoutingService", "No suitable coastal waypoints found. Falling back to direct route.")
        return fetchRoute(start, dest, packageName, "default")
    }

    val ordered = orderWaypointsAlongRoute(start, dest, bestWaypoints)
    val sampled = if (!isLongDistance) {
        // For short/medium oceanic routes pick a single coastal anchor nearest the destination
        if (ordered.isEmpty()) emptyList<GeoPoint>() else {
            val nearest = ordered.minByOrNull { wp ->
                com.example.scenic_navigation.utils.GeoUtils.haversine(wp.latitude, wp.longitude, dest.latitude, dest.longitude)
            } ?: ordered.first()
            listOf(nearest)
        }
    } else {
        // For long oceanic trips sample up to 5 anchors so the route follows the coast
        sampleWaypoints(ordered, maxPoints = 5)
    }

    return generateRouteViaWaypoints(start, dest, packageName, sampled)
}
```

Notes:
- Coastal waypoint sets are stored in `coastalWaypoints` and chosen with `findBestWaypointSet` which favors sets whose anchors are close to the destination and reasonably near the projected route.
- Short oceanic trips avoid detours; long trips sample multiple coastal anchors to create genuinely scenic coastal paths.

2) Mountain route selection (excerpt)

```kotlin
// Source: RoutingService.generateMountainRouteViaWaypoints
private suspend fun generateMountainRouteViaWaypoints(start: GeoPoint, dest: GeoPoint, packageName: String): List<GeoPoint> {
    Log.d("RoutingService", "Finding best mountain route...")
    val bestWaypoints = findBestWaypointSet(start, dest, mountainWaypoints, false)

    if (bestWaypoints.isEmpty()) {
        Log.w("RoutingService", "No suitable mountain waypoints found. Falling back to direct route.")
        return fetchRoute(start, dest, packageName, "default")
    }

    // Order mountain waypoints along the route then sample down to a small number
    val ordered = orderWaypointsAlongRoute(start, dest, bestWaypoints)
    val sampled = sampleWaypoints(ordered, maxPoints = 3)

    return generateRouteViaWaypoints(start, dest, packageName, sampled)
}
```

Notes:
- Mountain anchor sets are defined in `mountainWaypoints` and chosen similarly to coastal sets.
- Sampled waypoints are limited to avoid overly complex routes and keep OSRM requests practical.

3) Curation mapping (excerpt)

```kotlin
// Source: CurationMapper.map
object CurationMapper {
    fun map(intent: CurationIntent?, locale: String? = null): PlannerCurationConfig {
        if (intent == null) return PlannerCurationConfig("generic", emptyMap(), emptyList())

        val boosts = mutableMapOf<String, Double>()
        val filters = mutableListOf<String>()

        when (intent.seeing) {
            SeeingType.OCEANIC -> {
                filters.addAll(listOf("nature park", "historical site", "viewpoint", "beach", "coast", "museum", "restaurant"))
                boosts["beach"] = 1.0
                boosts["coast"] = 0.6
                boosts["nature park"] = 0.8
                boosts["park"] = 0.7
            }
            SeeingType.MOUNTAIN -> {
                filters.addAll(listOf("nature park", "peak", "viewpoint", "waterfall", "ridge"))
                boosts["peak"] = 1.0
                boosts["ridge"] = 0.6
                boosts["nature park"] = 0.8
            }
        }

        // Activity-based boosts (sightseeing, shop-and-dine, cultural, etc.) are applied below
        // ...existing activity->boost logic...

        val routeType = when (intent.seeing) {
            SeeingType.OCEANIC -> "oceanic"
            SeeingType.MOUNTAIN -> "mountain"
        }

        return PlannerCurationConfig(routeType, boosts.toMap(), filters.toList())
    }
}
```

Notes:
- `CurationMapper.map` converts a UI `CurationIntent` into three planner inputs: `routeType` (oceanic/mountain/generic), `poiBoosts` (tag -> multiplier), and `tagFilters` (preferred tags). The planner multiplies POI scores by `1 + totalBoost` to surface curated tags before ML reranking.

Where to look next
- Full implementations:
  - Oceanic & mountain waypoint sets + routing helpers: `app/src/main/java/com/example/scenic_navigation/services/RoutingService.kt`
  - Curation mapping (complete activity->tag boosts and uniqueness processing): `app/src/main/java/com/example/scenic_navigation/services/CurationMapper.kt`

These snippets show the planner-level decisions controlling scenic routing and how UI curation choices translate into concrete boosts and filters applied before returned candidate POIs are reranked.
