package com.example.scenic_navigation.services

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import android.util.LruCache
import android.widget.ImageView
import androidx.core.view.ViewCompat
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.example.scenic_navigation.R
import com.example.scenic_navigation.models.Poi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object PoiImageRepository {
    private const val TAG = "PoiImageRepository"

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private val urlCache = ConcurrentHashMap<String, String?>()
    private val bitmapCache = object : LruCache<String, Bitmap>(20 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun loadInto(imageView: ImageView, poi: Poi) {
        val key = cacheKey(poi)
        imageView.setTag(R.id.tag_poi_image_key, key)

        bitmapCache.get(key)?.let {
            imageView.setImageBitmap(it)
            return
        }

        imageView.setImageBitmap(createPlaceholder(poi.name))

        val lifecycleOwner = imageView.findViewTreeLifecycleOwner() ?: return
        lifecycleOwner.lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { fetchBitmap(poi, key) } ?: return@launch
            val activeKey = imageView.getTag(R.id.tag_poi_image_key) as? String
            val stillAttached = ViewCompat.isAttachedToWindow(imageView)
            if (activeKey == key && stillAttached) {
                imageView.setImageBitmap(bitmap)
            }
        }
    }

    private fun fetchBitmap(poi: Poi, key: String): Bitmap? {
        bitmapCache.get(key)?.let { return it }
        val cachedUrl = urlCache[key]
        var url = cachedUrl ?: resolveImageUrl(poi, preferDatabaseUrl = true).also {
            if (!it.isNullOrBlank()) {
                urlCache[key] = it
            } else {
                // Avoid sticky "no-url" cache entries so transient failures can retry.
                urlCache.remove(key)
            }
        }
        if (url.isNullOrBlank()) return null
        var bitmap = downloadBitmap(url)

        // If direct DB URL failed, try dynamic fallback discovery before giving up.
        if (bitmap == null && normalizeImageUrl(poi.imageUrl) == url) {
            Log.w(TAG, "Primary DB image URL failed for '${poi.name}', trying fallback sources")
            val fallbackUrl = resolveImageUrl(poi, preferDatabaseUrl = false)
            if (!fallbackUrl.isNullOrBlank()) {
                url = fallbackUrl
                bitmap = downloadBitmap(fallbackUrl)
                if (bitmap != null) {
                    urlCache[key] = fallbackUrl
                }
            }
        }

        if (bitmap == null) return null
        bitmapCache.put(key, bitmap)
        return bitmap
    }

    private fun resolveImageUrl(poi: Poi, preferDatabaseUrl: Boolean): String? {
        return try {
            if (preferDatabaseUrl) {
                // First priority: use the imageUrl from the database if available
                normalizeImageUrl(poi.imageUrl)?.let {
                    Log.d(TAG, "Using DB imageUrl for '${poi.name}'")
                    return it
                }
                if (poi.imageUrl.isNotBlank()) {
                    Log.w(TAG, "Ignoring invalid imageUrl for ${poi.name}: '${poi.imageUrl.take(80)}'")
                }
            }
            // Fallback: search for images using other methods
            if (poi.lat != null && poi.lon != null) {
                findWikimediaImageUrlForCoords(poi.lat, poi.lon)?.let { return it }
            }
            for (query in buildQueries(poi)) {
                findWikimediaImageUrlForQuery(query)?.let { return it }
                findCommonsImageForQuery(query)?.let { return it }
                findGoogleImageUrlForQuery(query)?.let { return it }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve image for ${poi.name}", e)
            null
        }
    }

    private fun buildQueries(poi: Poi): List<String> {
        val variants = linkedSetOf<String>()
        val name = poi.name.trim()
        if (name.isNotBlank()) variants.add(name)
        if (poi.photoHint.isNotBlank()) variants.add(poi.photoHint.trim())
        if (poi.municipality.isNotBlank()) {
            variants.add("$name ${poi.municipality}")
            variants.add("$name ${poi.municipality} Philippines")
        }
        if (poi.province.isNotBlank()) {
            variants.add("$name ${poi.province}")
            variants.add("$name ${poi.municipality} ${poi.province} Philippines".trim())
        }
        variants.add("$name Philippines")
        if (poi.category.isNotBlank()) {
            val cleanCategory = poi.category.replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").trim()
            if (cleanCategory.isNotBlank()) {
                variants.add("$name $cleanCategory")
                if (poi.municipality.isNotBlank()) variants.add("$name $cleanCategory ${poi.municipality}")
            }
        }
        poi.tags.forEach { tag ->
            val cleanTag = tag.replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").trim()
            if (cleanTag.isNotBlank()) {
                variants.add("$name $cleanTag Philippines")
                if (poi.municipality.isNotBlank()) variants.add("$name $cleanTag ${poi.municipality} Philippines")
            }
        }
        return variants.toList()
    }

    private fun findWikimediaImageUrlForQuery(query: String): String? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encoded&format=json&srlimit=5"
        val request = Request.Builder().url(searchUrl).get().build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val results = JSONObject(body).optJSONObject("query")?.optJSONArray("search") ?: return null
            for (i in 0 until results.length()) {
                val title = results.getJSONObject(i).optString("title")
                if (title.isBlank()) continue
                findWikipediaPageImage(title)?.let { return it }
                findWikidataImageForTitle(title)?.let { return it }
            }
        }
        return null
    }

    private fun findWikipediaPageImage(title: String): String? {
        val encodedTitle = URLEncoder.encode(title, "UTF-8")
        val pageImageUrl = "https://en.wikipedia.org/w/api.php?action=query&titles=$encodedTitle&prop=pageimages&piprop=original&format=json"
        val request = Request.Builder().url(pageImageUrl).get().build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val pages = JSONObject(body).optJSONObject("query")?.optJSONObject("pages") ?: return null
            val keys = pages.keys()
            while (keys.hasNext()) {
                val page = pages.optJSONObject(keys.next()) ?: continue
                val src = page.optJSONObject("original")?.optString("source")
                if (!src.isNullOrBlank()) return src
            }
        }
        return null
    }

    private fun findWikimediaImageUrlForCoords(lat: Double, lon: Double, radiusMeters: Int = 5000): String? {
        val coord = URLEncoder.encode("$lat|$lon", "UTF-8")
        val url = "https://en.wikipedia.org/w/api.php?action=query&list=geosearch&gscoord=$coord&gsradius=$radiusMeters&gslimit=10&format=json"
        val request = Request.Builder().url(url).get().build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val geo = JSONObject(body).optJSONObject("query")?.optJSONArray("geosearch") ?: return null
            for (i in 0 until geo.length()) {
                val title = geo.getJSONObject(i).optString("title")
                if (title.isBlank()) continue
                findWikipediaPageImage(title)?.let { return it }
                findWikidataImageForTitle(title)?.let { return it }
            }
        }
        return null
    }

    private fun findWikidataImageForTitle(title: String): String? {
        val encodedTitle = URLEncoder.encode(title, "UTF-8")
        val propsUrl = "https://en.wikipedia.org/w/api.php?action=query&titles=$encodedTitle&prop=pageprops&format=json"
        val request = Request.Builder().url(propsUrl).get().build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val pages = JSONObject(body).optJSONObject("query")?.optJSONObject("pages") ?: return null
            val keys = pages.keys()
            while (keys.hasNext()) {
                val qid = pages.optJSONObject(keys.next())?.optJSONObject("pageprops")?.optString("wikibase_item")
                if (qid.isNullOrBlank()) continue
                val entityUrl = "https://www.wikidata.org/wiki/Special:EntityData/$qid.json"
                val entityRequest = Request.Builder().url(entityUrl).get().build()
                httpClient.newCall(entityRequest).execute().use { entityResponse ->
                    if (!entityResponse.isSuccessful) return@use
                    val entityBody = entityResponse.body?.string() ?: return@use
                    val entity = JSONObject(entityBody).optJSONObject("entities")?.optJSONObject(qid) ?: return@use
                    val p18 = entity.optJSONObject("claims")?.optJSONArray("P18") ?: return@use
                    if (p18.length() == 0) return@use
                    val imageName = p18.optJSONObject(0)
                        ?.optJSONObject("mainsnak")
                        ?.optJSONObject("datavalue")
                        ?.optString("value")
                    if (imageName.isNullOrBlank()) return@use
                    val fileTitle = URLEncoder.encode(imageName, "UTF-8")
                    val imageInfoUrl = "https://commons.wikimedia.org/w/api.php?action=query&titles=$fileTitle&prop=imageinfo&iiprop=url&format=json"
                    val infoRequest = Request.Builder().url(imageInfoUrl).get().build()
                    httpClient.newCall(infoRequest).execute().use { infoResponse ->
                        if (!infoResponse.isSuccessful) return@use
                        val infoBody = infoResponse.body?.string() ?: return@use
                        val infoPages = JSONObject(infoBody).optJSONObject("query")?.optJSONObject("pages") ?: return@use
                        val infoKeys = infoPages.keys()
                        while (infoKeys.hasNext()) {
                            val page = infoPages.optJSONObject(infoKeys.next()) ?: continue
                            val imageUrl = page.optJSONArray("imageinfo")?.optJSONObject(0)?.optString("url")
                            if (!imageUrl.isNullOrBlank()) return imageUrl
                        }
                    }
                }
            }
        }
        return null
    }

    private fun findCommonsImageForQuery(query: String): String? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "https://commons.wikimedia.org/w/api.php?action=query&list=search&srsearch=$encoded&format=json&srlimit=6"
        val request = Request.Builder().url(searchUrl).get().build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val results = JSONObject(body).optJSONObject("query")?.optJSONArray("search") ?: return null
            for (i in 0 until results.length()) {
                val title = results.getJSONObject(i).optString("title")
                if (!title.startsWith("File:") && !title.startsWith("Image:")) continue
                val encodedTitle = URLEncoder.encode(title, "UTF-8")
                val imageInfoUrl = "https://commons.wikimedia.org/w/api.php?action=query&titles=$encodedTitle&prop=imageinfo&iiprop=url&format=json"
                val infoRequest = Request.Builder().url(imageInfoUrl).get().build()
                httpClient.newCall(infoRequest).execute().use { infoResponse ->
                    if (!infoResponse.isSuccessful) return@use
                    val infoBody = infoResponse.body?.string() ?: return@use
                    val pages = JSONObject(infoBody).optJSONObject("query")?.optJSONObject("pages") ?: return@use
                    val keys = pages.keys()
                    while (keys.hasNext()) {
                        val page = pages.optJSONObject(keys.next()) ?: continue
                        val imageUrl = page.optJSONArray("imageinfo")?.optJSONObject(0)?.optString("url")
                        if (!imageUrl.isNullOrBlank()) return imageUrl
                    }
                }
            }
        }
        return null
    }

    private fun findGoogleImageUrlForQuery(query: String): String? {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://www.google.com/search?tbm=isch&q=$encoded"
            val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Accept-Language", "en-US,en;q=0.9")
                .get()
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val callbackRegex = Regex("AF_initDataCallback\\((.*?)\\);", RegexOption.DOT_MATCHES_ALL)
                val urlRegex = Regex("https?://[^\\s\"'<>\\)\\]]+")
                val imageExt = Regex("\\.(jpg|jpeg|png|webp|gif)", RegexOption.IGNORE_CASE)
                val blacklist = listOf(
                    "/sprite",
                    "googleusercontent.com/static",
                    "gstatic.com/images/branding",
                    "/logos/",
                    "/icons/",
                    "data:image/svg",
                    "data:image/png;base64",
                    "placeholder",
                    "dots",
                )
                val candidates = mutableListOf<String>()
                callbackRegex.findAll(body).forEach { match ->
                    candidates.addAll(urlRegex.findAll(match.groupValues[1]).map { it.value })
                }
                Regex("(?i)(?:data-src|src)=[\"'](https?://[^\"'>]+)[\"']").find(body)?.let {
                    candidates.add(it.groupValues[1])
                }
                Regex("\\\\\"ou\\\\\":\\\\\"(https?://[^\\\\\"]+)\\\\\"").find(body)?.let {
                    candidates.add(it.groupValues[1].replace("\\u0026", "&"))
                }

                val filtered = candidates
                    .map { it.trim() }
                    .distinct()
                    .filter { it.startsWith("http") }
                    .filterNot { candidate -> blacklist.any { blocked -> candidate.contains(blocked, ignoreCase = true) } }

                val ordered = filtered.filter { imageExt.containsMatchIn(it) } + filtered.filterNot { imageExt.containsMatchIn(it) }
                for (candidate in ordered.distinct()) {
                    try {
                        val headRequest = Request.Builder().url(candidate).head().build()
                        httpClient.newCall(headRequest).execute().use { headResponse ->
                            if (headResponse.isSuccessful) {
                                val contentType = headResponse.header("Content-Type") ?: ""
                                val contentLength = headResponse.header("Content-Length")?.toLongOrNull() ?: -1L
                                if (contentType.startsWith("image/") && contentLength > 1000) return candidate
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
                filtered.firstOrNull()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Google image fallback failed for query='$query'", e)
            null
        }
    }

    private fun downloadBitmap(url: String): Bitmap? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Android) ScenicNavigation/1.0")
                .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                .get()
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val stream = response.body?.byteStream() ?: return null
                val bitmap = BitmapFactory.decodeStream(stream) ?: return null
                if (bitmap.width < 64 || bitmap.height < 64) return null
                bitmap
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to download bitmap: $url", e)
            null
        }
    }

    private fun createPlaceholder(title: String, width: Int = 800, height: Int = 480): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = ((title.hashCode() and 0x00FFFFFF) or 0xFF000000.toInt())
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        val overlay = Paint(Paint.ANTI_ALIAS_FLAG)
        overlay.color = Color.argb(96, 0, 0, 0)
        canvas.drawRect(0f, height - 160f, width.toFloat(), height.toFloat(), overlay)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        textPaint.color = Color.WHITE
        textPaint.textSize = 48f
        textPaint.isFakeBoldText = true
        canvas.drawText(title.take(24), 28f, height - 64f, textPaint)
        return bitmap
    }

    private fun cacheKey(poi: Poi): String {
        val lat = poi.lat?.toString() ?: "na"
        val lon = poi.lon?.toString() ?: "na"
        val imageUrl = normalizeImageUrl(poi.imageUrl) ?: ""
        return "${poi.name}|${poi.category}|${poi.municipality}|${poi.province}|${poi.photoHint}|$lat|$lon|$imageUrl"
    }

    private fun normalizeImageUrl(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) return null
        if (value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true)) {
            return value
        }
        return null
    }
}
