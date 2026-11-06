package com.example.wrdraft

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.wrdraft.data.Champion
import com.example.wrdraft.data.ChampionData
import com.example.wrdraft.data.ChampionIconLoader
import com.example.wrdraft.data.ChampionMetaRepository
import com.example.wrdraft.data.ChampionWinRate
import com.example.wrdraft.data.ChampionSection
import com.example.wrdraft.data.ChampionSectionContent
import com.example.wrdraft.data.ChampionSectionRepository
import com.example.wrdraft.data.ChampionSectionState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class InAppPage(val champion: Champion, val section: ChampionSection)

class MainActivity : ComponentActivity() {
    private var waitingForOverlayPermission = false
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            startOverlayService()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    DraftScreen(onStartOverlay = ::requestOverlay)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (waitingForOverlayPermission) {
            waitingForOverlayPermission = false
            if (Settings.canDrawOverlays(this)) {
                requestNotificationPermissionOrStart()
            }
        }
    }

    private fun requestOverlay() {
        if (Settings.canDrawOverlays(this)) {
            requestNotificationPermissionOrStart()
        } else {
            waitingForOverlayPermission = true
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
        }
    }

    private fun requestNotificationPermissionOrStart() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startOverlayService()
        }
    }

    private fun startOverlayService() {
        val intent = Intent(this, DraftOverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}

@Composable
fun DraftScreen(onStartOverlay: () -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf<Champion?>(null) }
    var selectedWinRate by remember { mutableStateOf<ChampionWinRate?>(null) }
    var winRateLoading by remember { mutableStateOf(false) }
    var winRateFailed by remember { mutableStateOf(false) }
    var offlineStatus by remember { mutableStateOf("") }
    var offlinePreparing by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var inAppPage by remember { mutableStateOf<InAppPage?>(null) }

    DisposableEffect(selected) {
        val champion = selected
        selectedWinRate = null
        winRateFailed = false
        winRateLoading = champion != null
        offlineStatus = ""
        offlinePreparing = false
        champion?.let {
            ChampionMetaRepository.loadWinRate(it) { result ->
                if (selected == champion) {
                    selectedWinRate = result.getOrNull()
                    winRateFailed = result.isFailure || selectedWinRate == null
                    winRateLoading = false
                }
            }
        }
        onDispose {}
    }

    val filtered = remember(query) {
        val trimmed = query.trim()
        ChampionData.champions.filter { it.name.contains(trimmed, ignoreCase = true) }
    }

    val currentPage = inAppPage
    if (currentPage != null) {
        BackHandler { inAppPage = null }
        InAppSourcePage(page = currentPage, onBack = { inAppPage = null })
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Draft Assistant", style = MaterialTheme.typography.headlineSmall)
        OutlinedButton(
            onClick = onStartOverlay,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Activar burbuja sobre el juego")
        }
        Text(
            "La primera vez, Android pedirá permiso para mostrarla sobre otras apps.",
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(
            onClick = { openUrl(context, ChampionData.ROSTER_SOURCE_URL) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "${ChampionData.champions.size} campeones · fuente: Riot Games " +
                    "(${ChampionData.ROSTER_SNAPSHOT_DATE})",
            )
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Buscar campeón") },
        )

        selected?.let { champion ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(champion.name, style = MaterialTheme.typography.titleLarge)
                WinRateLabel(
                    winRate = selectedWinRate,
                    isLoading = winRateLoading,
                    unavailable = winRateFailed,
                )
                OutlinedButton(
                    onClick = {
                        offlinePreparing = true
                        offlineStatus = "Descargando las cuatro secciones..."
                        ChampionSectionRepository.loadAll(context, champion) { states ->
                            if (selected == champion) {
                                val sectionCount = ChampionSection.values().size
                                val complete = states.size == sectionCount &&
                                    states.values.none { it.isRefreshing }
                                if (complete) {
                                    offlinePreparing = false
                                    val saved = states.values.count { it.content != null }
                                    offlineStatus = if (saved == sectionCount) {
                                        "Las cuatro secciones están guardadas para uso offline."
                                    } else {
                                        "$saved de $sectionCount secciones guardadas; " +
                                            "reintenta cuando haya conexión."
                                    }
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !offlinePreparing,
                ) {
                    Text(if (offlinePreparing) "Preparando copia offline..." else "Preparar para offline")
                }
                if (offlineStatus.isNotEmpty()) {
                    Text(offlineStatus, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "Datos de WildRiftCore y WildRiftFire. Se muestran como texto nativo y se guardan en este dispositivo al descargarlos.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            inAppPage = InAppPage(
                                champion = champion,
                                section = ChampionSection.COUNTERS,
                            )
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Counterpicks", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(
                        onClick = {
                            inAppPage = InAppPage(
                                champion = champion,
                                section = ChampionSection.SYNERGIES,
                            )
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Sinergias", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            inAppPage = InAppPage(
                                champion = champion,
                                section = ChampionSection.BUILD,
                            )
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Build")
                    }
                    OutlinedButton(
                        onClick = {
                            inAppPage = InAppPage(
                                champion = champion,
                                section = ChampionSection.GUIDE,
                            )
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Guía")
                    }
                }
            }
        }

        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text("No se encontraron campeones")
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(96.dp),
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(filtered, key = { it.slug }) { champion ->
                    ChampionTile(
                        champion = champion,
                        selected = selected == champion,
                        onClick = { selected = if (selected == champion) null else champion },
                    )
                }
            }
        }
    }
}

@Composable
private fun WinRateLabel(
    winRate: ChampionWinRate?,
    isLoading: Boolean,
    unavailable: Boolean,
) {
    val message = when {
        isLoading -> "WildRiftCore · win rate últimos 30 días: cargando..."
        winRate != null ->
            "WildRiftCore · win rate últimos 30 días: ${"%.2f".format(winRate.percentage)}% · Parche ${winRate.patch}"
        unavailable -> "WildRiftCore: win rate actual no disponible"
        else -> ""
    }
    if (message.isNotEmpty()) {
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun InAppSourcePage(page: InAppPage, onBack: () -> Unit) {
    val context = LocalContext.current
    var state by remember(page) {
        mutableStateOf(ChampionSectionState(null, isRefreshing = false, error = false))
    }
    var refreshRequest by remember(page) { mutableStateOf(0) }
    DisposableEffect(page, refreshRequest) {
        var active = true
        ChampionSectionRepository.load(context, page.champion, page.section) {
            if (active) state = it
        }
        onDispose { active = false }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("Volver") }
            Text(
                "${page.champion.name}: ${page.section.label}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                sectionStatus(page.section, state.content, state.isRefreshing, state.error),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { refreshRequest++ }) { Text("Actualizar") }
        }
        if (state.content == null && state.isRefreshing) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        }
        val content = state.content
        if (content == null && !state.isRefreshing) {
            Text(
                "No hay una copia guardada. Conéctate a internet para descargar esta sección.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else if (content != null) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                SelectionContainer {
                    Text(
                        content.text,
                        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

private fun sectionStatus(
    section: ChampionSection,
    content: ChampionSectionContent?,
    isRefreshing: Boolean,
    error: Boolean,
): String = when {
    content == null && isRefreshing -> "Descargando copia para uso offline..."
    content == null && error -> "No se pudo descargar; no hay copia offline."
    content != null && isRefreshing ->
        "Copia guardada · ${section.sourceLabel()} · actualizando..."
    content != null && error ->
        "Copia guardada · ${section.sourceLabel()} · ${content.patchLabel()} · " +
            "${content.fetchedAtLabel()} · no se pudo actualizar"
    content != null ->
        "Guardado offline · ${section.sourceLabel()} · ${content.patchLabel()} · ${content.fetchedAtLabel()}"
    else -> ""
}

private fun ChampionSection.sourceLabel(): String =
    if (this == ChampionSection.GUIDE) "WildRiftFire" else "WildRiftCore"

private fun ChampionSectionContent.patchLabel(): String =
    patch?.let { "Parche $it" } ?: "parche no indicado por la fuente"

private fun ChampionSectionContent.fetchedAtLabel(): String =
    "descargado ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(fetchedAt))}"

private fun openUrl(context: android.content.Context, url: String) {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}

@Composable
fun ChampionTile(champion: Champion, selected: Boolean, onClick: () -> Unit) {
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
                    text = champion.name.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                AndroidView(
                    factory = { ImageView(it) },
                    update = { ChampionIconLoader.load(champion, it) },
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = champion.name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
