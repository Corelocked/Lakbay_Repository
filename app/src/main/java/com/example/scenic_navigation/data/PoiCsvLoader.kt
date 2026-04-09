package com.example.scenic_navigation.data

import android.content.res.AssetManager
import com.example.scenic_navigation.models.Poi
import java.io.BufferedReader
import java.io.InputStreamReader

object PoiCsvLoader {
    private val csvSplit = Regex(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")

    data class ParsedPoiRow(
        val poi: Poi,
        val location: String
    )

    fun loadFromAssets(assetManager: AssetManager, path: String = "datasets/luzon_dataset.csv"): List<ParsedPoiRow> {
        assetManager.open(path).use { stream ->
            BufferedReader(InputStreamReader(stream)).use { reader ->
                val header = reader.readLine() ?: return emptyList()
                val columns = parseCsvLine(header).mapIndexed { index, name ->
                    normalize(name) to index
                }.toMap()

                return reader.lineSequence()
                    .filter { it.isNotBlank() }
                    .mapNotNull { line -> parseRow(line, columns) }
                    .toList()
            }
        }
    }

    private fun parseRow(line: String, columns: Map<String, Int>): ParsedPoiRow? {
        val parts = parseCsvLine(line)
        val name = value(parts, columns, "name")
        val category = value(parts, columns, "category")
        val location = value(parts, columns, "location")
        val description = value(parts, columns, "description")
        val municipality = value(parts, columns, "municipality").ifBlank {
            location.split(",").firstOrNull()?.trim().orEmpty()
        }
        val province = value(parts, columns, "province").ifBlank {
            location.split(",").lastOrNull()?.trim().orEmpty()
        }
        val photoHint = value(parts, columns, "photohint").ifBlank {
            buildPhotoHint(name, municipality, province)
        }
        val imageUrl = value(parts, columns, "image_url")
        val tags = value(parts, columns, "tags")
            .split("|")
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val lat = value(parts, columns, "lat").toDoubleOrNull()
        val lon = value(parts, columns, "lon").toDoubleOrNull()
        if (name.isBlank() || lat == null || lon == null) return null

        return ParsedPoiRow(
            poi = Poi(
                name = name,
                category = category,
                description = description,
                municipality = municipality,
                lat = lat,
                lon = lon,
                province = province,
                tags = tags,
                photoHint = photoHint,
                imageUrl = imageUrl
            ),
            location = location
        )
    }

    private fun value(parts: List<String>, columns: Map<String, Int>, key: String): String {
        val index = columns[key] ?: return ""
        return parts.getOrNull(index)?.trim()?.removeSurrounding("\"").orEmpty()
    }

    private fun normalize(value: String): String {
        return value.trim().removeSurrounding("\"").lowercase().replace(" ", "")
    }

    private fun parseCsvLine(line: String): List<String> {
        return line.split(csvSplit).map { it.trim() }
    }

    private fun buildPhotoHint(name: String, municipality: String, province: String): String {
        return listOf(name, municipality, province, "Philippines")
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }
}
