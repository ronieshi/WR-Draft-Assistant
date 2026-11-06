package com.example.wrdraft.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class ChampionBuild(val items: List<String>, val runes: List<String>)

data class ChampionStats(val name: String, val winRate: Double?, val build: ChampionBuild?)

/**
 * Carga la lista de campeones desde un feed JSON propio.
 * Formato esperado:
 * {"champions":[{"name":"Ahri","winRate":51.2,"build":{"items":["..."],"runes":["..."]}}]}
 */
object ChampionRepository {
    // Sustituir por la URL real del feed JSON que publiques.
    const val FEED_URL = "https://example.com/wr/champions.json"
    private const val CACHE_FILE = "champions_feed.json"

    fun parse(json: String): List<ChampionStats> {
        val arr = JSONObject(json).getJSONArray("champions")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val build = o.optJSONObject("build")?.let { b ->
                ChampionBuild(b.optStringList("items"), b.optStringList("runes"))
            }
            ChampionStats(
                name = o.getString("name"),
                winRate = if (o.has("winRate")) o.getDouble("winRate") else null,
                build = build,
            )
        }
    }

    private fun JSONObject.optStringList(key: String): List<String> {
        val a = optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).map { a.getString(it) }
    }

    private fun fetchRemote(): String {
        val conn = URL(FEED_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        try {
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Intenta descargar el feed y guardarlo en caché. Si falla, usa la última copia
     * guardada y, si tampoco existe, el dataset local de ejemplo.
     */
    fun load(context: Context): List<ChampionStats> {
        val file = File(context.filesDir, CACHE_FILE)
        return try {
            val json = fetchRemote()
            val parsed = parse(json) // validar antes de guardar
            file.writeText(json)
            parsed
        } catch (e: Exception) {
            try {
                parse(file.readText())
            } catch (ignored: Exception) {
                ChampionData.champions.map { ChampionStats(it, null, null) }
            }
        }
    }
}
