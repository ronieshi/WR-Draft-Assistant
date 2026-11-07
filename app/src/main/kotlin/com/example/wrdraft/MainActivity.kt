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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.painterResource
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
import com.example.wrdraft.data.ChampionVisualCard
import com.example.wrdraft.data.ChampionVisualItem
import com.example.wrdraft.data.preferredSynergyRole
import com.example.wrdraft.data.synergyCardsForRole
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class InAppPage(val champion: Champion, val section: ChampionSection)

private const val DONATION_URL = "https://www.paypal.com/paypalme/ronieshi"
private const val TIKTOK_URL = "https://www.tiktok.com/@ronieshi"
private const val INSTAGRAM_URL = "https://www.instagram.com/ronieshi"

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
            val preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
            var darkTheme by remember {
                mutableStateOf(preferences.getBoolean(DARK_THEME_KEY, false))
            }
            MaterialTheme(
                colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme(),
            ) {
                Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                    DraftScreen(
                        onStartOverlay = ::requestOverlay,
                        darkTheme = darkTheme,
                        onToggleTheme = {
                            darkTheme = !darkTheme
                            preferences.edit().putBoolean(DARK_THEME_KEY, darkTheme).apply()
                        },
                    )
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

    private companion object {
        const val PREFERENCES_NAME = "draft_assistant_preferences"
        const val DARK_THEME_KEY = "dark_theme"
    }
}

@Composable
fun DraftScreen(
    onStartOverlay: () -> Unit,
    darkTheme: Boolean,
    onToggleTheme: () -> Unit,
) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf<Champion?>(null) }
    var selectedWinRate by remember { mutableStateOf<ChampionWinRate?>(null) }
    var winRateLoading by remember { mutableStateOf(false) }
    var winRateFailed by remember { mutableStateOf(false) }
    var offlineStatus by remember { mutableStateOf("") }
    var offlinePreparing by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var inAppPage by remember { mutableStateOf<InAppPage?>(null) }
    var showInfoDialog by remember { mutableStateOf(false) }

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
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Draft Assistant",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    val champion = selected ?: return@IconButton
                    offlinePreparing = true
                    offlineStatus = "Descargando counterpicks, sinergias y builds..."
                    ChampionSectionRepository.loadAll(context, champion) { states ->
                        if (selected == champion) {
                            val sectionCount = ChampionSection.values().size
                            val complete = states.size == sectionCount &&
                                states.values.none { it.isRefreshing }
                            if (complete) {
                                offlinePreparing = false
                                val saved = states.values.count { it.content != null }
                                offlineStatus = if (saved == sectionCount) {
                                    "Datos guardados para uso offline."
                                } else {
                                    "$saved de $sectionCount secciones guardadas; reintenta con conexión."
                                }
                            }
                        }
                    }
                },
                enabled = selected != null && !offlinePreparing,
            ) {
                if (offlinePreparing) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_download),
                        contentDescription = "Preparar datos offline",
                    )
                }
            }
            IconButton(onClick = onToggleTheme) {
                Icon(
                    painter = painterResource(
                        if (darkTheme) R.drawable.ic_light_mode else R.drawable.ic_dark_mode,
                    ),
                    contentDescription = if (darkTheme) "Activar tema claro" else "Activar tema oscuro",
                )
            }
            IconButton(onClick = { showInfoDialog = true }) {
                Icon(
                    painter = painterResource(R.drawable.ic_info),
                    contentDescription = "Información, donaciones y redes sociales",
                )
            }
        }
        if (showInfoDialog) {
            AlertDialog(
                onDismissRequest = { showInfoDialog = false },
                title = { Text("Información") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = {
                                showInfoDialog = false
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(DONATION_URL)),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Donar con PayPal", modifier = Modifier.fillMaxWidth())
                        }
                        TextButton(
                            onClick = {
                                showInfoDialog = false
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(TIKTOK_URL)),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("TikTok · @ronieshi", modifier = Modifier.fillMaxWidth())
                        }
                        TextButton(
                            onClick = {
                                showInfoDialog = false
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(INSTAGRAM_URL)),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Instagram · @ronieshi", modifier = Modifier.fillMaxWidth())
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showInfoDialog = false }) {
                        Text("Cerrar")
                    }
                },
            )
        }
        if (offlineStatus.isNotEmpty()) {
            Text(offlineStatus, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(
            onClick = onStartOverlay,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Activar burbuja sobre el juego")
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
                OutlinedButton(
                    onClick = {
                        inAppPage = InAppPage(
                            champion = champion,
                            section = ChampionSection.BUILD,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Build")
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
    var selectedSynergyRole by remember(page) { mutableStateOf<String?>(null) }
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
            val championRoles = content.championRoles
            val synergyRole = selectedSynergyRole?.takeIf { it in championRoles }
                ?: content.preferredSynergyRole()
            val cards = if (page.section == ChampionSection.SYNERGIES) {
                content.synergyCardsForRole(synergyRole)
            } else {
                content.cards
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (content.cards.isNotEmpty()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (
                            page.section == ChampionSection.SYNERGIES &&
                            championRoles.size > 1
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                championRoles.forEach { role ->
                                    if (role == synergyRole) {
                                        Button(onClick = {}) { Text(role) }
                                    } else {
                                        OutlinedButton(onClick = { selectedSynergyRole = role }) {
                                            Text(role)
                                        }
                                    }
                                }
                            }
                        }
                        Column(
                            modifier = Modifier.weight(1f).fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (cards.isEmpty()) {
                                Text("No hay soportes registrados en las sinergias de esta fuente.")
                            } else {
                                cards.forEach { card -> ChampionVisualCardView(card) }
                            }
                        }
                    }
                } else {
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
}

@Composable
private fun ChampionVisualCardView(card: ChampionVisualCard) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOfNotNull(card.role, card.category).takeIf { it.isNotEmpty() }?.let {
                Text(
                    it.joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (card.items.isNotEmpty()) {
                Text(card.title, style = MaterialTheme.typography.titleMedium)
                card.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    card.items.forEachIndexed { index, item ->
                        VisualItemView(item)
                        if (index < card.items.lastIndex) {
                            Text("›", modifier = Modifier.padding(top = 14.dp), style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    card.iconUrl?.let { url ->
                        VisualIcon(url, card.title, Modifier.size(60.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(card.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            card.rank?.let {
                                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                                    Text(
                                        it,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                }
                            }
                        }
                        card.subtitle?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                        card.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}

@Composable
private fun VisualItemView(item: ChampionVisualItem) {
    Column(
        modifier = Modifier.width(68.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item.iconUrl?.let { VisualIcon(it, item.name, Modifier.size(52.dp)) }
            ?: Box(
                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(item.name.take(1), style = MaterialTheme.typography.titleMedium)
            }
        Text(
            item.name,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun VisualIcon(url: String, description: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            description.take(1),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        AndroidView(
            factory = { ImageView(it) },
            update = { ChampionIconLoader.loadUrl(url, description, it) },
            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
        )
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
    "WildRiftCore"

private fun ChampionSectionContent.patchLabel(): String =
    patch?.let { "Parche $it" } ?: "parche no indicado por la fuente"

private fun ChampionSectionContent.fetchedAtLabel(): String =
    "descargado ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(fetchedAt))}"

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
