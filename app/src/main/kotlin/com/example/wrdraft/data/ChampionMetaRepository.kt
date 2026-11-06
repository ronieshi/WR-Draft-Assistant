package com.example.wrdraft.data

import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

data class ChampionWinRate(
    val percentage: Double,
    val patch: String,
)

object ChampionMetaRepository {
    private data class CachedWinRate(val value: ChampionWinRate?, val loadedAt: Long)

    private val cache = mutableMapOf<String, CachedWinRate>()
    private val executor = Executors.newFixedThreadPool(3)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun loadWinRate(
        champion: Champion,
        callback: (Result<ChampionWinRate?>) -> Unit,
    ) {
        synchronized(cache) {
            cache[champion.slug]
                ?.takeIf { System.currentTimeMillis() - it.loadedAt < CACHE_DURATION_MS }
                ?.let {
                    mainHandler.post { callback(Result.success(it.value)) }
                    return
                }
        }

        executor.execute {
            val result = try {
                Result.success(fetchWinRate(champion.slug))
            } catch (error: IOException) {
                Result.failure(error)
            }
            if (result.isSuccess) {
                synchronized(cache) {
                    cache[champion.slug] =
                        CachedWinRate(result.getOrNull(), System.currentTimeMillis())
                }
            }
            mainHandler.post { callback(result) }
        }
    }

    private fun fetchWinRate(slug: String): ChampionWinRate? {
        val connection = URL(ChampionLinks.wildRiftCoreProfileUrl(slug))
            .openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("User-Agent", "WR-Draft-Assistant/1.0")
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP ${connection.responseCode}")
            }
            val html = connection.inputStream.bufferedReader().use { it.readText() }
            parseWinRate(html)
        } finally {
            connection.disconnect()
        }
    }

    internal fun parseWinRate(html: String): ChampionWinRate? {
        val patch = PATCH_REGEX.find(html)?.groupValues?.getOrNull(1) ?: return null
        val percentage = WIN_RATE_REGEX.find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()
            ?: return null
        return ChampionWinRate(percentage, patch)
    }

    private const val CACHE_DURATION_MS = 10 * 60 * 1000L
    private val PATCH_REGEX = Regex("""Patch\s+([0-9]+\.[0-9]+[a-z]?)""", RegexOption.IGNORE_CASE)
    private val WIN_RATE_REGEX = Regex(
        """id="trend-block-t"[^>]*>\s*Win Rate \(30d\)\s*</span>\s*<span class="trend-block__stat-val">\s*([0-9]+(?:\.[0-9]+)?)%""",
        RegexOption.IGNORE_CASE,
    )
}
