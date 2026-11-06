package com.example.wrdraft.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Html
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

enum class ChampionSection(val id: String, val label: String) {
    COUNTERS("counters", "Counterpicks"),
    SYNERGIES("synergies", "Sinergias"),
    BUILD("build", "Build"),
    GUIDE("guide", "Guía"),
}

data class ChampionSectionContent(
    val text: String,
    val patch: String?,
    val sourceUrl: String,
    val fetchedAt: Long,
)

data class ChampionSectionState(
    val content: ChampionSectionContent?,
    val isRefreshing: Boolean,
    val error: Boolean,
)

object ChampionSectionRepository {
    private val executor = Executors.newFixedThreadPool(3)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun load(
        context: Context,
        champion: Champion,
        section: ChampionSection,
        callback: (ChampionSectionState) -> Unit,
    ) {
        val database = ChampionSectionDatabase(context.applicationContext)
        executor.execute {
            var cached: ChampionSectionContent? = null
            try {
                post(callback, ChampionSectionState(null, isRefreshing = true, error = false))
                cached = database.read(champion.slug, section)
                post(callback, ChampionSectionState(cached, isRefreshing = true, error = false))
                val url = sourceUrl(champion, section)
                val html = download(url)
                val content = parse(html, url)
                database.write(champion.slug, section, content)
                cached = content
                post(callback, ChampionSectionState(content, isRefreshing = false, error = false))
            } catch (error: Exception) {
                Log.w(
                    LOG_TAG,
                    "Unable to load ${champion.slug}/${section.id} for offline use",
                    error,
                )
                post(callback, ChampionSectionState(cached, isRefreshing = false, error = true))
            } finally {
                database.close()
            }
        }
    }

    fun loadAll(
        context: Context,
        champion: Champion,
        callback: (Map<ChampionSection, ChampionSectionState>) -> Unit,
    ) {
        val states = mutableMapOf<ChampionSection, ChampionSectionState>()
        ChampionSection.values().forEach { section ->
            load(context, champion, section) { state ->
                states[section] = state
                callback(states.toMap())
            }
        }
    }

    private fun sourceUrl(champion: Champion, section: ChampionSection): String =
        when (section) {
            ChampionSection.COUNTERS -> ChampionLinks.wildRiftCoreCountersUrl(champion.slug)
            ChampionSection.SYNERGIES -> ChampionLinks.wildRiftCoreSynergiesUrl(champion.slug)
            ChampionSection.BUILD -> ChampionLinks.wildRiftCoreBuildUrl(champion.slug)
            ChampionSection.GUIDE -> ChampionLinks.wildRiftFireUrl(champion.slug)
        }

    private fun download(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("User-Agent", "WR-Draft-Assistant/1.0")
        return try {
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(html: String, sourceUrl: String): ChampionSectionContent {
        val main = MAIN_REGEX.find(html)?.groupValues?.getOrNull(1)
            ?: BODY_REGEX.find(html)?.groupValues?.getOrNull(1)
            ?: throw IllegalStateException("Source page did not contain readable content")
        val readableHtml = main
            .replace(COMMENT_REGEX, " ")
            .replace(HIDDEN_BLOCK_REGEX, " ")
            .replace(BREAK_REGEX, "\n")
            .replace(BLOCK_END_REGEX, "\n")
            .replace(TAG_REGEX, " ")
        val decoded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Html.fromHtml(readableHtml, Html.FROM_HTML_MODE_COMPACT)
        } else {
            @Suppress("DEPRECATION")
            Html.fromHtml(readableHtml)
        }
        val text = decoded
            .toString()
            .replace('\u00a0', ' ')
            .replace(WHITESPACE_REGEX, " ")
            .replace(NEWLINE_SPACE_REGEX, "\n")
            .replace(REPEATED_NEWLINES_REGEX, "\n\n")
            .trim()
            .take(MAX_CONTENT_LENGTH)
        if (text.isBlank()) throw IllegalStateException("Source page had no readable content")

        return ChampionSectionContent(
            text = text,
            patch = PATCH_REGEX.find(html)?.groupValues?.getOrNull(1),
            sourceUrl = sourceUrl,
            fetchedAt = System.currentTimeMillis(),
        )
    }

    private fun post(
        callback: (ChampionSectionState) -> Unit,
        state: ChampionSectionState,
    ) {
        mainHandler.post { callback(state) }
    }

    private const val MAX_CONTENT_LENGTH = 150_000
    private const val LOG_TAG = "ChampionSectionRepository"
    private val MAIN_REGEX = Regex("""<main\b[^>]*>([\s\S]*?)</main\s*>""", RegexOption.IGNORE_CASE)
    private val BODY_REGEX = Regex("""<body\b[^>]*>([\s\S]*?)</body\s*>""", RegexOption.IGNORE_CASE)
    private val COMMENT_REGEX = Regex("""<!--[\s\S]*?-->""")
    private val HIDDEN_BLOCK_REGEX = Regex(
        """<(script|style|noscript|svg|nav|footer)\b[^>]*>[\s\S]*?</\1\s*>""",
        RegexOption.IGNORE_CASE,
    )
    private val BREAK_REGEX = Regex("""<br\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val BLOCK_END_REGEX = Regex(
        """</(p|div|section|article|h[1-6]|li|tr|details|summary|header)\s*>""",
        RegexOption.IGNORE_CASE,
    )
    private val TAG_REGEX = Regex("""<[^>]+>""")
    private val PATCH_REGEX = Regex("""Patch\s+([0-9]+\.[0-9]+[a-z]?)""", RegexOption.IGNORE_CASE)
    private val WHITESPACE_REGEX = Regex("""[ \t\f]+""")
    private val NEWLINE_SPACE_REGEX = Regex(""" *\n *""")
    private val REPEATED_NEWLINES_REGEX = Regex("""\n{3,}""")
}

private class ChampionSectionDatabase(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_NAME (
                champion_slug TEXT NOT NULL,
                section_id TEXT NOT NULL,
                content TEXT NOT NULL,
                patch TEXT,
                source_url TEXT NOT NULL,
                fetched_at INTEGER NOT NULL,
                PRIMARY KEY (champion_slug, section_id)
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun read(championSlug: String, section: ChampionSection): ChampionSectionContent? =
        readableDatabase.query(
            TABLE_NAME,
            arrayOf("content", "patch", "source_url", "fetched_at"),
            "champion_slug = ? AND section_id = ?",
            arrayOf(championSlug, section.id),
            null,
            null,
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                ChampionSectionContent(
                    text = cursor.getString(0),
                    patch = if (cursor.isNull(1)) null else cursor.getString(1),
                    sourceUrl = cursor.getString(2),
                    fetchedAt = cursor.getLong(3),
                )
            }
        }

    fun write(
        championSlug: String,
        section: ChampionSection,
        content: ChampionSectionContent,
    ) {
        val values = ContentValues().apply {
            put("champion_slug", championSlug)
            put("section_id", section.id)
            put("content", content.text)
            put("patch", content.patch)
            put("source_url", content.sourceUrl)
            put("fetched_at", content.fetchedAt)
        }
        val rowId = writableDatabase.insertWithOnConflict(
            TABLE_NAME,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        check(rowId >= 0) { "Could not save champion section offline" }
    }

    private companion object {
        const val DATABASE_NAME = "champion_sections.db"
        const val DATABASE_VERSION = 1
        const val TABLE_NAME = "champion_sections"
    }
}
