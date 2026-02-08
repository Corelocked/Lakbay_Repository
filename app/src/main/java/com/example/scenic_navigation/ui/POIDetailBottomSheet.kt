package com.example.scenic_navigation.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.lifecycle.lifecycleScope
import com.example.scenic_navigation.R
import com.example.scenic_navigation.models.Poi
import com.example.scenic_navigation.FavoriteStore
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class POIDetailBottomSheet(private val poi: Poi) : BottomSheetDialogFragment() {

    private val TAG = "POIDetailBottomSheet"

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_poi_detail_bottom_sheet, container, false)
        Log.i(TAG, "onCreateView for POI='${poi.name}'")

        val tvTitle = view.findViewById<android.widget.TextView>(R.id.tv_poi_title)
        val tvCategory = view.findViewById<android.widget.TextView>(R.id.tv_poi_category)
        val tvDescription = view.findViewById<android.widget.TextView>(R.id.tv_poi_description)
        val btnSave = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_save)
        val btnNavigate = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_navigate)

        val ivImage = view.findViewById<ImageView>(R.id.iv_poi_image)
        try {
            val placeholder = createHeaderPlaceholder(poi.name)
            ivImage?.setImageBitmap(placeholder)
            Log.i(TAG, "Set image placeholder for POI='${poi.name}'")
        } catch (_: Exception) {}

        ivImage?.let {
            Log.i(TAG, "Starting image lookup for POI='${poi.name}'")
            loadPoiImage(it, poi)
        }

        tvTitle.text = poi.name
        tvCategory.text = poi.category
        tvDescription.text = if (poi.description.isNotBlank()) poi.description else "No description available for this location."

        btnSave.setOnClickListener {
            val key = "${poi.name}_${poi.lat}_${poi.lon}"
            if (FavoriteStore.isFavorite(key)) {
                FavoriteStore.removeFavorite(key)
                Snackbar.make(requireView(), "Removed from favorites", Snackbar.LENGTH_SHORT).show()
                btnSave.text = getString(R.string.save_button)
            } else {
                FavoriteStore.addFavorite(key, poi)
                Snackbar.make(requireView(), "Saved '${poi.name}'", Snackbar.LENGTH_SHORT).show()
                btnSave.text = getString(R.string.save_button)
            }
        }

        btnNavigate.setOnClickListener {
            // Open external maps app as a quick navigation action
            val lat = poi.lat
            val lon = poi.lon
            if (lat != null && lon != null) {
                val gmmIntentUri = Uri.parse("geo:$lat,$lon?q=$lat,$lon(${Uri.encode(poi.name)})")
                val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
                mapIntent.setPackage("com.google.android.apps.maps")
                if (mapIntent.resolveActivity(requireActivity().packageManager) != null) {
                    startActivity(mapIntent)
                } else {
                    // Fallback to generic intent
                    startActivity(Intent(Intent.ACTION_VIEW, gmmIntentUri))
                }
            } else {
                Snackbar.make(requireView(), "Location not available for this POI", Snackbar.LENGTH_SHORT).show()
            }
        }

        return view
    }

    // Build search variants and orchestrate lookup
    private fun loadPoiImage(iv: ImageView, poi: Poi) {
        // Build a few text-query variants (avoid embedding coordinates into text search which can confuse Google scraping)
        val variants = mutableListOf<String>()
        val name = poi.name.trim()
        if (name.isNotBlank()) variants.add(name)
        // Add municipality-only and country-qualified variants
        if (!poi.municipality.isNullOrBlank()) {
            variants.add("$name ${poi.municipality}")
            // include country to help Wikimedia/Commons disambiguation
            variants.add("$name ${poi.municipality} Philippines")
        }
        // add a plain country-qualified variant
        variants.add("$name Philippines")
        // add wikipedia keyword to prefer the encyclopedia page when searching web
        variants.add("$name wikipedia")
        // sanitize category (remove slashes and non-alphanumeric) and include if present
        if (!poi.category.isNullOrBlank()) {
            val sanitizedCat = poi.category.replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").trim()
            if (sanitizedCat.isNotBlank()) {
                if (!poi.municipality.isNullOrBlank()) variants.add("$name $sanitizedCat ${poi.municipality}")
                variants.add("$name $sanitizedCat")
            }
        }
        // de-duplicate while preserving order
        val deduped = mutableListOf<String>()
        for (v in variants) if (v.isNotBlank() && !deduped.contains(v)) deduped.add(v)
        variants.clear(); variants.addAll(deduped)

        Log.i(TAG, "Image lookup variants=${variants.joinToString(" | ")} for POI='${poi.name}'")

        // Launch coroutine to perform network ops off the main thread.
        // Use fragment lifecycleScope so brief view re-creations (viewLifecycleOwner) don't cancel the lookup.
        lifecycleScope.launch {
            try {
                var foundUrl: String? = null
                // First try Wikimedia geosearch by coordinates (strong signal) if available
                if (poi.lat != null && poi.lon != null) {
                    foundUrl = withContext(Dispatchers.IO) { findWikimediaImageUrlForCoords(poi.lat, poi.lon) }
                }
                // If not found from geo, try the text variants on Wikimedia, then Google (site-restricted) per variant
                if (foundUrl.isNullOrBlank()) {
                    for (v in variants) {
                        foundUrl = withContext(Dispatchers.IO) { findWikimediaImageUrlForQuery(v) }
                        if (!foundUrl.isNullOrBlank()) break
                        Log.i(TAG, "Wikimedia text search returned no image for variant='$v', trying Commons/Google")
                        // try commons first
                        foundUrl = withContext(Dispatchers.IO) { findCommonsImageForQuery(v) }
                        if (!foundUrl.isNullOrBlank()) break
                        // fallback to Google scraping
                        foundUrl = withContext(Dispatchers.IO) { findGoogleImageUrlForQuery(v) }
                        if (!foundUrl.isNullOrBlank()) break
                    }
                }

                if (!foundUrl.isNullOrBlank()) {
                    Log.i(TAG, "Found image URL: $foundUrl")
                    val bmp = withContext(Dispatchers.IO) { downloadBitmap(foundUrl) }
                    if (bmp != null) {
                        // Ensure the ImageView is still attached before attempting to set the bitmap
                        withContext(Dispatchers.Main) {
                            try {
                                val attached = try { iv.isAttachedToWindow } catch (_: Throwable) { false }
                                if (isAdded && attached) {
                                    iv.setImageBitmap(bmp)
                                    Log.i(TAG, "Downloaded and set bitmap from $foundUrl")
                                } else {
                                    Log.i(TAG, "View not attached; bitmap skipped")
                                }
                            } catch (t: Throwable) {
                                Log.w(TAG, "UI thread: failed to set bitmap", t)
                            }
                        }
                    } else {
                        Log.w(TAG, "Failed to decode or rejected bitmap from url: $foundUrl")
                    }
                } else {
                    Log.i(TAG, "No image URL found for variants='${variants.joinToString(" | ")}'")
                }

            } catch (ce: kotlinx.coroutines.CancellationException) {
                // Expected when the coroutine is cancelled (e.g., fragment being destroyed). Log at INFO and return quietly.
                Log.i(TAG, "Image lookup coroutine cancelled for POI='${poi.name}'")
                return@launch
            } catch (e: Exception) {
                Log.w(TAG, "Exception in loadPoiImage", e)
            }
        }
    }

    // Wikimedia search -> pageimages/page props -> page image
    private fun findWikimediaImageUrlForQuery(query: String): String? {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val searchUrl = "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encoded&format=json&srlimit=5"
            val req1 = Request.Builder().url(searchUrl).get().build()
            httpClient.newCall(req1).execute().use { resp1 ->
                if (!resp1.isSuccessful) return null
                val body = resp1.body?.string() ?: return null
                val root = JSONObject(body)
                val search = root.optJSONObject("query")?.optJSONArray("search") ?: return null
                if (search.length() == 0) return null
                for (i in 0 until search.length()) {
                    val item = search.getJSONObject(i)
                    val title = item.optString("title")
                    if (title.isNullOrBlank()) continue
                    // pageimages original
                    val t = URLEncoder.encode(title, "UTF-8")
                    val pageImageUrl = "https://en.wikipedia.org/w/api.php?action=query&titles=$t&prop=pageimages&piprop=original&format=json"
                    val req2 = Request.Builder().url(pageImageUrl).get().build()
                    httpClient.newCall(req2).execute().use { resp2 ->
                        if (!resp2.isSuccessful) return@use
                        val b2 = resp2.body?.string() ?: return@use
                        val root2 = JSONObject(b2)
                        val pages = root2.optJSONObject("query")?.optJSONObject("pages") ?: return@use
                        val keys = pages.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            val page = pages.optJSONObject(k)
                            val orig = page?.optJSONObject("original")
                            val src = orig?.optString("source")
                            if (!src.isNullOrBlank()) return src
                        }
                    }
                    // page images listing fallback
                    val pageImage = findWikimediaImageFromPageApi(title)
                    if (!pageImage.isNullOrBlank()) return pageImage
                    // commons search
                    val commons = findCommonsImageForQuery(title)
                    if (!commons.isNullOrBlank()) return commons
                    // wikidata
                    val wikidata = findWikidataImageForTitle(title)
                    if (!wikidata.isNullOrBlank()) return wikidata
                    // html fallback
                    val html = findWikimediaImageUrlFromPage(title)
                    if (!html.isNullOrBlank()) return html
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Exception while searching Wikimedia for query='$query'", e)
            null
        }
    }

    // List images on a wiki page and return first imageinfo URL
    private fun findWikimediaImageFromPageApi(title: String): String? {
        return try {
            val t = URLEncoder.encode(title, "UTF-8")
            val listUrl = "https://en.wikipedia.org/w/api.php?action=query&titles=$t&prop=images&format=json&imlimit=10"
            val req = Request.Builder().url(listUrl).get().build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val root = JSONObject(body)
                val pages = root.optJSONObject("query")?.optJSONObject("pages") ?: return null
                val keys = pages.keys()
                val fileTitles = mutableListOf<String>()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val page = pages.optJSONObject(k)
                    val images = page?.optJSONArray("images") ?: continue
                    for (i in 0 until images.length()) {
                        val img = images.getJSONObject(i)
                        val imgTitle = img.optString("title")
                        if (!imgTitle.isNullOrBlank() && imgTitle.startsWith("File:")) fileTitles.add(imgTitle)
                    }
                }
                for (ftRaw in fileTitles) {
                    val ft = URLEncoder.encode(ftRaw, "UTF-8")
                    val infoUrl = "https://en.wikipedia.org/w/api.php?action=query&titles=$ft&prop=imageinfo&iiprop=url&format=json"
                    val req2 = Request.Builder().url(infoUrl).get().build()
                    httpClient.newCall(req2).execute().use { resp2 ->
                        if (!resp2.isSuccessful) return@use
                        val b2 = resp2.body?.string() ?: return@use
                        val root2 = JSONObject(b2)
                        val pages2 = root2.optJSONObject("query")?.optJSONObject("pages") ?: return@use
                        val keys2 = pages2.keys()
                        while (keys2.hasNext()) {
                            val k2 = keys2.next()
                            val page2 = pages2.optJSONObject(k2)
                            val ii = page2?.optJSONArray("imageinfo")
                            if (ii != null && ii.length() > 0) {
                                val info = ii.getJSONObject(0)
                                val url = info.optString("url")
                                if (!url.isNullOrBlank()) return url
                            }
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Exception while listing images for title='$title'", e)
            null
        }
    }

    // Scrape article HTML for og:image or image_src
    private fun findWikimediaImageUrlFromPage(title: String): String? {
        return try {
            val path = title.replace(' ', '_')
            val url = "https://en.wikipedia.org/wiki/$path"
            val req = Request.Builder().url(url).get().build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val ogRegex = Regex("<meta[^>]+property=['\"]og:image['\"][^>]+content=['\"](https?://[^'\"]+)['\"]", RegexOption.IGNORE_CASE)
                val m = ogRegex.find(body)
                if (m != null) return m.groupValues[1]
                val linkRegex = Regex("<link[^>]+rel=['\"]image_src['\"][^>]+href=['\"](https?://[^'\"]+)['\"]", RegexOption.IGNORE_CASE)
                val lm = linkRegex.find(body)
                if (lm != null) return lm.groupValues[1]
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Exception while fetching article HTML for title='$title'", e)
            null
        }
    }

    // Geosearch nearby wiki pages and try to resolve images
    private fun findWikimediaImageUrlForCoords(lat: Double, lon: Double, radiusMeters: Int = 5000): String? {
        return try {
            val coord = "${lat}|${lon}"
            val encoded = URLEncoder.encode(coord, "UTF-8")
            val url = "https://en.wikipedia.org/w/api.php?action=query&list=geosearch&gscoord=$encoded&gsradius=$radiusMeters&gslimit=10&format=json"
            val req = Request.Builder().url(url).get().build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val root = JSONObject(body)
                val geo = root.optJSONObject("query")?.optJSONArray("geosearch") ?: return null
                for (i in 0 until geo.length()) {
                    val item = geo.getJSONObject(i)
                    val title = item.optString("title")
                    if (title.isNullOrBlank()) continue
                    val found = findWikimediaImageUrlForQuery(title)
                    if (!found.isNullOrBlank()) return found
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Exception while searching Wikimedia (geo) for coords=$lat,$lon", e)
            null
        }
    }

    // Google Images scraping fallback (last resort)
    private fun findGoogleImageUrlForQuery(query: String): String? {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://www.google.com/search?tbm=isch&q=$encoded"
            val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            val req = Request.Builder().url(url).header("User-Agent", ua).header("Accept-Language", "en-US,en;q=0.9").get().build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val afRegex = Regex("AF_initDataCallback\\((.*?)\\);", RegexOption.DOT_MATCHES_ALL)
                val urlRegex = Regex("https?://[^\\s\"'<>\\)\\]]+")
                val imageExt = Regex("\\.(jpg|jpeg|png|webp|gif)", RegexOption.IGNORE_CASE)
                val blacklist = listOf("/sprite", "googleusercontent.com/static", "gstatic.com/images/branding", "/logos/", "/icons/", "data:image/svg", "data:image/png;base64", "placeholder", "dots")
                val extendedBlacklist = blacklist + listOf("gstatic.com/gb", "ssl.gstatic.com/gb", "gstatic.com/chart", "gstatic.com/images/branding")
                val candidates = mutableListOf<String>()
                for (m in afRegex.findAll(body)) {
                    val block = m.groupValues[1]
                    val found = urlRegex.findAll(block).map { it.value }
                    candidates.addAll(found)
                }
                val attrRegex = Regex("(?i)(?:data-src|src)=[\"'](https?://[^\"'>]+)[\"']")
                val m2 = attrRegex.find(body)
                if (m2 != null) candidates.add(m2.groupValues[1])
                val jsonRegex = Regex("\\\\\"ou\\\\\":\\\\\"(https?://[^\\\\\"]+)\\\\\"")
                val jm = jsonRegex.find(body)
                if (jm != null) candidates.add(jm.groupValues[1].replace("\\u0026", "&"))
                val filtered = candidates.mapNotNull { it.trim() }.distinct().filter { it.startsWith("http") }.filter { c -> !extendedBlacklist.any { blk -> c.contains(blk, ignoreCase = true) } }
                Log.i(TAG, "Google raw candidates=${candidates.size}, preview=${candidates.take(5)}")
                val preferred = filtered.filter { imageExt.containsMatchIn(it) }
                val ordered = if (preferred.isNotEmpty()) preferred + filtered else filtered
                for (cand in ordered) {
                    try {
                        val headReq = Request.Builder().url(cand).head().build()
                        httpClient.newCall(headReq).execute().use { hResp ->
                            if (!hResp.isSuccessful) {
                                val getReq = Request.Builder().url(cand).get().build()
                                httpClient.newCall(getReq).execute().use { gResp ->
                                    val ct = gResp.header("Content-Type") ?: ""
                                    val cl = gResp.body?.contentLength() ?: -1L
                                    if (ct.startsWith("image/") && cl > 1000) {
                                        Log.i(TAG, "Candidate accepted (GET): $cand")
                                        return cand
                                    }
                                }
                            } else {
                                val ct = hResp.header("Content-Type") ?: ""
                                val cl = hResp.header("Content-Length")?.toLongOrNull() ?: -1L
                                if (ct.startsWith("image/") && cl > 1000) {
                                    Log.i(TAG, "Candidate accepted (HEAD): $cand")
                                    return cand
                                }
                            }
                        }
                    } catch (ex: Exception) {
                        Log.w(TAG, "Error verifying candidate: $cand", ex)
                    }
                }
                if (filtered.isNotEmpty()) return filtered.first()
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception while scraping Google images for query='$query'", e)
            null
        }
    }

    private fun downloadBitmap(url: String): Bitmap? {
        return try {
            val req = Request.Builder().url(url).get().build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val stream = resp.body?.byteStream() ?: return null
                val bmp = BitmapFactory.decodeStream(stream) ?: return null
                if (bmp.width < 64 || bmp.height < 64) return null
                bmp
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception while downloading bitmap from $url", e)
            null
        }
    }

    private fun createHeaderPlaceholder(title: String, width: Int = 800, height: Int = 360): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val hash = (title.hashCode() and 0x00FFFFFF) or 0xFF000000.toInt()
        paint.color = hash
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        val overlay = Paint(Paint.ANTI_ALIAS_FLAG)
        overlay.color = Color.argb(100, 0, 0, 0)
        canvas.drawRect(0f, height - 140f, width.toFloat(), height.toFloat(), overlay)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        textPaint.color = Color.WHITE
        textPaint.textSize = 48f
        textPaint.isFakeBoldText = true
        val text = title.take(28)
        canvas.drawText(text, 24f, (height - 60).toFloat(), textPaint)
        return bmp
    }

    // Get image from Wikidata (P18) via wikibase_item
    private fun findWikidataImageForTitle(title: String): String? {
        return try {
            val t = URLEncoder.encode(title, "UTF-8")
            val propsUrl = "https://en.wikipedia.org/w/api.php?action=query&titles=$t&prop=pageprops&format=json"
            val req = Request.Builder().url(propsUrl).get().build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val root = JSONObject(body)
                val pages = root.optJSONObject("query")?.optJSONObject("pages") ?: return null
                val keys = pages.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val page = pages.optJSONObject(k)
                    val qid = page?.optJSONObject("pageprops")?.optString("wikibase_item")
                    if (qid.isNullOrBlank()) continue
                    val entityUrl = "https://www.wikidata.org/wiki/Special:EntityData/$qid.json"
                    val req2 = Request.Builder().url(entityUrl).get().build()
                    httpClient.newCall(req2).execute().use { resp2 ->
                        if (!resp2.isSuccessful) continue
                        val b2 = resp2.body?.string() ?: continue
                        val root2 = JSONObject(b2)
                        val entities = root2.optJSONObject("entities") ?: continue
                        val ent = entities.optJSONObject(qid) ?: continue
                        val claims = ent.optJSONObject("claims") ?: continue
                        val p18Array = claims.optJSONArray("P18") ?: continue
                        if (p18Array.length() == 0) continue
                        val claim0 = p18Array.getJSONObject(0)
                        val datavalue = claim0.optJSONObject("mainsnak")?.optJSONObject("datavalue")
                        var imageName: String? = null
                        if (datavalue != null) {
                            val v = datavalue.opt("value")
                            if (v is String) imageName = v
                        }
                        if (imageName.isNullOrBlank()) imageName = claim0.optJSONObject("mainsnak")?.optJSONObject("datavalue")?.optString("value")
                        if (!imageName.isNullOrBlank()) {
                            val fileTitle = URLEncoder.encode(imageName, "UTF-8")
                            val infoUrl = "https://commons.wikimedia.org/w/api.php?action=query&titles=$fileTitle&prop=imageinfo&iiprop=url&format=json"
                            val req3 = Request.Builder().url(infoUrl).get().build()
                            httpClient.newCall(req3).execute().use { resp3 ->
                                if (!resp3.isSuccessful) return@use
                                val b3 = resp3.body?.string() ?: return@use
                                val root3 = JSONObject(b3)
                                val pages3 = root3.optJSONObject("query")?.optJSONObject("pages") ?: return@use
                                val keys3 = pages3.keys()
                                while (keys3.hasNext()) {
                                    val k3 = keys3.next()
                                    val page3 = pages3.optJSONObject(k3)
                                    val ii = page3?.optJSONArray("imageinfo")
                                    if (ii != null && ii.length() > 0) {
                                        val info = ii.getJSONObject(0)
                                        val url = info.optString("url")
                                        if (!url.isNullOrBlank()) return url
                                    }
                                }
                            }
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Exception while fetching Wikidata image for title='$title'", e)
            null
        }
    }

    // Search Commons for file pages matching the query
    private fun findCommonsImageForQuery(query: String): String? {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val searchUrl = "https://commons.wikimedia.org/w/api.php?action=query&list=search&srsearch=$encoded&format=json&srlimit=8"
            val req = Request.Builder().url(searchUrl).get().build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val root = JSONObject(body)
                val search = root.optJSONObject("query")?.optJSONArray("search") ?: return null
                for (i in 0 until search.length()) {
                    val item = search.getJSONObject(i)
                    val title = item.optString("title")
                    if (title.isNullOrBlank()) continue
                    if (title.startsWith("File:") || title.startsWith("Image:")) {
                        val ft = URLEncoder.encode(title, "UTF-8")
                        val infoUrl = "https://commons.wikimedia.org/w/api.php?action=query&titles=$ft&prop=imageinfo&iiprop=url&format=json"
                        val req2 = Request.Builder().url(infoUrl).get().build()
                        httpClient.newCall(req2).execute().use { resp2 ->
                            if (!resp2.isSuccessful) return@use
                            val b2 = resp2.body?.string() ?: return@use
                            val root2 = JSONObject(b2)
                            val pages2 = root2.optJSONObject("query")?.optJSONObject("pages") ?: return@use
                            val keys = pages2.keys()
                            while (keys.hasNext()) {
                                val k = keys.next()
                                val page = pages2.optJSONObject(k)
                                val ii = page?.optJSONArray("imageinfo")
                                if (ii != null && ii.length() > 0) {
                                    val info = ii.getJSONObject(0)
                                    val url = info.optString("url")
                                    if (!url.isNullOrBlank()) return url
                                }
                            }
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Exception while searching Commons for query='$query'", e)
            null
        }
    }

}
