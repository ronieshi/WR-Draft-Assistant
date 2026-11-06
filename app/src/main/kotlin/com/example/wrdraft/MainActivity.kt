package com.example.wrdraft

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
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
import com.example.wrdraft.data.ChampionLinks

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
    var query by remember { mutableStateOf("") }
    var buildUrl by remember { mutableStateOf<String?>(null) }

    val filtered = remember(query) {
        val trimmed = query.trim()
        ChampionData.champions.filter { it.name.contains(trimmed, ignoreCase = true) }
    }

    val currentBuildUrl = buildUrl
    if (currentBuildUrl != null) {
        BackHandler { buildUrl = null }
        InAppBuildPage(url = currentBuildUrl, onBack = { buildUrl = null })
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
                Text(
                    "Counters y sinergias de WildRiftCore. Los matchups pueden variar por parche.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            openUrl(context, ChampionLinks.wildRiftCoreCountersUrl(champion.slug))
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Counterpicks", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(
                        onClick = {
                            openUrl(context, ChampionLinks.wildRiftCoreSynergiesUrl(champion.slug))
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
                            buildUrl = ChampionLinks.wildRiftCoreBuildUrl(champion.slug)
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Build")
                    }
                    OutlinedButton(
                        onClick = {
                            openUrl(context, ChampionLinks.wildRiftFireUrl(champion.slug))
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
private fun InAppBuildPage(url: String, onBack: () -> Unit) {
    var isLoading by remember(url) { mutableStateOf(true) }
    var hasError by remember(url) { mutableStateOf(false) }
    var reloadRequest by remember(url) { mutableStateOf(0) }

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
                "Build del parche actual",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        }
        if (hasError) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "No se pudo cargar la build. Comprueba tu conexión.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    hasError = false
                    isLoading = true
                    reloadRequest++
                }) {
                    Text("Reintentar")
                }
            }
        }
        BuildWebView(
            url = url,
            reloadRequest = reloadRequest,
            onLoadingChanged = { isLoading = it },
            onLoadError = { hasError = true },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BuildWebView(
    url: String,
    reloadRequest: Int,
    onLoadingChanged: (Boolean) -> Unit,
    onLoadError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                settings.safeBrowsingEnabled = true
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    val host = request.url.host.orEmpty()
                    return host != "wildriftcore.com" && !host.endsWith(".wildriftcore.com")
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                    onLoadingChanged(true)
                }

                override fun onPageCommitVisible(view: WebView, url: String?) {
                    onLoadingChanged(false)
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: android.webkit.WebResourceError,
                ) {
                    if (request.isForMainFrame) {
                        onLoadingChanged(false)
                        onLoadError()
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    errorResponse: WebResourceResponse,
                ) {
                    if (request.isForMainFrame && errorResponse.statusCode >= 400) {
                        onLoadingChanged(false)
                        onLoadError()
                    }
                }

                override fun onReceivedSslError(
                    view: WebView,
                    handler: SslErrorHandler,
                    error: SslError,
                ) {
                    handler.cancel()
                    onLoadingChanged(false)
                    onLoadError()
                }

            }
        }
    }

    AndroidView(
        factory = { webView },
        modifier = modifier,
        update = { view ->
            val loadKey = "$url#$reloadRequest"
            if (view.tag != loadKey) {
                view.tag = loadKey
                view.loadUrl(url)
            }
        },
    )
    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }
}

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
