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
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

enum class ChampionSection(val id: String, val label: String) {
    COUNTERS("counters", "Counterpicks"),
    SYNERGIES("synergies", "Sinergias"),
    BUILD("build", "Build"),
}

data class ChampionSectionContent(
    val text: String,
    val patch: String?,
    val sourceUrl: String,
    val fetchedAt: Long,
    val cards: List<ChampionVisualCard> = emptyList(),
    val championRoles: List<String> = emptyList(),
)

data class ChampionVisualCard(
    val title: String,
    val subtitle: String? = null,
    val detail: String? = null,
    val iconUrl: String? = null,
    val rank: String? = null,
    val category: String? = null,
    val role: String? = null,
    val items: List<ChampionVisualItem> = emptyList(),
)

data class ChampionVisualItem(
    val name: String,
    val iconUrl: String? = null,
)

data class ChampionSectionState(
    val content: ChampionSectionContent?,
    val isRefreshing: Boolean,
    val error: Boolean,
)

fun ChampionSectionContent.preferredSynergyRole(): String? = when {
    "ADC" in championRoles -> "ADC"
    "Support" in championRoles -> "Support"
    else -> championRoles.firstOrNull()
}

fun ChampionSectionContent.synergyCardsForRole(role: String?): List<ChampionVisualCard> =
    when (role) {
        "ADC" -> cards.filter { it.subtitle.equals("Support", ignoreCase = true) }
        "Support" -> cards.sortedBy {
            if (it.subtitle.equals("ADC", ignoreCase = true)) 0 else 1
        }
        else -> cards
    }

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
                val content = parse(html, url, section)
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

    private fun parse(
        html: String,
        sourceUrl: String,
        section: ChampionSection,
    ): ChampionSectionContent {
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
            cards = parseCards(main, section, sourceUrl),
            championRoles = parseChampionRoles(html),
        )
    }

    private fun parseChampionRoles(html: String): List<String> =
        CHAMPION_ROLE_REGEX.findAll(html)
            .flatMap { match ->
                plainText(match.groupValues[1])
                    .replace("★", "")
                    .split(ROLE_SEPARATOR_REGEX)
            }
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map { role ->
                roleLabel(role).let { if (it == "Dragon Lane") "ADC" else it }
            }
            .distinct()

    private fun parseCards(
        html: String,
        section: ChampionSection,
        sourceUrl: String,
    ): List<ChampionVisualCard> = when (section) {
        ChampionSection.COUNTERS -> parseCounterCards(html, sourceUrl)
        ChampionSection.SYNERGIES -> parseSynergyCards(html, sourceUrl)
        ChampionSection.BUILD -> parseBuildCards(html, sourceUrl)
    }

    private fun parseCounterCards(html: String, sourceUrl: String): List<ChampionVisualCard> {
        val panels = ROLE_COUNTER_PANEL_REGEX.findAll(html).toList()
        if (panels.isEmpty()) return parseCounterPanel(html, sourceUrl, role = null)

        val activeRole = ACTIVE_COUNTER_ROLE_REGEX.find(html)?.groupValues?.getOrNull(1)
        val roleCards = panels.mapIndexed { index, panel ->
            val end = panels.getOrNull(index + 1)?.range?.first ?: html.length
            val role = roleLabel(panel.groupValues[1])
            role to parseCounterPanel(html.substring(panel.range.first, end), sourceUrl, role)
        }
        val orderedRoleCards = if (activeRole == null) roleCards else roleCards.sortedBy { (role, _) ->
            if (role.equals(roleLabel(activeRole), ignoreCase = true)) 0 else 1
        }
        return orderedRoleCards.flatMap { it.second }
    }

    private fun parseCounterPanel(
        html: String,
        sourceUrl: String,
        role: String?,
    ): List<ChampionVisualCard> {
        val cards = mutableListOf<ChampionVisualCard>()
        BEST_PICKS_REGEX.find(html)?.groupValues?.getOrNull(1)?.let { list ->
            BEST_PICK_REGEX.findAll(list).forEach { match ->
                val entry = match.groupValues[1]
                val name = textFrom(entry, BEST_PICK_NAME_REGEX) ?: return@forEach
                cards += ChampionVisualCard(
                    title = name,
                    iconUrl = imageUrl(entry, sourceUrl),
                    rank = textFrom(entry, RANK_REGEX),
                    category = "Mejores elecciones",
                    detail = textFrom(entry, EDGE_REGEX)?.let { "Ventaja $it" },
                    role = role,
                )
            }
        }

        AVOID_SECTION_REGEX.find(html)?.groupValues?.getOrNull(1)?.let { block ->
            AVOID_LINE_REGEX.findAll(block).forEach { match ->
                val entry = match.groupValues[1]
                val name = textFrom(entry, BOLD_TEXT_REGEX) ?: return@forEach
                cards += ChampionVisualCard(
                    title = name,
                    iconUrl = imageUrl(entry, sourceUrl),
                    rank = textFrom(entry, EDGE_REGEX),
                    category = "Evitar contra este campeón",
                    role = role,
                )
            }
        }

        KEY_ITEM_SECTION_REGEX.find(html)?.groupValues?.getOrNull(1)?.let { block ->
            textFrom(block, BOLD_TEXT_REGEX)?.let { name ->
                cards += ChampionVisualCard(
                    title = name,
                    subtitle = textFrom(block, SMALL_TEXT_REGEX),
                    iconUrl = imageUrl(block, sourceUrl),
                    category = "Objeto clave",
                    role = role,
                )
            }
        }
        return cards
    }

    private fun parseSynergyCards(html: String, sourceUrl: String): List<ChampionVisualCard> {
        val cards = mutableListOf<ChampionVisualCard>()
        SYNERGY_TIER_REGEX.findAll(html).forEach { tierMatch ->
            val block = tierMatch.groupValues[1]
            val tier = textFrom(block, TIER_TITLE_REGEX) ?: "Sinergia"
            val starts = SYNERGY_CARD_START_REGEX.findAll(block).map { it.range.first }.toList()
            starts.forEachIndexed { index, start ->
                val end = starts.getOrNull(index + 1) ?: block.length
                val entry = block.substring(start, end)
                val name = textFrom(entry, SYNERGY_NAME_REGEX) ?: return@forEachIndexed
                cards += ChampionVisualCard(
                    title = name,
                    subtitle = textFrom(entry, SYNERGY_ROLE_REGEX),
                    detail = textFrom(entry, SYNERGY_DETAIL_REGEX),
                    iconUrl = imageUrl(entry, sourceUrl),
                    category = tier.replace('_', ' ').uppercase(),
                )
            }
        }
        return cards
    }

    private fun parseBuildCards(html: String, sourceUrl: String): List<ChampionVisualCard> {
        val roleLabels = BUILD_ROLE_NAV_REGEX.find(html)?.groupValues?.getOrNull(1)
            ?.let { nav ->
                BUILD_ROLE_LABEL_REGEX.findAll(nav)
                    .map { roleLabel(plainText(it.groupValues[1])) }
                    .toList()
            }
            .orEmpty()
        val panels = BUILD_ROLE_PANEL_REGEX.findAll(html).toList()
        if (panels.isEmpty()) return parseBuildPanel(html, sourceUrl, role = null)

        return panels.flatMapIndexed { index, panel ->
            val end = panels.getOrNull(index + 1)?.range?.first ?: html.length
            val block = html.substring(panel.range.first, end)
            val titleRole = BUILD_ROLE_TITLE_REGEX.find(block)?.groupValues?.getOrNull(1)
            val role = roleLabels.getOrNull(index)
                ?: titleRole?.let(::roleLabel)
            parseBuildPanel(block, sourceUrl, role)
        }
    }

    private fun parseBuildPanel(
        html: String,
        sourceUrl: String,
        role: String?,
    ): List<ChampionVisualCard> =
        BUILD_VARIANT_REGEX.findAll(html).mapNotNull variant@{ match ->
            val block = match.groupValues[1]
            val title = textFrom(block, BUILD_TITLE_REGEX) ?: return@variant null
            val items = BUILD_ITEM_REGEX.findAll(block).mapNotNull item@{ itemMatch ->
                val itemBlock = itemMatch.groupValues[1]
                val itemName = textFrom(itemBlock, BUILD_ITEM_NAME_REGEX) ?: return@item null
                ChampionVisualItem(itemName, imageUrl(itemBlock, sourceUrl))
            }.toList()
            ChampionVisualCard(
                title = title,
                detail = textFrom(block, BUILD_DESCRIPTION_REGEX),
                category = "Build",
                role = role,
                items = items,
            )
        }.toList()

    private fun roleLabel(rawRole: String): String {
        val role = rawRole.trim()
        return when {
            role.contains("support", ignoreCase = true) -> "Support"
            role.contains("jungle", ignoreCase = true) -> "Jungle"
            role.contains("mid", ignoreCase = true) -> "Mid"
            role.contains("top", ignoreCase = true) ||
                role.contains("baron", ignoreCase = true) -> "Top"
            role.contains("dragon", ignoreCase = true) ||
                role.contains("duo", ignoreCase = true) ||
                role.contains("adc", ignoreCase = true) -> "Dragon Lane"
            else -> role
        }
    }

    private fun imageUrl(html: String, baseUrl: String): String? {
        val path = IMAGE_SRC_REGEX.find(html)?.groupValues?.getOrNull(1) ?: return null
        return try {
            val resolved = URL(URL(baseUrl), path)
            if (resolved.protocol == "https" &&
                (resolved.host == "wildriftcore.com" || resolved.host == "www.wildriftfire.com")
            ) {
                resolved.toString()
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun textFrom(html: String, regex: Regex): String? =
        regex.find(html)?.groupValues?.getOrNull(1)?.let(::plainText)?.takeIf { it.isNotBlank() }

    private fun plainText(value: String): String {
        val decoded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Html.fromHtml(value, Html.FROM_HTML_MODE_COMPACT)
        } else {
            @Suppress("DEPRECATION")
            Html.fromHtml(value)
        }
        return decoded.toString().replace(WHITESPACE_REGEX, " ").trim()
    }

    private fun post(
        callback: (ChampionSectionState) -> Unit,
        state: ChampionSectionState,
    ) {
        mainHandler.post { callback(state) }
    }

    private const val MAX_CONTENT_LENGTH = 150_000
    private const val LOG_TAG = "ChampionSectionRepository"
    private val BEST_PICKS_REGEX = Regex(
        """<ol\b[^>]*class="nx-md__best"[^>]*>([\s\S]*?)</ol\s*>""",
        RegexOption.IGNORE_CASE,
    )
    private val ROLE_COUNTER_PANEL_REGEX = Regex(
        """<div\b(?=[^>]*class="[^"]*\bnx-role-panel\b[^"]*")(?=[^>]*data-role="([^"]+)")[^>]*>""",
        RegexOption.IGNORE_CASE,
    )
    private val ACTIVE_COUNTER_ROLE_REGEX = Regex(
        """<button\b(?=[^>]*class="[^"]*\bnx-role-tab--active\b[^"]*")(?=[^>]*data-role="([^"]+)")[^>]*>""",
        RegexOption.IGNORE_CASE,
    )
    private val CHAMPION_ROLE_REGEX = Regex(
        """<span\b(?=[^>]*class="[^"]*\bcd-chip--role\b[^"]*")[^>]*>([\s\S]*?)</span\s*>""",
        RegexOption.IGNORE_CASE,
    )
    private val ROLE_SEPARATOR_REGEX = Regex("""[·/]""")
    private val BEST_PICK_REGEX = Regex("""<li\b[^>]*>([\s\S]*?)</li\s*>""", RegexOption.IGNORE_CASE)
    private val BEST_PICK_NAME_REGEX = Regex(
        """<span\b[^>]*class="nx-md__bname"[^>]*>([\s\S]*?)</span\s*>""",
        RegexOption.IGNORE_CASE,
    )
    private val RANK_REGEX = Regex(
        """<span\b[^>]*class="nx-md__brank"[^>]*>([\s\S]*?)</span\s*>""",
        RegexOption.IGNORE_CASE,
    )
    private val EDGE_REGEX = Regex(
        """<span\b[^>]*class="nx-md__[bl]edge"[^>]*>([\s\S]*?)</span\s*>""",
        RegexOption.IGNORE_CASE,
    )
    private val AVOID_SECTION_REGEX = Regex(
        """<section\b(?=[^>]*data-kind="avoid")[^>]*>([\s\S]*?)</section\s*>""",
        RegexOption.IGNORE_CASE,
    )
    private val AVOID_LINE_REGEX = Regex(
        """<div\b[^>]*class="nx-md__line"[^>]*>([\s\S]*?)</div\s*>""",
        RegexOption.IGNORE_CASE,
    )
    private val KEY_ITEM_SECTION_REGEX = Regex(
        """<section\b(?=[^>]*data-kind="item")[^>]*>([\s\S]*?)</section\s*>""",
        RegexOption.IGNORE_CASE,
    )
    private val BOLD_TEXT_REGEX = Regex("""<b\b[^>]*>([\s\S]*?)</b\s*>""", RegexOption.IGNORE_CASE)
    private val SMALL_TEXT_REGEX = Regex("""<small\b[^>]*>([\s\S]*?)</small\s*>""", RegexOption.IGNORE_CASE)
    private val SYNERGY_TIER_REGEX = Regex(
        """<details\b[^>]*class="syn-tier-block"[^>]*>([\s\S]*?)</details\s*>""",
        RegexOption.IGNORE_CASE,
    )
    private val TIER_TITLE_REGEX = Regex(
        """<summary\b[^>]*>[\s\S]*?<span>([\s\S]*?)</span>""",
        RegexOption.IGNORE_CASE,
    )
    private val SYNERGY_CARD_START_REGEX = Regex(
        """<div\b[^>]*class="nx-counter-item"[^>]*>""",
        RegexOption.IGNORE_CASE,
    )
    private val SYNERGY_NAME_REGEX = Regex(
        """<a\b[^>]*class="nx-counter-name"[^>]*>([\s\S]*?)</a\s*>""",
        RegexOption.IGNORE_CASE,
    )
    private val SYNERGY_DETAIL_REGEX = Regex(
        """<span\b[^>]*class="nx-counter-comment"[^>]*>([\s\S]*?)</span\s*>""",
        RegexOption.IGNORE_CASE,
    )
    private val SYNERGY_ROLE_REGEX = Regex(
        """<span\b[^>]*class="nx-syn-role-badge"[^>]*>([\s\S]*?)</span\s*>""",
        RegexOption.IGNORE_CASE,
    )
    private val BUILD_VARIANT_REGEX = Regex(
        """<article\b[^>]*class="engine-builds__variant"[^>]*>([\s\S]*?)</article\s*>""",
        RegexOption.IGNORE_CASE,
    )
    private val BUILD_ROLE_NAV_REGEX = Regex(
        """<div\b[^>]*class="eb-roles__nav"[^>]*>([\s\S]*?)</div\s*>""",
        RegexOption.IGNORE_CASE,
    )
    private val BUILD_ROLE_LABEL_REGEX = Regex(
        """<label\b[^>]*>([\s\S]*?)</label\s*>""",
        RegexOption.IGNORE_CASE,
    )
    private val BUILD_ROLE_PANEL_REGEX = Regex(
        """<div\b[^>]*class="eb-rolepanel"[^>]*>""",
        RegexOption.IGNORE_CASE,
    )
    private val BUILD_ROLE_TITLE_REGEX = Regex(
        """<h[34]\b[^>]*class="eb-role-title"[^>]*>[\s\S]*?\s((?:Mid(?: Lane)?|Support|Jungle|Top|Baron Lane|Dragon Lane|Duo Lane))\s*—""",
        RegexOption.IGNORE_CASE,
    )
    private val BUILD_TITLE_REGEX = Regex("""<h[34]\b[^>]*>([\s\S]*?)</h[34]\s*>""", RegexOption.IGNORE_CASE)
    private val BUILD_DESCRIPTION_REGEX = Regex(
        """<p\b[^>]*class="eb-variant-desc"[^>]*>([\s\S]*?)</p\s*>""",
        RegexOption.IGNORE_CASE,
    )
    private val BUILD_ITEM_REGEX = Regex(
        """<a\b[^>]*class="eb-item"[^>]*>([\s\S]*?)</a\s*>""",
        RegexOption.IGNORE_CASE,
    )
    private val BUILD_ITEM_NAME_REGEX = Regex(
        """<span\b[^>]*>([\s\S]*?)</span\s*>""",
        RegexOption.IGNORE_CASE,
    )
    private val IMAGE_SRC_REGEX = Regex("""<img\b[^>]*src="([^"]+)"[^>]*>""", RegexOption.IGNORE_CASE)
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
                visual_json TEXT NOT NULL DEFAULT '[]',
                champion_roles TEXT NOT NULL DEFAULT '[]',
                patch TEXT,
                source_url TEXT NOT NULL,
                fetched_at INTEGER NOT NULL,
                PRIMARY KEY (champion_slug, section_id)
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN visual_json TEXT NOT NULL DEFAULT '[]'")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN champion_roles TEXT NOT NULL DEFAULT '[]'")
        }
    }

    fun read(championSlug: String, section: ChampionSection): ChampionSectionContent? =
        readableDatabase.query(
            TABLE_NAME,
            arrayOf("content", "visual_json", "patch", "source_url", "fetched_at", "champion_roles"),
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
                    cards = decodeCards(cursor.getString(1)),
                    patch = if (cursor.isNull(2)) null else cursor.getString(2),
                    sourceUrl = cursor.getString(3),
                    fetchedAt = cursor.getLong(4),
                    championRoles = decodeRoles(cursor.getString(5)),
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
            put("visual_json", encodeCards(content.cards))
            put("patch", content.patch)
            put("source_url", content.sourceUrl)
            put("fetched_at", content.fetchedAt)
            put("champion_roles", JSONArray(content.championRoles).toString())
        }
        val rowId = writableDatabase.insertWithOnConflict(
            TABLE_NAME,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        check(rowId >= 0) { "Could not save champion section offline" }
    }

    private fun encodeCards(cards: List<ChampionVisualCard>): String = JSONArray().apply {
        cards.forEach { card ->
            put(JSONObject().apply {
                put("title", card.title)
                put("subtitle", card.subtitle)
                put("detail", card.detail)
                put("iconUrl", card.iconUrl)
                put("rank", card.rank)
                put("category", card.category)
                put("role", card.role)
                put("items", JSONArray().apply {
                    card.items.forEach { item ->
                        put(JSONObject().apply {
                            put("name", item.name)
                            put("iconUrl", item.iconUrl)
                        })
                    }
                })
            })
        }
    }.toString()

    private fun decodeRoles(json: String): List<String> = runCatching {
        val array = JSONArray(json)
        (0 until array.length()).map(array::getString)
    }.getOrDefault(emptyList())

    private fun decodeCards(json: String): List<ChampionVisualCard> = runCatching {
        val array = JSONArray(json)
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val items = item.optJSONArray("items") ?: JSONArray()
            ChampionVisualCard(
                title = item.getString("title"),
                subtitle = item.optString("subtitle").takeIf { it.isNotBlankOrNull() },
                detail = item.optString("detail").takeIf { it.isNotBlankOrNull() },
                iconUrl = item.optString("iconUrl").takeIf { it.isNotBlankOrNull() },
                rank = item.optString("rank").takeIf { it.isNotBlankOrNull() },
                category = item.optString("category").takeIf { it.isNotBlankOrNull() },
                role = item.optString("role").takeIf { it.isNotBlankOrNull() },
                items = (0 until items.length()).map { itemIndex ->
                    val visualItem = items.getJSONObject(itemIndex)
                    ChampionVisualItem(
                        name = visualItem.getString("name"),
                        iconUrl = visualItem.optString("iconUrl").takeIf { it.isNotBlankOrNull() },
                    )
                },
            )
        }
    }.getOrDefault(emptyList())

    private fun String.isNotBlankOrNull(): Boolean = isNotBlank() && this != "null"

    private companion object {
        const val DATABASE_NAME = "champion_sections.db"
        const val DATABASE_VERSION = 3
        const val TABLE_NAME = "champion_sections"
    }
}
