package com.example.wrdraft

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.wrdraft.data.Champion
import com.example.wrdraft.data.ChampionData
import com.example.wrdraft.data.ChampionIconLoader
import com.example.wrdraft.data.ChampionMetaRepository
import com.example.wrdraft.data.ChampionSection
import com.example.wrdraft.data.ChampionSectionContent
import com.example.wrdraft.data.ChampionSectionRepository
import com.example.wrdraft.data.ChampionSectionState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class DraftOverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private var bubble: TextView? = null
    private var panel: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        if (!android.provider.Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        showBubble()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        closeOverlayViews()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    private fun showBubble() {
        if (bubble != null || panel != null) return

        val view = TextView(this).apply {
            text = "WR"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            contentDescription = "Abrir asistente de draft"
            background = roundedBackground(COLOR_ACCENT, dp(30))
            elevation = dp(8).toFloat()
        }
        val params = overlayLayoutParams(dp(58), dp(58), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        params.x = dp(12)
        params.y = dp(180)
        view.setOnTouchListener(dragListener(view, params) { showPanel(params.x, params.y) })
        windowManager.addView(view, params)
        bubble = view
        overlayParams = params
    }

    private fun showPanel(x: Int, y: Int) {
        val oldBubble = bubble ?: return
        val params = overlayParams ?: return
        windowManager.removeView(oldBubble)
        bubble = null

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBackground(Color.rgb(25, 27, 38), dp(18))
            elevation = dp(12).toFloat()
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "Draft en vivo"
            textSize = 18f
            setTextColor(Color.WHITE)
        }
        header.addView(title, LinearLayout.LayoutParams(0, dp(44), 1f))
        val minimize = textButton("—") {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(root.windowToken, 0)
            windowManager.removeView(root)
            panel = null
            showBubble()
        }
        header.addView(minimize, LinearLayout.LayoutParams(dp(42), dp(42)))
        val close = textButton("×") { stopSelf() }
        header.addView(close, LinearLayout.LayoutParams(dp(42), dp(42)))
        root.addView(header)

        val search = EditText(this).apply {
            hint = "Buscar campeón"
            textSize = 15f
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            setPadding(dp(10), 0, dp(10), 0)
            background = roundedBackground(Color.rgb(48, 51, 65), dp(10))
        }
        root.addView(search, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(48),
        ).apply { bottomMargin = dp(8) })

        val selectedLabel = TextView(this).apply {
            text = "Selecciona un campeón"
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(dp(2), dp(4), dp(2), dp(4))
        }
        root.addView(selectedLabel)

        val winRateLabel = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(dp(2), 0, dp(2), dp(6))
        }
        root.addView(winRateLabel)
        var currentChampion: Champion? = null
        val offlineLabel = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(dp(6), 0, dp(4), 0)
        }
        val offlineButton = textButton("Offline") {
            val champion = currentChampion ?: return@textButton
            offlineLabel.text = "Descargando las cuatro secciones..."
            ChampionSectionRepository.loadAll(this@DraftOverlayService, champion) { states ->
                if (currentChampion == champion) {
                    val sectionCount = ChampionSection.values().size
                    val complete = states.size == sectionCount &&
                        states.values.none { it.isRefreshing }
                    if (complete) {
                        val saved = states.values.count { it.content != null }
                        offlineLabel.text = if (saved == sectionCount) {
                            "Las cuatro secciones están guardadas offline."
                        } else {
                            "$saved de $sectionCount secciones guardadas."
                        }
                    }
                }
            }
        }
        val offlineRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            addView(offlineLabel, LinearLayout.LayoutParams(0, dp(38), 1f))
            addView(offlineButton, LinearLayout.LayoutParams(dp(82), dp(36)))
        }
        root.addView(offlineRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(40),
        ).apply { bottomMargin = dp(4) })

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        root.addView(actions, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(44),
        ).apply { bottomMargin = dp(8) })

        val content = FrameLayout(this)
        root.addView(
            content,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        fun showChampionList() {
            content.removeAllViews()
            val scroll = ScrollView(this)
            val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val term = search.text.toString().trim().lowercase(Locale.ROOT)
            ChampionData.champions
                .filter { it.name.lowercase(Locale.ROOT).contains(term) }
                .forEach { champion ->
                    val row = championRow(champion) {
                        currentChampion = champion
                        selectedLabel.text = champion.name
                        winRateLabel.text = "WildRiftCore · win rate últimos 30 días: cargando..."
                        actions.visibility = View.VISIBLE
                        offlineRow.visibility = View.VISIBLE
                        offlineLabel.text = ""
                        actions.removeAllViews()
                        ChampionMetaRepository.loadWinRate(champion) { result ->
                            if (currentChampion == champion) {
                                val winRate = result.getOrNull()
                                winRateLabel.text = when {
                                    winRate != null ->
                                        "WildRiftCore · win rate últimos 30 días: ${"%.2f".format(winRate.percentage)}% · Parche ${winRate.patch}"
                                    else -> "WildRiftCore: win rate actual no disponible"
                                }
                            }
                        }
                        addAction(actions, "Counters") {
                            showNativeSectionPage(
                                content,
                                champion,
                                ChampionSection.COUNTERS,
                                { showChampionList() },
                            )
                        }
                        addAction(actions, "Sinergias") {
                            showNativeSectionPage(
                                content,
                                champion,
                                ChampionSection.SYNERGIES,
                                { showChampionList() },
                            )
                        }
                        addAction(actions, "Build") {
                            showNativeSectionPage(
                                content,
                                champion,
                                ChampionSection.BUILD,
                                { showChampionList() },
                            )
                        }
                        addAction(actions, "Guía") {
                            showNativeSectionPage(
                                content,
                                champion,
                                ChampionSection.GUIDE,
                                { showChampionList() },
                            )
                        }
                    }

                    row.gravity = Gravity.CENTER_VERTICAL
                    results.addView(row, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(44),
                    ))
                }
            scroll.addView(results)
            content.addView(scroll)
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                showChampionList()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        showChampionList()

        params.width = min(dp(340), resources.displayMetrics.widthPixels - dp(24))
        params.height = min(dp(560), (resources.displayMetrics.heightPixels * 0.78f).toInt())
        params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        val maxX = max(0, resources.displayMetrics.widthPixels - params.width)
        val maxY = max(0, resources.displayMetrics.heightPixels - params.height)
        params.x = x.coerceIn(0, maxX)
        params.y = y.coerceIn(0, maxY)
        windowManager.addView(root, params)
        panel = root
        overlayParams = params
    }

    private fun showNativeSectionPage(
        content: FrameLayout,
        champion: Champion,
        section: ChampionSection,
        onBack: () -> Unit,
    ) {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(content.windowToken, 0)
        content.removeAllViews()
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val back = textButton("‹  ${champion.name}: ${section.label}  ·  Volver") { onBack() }
        page.addView(back, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(44),
        ))

        val status = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(dp(6), dp(2), dp(4), dp(2))
        }
        val body = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            setTextIsSelectable(true)
            setPadding(dp(8), dp(8), dp(8), dp(12))
        }
        val refresh = textButton("Actualizar") {
            loadNativeSection(champion, section, status, body)
        }.apply {
            textSize = 12f
        }
        val metadata = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(status, LinearLayout.LayoutParams(0, dp(40), 1f))
            addView(refresh, LinearLayout.LayoutParams(dp(96), dp(38)))
        }
        page.addView(metadata)

        val scroll = ScrollView(this).apply { addView(body) }
        page.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))
        content.addView(page)
        loadNativeSection(champion, section, status, body)
    }

    private fun loadNativeSection(
        champion: Champion,
        section: ChampionSection,
        status: TextView,
        body: TextView,
    ) {
        ChampionSectionRepository.load(this, champion, section) { state ->
            status.text = overlaySectionStatus(section, state)
            state.content?.let { body.text = it.text }
            if (state.content == null && !state.isRefreshing) {
                body.text = "No hay copia guardada. Conéctate para descargar esta sección."
            }
        }
    }

    private fun overlaySectionStatus(
        section: ChampionSection,
        state: ChampionSectionState,
    ): String {
        val content = state.content
        val source = if (section == ChampionSection.GUIDE) "WildRiftFire" else "WildRiftCore"
        return when {
            content == null && state.isRefreshing -> "Descargando copia offline..."
            content == null -> "No se pudo descargar; no hay copia offline"
            state.isRefreshing -> "Copia offline · $source · actualizando..."
            state.error -> "Copia offline · $source · ${content.patch?.let { "Parche $it" } ?: "parche sin indicar"}" +
                " · ${content.fetchedAtLabel()} · no se pudo actualizar"
            else -> "Guardado offline · $source · " +
                (content.patch?.let { "Parche $it" } ?: "parche sin indicar") +
                " · ${content.fetchedAtLabel()}"
        }
    }

    private fun ChampionSectionContent.fetchedAtLabel(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(fetchedAt))

    private fun addAction(parent: LinearLayout, label: String, onClick: () -> Unit) {
        val button = textButton(label, onClick).apply {
            textSize = 13f
            gravity = Gravity.CENTER
        }
        parent.addView(button, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
            marginEnd = dp(4)
        })
    }

    private fun championRow(champion: Champion, onClick: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(2), dp(8), dp(2))
            background = roundedBackground(Color.rgb(48, 51, 65), dp(10))
            setOnClickListener { onClick() }
        }
        val iconFrame = FrameLayout(this)
        val fallback = TextView(this).apply {
            text = champion.name.take(1).uppercase()
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedBackground(COLOR_ACCENT, dp(22))
        }
        iconFrame.addView(fallback, FrameLayout.LayoutParams(dp(40), dp(40)))
        val icon = ImageView(this).apply {
            background = roundedBackground(Color.TRANSPARENT, dp(22))
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
        }
        iconFrame.addView(icon, FrameLayout.LayoutParams(dp(40), dp(40)))
        ChampionIconLoader.load(champion, icon)
        row.addView(iconFrame, LinearLayout.LayoutParams(dp(40), dp(40)).apply {
            marginEnd = dp(10)
        })
        val name = TextView(this).apply {
            text = champion.name
            textSize = 16f
            setTextColor(Color.WHITE)
        }
        row.addView(name, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT,
        ))
        return row
    }

    private fun textButton(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedBackground(Color.rgb(72, 62, 115), dp(10))
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { onClick() }
        }

    private fun dragListener(view: View, params: WindowManager.LayoutParams, onClick: () -> Unit) =
        object : View.OnTouchListener {
            private var startX = 0f
            private var startY = 0f
            private var originX = 0
            private var originY = 0
            private var moved = false
            private val touchSlop = ViewConfiguration.get(this@DraftOverlayService).scaledTouchSlop

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        startY = event.rawY
                        originX = params.x
                        originY = params.y
                        moved = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - startX).toInt()
                        val dy = (event.rawY - startY).toInt()
                        if (abs(dx) > touchSlop || abs(dy) > touchSlop) moved = true
                        if (moved) {
                            params.x = (originX + dx).coerceIn(
                                0,
                                max(0, resources.displayMetrics.widthPixels - view.width),
                            )
                            params.y = (originY + dy).coerceIn(
                                0,
                                max(0, resources.displayMetrics.heightPixels - view.height),
                            )
                            runCatching { windowManager.updateViewLayout(view, params) }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) onClick()
                        return true
                    }
                    MotionEvent.ACTION_CANCEL -> return true
                }
                return false
            }
        }

    private fun overlayLayoutParams(width: Int, height: Int, flags: Int) =
        WindowManager.LayoutParams(
            width,
            height,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

    private fun closeOverlayViews() {
        panel?.let {
            runCatching { windowManager.removeViewImmediate(it) }
        }
        bubble?.let { runCatching { windowManager.removeViewImmediate(it) } }
        panel = null
        bubble = null
        overlayParams = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Asistente flotante",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Mantiene activa la burbuja del asistente de draft"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopOverlay = PendingIntent.getService(
            this,
            1,
            Intent(this, DraftOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("Asistente de draft activo")
            .setContentText("Burbuja lista para buscar picks")
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cerrar burbuja", stopOverlay)
            .build()
    }

    private fun roundedBackground(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val ACTION_STOP = "com.example.wrdraft.action.STOP_OVERLAY"
        private const val CHANNEL_ID = "draft_overlay"
        private const val NOTIFICATION_ID = 42
        private const val COLOR_ACCENT = 0xFF7057D9.toInt()
    }
}
