package com.example.scenic_navigation.utils

import com.example.scenic_navigation.models.Poi
import java.util.Locale

object PoiTagFormatter {
    fun formatPrimaryTags(poi: Poi, maxTags: Int = 3, preferredTokens: Set<String> = emptySet()): String {
        val cleaned = formatTagList(poi, maxTags, preferredTokens)

        return if (cleaned.isNotEmpty()) {
            cleaned.joinToString(" · ")
        } else {
            "POI"
        }
    }

    fun formatTagList(poi: Poi, maxTags: Int = 3, preferredTokens: Set<String> = emptySet()): List<String> {
        val sourceTags = if (poi.tags.isNotEmpty()) poi.tags else poi.category.split("/", "|")
        val normalizedPreferred = preferredTokens
            .map { canonicalToken(normalizeToken(it)) }
            .filter { it.isNotBlank() }
            .toSet()

        return sourceTags
            .map { normalizeTag(it) }
            .filter { it.isNotBlank() }
            .distinctBy { canonicalToken(normalizeToken(it)) }
            .sortedWith(
                compareByDescending<String> { tag ->
                    val normalizedTag = canonicalToken(normalizeToken(tag))
                    normalizedPreferred.any { token ->
                        normalizedTag.contains(token) || token.contains(normalizedTag)
                    }
                }.thenBy { it }
            )
            .take(maxTags)
    }

    private fun normalizeTag(raw: String): String {
        val compact = canonicalToken(normalizeToken(raw))

        if (compact.isBlank()) return ""

        return compact
            .split(' ')
            .joinToString(" ") { word ->
                when (word) {
                    "poi" -> "POI"
                    "and" -> "and"
                    else -> word.replaceFirstChar { it.titlecase(Locale.US) }
                }
            }
    }

    private fun normalizeToken(raw: String): String {
        return raw
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase(Locale.US)
    }

    private fun canonicalToken(raw: String): String {
        return when {
            raw in setOf("historic", "historical", "historical site", "heritage", "monument", "landmark") -> "heritage"
            raw in setOf("museum", "gallery", "art", "cultural", "culture") -> "culture"
            raw in setOf("beach", "coastal", "coast", "bay", "ocean", "shore", "island") -> "beach"
            raw in setOf("mountain", "peak", "ridge", "volcano", "hiking", "trail", "adventure", "hike") -> "mountain"
            raw in setOf("restaurant", "food", "dining", "cafe", "bakery", "deli", "wine") -> "dining"
            raw in setOf("park", "nature", "nature park", "garden", "waterfall", "forest") -> "nature"
            raw in setOf("relaxation", "relax", "wellness", "spa", "resort") -> "relaxation"
            raw in setOf("family", "family friendly", "playground", "zoo", "picnic") -> "family"
            raw in setOf("romantic", "sunset") -> "romantic"
            raw in setOf("shopping", "shop", "market", "souvenir", "mall") -> "shopping"
            raw in setOf("view", "viewpoint", "scenic", "tourism", "tourist attraction", "attraction") -> "scenic"
            else -> raw
        }
    }
}
