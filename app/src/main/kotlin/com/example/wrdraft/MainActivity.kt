package com.example.wrdraft

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.wrdraft.data.ChampionData
import com.example.wrdraft.data.ChampionLinks
import com.example.wrdraft.data.ChampionRepository
import com.example.wrdraft.data.ChampionStats
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    DraftScreen()
                }
            }
        }
    }
}

@Composable
fun DraftScreen() {
    val context = LocalContext.current
    var selected by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var stats by remember { mutableStateOf<List<ChampionStats>>(emptyList()) }

    // La descarga del feed se hace fuera del hilo principal.
    LaunchedEffect(Unit) {
        stats = withContext(Dispatchers.IO) { ChampionRepository.load(context) }
    }

    val names = stats.map { it.name }
    val trimmed = query.trim()
    val filtered = names.filter { it.contains(trimmed, ignoreCase = true) }
    // Permite elegir cualquier nombre escrito, aunque no esté en el dataset.
    val typedIsNew = trimmed.isNotEmpty() &&
        names.none { it.equals(trimmed, ignoreCase = true) }
    val matchups = selected?.let { ChampionData.matchupsFor(it) }
    val selectedStats = selected?.let { name -> stats.firstOrNull { it.name == name } }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Draft Assistant", style = MaterialTheme.typography.headlineSmall)
        Text("Busca o escribe cualquier campeón", style = MaterialTheme.typography.bodyMedium)

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Campeón") },
        )

        LazyVerticalGrid(
            columns = GridCells.Adaptive(96.dp),
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (typedIsNew) {
                item {
                    ChampionTile(
                        name = trimmed,
                        selected = selected == trimmed,
                        isCustom = true,
                        onClick = { selected = if (selected == trimmed) null else trimmed },
                    )
                }
            }
            items(filtered) { name ->
                ChampionTile(
                    name = name,
                    selected = selected == name,
                    isCustom = false,
                    onClick = { selected = if (selected == name) null else name },
                )
            }
        }

        selectedStats?.let { s ->
            Text(
                text = s.winRate?.let { "Win rate: ${"%.1f".format(it)}%" } ?: "Win rate: sin datos",
                style = MaterialTheme.typography.bodyLarge,
            )
            s.build?.let { b ->
                MatchupSection("Objetos más jugados", b.items)
                MatchupSection("Runas más jugadas", b.runes)
            }
        }

        selected?.let { name ->
            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(ChampionLinks.wildriftCoreBuildUrl(name)))
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Build de $name en WildRiftCore")
            }
            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(ChampionLinks.wildRiftFireUrl(name)))
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Guía de $name en WildRiftFire")
            }
        }

        selected?.let { name ->
            if (matchups == null) {
                Text("Sin datos para $name", style = MaterialTheme.typography.bodyLarge)
            } else {
                MatchupSection("Counter picks", matchups.counters)
                MatchupSection("Sinergias", matchups.synergies)
            }
        }
    }
}

@Composable
fun ChampionTile(name: String, selected: Boolean, isCustom: Boolean, onClick: () -> Unit) {
    val container = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val content = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = container,
        contentColor = content,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = name.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (isCustom) {
                Text("Personalizado", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
fun MatchupSection(title: String, champions: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(champions.joinToString(", "), style = MaterialTheme.typography.bodyLarge)
    }
}
