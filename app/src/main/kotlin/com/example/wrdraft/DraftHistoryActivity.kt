package com.example.wrdraft

import android.os.Bundle
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import com.example.wrdraft.data.Champion
import com.example.wrdraft.data.ChampionData
import com.example.wrdraft.data.ChampionIconLoader
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private const val HISTORY_PREFS = "personal_draft_history"
private const val HISTORY_KEY = "drafts"
private val ROLE_NAMES = listOf("Baron", "Jungla", "Medio", "Dragón", "Soporte")

private data class DraftRecord(
    val id: String,
    val ownChampion: String,
    val wonAgainst: List<String>,
    val lostAgainst: List<String>,
)

/** A companion screen for keeping a local, five-role matchup notebook. */
class DraftHistoryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(HISTORY_PREFS, MODE_PRIVATE)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    DraftHistoryScreen(
                        initialDrafts = loadDrafts(prefs.getString(HISTORY_KEY, "[]")),
                        onSave = { drafts ->
                            prefs.edit().putString(HISTORY_KEY, encodeDrafts(drafts)).apply()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DraftHistoryScreen(
    initialDrafts: List<DraftRecord>,
    onSave: (List<DraftRecord>) -> Unit,
) {
    val drafts = remember { mutableStateListOf<DraftRecord>().also { it.addAll(initialDrafts) } }
    var editing by remember { mutableStateOf(false) }
    var own by remember { mutableStateOf("") }
    var won by remember { mutableStateOf(List(5) { "" }) }
    var lost by remember { mutableStateOf(List(5) { "" }) }
    var picker by remember { mutableStateOf<PickerTarget?>(null) }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Mis drafts", style = MaterialTheme.typography.headlineSmall)
            if (!editing) {
                Button(onClick = {
                    own = ""
                    won = List(5) { "" }
                    lost = List(5) { "" }
                    editing = true
                }) { Text("+ Añadir") }
            }
        }
        Text("Historial guardado solo en este dispositivo.", style = MaterialTheme.typography.bodyMedium)

        if (editing) {
            Text("Mi equipo", style = MaterialTheme.typography.titleMedium)
            ChampionSlot(
                label = "Mi campeón",
                championSlug = own,
                onClick = { picker = PickerTarget("own", -1) },
            )
            Text("Victoria", style = MaterialTheme.typography.titleMedium)
            ROLE_NAMES.forEachIndexed { index, role ->
                ChampionSlot(role, won[index]) { picker = PickerTarget("won", index) }
            }
            Text("Derrota", style = MaterialTheme.typography.titleMedium)
            ROLE_NAMES.forEachIndexed { index, role ->
                ChampionSlot(role, lost[index]) { picker = PickerTarget("lost", index) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = own.isNotBlank(), onClick = {
                    drafts.add(0, DraftRecord(UUID.randomUUID().toString(), own, won, lost))
                    onSave(drafts.toList())
                    editing = false
                }) { Text("Guardar draft") }
                OutlinedButton(onClick = { editing = false }) { Text("Cancelar") }
            }
        } else if (drafts.isEmpty()) {
            Text("Todavía no hay drafts guardados. Pulsa + Añadir para crear uno.")
        } else {
            drafts.forEach { draft ->
                DraftCard(
                    draft = draft,
                    onDelete = {
                        drafts.removeAll { it.id == draft.id }
                        onSave(drafts.toList())
                    },
                )
            }
        }
    }

    picker?.let { target ->
        ChampionPicker(
            onDismiss = { picker = null },
            onSelect = { champion ->
                when (target.group) {
                    "own" -> own = champion.slug
                    "won" -> won = won.toMutableList().also { it[target.index] = champion.slug }
                    else -> lost = lost.toMutableList().also { it[target.index] = champion.slug }
                }
                picker = null
            },
        )
    }
}

private data class PickerTarget(val group: String, val index: Int)

@Composable
private fun ChampionSlot(label: String, championSlug: String, onClick: () -> Unit) {
    val champion = ChampionData.champions.firstOrNull { it.slug == championSlug }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ChampionPortrait(champion, Modifier.size(52.dp))
            Column {
                Text(label, style = MaterialTheme.typography.labelMedium)
                Text(champion?.name ?: "Elegir campeón", style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

@Composable
private fun ChampionPicker(onDismiss: () -> Unit, onSelect: (Champion) -> Unit) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query) {
        ChampionData.champions.filter { it.name.contains(query.trim(), ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Elegir campeón") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Buscar") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn {
                    items(filtered, key = { it.slug }) { champion ->
                        Surface(
                            onClick = { onSelect(champion) },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surface,
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                ChampionPortrait(champion, Modifier.size(44.dp))
                                Text(champion.name, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}

@Composable
private fun ChampionPortrait(champion: Champion?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = CircleShape,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    champion?.name?.take(1)?.uppercase() ?: "?",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        if (champion != null) {
            AndroidView(
                factory = { ImageView(it) },
                update = { ChampionIconLoader.load(champion, it) },
                modifier = Modifier.fillMaxSize().clip(CircleShape),
            )
        }
    }
}

@Composable
private fun DraftCard(draft: DraftRecord, onDelete: () -> Unit) {
    val ownChampion = ChampionData.champions.firstOrNull { it.slug == draft.ownChampion }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ChampionPortrait(ownChampion, Modifier.size(64.dp))
                Column {
                    Text("Mi campeón", style = MaterialTheme.typography.labelMedium)
                    Text(ownChampion?.name ?: draft.ownChampion, style = MaterialTheme.typography.titleMedium)
                }
            }
            MatchupGroup("Victoria", draft.wonAgainst)
            MatchupGroup("Derrota", draft.lostAgainst)
            TextButton(onClick = onDelete) { Text("Eliminar draft") }
        }
    }
}

@Composable
private fun MatchupGroup(title: String, slugs: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        val matchups = ROLE_NAMES.mapIndexedNotNull { index, role ->
            slugs.getOrNull(index)?.takeIf(String::isNotBlank)?.let { slug ->
                role to ChampionData.champions.firstOrNull { it.slug == slug }
            }
        }
        if (matchups.isEmpty()) {
            Text("Sin campeones registrados", style = MaterialTheme.typography.bodySmall)
        } else {
            matchups.forEach { (role, champion) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ChampionPortrait(champion, Modifier.size(36.dp))
                    Text(role, style = MaterialTheme.typography.labelMedium)
                    Text(champion?.name ?: "Campeón desconocido", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private fun encodeDrafts(drafts: List<DraftRecord>): String = JSONArray().apply {
    drafts.forEach { draft ->
        put(JSONObject().apply {
            put("id", draft.id)
            put("own", draft.ownChampion)
            put("won", JSONArray(draft.wonAgainst))
            put("lost", JSONArray(draft.lostAgainst))
        })
    }
}.toString()

private fun loadDrafts(json: String?): List<DraftRecord> = try {
    val array = JSONArray(json ?: "[]")
    (0 until array.length()).map { index ->
        val item = array.getJSONObject(index)
        DraftRecord(
            id = item.optString("id", UUID.randomUUID().toString()),
            ownChampion = item.optString("own"),
            wonAgainst = item.optJSONArray("won").toStringList(),
            lostAgainst = item.optJSONArray("lost").toStringList(),
        )
    }
} catch (_: Exception) {
    emptyList()
}

private fun JSONArray?.toStringList(): List<String> =
    (0 until (this?.length() ?: 0)).map { this?.optString(it).orEmpty() }.let { values ->
        (values + List((5 - values.size).coerceAtLeast(0)) { "" }).take(5)
    }
