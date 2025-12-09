package com.example.scenic_navigation.services

import android.content.Context
import android.util.Log
import com.example.scenic_navigation.models.ScenicPoi
import com.example.scenic_navigation.utils.GeoUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.osmdroid.util.GeoPoint
import java.util.LinkedHashMap
import java.util.Locale
import kotlin.math.pow

/**
 * Service for scenic route planning and scoring
 */
class ScenicRoutePlanner(
    private val context: Context? = null,
    // optional overrides for clustering params (meters, minPts). If null, use Config defaults.
    clusterEpsMeters: Double? = null,
    clusterMinPts: Int? = null
) {
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .connectionPool(okhttp3.ConnectionPool(8, 5, java.util.concurrent.TimeUnit.MINUTES))
            .build()
    }

    // (Curation boosting applied at planner level)

    companion object {
        // Minimum scenic score threshold - filter out POIs below this value
        private const val MIN_SCENIC_SCORE = 35

        // LRU cache for Overpass queries at specific rounded locations
        private val poiQueryCache: MutableMap<String, List<com.example.scenic_navigation.models.ScenicPoi>> =
            java.util.Collections.synchronizedMap(
                object : java.util.LinkedHashMap<String, List<com.example.scenic_navigation.models.ScenicPoi>>(128, 0.75f, true) {
                    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<com.example.scenic_navigation.models.ScenicPoi>>?): Boolean {
                        return size > 300
                    }
                }
            )
        // Smart POI generation config
        // Tags which are allowed to have synthesized names when `name` is missing
        private val ALLOWED_SYNTHESIS_TAGS = setOf(
            "viewpoint",
            "waterfall",
            "peak",
            "saddle",
            "bay",
            "cape",
            "ridge",
            "nature_reserve",
            "park",
            "attraction",
            "historic",
            "museum"
        )

        // Whitelist of primary tags we consider for POIs (anything outside will be skipped)
        private val POI_TAG_WHITELIST = setOf(
            "viewpoint",
            "park",
            "attraction",
            "picnic_site",
            "camp_site",
            "museum",
            "historic",
            "waterfall",
            "peak",
            "bay",
            "cape",
            "coastline",
            "beach",
            "nature_reserve",
            "wood",
            "ridge"
        )

        // Blend weight for generic query contribution (0.0..1.0)
        private const val GENERIC_BLEND_WEIGHT = 0.5
        // Preserve companion-level defaults as fallbacks
        private const val CLUSTER_EPS_METERS = 2000.0
        private const val CLUSTER_MIN_PTS = 3
    }

    // Instance-level cluster parameters (resolved from constructor or Config)
    private val clusterEpsMeters: Double = clusterEpsMeters ?: com.example.scenic_navigation.config.Config.DEFAULT_CLUSTER_EPS_METERS
    private val clusterMinPts: Int = clusterMinPts ?: com.example.scenic_navigation.config.Config.DEFAULT_CLUSTER_MIN_PTS

    // PoiService used to access the local dataset (luzon CSV)
    private val poiService: com.example.scenic_navigation.services.PoiService by lazy {
        com.example.scenic_navigation.services.PoiService(context)
    }

    private fun deviceLocaleTag(): String? {
        return try {
            val cfg = context?.resources?.configuration
            if (cfg == null) return Locale.getDefault().toString()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                cfg.locales?.get(0)?.toString() ?: Locale.getDefault().toString()
            } else {
                cfg.locale?.toString() ?: Locale.getDefault().toString()
            }
        } catch (e: Exception) {
            Locale.getDefault().toString()
        }
    }

    private fun computeScoreFromCategory(cat: String?, routeType: String, isNearCoast: Boolean): Int {
        val type = cat?.lowercase(Locale.getDefault()) ?: "unknown"
        return when (routeType) {
            "oceanic" -> if (isNearCoast) when {
                type.contains("beach") -> 120
                type.contains("coast") -> 110
                type.contains("bay") -> 105
                type.contains("cape") -> 100
                type.contains("view") -> 95
                type.contains("nature") -> 85
                type.contains("water") -> 75
                type.contains("waterfall") -> 70
                type.contains("park") -> 60
                type.contains("attraction") -> 50
                else -> 20
            } else 20
            "mountain" -> when {
                type.contains("peak") -> 120
                type.contains("volcano") -> 115
                type.contains("saddle") -> 105
                type.contains("view") -> 100
                type.contains("ridge") -> 95
                type.contains("nature") -> 90
                type.contains("wood") -> 80
                type.contains("waterfall") -> 75
                type.contains("park") -> 65
                type.contains("attract") || type.contains("attraction") -> 55
                else -> 20
            }
            else -> when {
                type.contains("view") -> 110
                type.contains("peak") -> 100
                type.contains("nature") -> 95
                type.contains("beach") -> 90
                type.contains("park") -> 85
                type.contains("wood") -> 70
                type.contains("historic") -> 65
                type.contains("museum") -> 60
                type.contains("hotel") -> 25
                type.contains("camp") -> 40
                type.contains("picnic") -> 35
                else -> 20
            }
        }
    }

    /**
     * Fetch scenic POIs along a route with dynamic sampling based on route length
     */
    suspend fun fetchScenicPois(
        routePoints: List<GeoPoint>,
        packageName: String,
        routeType: String = "generic",
        curationIntent: com.example.scenic_navigation.models.CurationIntent? = null,
        onStatusUpdate: ((String) -> Unit)? = null
    ): List<ScenicPoi> = withContext(Dispatchers.IO) {
        if (routePoints.isEmpty()) return@withContext emptyList()

        val length = GeoUtils.computeRouteLength(routePoints)

        // Determine sampling parameters based on route length
        val spacing = when {
            length > 400_000 -> 20_000.0
            length > 300_000 -> 15_000.0
            length > 200_000 -> 12_000.0
            length > 120_000 -> 10_000.0
            length > 80_000 -> 8_000.0
            length > 40_000 -> 6_000.0
            else -> 4_000.0
        }

        val maxSamples = when {
            length > 400_000 -> 40
            length > 200_000 -> 32
            length > 120_000 -> 28
            else -> 20
        }

        // Create segment centers for batched bbox/around queries so we run fewer Overpass requests
        val segmentLengthMeters = 50_000.0 // target ~50km per segment
        val segments = maxOf(1, (length / segmentLengthMeters).toInt())
        val scenicPois = mutableListOf<ScenicPoi>()
        val semaphore = Semaphore(4) // slightly higher concurrency for segment queries
        val mutex = Mutex()

        // Build list of segment point slices (evenly split by index)
        val segmentsList = mutableListOf<List<GeoPoint>>()
        for (s in 0 until segments) {
            val startIdx = (s * routePoints.size) / segments
            val endIdx = (((s + 1) * routePoints.size) / segments).coerceAtMost(routePoints.size - 1)
            val sub = routePoints.subList(startIdx, endIdx.coerceAtLeast(startIdx))
            if (sub.isNotEmpty()) segmentsList.add(sub)
        }

        supervisorScope {
            segmentsList.forEachIndexed { idx, sub ->
                launch {
                    semaphore.withPermit {
                        try {
                            onStatusUpdate?.invoke("Scenic segment ${idx + 1}/${segmentsList.size}…")
                        } catch (_: Exception) {}

                        // Compute bbox for this segment and add a small padding
                        val latitudes = sub.map { it.latitude }
                        val longitudes = sub.map { it.longitude }
                        val minLat = latitudes.minOrNull() ?: sub.first().latitude
                        val maxLat = latitudes.maxOrNull() ?: sub.first().latitude
                        val minLon = longitudes.minOrNull() ?: sub.first().longitude
                        val maxLon = longitudes.maxOrNull() ?: sub.first().longitude

                        // Padding in degrees (approx): convert ~radius meters to degrees roughly
                        val padDegrees = 0.02 // ~2km padding, coarse but sufficient
                        val bboxMinLat = (minLat - padDegrees)
                        val bboxMaxLat = (maxLat + padDegrees)
                        val bboxMinLon = (minLon - padDegrees)
                        val bboxMaxLon = (maxLon + padDegrees)

                        val cacheKey = "bbox_${"%.3f".format(bboxMinLat)}_${"%.3f".format(bboxMinLon)}_${"%.3f".format(bboxMaxLat)}_${"%.3f".format(bboxMaxLon)}_${routeType}"
                        val cached = poiQueryCache[cacheKey]
                        val pois = if (cached != null) cached else {
                            val fresh = fetchScenicPoisInBBox(bboxMinLat, bboxMinLon, bboxMaxLat, bboxMaxLon, packageName, routeType, sub, curationIntent)
                            if (fresh.isNotEmpty()) poiQueryCache[cacheKey] = fresh
                            fresh
                        }

                        if (pois.isNotEmpty()) {
                            mutex.lock()
                            try {
                                scenicPois.addAll(pois)
                            } finally {
                                mutex.unlock()
                            }
                        }
                    }
                }
                }
            }
        
        // De-duplicate and sort by score
        val dedup = LinkedHashMap<String, ScenicPoi>()
        for (p in scenicPois) {
            val key = "${"%.5f".format(p.lat)}_${"%.5f".format(p.lon)}_${p.name}"
            val existing = dedup[key]
            if (existing == null || p.score > existing.score) {
                dedup[key] = p
            }
        }

        var list = dedup.values.toMutableList()
        list.sortByDescending { it.score }

        // Apply curation-based boosts and filters (if provided)
        try {
            val cfg = com.example.scenic_navigation.services.CurationMapper.map(curationIntent, deviceLocaleTag())
            if (cfg.poiBoosts.isNotEmpty() || cfg.tagFilters.isNotEmpty()) {
                list = list.map { poi ->
                    var totalBoost = 0.0
                    val t = poi.type.lowercase(Locale.getDefault())
                    for ((k, v) in cfg.poiBoosts) {
                        if (t.contains(k)) totalBoost += v
                    }
                    // If tagFilters are present and poi doesn't match any, deprioritize slightly
                    val matchesFilter = if (cfg.tagFilters.isEmpty()) true else cfg.tagFilters.any { pred ->
                        pred.value.isBlank() || t.contains(pred.value.lowercase(Locale.getDefault()))
                    }
                    val multiplier = (1.0 + totalBoost).coerceAtMost(3.0) * if (matchesFilter) 1.0 else 0.85
                    poi.copy(score = (poi.score * multiplier).toInt())
                }.toMutableList()
                list.sortByDescending { it.score }
            }
        } catch (_: Exception) {}

        // Return top results (capped). We intentionally avoid extra densification rounds
        // here to keep route planning responsive; dataset merging and segment batching
        // supply adequate coverage without extra network calls.
        return@withContext if (list.size > 200) list.subList(0, 200) else list
    }

    private suspend fun fetchScenicPoisAtPoint(
        point: GeoPoint,
        radius: Int,
        packageName: String,
        routeType: String = "generic",
        routePoints: List<GeoPoint>,
        curationIntent: com.example.scenic_navigation.models.CurationIntent? = null
    ): List<ScenicPoi> = withContext(Dispatchers.IO) {
        val lat = point.latitude
        val lon = point.longitude

        // Build curation-aware type-specific query
        val cfg = com.example.scenic_navigation.services.CurationMapper.map(curationIntent, deviceLocaleTag())

        fun buildPredicateLines(prefix: String): String {
            if (cfg.tagFilters.isEmpty()) return ""
            val sb = StringBuilder()
            for (p in cfg.tagFilters) {
                val clause = when {
                    p.value.isBlank() -> "[${p.key}]"
                    p.value.startsWith("~") -> {
                        // value contains a regex (without leading ~ in Overpass syntax we keep the string after ~)
                        val pat = p.value.removePrefix("~")
                        "[${p.key}~\"$pat\"]"
                    }
                    else -> "[${p.key}=${p.value}]"
                }
                sb.append("  node(around:$radius,$lat,$lon)$clause;\n")
                sb.append("  way(around:$radius,$lat,$lon)$clause;\n")
                sb.append("  relation(around:$radius,$lat,$lon)$clause;\n")
            }
            return sb.toString()
        }

        // Type-specific query
        val qlType = when (routeType) {
            "oceanic" -> """
[out:json][timeout:15];
(
  node(around:$radius,$lat,$lon)[natural=beach];
  node(around:$radius,$lat,$lon)[natural=coastline];
  node(around:$radius,$lat,$lon)[natural=bay];
  node(around:$radius,$lat,$lon)[natural=cape];
  node(around:$radius,$lat,$lon)[tourism=viewpoint];
  node(around:$radius,$lat,$lon)[leisure=nature_reserve];
  node(around:$radius,$lat,$lon)[leisure=park];
  node(around:$radius,$lat,$lon)[tourism=attraction];
  node(around:$radius,$lat,$lon)[tourism=picnic_site];
  node(around:$radius,$lat,$lon)[natural=water];
  node(around:$radius,$lat,$lon)[waterway=waterfall];
  way(around:$radius,$lat,$lon)[natural=beach];
  way(around:$radius,$lat,$lon)[natural=coastline];
  way(around:$radius,$lat,$lon)[natural=bay];
);
out center 60;
""".trimIndent()
            "mountain" -> """
[out:json][timeout:15];
(
  node(around:$radius,$lat,$lon)[natural=peak];
  node(around:$radius,$lat,$lon)[natural=saddle];
  node(around:$radius,$lat,$lon)[natural=volcano];
  node(around:$radius,$lat,$lon)[natural=wood];
  node(around:$radius,$lat,$lon)[tourism=viewpoint];
  node(around:$radius,$lat,$lon)[leisure=nature_reserve];
  node(around:$radius,$lat,$lon)[natural=ridge];
  node(around:$radius,$lat,$lon)[tourism=attraction];
  node(around:$radius,$lat,$lon)[leisure=park];
  node(around:$radius,$lat,$lon)[waterway=waterfall];
  way(around:$radius,$lat,$lon)[natural=wood];
);
out center 60;
""".trimIndent()
            else -> """
[out:json][timeout:15];
(
  node(around:$radius,$lat,$lon)[tourism=viewpoint];
  node(around:$radius,$lat,$lon)[leisure=park];
  node(around:$radius,$lat,$lon)[natural=peak];
  node(around:$radius,$lat,$lon)[natural=wood];
  node(around:$radius,$lat,$lon)[tourism=attraction];
  node(around:$radius,$lat,$lon)[natural=beach];
  node(around:$radius,$lat,$lon)[leisure=nature_reserve];
  node(around:$radius,$lat,$lon)[tourism=picnic_site];
  node(around:$radius,$lat,$lon)[tourism=camp_site];
  node(around:$radius,$lat,$lon)[tourism=hotel];
  node(around:$radius,$lat,$lon)[tourism=museum];
  node(around:$radius,$lat,$lon)[historic];
);
out center 60;
""".trimIndent()
        }

        // Generic attractions query (lighter)
        val extraPredLinesForGeneric = buildPredicateLines("")

        var qlGeneric = """
[out:json][timeout:12];
(
  node(around:$radius,$lat,$lon)[tourism=viewpoint];
  node(around:$radius,$lat,$lon)[leisure=park];
  node(around:$radius,$lat,$lon)[tourism=attraction];
  node(around:$radius,$lat,$lon)[tourism=picnic_site];
  node(around:$radius,$lat,$lon)[tourism=camp_site];
  node(around:$radius,$lat,$lon)[historic];
);
out center 40;
""".trimIndent()

        if (extraPredLinesForGeneric.isNotBlank()) {
            qlGeneric = qlGeneric.replace(")\nout", extraPredLinesForGeneric + ")\nout")
        }

        val mediaType = "text/plain; charset=utf-8".toMediaType()
        var qlTypeFinal = qlType
        val extraPredLinesForType = buildPredicateLines("")
        if (extraPredLinesForType.isNotBlank()) {
            qlTypeFinal = qlTypeFinal.replace(")\nout", extraPredLinesForType + ")\nout")
        }
        val requestTypeBody = qlTypeFinal.toRequestBody(mediaType)

        // Helper: parse Overpass JSON and compute scores according to mode
        fun parseElementsToMap(body: String, scoringMode: String): MutableMap<String, ScenicPoi> {
            val m = mutableMapOf<String, ScenicPoi>()
            try {
                val json = org.json.JSONObject(body)
                val elements = json.optJSONArray("elements") ?: return m

                for (i in 0 until elements.length()) {
                    val el = elements.getJSONObject(i)
                    var elLat = el.optDouble("lat", Double.NaN)
                    var elLon = el.optDouble("lon", Double.NaN)

                    if (elLat.isNaN() || elLon.isNaN()) {
                        val center = el.optJSONObject("center")
                        if (center != null) {
                            elLat = center.optDouble("lat", Double.NaN)
                            elLon = center.optDouble("lon", Double.NaN)
                        }
                    }

                    val tags = el.optJSONObject("tags")
                    val rawName = tags?.optString("name")?.trim().takeUnless { it.isNullOrEmpty() }
                    val altName = tags?.optString("alt_name")?.trim().takeUnless { it.isNullOrEmpty() }
                    val operator = tags?.optString("operator")?.trim().takeUnless { it.isNullOrEmpty() }
                    val descr = tags?.optString("description")?.trim().takeUnless { it.isNullOrEmpty() }

                    val type = tags?.optString("tourism")
                        ?: tags?.optString("leisure")
                        ?: tags?.optString("natural")
                        ?: tags?.optString("historic")
                        ?: tags?.optString("waterway")
                        ?: "unknown"

                    // Skip items whose primary type is unknown or not whitelisted
                    val typeKey = type.lowercase(Locale.getDefault())
                    if (typeKey == "unknown" || !POI_TAG_WHITELIST.contains(typeKey)) continue

                    // Determine display name according to smart policy:
                    // prefer explicit `name`, then alt/operator/description, then synthesize only for allowed tags
                    fun friendlyTypeName(t: String?): String {
                        if (t == null || t.isBlank()) return "Scenic Location"
                        return when (t.lowercase(Locale.getDefault())) {
                            "beach" -> "Beach"
                            "coastline" -> "Coastline"
                            "bay" -> "Bay"
                            "cape" -> "Cape"
                            "viewpoint" -> "Viewpoint"
                            "nature_reserve" -> "Nature Reserve"
                            "park" -> "Park"
                            "attraction" -> "Attraction"
                            "picnic_site" -> "Picnic Site"
                            "water" -> "Water"
                            "waterfall" -> "Waterfall"
                            "peak" -> "Peak"
                            "saddle" -> "Saddle"
                            "volcano" -> "Volcano"
                            "wood" -> "Wood"
                            "ridge" -> "Ridge"
                            else -> t.replace('_', ' ').replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                        }
                    }

                    val name = when {
                        !rawName.isNullOrEmpty() -> rawName
                        !altName.isNullOrEmpty() -> altName
                        !operator.isNullOrEmpty() -> operator
                        !descr.isNullOrEmpty() -> descr
                        ALLOWED_SYNTHESIS_TAGS.contains(typeKey) -> {
                            val friendly = friendlyTypeName(type)
                            if (friendly == "Scenic Location") "Scenic Location" else "$friendly (unnamed)"
                        }
                        else -> continue
                    }

                    if (!elLat.isNaN() && !elLon.isNaN()) {
                        val isNearCoast = routePoints.any { p ->
                            p.distanceToAsDouble(GeoPoint(elLat, elLon)) < 20000 // 20km threshold
                        }

                        val score = if (scoringMode == "type") {
                            when (routeType) {
                                "oceanic" -> if (isNearCoast) when {
                                    type.equals("beach", true) -> 120
                                    type.equals("coastline", true) -> 110
                                    type.equals("bay", true) -> 105
                                    type.equals("cape", true) -> 100
                                    type.equals("viewpoint", true) -> 95
                                    type.equals("nature_reserve", true) -> 85
                                    type.equals("water", true) -> 75
                                    type.equals("waterfall", true) -> 70
                                    type.equals("park", true) -> 60
                                    type.equals("attraction", true) -> 50
                                    type.equals("picnic_site", true) -> 40
                                    else -> 20
                                } else 20
                                "mountain" -> when {
                                    type.equals("peak", true) -> 120
                                    type.equals("volcano", true) -> 115
                                    type.equals("saddle", true) -> 105
                                    type.equals("viewpoint", true) -> 100
                                    type.equals("ridge", true) -> 95
                                    type.equals("nature_reserve", true) -> 90
                                    type.equals("wood", true) -> 80
                                    type.equals("waterfall", true) -> 75
                                    type.equals("park", true) -> 65
                                    type.equals("attraction", true) -> 55
                                    else -> 20
                                }
                                else -> when {
                                    type.equals("viewpoint", true) -> 110
                                    type.equals("peak", true) -> 100
                                    type.equals("nature_reserve", true) -> 95
                                    type.equals("beach", true) -> 90
                                    type.equals("park", true) -> 85
                                    type.equals("wood", true) -> 70
                                    type.equals("historic", true) -> 65
                                    type.equals("attraction", true) -> 60
                                    type.equals("museum", true) -> 45
                                    type.equals("hotel", true) -> 25
                                    type.equals("camp_site", true) -> 40
                                    type.equals("picnic_site", true) -> 35
                                    else -> 20
                                }
                            }
                        } else {
                            when {
                                type.equals("viewpoint", true) -> 110
                                type.equals("peak", true) -> 100
                                type.equals("nature_reserve", true) -> 95
                                type.equals("beach", true) -> 90
                                type.equals("park", true) -> 85
                                type.equals("wood", true) -> 70
                                type.equals("historic", true) -> 65
                                type.equals("attraction", true) -> 60
                                type.equals("museum", true) -> 45
                                type.equals("hotel", true) -> 25
                                type.equals("camp_site", true) -> 40
                                type.equals("picnic_site", true) -> 35
                                else -> 20
                            }
                        }

                        val key = "${"%.5f".format(elLat)}_${"%.5f".format(elLon)}_$name"
                        m[key] = ScenicPoi(name, type, elLat, elLon, score)
                    }
                }
            } catch (e: Exception) {
                Log.e("ScenicRoutePlanner", "Error parsing Overpass body: ${e.message}")
            }
            return m
        }

        try {
            val requestType = Request.Builder()
                .url("https://overpass-api.de/api/interpreter")
                .post(requestTypeBody)
                .header("User-Agent", "$packageName/1.0 (contact: cedricjoshua.palapuz@gmail.com)")
                .header("Accept", "application/json")
                .build()

            val requestTypeResp = httpClient.newCall(requestType).execute()
            val typeMap = if (requestTypeResp.isSuccessful) {
                val body = requestTypeResp.body?.string() ?: ""
                requestTypeResp.close()
                parseElementsToMap(body, "type")
            } else {
                requestTypeResp.close()
                mutableMapOf()
            }

            val requestGeneric = Request.Builder()
                .url("https://overpass-api.de/api/interpreter")
                .post(qlGeneric.toRequestBody(mediaType))
                .header("User-Agent", "$packageName/1.0 (contact: cedricjoshua.palapuz@gmail.com)")
                .header("Accept", "application/json")
                .build()

            val requestGenericResp = httpClient.newCall(requestGeneric).execute()
            val genericMap = if (requestGenericResp.isSuccessful) {
                val body = requestGenericResp.body?.string() ?: ""
                requestGenericResp.close()
                parseElementsToMap(body, "generic")
            } else {
                requestGenericResp.close()
                mutableMapOf()
            }

            // Merge maps and compute blended score: typeScore + 0.5 * genericScore
            val allKeys = mutableSetOf<String>()
            allKeys.addAll(typeMap.keys)
            allKeys.addAll(genericMap.keys)

            val merged = mutableListOf<ScenicPoi>()
            for (k in allKeys) {
                val t = typeMap[k]
                val g = genericMap[k]
                val blended = (t?.score ?: 0) + GENERIC_BLEND_WEIGHT * (g?.score ?: 0)
                val chosen = t ?: g
                if (chosen != null) {
                    merged.add(ScenicPoi(chosen.name, chosen.type, chosen.lat, chosen.lon, blended.toInt()))
                }
            }

            // Before adjusting, merge any local dataset POIs near the route so dataset entries appear
            try {
                val datasetPois = poiService.getLocalPoisNearRoute(routePoints, 2000)
                for (dp in datasetPois) {
                    try {
                        val dlat = dp.lat ?: continue
                        val dlon = dp.lon ?: continue
                        val isNearCoast = routePoints.any { p -> p.distanceToAsDouble(GeoPoint(dlat, dlon)) < 20000 }
                        val score = computeScoreFromCategory(dp.category, routeType, isNearCoast)
                        val key = "${"%.5f".format(dlat)}_${"%.5f".format(dlon)}_${dp.name}"
                        if (!typeMap.containsKey(key) && !genericMap.containsKey(key)) {
                            // Insert at merged so it will be adjusted by proximity/rarity logic below
                            merged.add(com.example.scenic_navigation.models.ScenicPoi(dp.name, dp.category, dlat, dlon, score))
                        }
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.w("ScenicRoutePlanner", "Error merging dataset POIs: ${e.message}")
            }

            // Curation boosts/filters were applied earlier when building queries and will be applied
            // again as a safety net below when ranking POIs.

            // Improve recommendations by adjusting scores using proximity to route,
            // type rarity, and spatial diversity.
            try {
                // Precompute distances to route (nearest sample) for each POI
                val poiDistances = merged.map { poi ->
                    val nearest = routePoints.minOfOrNull { p ->
                        GeoUtils.haversine(p.latitude, p.longitude, poi.lat, poi.lon)
                    } ?: Double.MAX_VALUE
                    Pair(poi, nearest)
                }

                // Type frequency map
                val typeFreq = merged.groupingBy { it.type.lowercase(Locale.getDefault()) }.eachCount()
                val maxFreq = typeFreq.values.maxOrNull() ?: 1

                // Compute neighbor counts for diversity penalty
                val neighborCounts = mutableMapOf<ScenicPoi, Int>()
                for (i in merged.indices) {
                    val a = merged[i]
                    var cnt = 0
                    for (j in merged.indices) {
                        if (i == j) continue
                        val b = merged[j]
                        val d = GeoUtils.haversine(a.lat, a.lon, b.lat, b.lon)
                        if (d <= 100.0) cnt++
                    }
                    neighborCounts[a] = cnt
                }

                val adjusted = merged.map { poi ->
                    val dist = poiDistances.firstOrNull { it.first == poi }?.second ?: Double.MAX_VALUE

                    // proximity boost: closer POIs get a modest boost
                    val proximityBoost = when {
                        dist <= 500.0 -> 0.20
                        dist <= 2000.0 -> 0.10
                        else -> 0.0
                    }

                    // rarity boost: rarer types get a small bonus
                    val freq = typeFreq[poi.type.lowercase(Locale.getDefault())] ?: 1
                    val rarityBoost = ((maxFreq - freq).toDouble() / maxFreq.toDouble()) * 0.15

                    // diversity penalty: if many neighbors, slightly reduce score
                    val neighbors = neighborCounts[poi] ?: 0
                    val diversityPenaltyFactor = 1.0 / (1.0 + neighbors * 0.15)

                    val base = poi.score.toDouble()
                    val boosted = base * (1.0 + proximityBoost + rarityBoost) * diversityPenaltyFactor
                    poi.copy(score = boosted.coerceIn(1.0, 250.0).toInt())
                }

                // Apply curation config for point-level fetches too
                try {
                    if (cfg.poiBoosts.isNotEmpty() || cfg.tagFilters.isNotEmpty()) {
                        val after = adjusted.map { poi ->
                            var totalBoost = 0.0
                            val t = poi.type.lowercase(Locale.getDefault())
                            for ((k, v) in cfg.poiBoosts) if (t.contains(k)) totalBoost += v
                            val matchesFilter = if (cfg.tagFilters.isEmpty()) true else cfg.tagFilters.any { pred ->
                                pred.value.isBlank() || t.contains(pred.value.lowercase(Locale.getDefault()))
                            }
                            val multiplier = (1.0 + totalBoost).coerceAtMost(3.0) * if (matchesFilter) 1.0 else 0.85
                            poi.copy(score = (poi.score * multiplier).toInt())
                        }
                        // Filter out POIs with low scenic scores or blank names
                        val filtered = after.filter { poi ->
                            poi.score >= MIN_SCENIC_SCORE && poi.name.isNotBlank()
                        }
                        Log.d("ScenicRoutePlanner", "Filtered POIs: ${after.size} -> ${filtered.size} (removed ${after.size - filtered.size} low-score/unnamed POIs)")
                        return@withContext filtered.sortedByDescending { it.score }
                    }
                } catch (_: Exception) {}

                // Sort adjusted scores and return, filtering out low-score POIs and unnamed ones
                val filtered = adjusted.filter { poi ->
                    poi.score >= MIN_SCENIC_SCORE && poi.name.isNotBlank()
                }
                Log.d("ScenicRoutePlanner", "Filtered POIs: ${adjusted.size} -> ${filtered.size} (removed ${adjusted.size - filtered.size} low-score/unnamed POIs)")
                return@withContext filtered.sortedByDescending { it.score }
            } catch (e: Exception) {
                Log.w("ScenicRoutePlanner", "Error adjusting POI scores: ${e.message}")
                return@withContext merged
            }
        } catch (e: Exception) {
            Log.e("ScenicRoutePlanner", "Error fetching scenic POIs: ${e.message}")
            return@withContext emptyList()
        }
    }

    private suspend fun fetchScenicPoisInBBox(
        minLat: Double,
        minLon: Double,
        maxLat: Double,
        maxLon: Double,
        packageName: String,
        routeType: String = "generic",
        segmentPoints: List<GeoPoint>,
        curationIntent: com.example.scenic_navigation.models.CurationIntent? = null
    ): List<ScenicPoi> = withContext(Dispatchers.IO) {
        // Build type-specific bbox queries (uses bbox: minLat,minLon,maxLat,maxLon)
        val qlType = when (routeType) {
            "oceanic" -> {
                """
[out:json][timeout:20];
(
  node($minLat,$minLon,$maxLat,$maxLon)[natural=beach];
  node($minLat,$minLon,$maxLat,$maxLon)[natural=coastline];
  node($minLat,$minLon,$maxLat,$maxLon)[natural=bay];
  node($minLat,$minLon,$maxLat,$maxLon)[natural=cape];
  node($minLat,$minLon,$maxLat,$maxLon)[tourism=viewpoint];
  node($minLat,$minLon,$maxLat,$maxLon)[leisure=nature_reserve];
  node($minLat,$minLon,$maxLat,$maxLon)[leisure=park];
  node($minLat,$minLon,$maxLat,$maxLon)[tourism=attraction];
  node($minLat,$minLon,$maxLat,$maxLon)[tourism=picnic_site];
  node($minLat,$minLon,$maxLat,$maxLon)[natural=water];
  node($minLat,$minLon,$maxLat,$maxLon)[waterway=waterfall];
  way($minLat,$minLon,$maxLat,$maxLon)[natural=beach];
  way($minLat,$minLon,$maxLat,$maxLon)[natural=coastline];
  way($minLat,$minLon,$maxLat,$maxLon)[natural=bay];
);
out center 80;
""".trimIndent()
            }
            "mountain" -> {
                """
[out:json][timeout:20];
(
  node($minLat,$minLon,$maxLat,$maxLon)[natural=peak];
  node($minLat,$minLon,$maxLat,$maxLon)[natural=saddle];
  node($minLat,$minLon,$maxLat,$maxLon)[natural=volcano];
  node($minLat,$minLon,$maxLat,$maxLon)[natural=wood];
  node($minLat,$minLon,$maxLat,$maxLon)[tourism=viewpoint];
  node($minLat,$minLon,$maxLat,$maxLon)[leisure=nature_reserve];
  node($minLat,$minLon,$maxLat,$maxLon)[natural=ridge];
  node($minLat,$minLon,$maxLat,$maxLon)[tourism=attraction];
  node($minLat,$minLon,$maxLat,$maxLon)[leisure=park];
  node($minLat,$minLon,$maxLat,$maxLon)[waterway=waterfall];
  way($minLat,$minLon,$maxLat,$maxLon)[natural=wood];
);
out center 80;
""".trimIndent()
            }
            else -> {
                """
[out:json][timeout:20];
(
  node($minLat,$minLon,$maxLat,$maxLon)[tourism=viewpoint];
  node($minLat,$minLon,$maxLat,$maxLon)[leisure=park];
  node($minLat,$minLon,$maxLat,$maxLon)[natural=peak];
  node($minLat,$minLon,$maxLat,$maxLon)[natural=wood];
  node($minLat,$minLon,$maxLat,$maxLon)[tourism=attraction];
  node($minLat,$minLon,$maxLat,$maxLon)[natural=beach];
  node($minLat,$minLon,$maxLat,$maxLon)[leisure=nature_reserve];
  node($minLat,$minLon,$maxLat,$maxLon)[tourism=picnic_site];
  node($minLat,$minLon,$maxLat,$maxLon)[tourism=camp_site];
  node($minLat,$minLon,$maxLat,$maxLon)[tourism=hotel];
  node($minLat,$minLon,$maxLat,$maxLon)[tourism=museum];
  node($minLat,$minLon,$maxLat,$maxLon)[historic];
);
out center 80;
""".trimIndent()
            }
        }

        val qlGeneric = """
[out:json][timeout:18];
(
  node($minLat,$minLon,$maxLat,$maxLon)[tourism=viewpoint];
  node($minLat,$minLon,$maxLat,$maxLon)[leisure=park];
  node($minLat,$minLon,$maxLat,$maxLon)[tourism=attraction];
  node($minLat,$minLon,$maxLat,$maxLon)[tourism=picnic_site];
  node($minLat,$minLon,$maxLat,$maxLon)[tourism=camp_site];
  node($minLat,$minLon,$maxLat,$maxLon)[historic];
);
out center 60;
""".trimIndent()

        val mediaType = "text/plain; charset=utf-8".toMediaType()

        // Local parser copied from fetchScenicPoisAtPoint to handle bbox responses
        fun parseElementsToMap(body: String, scoringMode: String): MutableMap<String, ScenicPoi> {
            val m = mutableMapOf<String, ScenicPoi>()
            try {
                val json = org.json.JSONObject(body)
                val elements = json.optJSONArray("elements") ?: return m

                for (i in 0 until elements.length()) {
                    val el = elements.getJSONObject(i)
                    var elLat = el.optDouble("lat", Double.NaN)
                    var elLon = el.optDouble("lon", Double.NaN)

                    if (elLat.isNaN() || elLon.isNaN()) {
                        val center = el.optJSONObject("center")
                        if (center != null) {
                            elLat = center.optDouble("lat", Double.NaN)
                            elLon = center.optDouble("lon", Double.NaN)
                        }
                    }

                    val tags = el.optJSONObject("tags")
                    val name = tags?.optString("name") ?: "Scenic Location"
                    val type = tags?.optString("tourism")
                        ?: tags?.optString("leisure")
                        ?: tags?.optString("natural")
                        ?: tags?.optString("historic")
                        ?: tags?.optString("waterway")
                        ?: "unknown"

                    if (!elLat.isNaN() && !elLon.isNaN()) {
                        val isNearCoast = segmentPoints.any { p ->
                            p.distanceToAsDouble(GeoPoint(elLat, elLon)) < 20000
                        }

                        val score = if (scoringMode == "type") {
                            when (routeType) {
                                "oceanic" -> if (isNearCoast) when {
                                    type.equals("beach", true) -> 120
                                    type.equals("coastline", true) -> 110
                                    type.equals("bay", true) -> 105
                                    type.equals("cape", true) -> 100
                                    type.equals("viewpoint", true) -> 95
                                    type.equals("nature_reserve", true) -> 85
                                    type.equals("water", true) -> 75
                                    type.equals("waterfall", true) -> 70
                                    type.equals("park", true) -> 60
                                    type.equals("attraction", true) -> 50
                                    type.equals("picnic_site", true) -> 40
                                    else -> 20
                                } else 20
                                "mountain" -> when {
                                    type.equals("peak", true) -> 120
                                    type.equals("volcano", true) -> 115
                                    type.equals("saddle", true) -> 105
                                    type.equals("viewpoint", true) -> 100
                                    type.equals("ridge", true) -> 95
                                    type.equals("nature_reserve", true) -> 90
                                    type.equals("wood", true) -> 80
                                    type.equals("waterfall", true) -> 75
                                    type.equals("park", true) -> 65
                                    type.equals("attraction", true) -> 55
                                    else -> 20
                                }
                                else -> when {
                                    type.equals("viewpoint", true) -> 110
                                    type.equals("peak", true) -> 100
                                    type.equals("nature_reserve", true) -> 95
                                    type.equals("beach", true) -> 90
                                    type.equals("park", true) -> 85
                                    type.equals("wood", true) -> 70
                                    type.equals("historic", true) -> 65
                                    type.equals("attraction", true) -> 60
                                    type.equals("museum", true) -> 45
                                    type.equals("hotel", true) -> 25
                                    type.equals("camp_site", true) -> 40
                                    type.equals("picnic_site", true) -> 35
                                    else -> 20
                                }
                            }
                        } else {
                            when {
                                type.equals("viewpoint", true) -> 110
                                type.equals("peak", true) -> 100
                                type.equals("nature_reserve", true) -> 95
                                type.equals("beach", true) -> 90
                                type.equals("park", true) -> 85
                                type.equals("wood", true) -> 70
                                type.equals("historic", true) -> 65
                                type.equals("attraction", true) -> 60
                                type.equals("museum", true) -> 45
                                type.equals("hotel", true) -> 25
                                type.equals("camp_site", true) -> 40
                                type.equals("picnic_site", true) -> 35
                                else -> 20
                            }
                        }

                        val key = "${"%.5f".format(elLat)}_${"%.5f".format(elLon)}_$name"
                        m[key] = ScenicPoi(name, type, elLat, elLon, score)
                    }
                }
            } catch (e: Exception) {
                Log.e("ScenicRoutePlanner", "Error parsing Overpass body (bbox): ${e.message}")
            }
            return m
        }

        try {
            val requestType = Request.Builder()
                .url("https://overpass-api.de/api/interpreter")
                .post(qlType.toRequestBody(mediaType))
                .header("User-Agent", "$packageName/1.0 (contact: cedricjoshua.palapuz@gmail.com)")
                .header("Accept", "application/json")
                .build()

            val requestTypeResp = httpClient.newCall(requestType).execute()
            val typeMap = if (requestTypeResp.isSuccessful) {
                val body = requestTypeResp.body?.string() ?: ""
                requestTypeResp.close()
                parseElementsToMap(body, "type")
            } else {
                requestTypeResp.close()
                mutableMapOf()
            }

            val requestGeneric = Request.Builder()
                .url("https://overpass-api.de/api/interpreter")
                .post(qlGeneric.toRequestBody(mediaType))
                .header("User-Agent", "$packageName/1.0 (contact: cedricjoshua.palapuz@gmail.com)")
                .header("Accept", "application/json")
                .build()

            val requestGenericResp = httpClient.newCall(requestGeneric).execute()
            val genericMap = if (requestGenericResp.isSuccessful) {
                val body = requestGenericResp.body?.string() ?: ""
                requestGenericResp.close()
                parseElementsToMap(body, "generic")
            } else {
                requestGenericResp.close()
                mutableMapOf()
            }

            val allKeys = mutableSetOf<String>()
            allKeys.addAll(typeMap.keys)
            allKeys.addAll(genericMap.keys)

            val merged = mutableListOf<ScenicPoi>()
            for (k in allKeys) {
                val t = typeMap[k]
                val g = genericMap[k]
                val blended = (t?.score ?: 0) + GENERIC_BLEND_WEIGHT * (g?.score ?: 0)
                val chosen = t ?: g
                if (chosen != null) {
                    merged.add(ScenicPoi(chosen.name, chosen.type, chosen.lat, chosen.lon, blended.toInt()))
                }
            }

            // Apply curation mapping on bbox-level results
            try {
                val cfg = com.example.scenic_navigation.services.CurationMapper.map(curationIntent, deviceLocaleTag())
                if (cfg.poiBoosts.isNotEmpty() || cfg.tagFilters.isNotEmpty()) {
                    val after = merged.map { poi ->
                        var totalBoost = 0.0
                        val t = poi.type.lowercase(Locale.getDefault())
                        for ((k, v) in cfg.poiBoosts) if (t.contains(k)) totalBoost += v
                        val matchesFilter = if (cfg.tagFilters.isEmpty()) true else cfg.tagFilters.any { pred ->
                            pred.value.isBlank() || t.contains(pred.value.lowercase(Locale.getDefault()))
                        }
                        val multiplier = (1.0 + totalBoost).coerceAtMost(3.0) * if (matchesFilter) 1.0 else 0.85
                        poi.copy(score = (poi.score * multiplier).toInt())
                    }
                    // Filter out POIs with low scenic scores or blank names
                    val filtered = after.filter { poi ->
                        poi.score >= MIN_SCENIC_SCORE && poi.name.isNotBlank()
                    }
                    Log.d("ScenicRoutePlanner", "BBox filtered POIs: ${after.size} -> ${filtered.size} (removed ${after.size - filtered.size} low-score/unnamed)")
                    return@withContext filtered
                }
            } catch (_: Exception) {}

            // Filter before returning
            val filtered = merged.filter { poi ->
                poi.score >= MIN_SCENIC_SCORE && poi.name.isNotBlank()
            }
            Log.d("ScenicRoutePlanner", "BBox filtered POIs: ${merged.size} -> ${filtered.size} (removed ${merged.size - filtered.size} low-score/unnamed)")
            return@withContext filtered
        } catch (e: Exception) {
            Log.e("ScenicRoutePlanner", "Error fetching scenic POIs (bbox): ${e.message}")
            return@withContext emptyList()
        }
    }

    /**
     * Suggest a small set of via-points (GeoPoint) for a curated route between start and dest.
     * Picks high-scoring POIs within the bounding box between start and dest and returns up to `maxPoints`
     * points that are sufficiently spatially separated.
     */
    suspend fun suggestViaPointsForCuration(
        start: GeoPoint,
        dest: GeoPoint,
        packageName: String,
        curationIntent: com.example.scenic_navigation.models.CurationIntent?,
        maxPoints: Int = 3
    ): List<org.osmdroid.util.GeoPoint> = withContext(Dispatchers.IO) {
        try {
            val minLat = minOf(start.latitude, dest.latitude)
            val maxLat = maxOf(start.latitude, dest.latitude)
            val minLon = minOf(start.longitude, dest.longitude)
            val maxLon = maxOf(start.longitude, dest.longitude)

            // padding based on distance
            val pad = 0.2 // ~20km coarse padding; expands search area for via-points
            val bboxMinLat = (minLat - pad)
            val bboxMaxLat = (maxLat + pad)
            val bboxMinLon = (minLon - pad)
            val bboxMaxLon = (maxLon + pad)

            val routeType = com.example.scenic_navigation.services.CurationMapper.map(curationIntent, deviceLocaleTag()).routeType

            val candidates = fetchScenicPoisInBBox(bboxMinLat, bboxMinLon, bboxMaxLat, bboxMaxLon, packageName, routeType, listOf(start, dest), curationIntent)

            if (candidates.isEmpty()) return@withContext emptyList()

            // Use a simple DBSCAN-like clustering to pick representative cluster centroids.
            // This reduces noisy isolated picks and returns spatially representative via-points.
            // Use instance-level cluster params so preferences passed to the planner take effect.
            val epsMeters = this@ScenicRoutePlanner.clusterEpsMeters // cluster radius (from constructor or Config)
            val minPts = this@ScenicRoutePlanner.clusterMinPts

            data class TmpPt(val poi: ScenicPoi, var cluster: Int = -1)

            val pts = candidates.sortedByDescending { it.score }.map { TmpPt(it) }.toMutableList()
            var clusterId = 0

            fun neighbors(idx: Int): List<Int> {
                val out = mutableListOf<Int>()
                val a = pts[idx].poi
                for (j in pts.indices) {
                    if (j == idx) continue
                    val b = pts[j].poi
                    val d = GeoUtils.haversine(a.lat, a.lon, b.lat, b.lon)
                    if (d <= epsMeters) out.add(j)
                }
                return out
            }

            for (i in pts.indices) {
                if (pts[i].cluster != -1) continue
                val nb = neighbors(i)
                if (nb.size + 1 < minPts) {
                    // mark as its own cluster (singleton)
                    pts[i].cluster = clusterId
                    clusterId++
                } else {
                    // expand cluster
                    pts[i].cluster = clusterId
                    val queue = ArrayDeque<Int>()
                    nb.forEach { queue.add(it) }
                    while (queue.isNotEmpty()) {
                        val cur = queue.removeFirst()
                        if (pts[cur].cluster == -1) {
                            pts[cur].cluster = clusterId
                            val curNb = neighbors(cur)
                            if (curNb.size + 1 >= minPts) curNb.forEach { queue.add(it) }
                        }
                    }
                    clusterId++
                }
            }

            // Aggregate clusters to centroids and score
            val clusters = mutableMapOf<Int, MutableList<ScenicPoi>>()
            for (p in pts) {
                val id = p.cluster
                if (id < 0) continue
                clusters.getOrPut(id) { mutableListOf() }.add(p.poi)
            }

            val centroids = clusters.mapNotNull { (id, members) ->
                if (members.isEmpty()) return@mapNotNull null
                val avgLat = members.map { it.lat }.average()
                val avgLon = members.map { it.lon }.average()
                val clusterScore = members.maxOf { it.score }
                Triple(clusterScore, avgLat, avgLon)
            }.sortedByDescending { it.first }

            val chosen = centroids.take(maxPoints).map { Triple -> org.osmdroid.util.GeoPoint(Triple.second, Triple.third) }
            return@withContext chosen
        } catch (e: Exception) {
            Log.w("ScenicRoutePlanner", "Error suggesting via-points: ${e.message}")
            return@withContext emptyList()
        }
    }

    /**
     * Calculate scenic score for a route based on POIs
     */
    fun calculateScenicScore(route: List<GeoPoint>, scenicPois: List<ScenicPoi>): Double {
        if (route.isEmpty()) return Double.NEGATIVE_INFINITY

        val lengthMeters = GeoUtils.computeRouteLength(route).coerceAtLeast(1.0)
        val totalScore = scenicPois.sumOf { it.score }
        val avgScore = if (scenicPois.isNotEmpty()) totalScore.toDouble() / scenicPois.size else 0.0
        val distinctTypes = scenicPois.map { it.type.lowercase(Locale.getDefault()) }.toSet().size
        val density = scenicPois.size / (lengthMeters / 1000.0)

        return (totalScore * 1.2) + (avgScore * 15) + (distinctTypes * 90) + (density * 400) +
                Math.log(lengthMeters) * 25
    }

    /**
     * Select the most scenic route from alternatives
     */
    suspend fun selectMostScenicRoute(
        alternatives: List<List<GeoPoint>>,
        packageName: String,
        routeType: String = "generic",
        curationIntent: com.example.scenic_navigation.models.CurationIntent? = null,
        onStatusUpdate: ((String) -> Unit)? = null
    ): Pair<List<GeoPoint>?, List<ScenicPoi>> = withContext(Dispatchers.IO) {
        if (alternatives.isEmpty()) return@withContext Pair(null, emptyList())

        var mostScenicRoute: List<GeoPoint>? = null
        var mostScenicPois: List<ScenicPoi> = emptyList()
        var highestScore = Double.NEGATIVE_INFINITY

        for (alt in alternatives) {
            val pois = fetchScenicPois(alt, packageName, routeType, curationIntent, onStatusUpdate)
            val score = calculateScenicScore(alt, pois)

            Log.d("ScenicRoutePlanner",
                "Route length=${GeoUtils.computeRouteLength(alt).toInt()}m " +
                "scenicCount=${pois.size} score=$score (type=$routeType)")

            if (score > highestScore) {
                highestScore = score
                mostScenicRoute = alt
                mostScenicPois = pois
            }
        }

        return@withContext Pair(mostScenicRoute, mostScenicPois)
    }
}