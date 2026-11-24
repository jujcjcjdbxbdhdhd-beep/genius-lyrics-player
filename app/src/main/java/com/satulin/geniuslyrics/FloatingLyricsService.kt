package com.satulin.geniuslyrics

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.widget.NestedScrollView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin

class FloatingLyricsService : Service() {

    enum class IslandState {
        MINI, COMPACT, EXPANDED
    }

    companion object {
        private const val SYNC_LEAD_MS = 160L

        @Volatile
        var isRunning: Boolean = false

        const val ACTION_STOP_ISLAND = "com.satulin.geniuslyrics.ACTION_STOP_ISLAND"
        const val ACTION_ISLAND_STATE_CHANGED = "com.satulin.geniuslyrics.ACTION_ISLAND_STATE_CHANGED"
        const val PREFS_NAME = "genius_island_prefs"
        const val KEY_ISLAND_ENABLED = "island_enabled"
        const val KEY_ISLAND_Y = "island_y_px"

        private const val CHANNEL_ID = "dynamic_island_channel"
        private const val NOTIFICATION_ID = 5201

        fun isIslandEnabled(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ISLAND_ENABLED, false)
        }

        fun setIslandEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ISLAND_ENABLED, enabled)
                .apply()
        }
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var widthAnimator: ValueAnimator? = null
    private var snapAnimator: ValueAnimator? = null
    private var borderGlowAnimator: ValueAnimator? = null

    private var currentState: IslandState = IslandState.COMPACT
    private var currentStyle: IslandAnimStyle = IslandAnimStyle.FLY_IN
    private var displayMode: LyricsDisplayMode = LyricsDisplayMode.KARAOKE
    private var lastOverlayWordKey: Long = -1L
    private var compactUsingA: Boolean = true
    private var expandedUsingA: Boolean = true
    private var compactSwapGen: Int = 0
    private var expandedSwapGen: Int = 0
    private var compactTypeJob: Job? = null
    private var expandedTypeJob: Job? = null
    private var eqPhase: Double = 0.0

    private var isForegroundStarted: Boolean = false
    private var isAutoScrolling: Boolean = true
    private var scrollJob: Job? = null
    private var equalizerJob: Job? = null
    private var loadJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var currentTitle: String = ""
    private var currentArtist: String = ""
    private var currentSourceName: String = ""
    private var currentLines: List<LyricLine> = emptyList()
    private var syncedLines: List<LyricLine> = emptyList()
    private var isSyncedSource: Boolean = false
    private var activeIndex: Int = -1
    private val overlayLineViews = mutableListOf<TextView>()

    private var isMediaPlaying: Boolean = false
    private var lastKnownPositionMs: Long = 0L
    private var totalDurationMs: Long = 0L
    private var lastUpdateTimeSystem: Long = 0L
    private var manualSyncOffsetMs: Long = 0L
    private var playbackSpeed: Float = 1.0f
    private var lastAdaptedDurationMs: Long = 0L
    private var currentLyricsResult: LyricsResult? = null
    private var currentRawTitleForAdapt: String = ""
    private var virtualPositionMs: Long = 0L
    private var seekIgnoreUntilSystemMs: Long = 0L

    private val trackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                MediaNotificationListener.ACTION_TRACK_CHANGED -> {
                    val title = MediaNotificationListener.sanitizeText(intent.getStringExtra(MediaNotificationListener.EXTRA_TITLE))
                    val artist = MediaNotificationListener.sanitizeText(intent.getStringExtra(MediaNotificationListener.EXTRA_ARTIST))
                    val rawTitle = MediaNotificationListener.sanitizeText(intent.getStringExtra(MediaNotificationListener.EXTRA_RAW_TITLE))
                    val artistFromTitle = intent.getBooleanExtra(MediaNotificationListener.EXTRA_ARTIST_FROM_TITLE, false)
                    val isPlaying = intent.getBooleanExtra(MediaNotificationListener.EXTRA_IS_PLAYING, true)
                    val pos = intent.getLongExtra(MediaNotificationListener.EXTRA_POSITION_MS, 0L)
                    val dur = intent.getLongExtra(MediaNotificationListener.EXTRA_DURATION_MS, 0L)
                    val updated = intent.getLongExtra(MediaNotificationListener.EXTRA_UPDATED_AT, SystemClock.elapsedRealtime())
                    val spd = intent.getFloatExtra(MediaNotificationListener.EXTRA_PLAYBACK_SPEED, 1.0f)
                    playbackSpeed = if (spd in 0.25f..4.0f) spd else 1.0f

                    updatePlaybackTime(pos, dur, updated, isPlaying)
                    updateTrack(title, artist, rawTitle, artistFromTitle)
                }
                MediaNotificationListener.ACTION_PLAYBACK_STATE -> {
                    val isPlaying = intent.getBooleanExtra(MediaNotificationListener.EXTRA_IS_PLAYING, true)
                    val pos = intent.getLongExtra(MediaNotificationListener.EXTRA_POSITION_MS, 0L)
                    val dur = intent.getLongExtra(MediaNotificationListener.EXTRA_DURATION_MS, totalDurationMs)
                    val updated = intent.getLongExtra(MediaNotificationListener.EXTRA_UPDATED_AT, SystemClock.elapsedRealtime())
                    val spd = intent.getFloatExtra(MediaNotificationListener.EXTRA_PLAYBACK_SPEED, 1.0f)
                    playbackSpeed = if (spd in 0.25f..4.0f) spd else 1.0f
                    val fromSeek = intent.getBooleanExtra(MediaNotificationListener.EXTRA_FROM_SEEK, false)

                    val playChanged = (isPlaying != isMediaPlaying)
                    val now = SystemClock.elapsedRealtime()
                    if (fromSeek) {
                        seekIgnoreUntilSystemMs = now + 650L
                        updatePlaybackTime(pos, dur, updated, isPlaying)
                        updateProgressViews()
                        recalculateActiveLineNow(advanceVirtual = false)
                        updateNotificationContent()
                    } else if (now < seekIgnoreUntilSystemMs && abs(pos - lastKnownPositionMs) > 2500L) {
                        isMediaPlaying = isPlaying
                        if (dur > 0L) totalDurationMs = dur
                        if (playChanged) {
                            updateNotificationContent()
                        }
                    } else {
                        updatePlaybackTime(pos, dur, updated, isPlaying)
                        updateProgressViews()
                        if (playChanged) {
                            updateNotificationContent()
                        }
                    }
                }
                AppMotionPrefs.ACTION_PREFS_CHANGED -> {
                    currentStyle = AppMotionPrefs.getIslandStyle(this@FloatingLyricsService)
                    displayMode = AppMotionPrefs.getDisplayMode(this@FloatingLyricsService)
                    val sharedOffset = AppMotionPrefs.currentSyncOffsetMs
                    val offsetChanged = sharedOffset != manualSyncOffsetMs
                    manualSyncOffsetMs = sharedOffset
                    updateStyleChipsUI()
                    applyDisplayModeUI()
                    if (offsetChanged) {
                        recalculateActiveLineNow(advanceVirtual = false)
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        if (intent?.action == ACTION_STOP_ISLAND) {
            setIslandEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        isRunning = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            setIslandEnabled(this, false)
            stopSelf()
            return
        }

        if (overlayView != null) {
            sendIslandStateBroadcast()
            return
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val layoutParamsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val initialWidth = (screenWidth * 0.84f).toInt().coerceAtMost(dp(340))
        val savedY = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_ISLAND_Y, dp(6))
            .coerceIn(0, (screenHeight - dp(80)).coerceAtLeast(0))

        val params = WindowManager.LayoutParams(
            initialWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutParamsType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = savedY
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        layoutParams = params

        val themedContext = ContextThemeWrapper(this, R.style.Theme_GeniusLyrics)
        overlayView = LayoutInflater.from(themedContext).inflate(R.layout.overlay_lyrics, null)

        currentStyle = AppMotionPrefs.getIslandStyle(this)
        displayMode = AppMotionPrefs.getDisplayMode(this)

        setupIslandViewsAndListeners()

        try {
            windowManager?.addView(overlayView, params)
        } catch (e: Exception) {
            isRunning = false
            setIslandEnabled(this, false)
            stopSelf()
            return
        }

        setIslandState(IslandState.COMPACT, animate = false)

        // Register for track, playback & motion preference updates
        val filter = IntentFilter().apply {
            addAction(MediaNotificationListener.ACTION_TRACK_CHANGED)
            addAction(MediaNotificationListener.ACTION_PLAYBACK_STATE)
            addAction(AppMotionPrefs.ACTION_PREFS_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(trackReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(trackReceiver, filter)
        }

        MediaNotificationListener.refreshActiveTrack()

        val initTitle = MediaNotificationListener.sanitizeText(MediaNotificationListener.currentTitle)
        val initArtist = MediaNotificationListener.sanitizeText(MediaNotificationListener.currentArtist)
        val initRaw = MediaNotificationListener.sanitizeText(MediaNotificationListener.currentRawTitle)
        if (initTitle.isNotBlank()) {
            updatePlaybackTime(
                MediaNotificationListener.currentPositionMs,
                MediaNotificationListener.currentDurationMs,
                MediaNotificationListener.lastPositionUpdateTime,
                MediaNotificationListener.isPlaying
            )
            updateTrack(
                initTitle,
                initArtist,
                initRaw.ifBlank { initTitle },
                MediaNotificationListener.currentArtistFromTitle
            )
        }

        sendIslandStateBroadcast()
        startSyncLoop()
        startEqualizerLoop()
    }

    private fun setupIslandViewsAndListeners() {
        val root = overlayView ?: return
        val layoutMini = root.findViewById<View>(R.id.layoutIslandMini)
        val layoutCompact = root.findViewById<View>(R.id.layoutIslandCompact)
        val header = root.findViewById<View>(R.id.overlayHeader)

        val btnCompactToMini = root.findViewById<View>(R.id.btnCompactToMini)
        val btnOpenApp = root.findViewById<View>(R.id.btnOverlayOpenApp)
        val btnCollapse = root.findViewById<View>(R.id.btnOverlayCollapse)
        val btnClose = root.findViewById<View>(R.id.btnOverlayClose)
        val btnScroll = root.findViewById<ImageButton>(R.id.btnOverlayScroll)
        val btnSyncBack = root.findViewById<View>(R.id.btnOverlaySyncBack)
        val btnSyncFwd = root.findViewById<View>(R.id.btnOverlaySyncFwd)
        val btnGenius = root.findViewById<View>(R.id.btnOverlayGenius)
        val btnOverlayKaraoke = root.findViewById<TextView>(R.id.btnOverlayKaraoke)
        val btnStyleFly = root.findViewById<TextView>(R.id.btnStyleFly)
        val btnStyleFade = root.findViewById<TextView>(R.id.btnStyleFade)
        val btnStyleSlide = root.findViewById<TextView>(R.id.btnStyleSlide)
        val btnStylePop = root.findViewById<TextView>(R.id.btnStylePop)
        val btnStyleTypewriter = root.findViewById<TextView>(R.id.btnStyleTypewriter)

        root.findViewById<TextView>(R.id.tvCompactLyricA)?.isSelected = true
        root.findViewById<TextView>(R.id.tvCompactLyricB)?.isSelected = true
        root.findViewById<TextView>(R.id.tvOverlayNextLine)?.setOnClickListener {
            val nextIdx = activeIndex + 1
            if (nextIdx in currentLines.indices) {
                seekToOverlayLine(nextIdx)
            }
        }

        MotionUtils.attachTactilePress(
            btnCompactToMini,
            btnOpenApp,
            btnCollapse,
            btnClose,
            btnScroll,
            btnSyncBack,
            btnSyncFwd,
            btnGenius,
            btnOverlayKaraoke,
            btnStyleFly,
            btnStyleFade,
            btnStyleSlide,
            btnStylePop,
            btnStyleTypewriter
        )

        attachIslandTouchHandler(layoutMini, isExpandedHeader = false)
        attachIslandTouchHandler(layoutCompact, isExpandedHeader = false)
        attachIslandTouchHandler(header, isExpandedHeader = true)

        btnCompactToMini?.setOnClickListener {
            setIslandState(IslandState.MINI, animate = true)
        }

        btnCollapse?.setOnClickListener {
            setIslandState(IslandState.COMPACT, animate = true)
        }

        btnClose?.setOnClickListener {
            setIslandEnabled(this, false)
            stopSelf()
        }

        btnOpenApp?.setOnClickListener {
            setIslandState(IslandState.COMPACT, animate = true)
            launchMainApp(openGenius = false)
        }

        btnGenius?.setOnClickListener {
            setIslandState(IslandState.COMPACT, animate = true)
            launchMainApp(openGenius = true)
        }

        btnScroll?.setOnClickListener {
            isAutoScrolling = !isAutoScrolling
            if (isAutoScrolling) {
                btnScroll.setImageResource(android.R.drawable.ic_media_pause)
                startSyncLoop()
            } else {
                btnScroll.setImageResource(android.R.drawable.ic_media_play)
                stopSyncLoop()
            }
        }

        btnSyncBack?.setOnClickListener {
            manualSyncOffsetMs -= 500L
            AppMotionPrefs.setSyncOffsetMs(this, manualSyncOffsetMs)
            recalculateActiveLineNow()
            val secStr = String.format("%+.1fс", manualSyncOffsetMs / 1000.0)
            Toast.makeText(this, "Синхронизация: $secStr", Toast.LENGTH_SHORT).show()
        }

        btnSyncFwd?.setOnClickListener {
            manualSyncOffsetMs += 500L
            AppMotionPrefs.setSyncOffsetMs(this, manualSyncOffsetMs)
            recalculateActiveLineNow()
            val secStr = String.format("%+.1fс", manualSyncOffsetMs / 1000.0)
            Toast.makeText(this, "Синхронизация: $secStr", Toast.LENGTH_SHORT).show()
        }

        btnOverlayKaraoke?.setOnClickListener {
            displayMode = LyricsDisplayMode.nextMode(displayMode)
            AppMotionPrefs.setDisplayMode(this, displayMode)
            root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            applyDisplayModeUI()
            Toast.makeText(
                this,
                "Режим: ${displayMode.label}",
                Toast.LENGTH_SHORT
            ).show()
        }

        btnStyleFly?.setOnClickListener { selectStyle(IslandAnimStyle.FLY_IN) }
        btnStyleFade?.setOnClickListener { selectStyle(IslandAnimStyle.FADE) }
        btnStyleSlide?.setOnClickListener { selectStyle(IslandAnimStyle.SLIDE_KINETIC) }
        btnStylePop?.setOnClickListener { selectStyle(IslandAnimStyle.ELASTIC_POP) }
        btnStyleTypewriter?.setOnClickListener { selectStyle(IslandAnimStyle.TYPEWRITER) }

        updateStyleChipsUI()
        applyDisplayModeUI()
    }

    private fun selectStyle(style: IslandAnimStyle) {
        currentStyle = style
        AppMotionPrefs.setIslandStyle(this, style)
        updateStyleChipsUI()
        overlayView?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        pulseIslandBorder()

        val previewLine = if (activeIndex in currentLines.indices) {
            currentLines[activeIndex].text
        } else {
            "${currentTitle.ifBlank { "Живой остров" }} • ${style.label}"
        }
        setCompactTextAnimated(previewLine, animateSwap = true)
        setExpandedTextAnimated(previewLine, animateSwap = true)
    }

    private fun makeChipDrawable(fillColor: Int, strokeColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(fillColor)
            setStroke(dp(1).coerceAtLeast(1), strokeColor)
        }
    }

    private fun updateStyleChipsUI() {
        val root = overlayView ?: return
        val chipMap = listOf(
            IslandAnimStyle.FLY_IN to root.findViewById<TextView>(R.id.btnStyleFly),
            IslandAnimStyle.FADE to root.findViewById<TextView>(R.id.btnStyleFade),
            IslandAnimStyle.SLIDE_KINETIC to root.findViewById<TextView>(R.id.btnStyleSlide),
            IslandAnimStyle.ELASTIC_POP to root.findViewById<TextView>(R.id.btnStylePop),
            IslandAnimStyle.TYPEWRITER to root.findViewById<TextView>(R.id.btnStyleTypewriter)
        )
        val activeFill = Color.parseColor("#183E28")
        val activeStroke = Color.parseColor("#39FF88")
        val activeText = Color.parseColor("#39FF88")
        val idleFill = Color.parseColor("#161D28")
        val idleStroke = Color.parseColor("#283447")
        val idleText = Color.parseColor("#9DA7B5")

        for ((style, tv) in chipMap) {
            if (tv == null) continue
            val isSelected = (style == currentStyle)
            tv.background = makeChipDrawable(
                if (isSelected) activeFill else idleFill,
                if (isSelected) activeStroke else idleStroke
            )
            tv.setTextColor(if (isSelected) activeText else idleText)
        }
    }

    private fun applyDisplayModeUI() {
        val root = overlayView ?: return
        val btnKaraoke = root.findViewById<TextView>(R.id.btnOverlayKaraoke)
        val layoutLive = root.findViewById<View>(R.id.layoutOverlayLive)
        val dividerLive = root.findViewById<View>(R.id.dividerOverlayLive)
        val scrollChips = root.findViewById<HorizontalScrollView>(R.id.scrollStyleChips)
        val btnSyncBack = root.findViewById<View>(R.id.btnOverlaySyncBack)
        val btnSyncFwd = root.findViewById<View>(R.id.btnOverlaySyncFwd)
        val btnScroll = root.findViewById<View>(R.id.btnOverlayScroll)

        when (displayMode) {
            LyricsDisplayMode.KARAOKE -> {
                btnKaraoke?.text = "🎤 Караоке"
                btnKaraoke?.background = makeChipDrawable(
                    Color.parseColor("#143520"),
                    Color.parseColor("#1DB954")
                )
                btnKaraoke?.setTextColor(Color.parseColor("#39FF88"))
            }
            LyricsDisplayMode.WORD_BY_WORD -> {
                btnKaraoke?.text = "✨ По словам"
                btnKaraoke?.background = makeChipDrawable(
                    Color.parseColor("#103038"),
                    Color.parseColor("#00D4FF")
                )
                btnKaraoke?.setTextColor(Color.parseColor("#5CEEFF"))
            }
            LyricsDisplayMode.PLAIN_TEXT -> {
                btnKaraoke?.text = "📄 Текст"
                btnKaraoke?.background = makeChipDrawable(
                    Color.parseColor("#232A36"),
                    Color.parseColor("#4A5668")
                )
                btnKaraoke?.setTextColor(Color.WHITE)
            }
        }

        val isKaraokeActive = (displayMode != LyricsDisplayMode.PLAIN_TEXT)
        val karaokeVis = if (isKaraokeActive) View.VISIBLE else View.GONE
        layoutLive?.visibility = karaokeVis
        dividerLive?.visibility = karaokeVis
        scrollChips?.visibility = karaokeVis
        btnSyncBack?.visibility = karaokeVis
        btnSyncFwd?.visibility = karaokeVis
        btnScroll?.visibility = karaokeVis

        lastOverlayWordKey = -1L

        if (!isKaraokeActive) {
            val plainLabel = if (currentTitle.isNotBlank()) {
                val trackLabel = if (currentArtist.isNotBlank()) "$currentArtist — $currentTitle" else currentTitle
                "♪ $trackLabel"
            } else {
                "♪ Просто текст"
            }
            setCompactTextAnimated(plainLabel, animateSwap = false)
            renderPlainOverlayLines()
            updateNotificationContent()
        } else if (currentLines.isNotEmpty()) {
            val targetIdx = activeIndex.coerceAtLeast(0).coerceAtMost(currentLines.lastIndex)
            applyHighlight(targetIdx, scroll = true, animateSwap = false)
            recalculateActiveLineNow(advanceVirtual = false)
            updateNotificationContent()
        }
        if (isAutoScrolling) {
            startSyncLoop()
        }
    }

    private fun renderPlainOverlayLines() {
        for (i in overlayLineViews.indices) {
            val tv = overlayLineViews[i]
            val trimmed = currentLines.getOrNull(i)?.text?.trim() ?: ""
            val isHeader = trimmed.startsWith("[") && trimmed.endsWith("]")
            if (isHeader) {
                tv.setTextColor(Color.parseColor("#1DB954"))
                tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
            } else {
                tv.setTextColor(Color.parseColor("#F0F2F5"))
                tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachIslandTouchHandler(target: View?, isExpandedHeader: Boolean) {
        if (target == null) return
        val touchSlop = dp(10)
        val swipeUpThreshold = dp(32)

        var initialX = 0
        var initialY = 0
        var downRawX = 0f
        var downRawY = 0f
        var isDragging = false

        target.setOnTouchListener { _, event ->
            val params = layoutParams ?: return@setOnTouchListener false
            val screenW = resources.displayMetrics.widthPixels
            val screenH = resources.displayMetrics.heightPixels

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    snapAnimator?.cancel()
                    initialX = params.x
                    initialY = params.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!isDragging && hypot(dx.toDouble(), dy.toDouble()) > touchSlop) {
                        isDragging = true
                    }
                    if (isDragging) {
                        params.x = (initialX + dx.toInt()).coerceIn(-(screenW / 3), screenW / 3)
                        params.y = (initialY + dy.toInt()).coerceIn(0, (screenH - dp(80)).coerceAtLeast(0))
                        try {
                            windowManager?.updateViewLayout(overlayView, params)
                        } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (isExpandedHeader && dy < -swipeUpThreshold && abs(dy) > abs(dx)) {
                        // Swipe up on expanded header collapses back smoothly & restores saved Y
                        params.x = 0
                        params.y = initialY
                        try {
                            windowManager?.updateViewLayout(overlayView, params)
                        } catch (_: Exception) {}
                        setIslandState(IslandState.COMPACT, animate = true)
                    } else if (isDragging) {
                        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit()
                            .putInt(KEY_ISLAND_Y, params.y)
                            .apply()
                        snapHorizontallyToCenter(params)
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                        when (currentState) {
                            IslandState.MINI -> setIslandState(IslandState.COMPACT, animate = true)
                            IslandState.COMPACT -> setIslandState(IslandState.EXPANDED, animate = true)
                            IslandState.EXPANDED -> setIslandState(IslandState.COMPACT, animate = true)
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun snapHorizontallyToCenter(params: WindowManager.LayoutParams) {
        if (params.x == 0) return
        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofInt(params.x, 0).apply {
            duration = 200L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                params.x = anim.animatedValue as Int
                try {
                    overlayView?.let { windowManager?.updateViewLayout(it, params) }
                } catch (_: Exception) {}
            }
            start()
        }
    }

    private fun setIslandState(newState: IslandState, animate: Boolean = true) {
        currentState = newState
        val root = overlayView ?: return
        val params = layoutParams ?: return

        val layoutMini = root.findViewById<View>(R.id.layoutIslandMini)
        val layoutCompact = root.findViewById<View>(R.id.layoutIslandCompact)
        val layoutExpanded = root.findViewById<View>(R.id.layoutIslandExpanded)

        layoutMini?.visibility = if (newState == IslandState.MINI) View.VISIBLE else View.GONE
        layoutCompact?.visibility = if (newState == IslandState.COMPACT) View.VISIBLE else View.GONE
        layoutExpanded?.visibility = if (newState == IslandState.EXPANDED) View.VISIBLE else View.GONE

        if (newState == IslandState.COMPACT) {
            root.findViewById<TextView>(R.id.tvCompactLyricA)?.isSelected = true
            root.findViewById<TextView>(R.id.tvCompactLyricB)?.isSelected = true
        }

        val activeContainer = when (newState) {
            IslandState.MINI -> layoutMini
            IslandState.COMPACT -> layoutCompact
            IslandState.EXPANDED -> layoutExpanded
        }
        if (animate && activeContainer != null) {
            activeContainer.animate().cancel()
            activeContainer.pivotY = 0f
            activeContainer.alpha = 0f
            activeContainer.scaleX = 0.93f
            activeContainer.scaleY = 0.90f
            activeContainer.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(240L)
                .setInterpolator(MotionUtils.EASE_OUT_EXPO)
                .start()
            pulseIslandBorder()
        } else if (activeContainer != null) {
            activeContainer.alpha = 1f
            activeContainer.scaleX = 1f
            activeContainer.scaleY = 1f
        }

        val screenWidth = resources.displayMetrics.widthPixels
        val targetWidth = when (newState) {
            IslandState.MINI -> dp(146)
            IslandState.COMPACT -> (screenWidth * 0.84f).toInt().coerceAtMost(dp(340))
            IslandState.EXPANDED -> (screenWidth * 0.92f).toInt().coerceAtMost(dp(400))
        }

        widthAnimator?.cancel()
        val startWidth = if (params.width > 0) params.width else targetWidth
        if (animate && startWidth != targetWidth) {
            widthAnimator = ValueAnimator.ofInt(startWidth, targetWidth).apply {
                duration = 280L
                interpolator = OvershootInterpolator(0.75f)
                addUpdateListener { anim ->
                    params.width = anim.animatedValue as Int
                    try {
                        windowManager?.updateViewLayout(root, params)
                    } catch (_: Exception) {}
                }
                start()
            }
        } else {
            params.width = targetWidth
            try {
                windowManager?.updateViewLayout(root, params)
            } catch (_: Exception) {}
        }
    }

    private fun pulseIslandBorder() {
        val root = overlayView ?: return
        val targetView: View?
        val fillColor: Int
        val restStroke: Int
        when (currentState) {
            IslandState.COMPACT -> {
                targetView = root.findViewById(R.id.layoutIslandCompact)
                fillColor = Color.parseColor("#F5070A0F")
                restStroke = Color.parseColor("#2C3544")
            }
            IslandState.EXPANDED -> {
                targetView = root.findViewById(R.id.layoutIslandExpanded)
                fillColor = Color.parseColor("#FA090D14")
                restStroke = Color.parseColor("#2E394A")
            }
            IslandState.MINI -> return
        }
        borderGlowAnimator?.cancel()
        borderGlowAnimator = MotionUtils.createBorderGlowAnimator(
            view = targetView,
            fillColor = fillColor,
            glowColor = Color.parseColor("#39FF88"),
            restStrokeColor = restStroke,
            cornerRadiusPx = dp(28).toFloat(),
            restStrokeWidthPx = dp(1).coerceAtLeast(1),
            glowStrokeWidthPx = dp(2).coerceAtLeast(2)
        )?.apply { start() }
    }

    private fun launchMainApp(openGenius: Boolean) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (openGenius) {
                putExtra(MainActivity.EXTRA_OPEN_GENIUS_WEB, true)
            }
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {}
    }

    private fun startAsForeground() {
        ensureNotificationChannel()
        val notification = buildNotification()
        if (!isForegroundStarted) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                isForegroundStarted = true
            } catch (_: Exception) {}
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Живой остров (текст песен)",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Постоянный мини-оверлей с синхронным текстом поверх приложений"
                    setShowBadge(false)
                    setSound(null, null)
                }
                mgr.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            10,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, FloatingLyricsService::class.java).apply {
            action = ACTION_STOP_ISLAND
        }
        val stopPending = PendingIntent.getService(
            this,
            11,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val titleText = if (currentTitle.isNotBlank()) {
            if (currentArtist.isNotBlank()) "$currentArtist — $currentTitle" else currentTitle
        } else {
            "Живой остров активен"
        }

        val lineText = if (displayMode == LyricsDisplayMode.PLAIN_TEXT && currentTitle.isNotBlank()) {
            "Режим: Просто текст"
        } else if (activeIndex in currentLines.indices) {
            currentLines[activeIndex].text
        } else if (currentTitle.isNotBlank()) {
            if (isMediaPlaying) "Синхронизация текста включена" else "Пауза"
        } else {
            "Текст песен отображается поверх приложений"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(titleText)
            .setContentText(lineText)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Выключить", stopPending)
            .build()
    }

    private fun updateNotificationContent() {
        try {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            mgr.notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {}
    }

    private fun updatePlaybackTime(positionMs: Long, durationMs: Long, updatedAt: Long, playing: Boolean) {
        lastKnownPositionMs = positionMs.coerceAtLeast(0L)
        if (durationMs > 0L) {
            totalDurationMs = durationMs
        }
        val now = SystemClock.elapsedRealtime()
        lastUpdateTimeSystem = if (updatedAt in 1L..now && (now - updatedAt) <= 30_000L) updatedAt else now
        isMediaPlaying = playing
    }

    private fun refreshAdaptedTimelineIfNeeded() {
        val res = currentLyricsResult ?: return
        if (totalDurationMs <= 10_000L || currentLines.isEmpty()) return
        if (lastAdaptedDurationMs == totalDurationMs && (syncedLines.firstOrNull()?.timeMs ?: -1L) >= 0L) return
        syncedLines = GeniusLyricsFetcher.adaptLyricsToTrackDuration(res, totalDurationMs, currentRawTitleForAdapt)
        lastAdaptedDurationMs = totalDurationMs
    }

    private fun getCurrentTrackPosition(): Long {
        if (!isMediaPlaying || lastUpdateTimeSystem == 0L) {
            return lastKnownPositionMs.coerceAtLeast(0L)
        }
        val elapsed = ((SystemClock.elapsedRealtime() - lastUpdateTimeSystem).coerceAtLeast(0L) * playbackSpeed).toLong()
        val raw = lastKnownPositionMs + elapsed
        return if (totalDurationMs > 0L) raw.coerceAtMost(totalDurationMs) else raw
    }

    private fun updateProgressViews() {
        val root = overlayView ?: return
        val pbCompact = root.findViewById<ProgressBar>(R.id.pbCompactProgress)
        val pbOverlay = root.findViewById<ProgressBar>(R.id.pbOverlayProgress)
        val tvTime = root.findViewById<TextView>(R.id.tvOverlayTime)

        val currentMs = getCurrentTrackPosition()
        if (totalDurationMs > 0L) {
            val progress = ((currentMs.toDouble() / totalDurationMs) * 1000).toInt().coerceIn(0, 1000)
            MotionUtils.animateProgressSmooth(pbCompact, progress)
            MotionUtils.animateProgressSmooth(pbOverlay, progress)
            tvTime?.text = "${formatTime(currentMs)} / ${formatTime(totalDurationMs)}"
        } else {
            pbCompact?.progress = 0
            pbOverlay?.progress = 0
            tvTime?.text = "${formatTime(currentMs)} / --:--"
        }
    }

    private fun updateTrack(title: String, artist: String, rawTitle: String, artistFromTitle: Boolean) {
        val cleanTitle = MediaNotificationListener.sanitizeText(title)
        val cleanArtist = MediaNotificationListener.sanitizeText(artist)
        if (cleanTitle.isBlank()) return
        if (cleanTitle == currentTitle && cleanArtist == currentArtist && currentLines.isNotEmpty()) {
            return
        }

        currentTitle = cleanTitle
        currentArtist = cleanArtist
        currentSourceName = ""
        currentLines = emptyList()
        syncedLines = emptyList()
        activeIndex = -1
        virtualPositionMs = 0L
        manualSyncOffsetMs = 0L
        AppMotionPrefs.resetSyncOffset()
        currentLyricsResult = null
        lastAdaptedDurationMs = 0L
        currentRawTitleForAdapt = rawTitle.ifBlank { cleanTitle }
        seekIgnoreUntilSystemMs = 0L
        overlayLineViews.clear()

        val root = overlayView
        val tvTitle = root?.findViewById<TextView>(R.id.tvOverlayTitle)
        val tvSub = root?.findViewById<TextView>(R.id.tvOverlaySubtitle)
        val tvMini = root?.findViewById<TextView>(R.id.tvMiniLabel)
        val tvNext = root?.findViewById<TextView>(R.id.tvOverlayNextLine)
        val tvEmpty = root?.findViewById<TextView>(R.id.tvOverlayEmptyStatus)
        val container = root?.findViewById<LinearLayout>(R.id.containerOverlayLyrics)
        val scrollView = root?.findViewById<NestedScrollView>(R.id.scrollOverlayLyrics)

        tvTitle?.text = cleanTitle
        tvSub?.text = if (cleanArtist.isNotBlank()) cleanArtist else "SoundCloud • Spotify"
        tvMini?.text = "♪ $cleanTitle"
        lastOverlayWordKey = -1L
        setCompactTextAnimated(
            if (displayMode != LyricsDisplayMode.PLAIN_TEXT) "Поиск: $cleanTitle…" else "♪ $cleanTitle",
            animateSwap = false
        )
        setExpandedTextAnimated("Поиск текста...", animateSwap = false)
        tvNext?.text = ""
        container?.removeAllViews()
        tvEmpty?.visibility = View.VISIBLE
        tvEmpty?.text = "Загрузка текста..."
        scrollView?.scrollTo(0, 0)
        updateNotificationContent()

        loadJob?.cancel()
        loadJob = scope.launch {
            val result = GeniusLyricsFetcher.getLyrics(
                context = applicationContext,
                artist = cleanArtist,
                title = cleanTitle,
                rawTitle = rawTitle.ifBlank { cleanTitle },
                artistFromTitle = artistFromTitle,
                trackDurationMs = if (totalDurationMs > 0L) totalDurationMs else MediaNotificationListener.currentDurationMs
            )
            if (!isActive) return@launch
            result.onSuccess { lyricsResult ->
                if (!isActive) return@onSuccess
                currentLines = lyricsResult.lines
                isSyncedSource = lyricsResult.isSynced
                currentSourceName = lyricsResult.sourceName
                currentLyricsResult = lyricsResult
                if (totalDurationMs <= 0L && MediaNotificationListener.currentDurationMs > 0L) {
                    totalDurationMs = MediaNotificationListener.currentDurationMs
                }
                syncedLines = if (totalDurationMs > 10_000L) {
                    lastAdaptedDurationMs = totalDurationMs
                    GeniusLyricsFetcher.adaptLyricsToTrackDuration(lyricsResult, totalDurationMs, currentRawTitleForAdapt)
                } else {
                    currentLines
                }

                populateOverlayLyrics(currentLines)

                val dispArtist = lyricsResult.artist.ifBlank { cleanArtist }
                val dispTitle = lyricsResult.title.ifBlank { cleanTitle }
                tvTitle?.text = dispTitle
                tvSub?.text = if (dispArtist.isNotBlank()) "$dispArtist • ${lyricsResult.sourceName}" else lyricsResult.sourceName
                tvMini?.text = "♪ $dispTitle"

                if (displayMode == LyricsDisplayMode.PLAIN_TEXT) {
                    val trackLabel = if (dispArtist.isNotBlank()) "$dispArtist — $dispTitle" else dispTitle
                    setCompactTextAnimated("♪ $trackLabel", animateSwap = false)
                    renderPlainOverlayLines()
                } else {
                    applyHighlight(0, scroll = false, animateSwap = false)
                    if (getCurrentTrackPosition() > 0L) {
                        recalculateActiveLineNow(advanceVirtual = false)
                    }
                }
                updateNotificationContent()
            }.onFailure {
                if (!isActive) return@onFailure
                overlayLineViews.clear()
                container?.removeAllViews()
                setCompactTextAnimated("$cleanTitle — нет на Genius", animateSwap = false)
                setExpandedTextAnimated("Текст не найден", animateSwap = false)
                tvNext?.text = "Нажмите «Genius» внизу для поиска"
                tvEmpty?.visibility = View.VISIBLE
                tvEmpty?.text = "Текст не найден."
                updateNotificationContent()
            }
        }
    }

    private fun startEqualizerLoop() {
        equalizerJob?.cancel()
        equalizerJob = scope.launch {
            while (isActive) {
                val root = overlayView
                if (root != null) {
                    eqPhase += 0.34
                    val bars: List<View?> = when (currentState) {
                        IslandState.MINI -> listOf(
                            root.findViewById(R.id.eqMini1),
                            root.findViewById(R.id.eqMini2),
                            root.findViewById(R.id.eqMini3),
                            root.findViewById(R.id.eqMini4)
                        )
                        IslandState.COMPACT -> listOf(
                            root.findViewById(R.id.eqCompact1),
                            root.findViewById(R.id.eqCompact2),
                            root.findViewById(R.id.eqCompact3),
                            root.findViewById(R.id.eqCompact4)
                        )
                        IslandState.EXPANDED -> listOf(
                            root.findViewById(R.id.eqExp1),
                            root.findViewById(R.id.eqExp2),
                            root.findViewById(R.id.eqExp3),
                            root.findViewById(R.id.eqExp4)
                        )
                    }
                    val phaseOffsets = doubleArrayOf(0.0, 1.35, 2.7, 4.05)
                    for (i in bars.indices) {
                        val bar = bars[i] ?: continue
                        val h = bar.height
                        if (h > 0) bar.pivotY = h.toFloat()
                        val targetScale = if (isMediaPlaying) {
                            val primary = (sin(eqPhase + phaseOffsets[i]) + 1.0) * 0.5
                            val harmonic = (sin(eqPhase * 1.9 + phaseOffsets[i] * 0.7) + 1.0) * 0.5
                            (0.24 + 0.74 * (0.65 * primary + 0.35 * harmonic)).toFloat().coerceIn(0.22f, 1.0f)
                        } else {
                            0.22f
                        }
                        bar.animate()
                            .scaleY(targetScale)
                            .setDuration(75L)
                            .setInterpolator(MotionUtils.EASE_OUT_CUBIC)
                            .start()
                    }
                }
                delay(70)
            }
        }
    }

    private fun startSyncLoop() {
        scrollJob?.cancel()
        scrollJob = scope.launch {
            while (isActive && isAutoScrolling) {
                val tickDelayMs = if (displayMode == LyricsDisplayMode.WORD_BY_WORD) 55L else 140L
                updateProgressViews()
                if (displayMode != LyricsDisplayMode.PLAIN_TEXT && currentLines.isNotEmpty() && isMediaPlaying) {
                    recalculateActiveLineNow(advanceVirtual = true, tickStepMs = tickDelayMs)
                }
                delay(tickDelayMs)
            }
        }
    }

    private fun recalculateActiveLineNow(advanceVirtual: Boolean = false, tickStepMs: Long = 150L) {
        if (displayMode == LyricsDisplayMode.PLAIN_TEXT || currentLines.isEmpty()) return

        refreshAdaptedTimelineIfNeeded()

        val virtualDur = (currentLines.size * 2800L).coerceAtLeast(30_000L)
        val timeline = if ((syncedLines.firstOrNull()?.timeMs ?: -1L) >= 0L) {
            syncedLines
        } else {
            GeniusLyricsFetcher.estimateLineTimestamps(currentLines, virtualDur).also {
                syncedLines = it
            }
        }

        val effectiveDur = if (totalDurationMs > 10_000L) totalDurationMs else virtualDur
        val effectiveMs = if (totalDurationMs > 10_000L) {
            (getCurrentTrackPosition() + manualSyncOffsetMs + SYNC_LEAD_MS).coerceAtLeast(0L)
        } else {
            if (advanceVirtual) {
                virtualPositionMs += tickStepMs
            }
            val lastTs = timeline.lastOrNull()?.timeMs ?: 30_000L
            if (virtualPositionMs > lastTs + 3000L) virtualPositionMs = 0L
            (virtualPositionMs + manualSyncOffsetMs).coerceAtLeast(0L)
        }

        var bestIdx = -1
        for (i in timeline.indices) {
            val t = timeline[i].timeMs
            if (t in 0..effectiveMs) {
                bestIdx = i
            } else if (t > effectiveMs) {
                break
            }
        }

        if (bestIdx != -1 && bestIdx != activeIndex) {
            lastOverlayWordKey = -1L
            applyHighlight(bestIdx, scroll = true, animateSwap = true)
        }

        if (displayMode == LyricsDisplayMode.WORD_BY_WORD && activeIndex in timeline.indices) {
            if (currentStyle == IslandAnimStyle.TYPEWRITER &&
                (compactTypeJob?.isActive == true || expandedTypeJob?.isActive == true)
            ) {
                return
            }
            val beat = WordTimingEngine.computeBeatState(timeline, activeIndex, effectiveMs, effectiveDur)
            if (beat != null) {
                val wordCode = if (beat.isCompleted) 0xFFFFL else beat.activeWordIndex.toLong()
                val wordKey = (activeIndex.toLong() shl 20) or wordCode
                if (wordKey != lastOverlayWordKey) {
                    lastOverlayWordKey = wordKey
                    applyOverlayWordBeat(activeIndex, beat)
                }
            }
        }
    }

    private fun applyOverlayWordBeat(idx: Int, beat: WordTimingEngine.WordBeatState) {
        val root = overlayView ?: return
        val lineText = currentLines.getOrNull(idx)?.text ?: return
        val trimmed = lineText.trim()
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) return

        val span = MotionUtils.buildWordBeatSpannable(lineText, beat)

        val compactActive = if (compactUsingA) {
            root.findViewById<TextView>(R.id.tvCompactLyricA)
        } else {
            root.findViewById<TextView>(R.id.tvCompactLyricB)
        }
        compactActive?.text = span

        val expandedActive = if (expandedUsingA) {
            root.findViewById<TextView>(R.id.tvOverlayCurrentA)
        } else {
            root.findViewById<TextView>(R.id.tvOverlayCurrentB)
        }
        expandedActive?.text = span

        overlayLineViews.getOrNull(idx)?.text = span
    }

    private fun resetStageView(tv: TextView?) {
        if (tv == null) return
        tv.animate().cancel()
        tv.alpha = 1f
        tv.translationX = 0f
        tv.translationY = 0f
        tv.scaleX = 1f
        tv.scaleY = 1f
        tv.rotationX = 0f
    }

    private fun setCompactTextAnimated(newText: String, animateSwap: Boolean) {
        val root = overlayView ?: return
        val tvA = root.findViewById<TextView>(R.id.tvCompactLyricA) ?: return
        val tvB = root.findViewById<TextView>(R.id.tvCompactLyricB) ?: return
        val gen = ++compactSwapGen
        compactTypeJob?.cancel()
        if (!animateSwap) {
            resetStageView(tvB)
            tvB.visibility = View.GONE
            compactUsingA = true
            swapDualStageText(
                outgoing = null,
                incoming = tvA,
                newText = newText,
                animateSwap = false,
                isCompact = true,
                generationCheck = { gen == compactSwapGen }
            )
            return
        }
        val outgoing = if (compactUsingA) tvA else tvB
        val incoming = if (compactUsingA) tvB else tvA
        compactUsingA = !compactUsingA
        compactTypeJob = swapDualStageText(
            outgoing = outgoing,
            incoming = incoming,
            newText = newText,
            animateSwap = true,
            isCompact = true,
            generationCheck = { gen == compactSwapGen }
        )
    }

    private fun setExpandedTextAnimated(newText: String, animateSwap: Boolean) {
        val root = overlayView ?: return
        val tvA = root.findViewById<TextView>(R.id.tvOverlayCurrentA) ?: return
        val tvB = root.findViewById<TextView>(R.id.tvOverlayCurrentB) ?: return
        val gen = ++expandedSwapGen
        expandedTypeJob?.cancel()
        if (!animateSwap) {
            resetStageView(tvB)
            tvB.visibility = View.GONE
            expandedUsingA = true
            swapDualStageText(
                outgoing = null,
                incoming = tvA,
                newText = newText,
                animateSwap = false,
                isCompact = false,
                generationCheck = { gen == expandedSwapGen }
            )
            return
        }
        val outgoing = if (expandedUsingA) tvA else tvB
        val incoming = if (expandedUsingA) tvB else tvA
        expandedUsingA = !expandedUsingA
        expandedTypeJob = swapDualStageText(
            outgoing = outgoing,
            incoming = incoming,
            newText = newText,
            animateSwap = true,
            isCompact = false,
            generationCheck = { gen == expandedSwapGen }
        )
    }

    private fun swapDualStageText(
        outgoing: TextView?,
        incoming: TextView,
        newText: String,
        animateSwap: Boolean,
        isCompact: Boolean,
        generationCheck: () -> Boolean
    ): Job? {
        resetStageView(incoming)
        incoming.visibility = View.VISIBLE
        incoming.isSelected = true

        if (!animateSwap) {
            outgoing?.let {
                resetStageView(it)
                it.visibility = View.GONE
            }
            incoming.text = newText
            return null
        }

        if (outgoing != null) {
            outgoing.animate().cancel()
            outgoing.visibility = View.VISIBLE
        }

        when (currentStyle) {
            IslandAnimStyle.FLY_IN -> {
                outgoing?.animate()
                    ?.translationY(-dp(30).toFloat())
                    ?.alpha(0f)
                    ?.rotationX(-24f)
                    ?.setDuration(180L)
                    ?.setInterpolator(MotionUtils.EASE_OUT_CUBIC)
                    ?.withEndAction {
                        if (generationCheck()) {
                            outgoing.visibility = View.GONE
                            resetStageView(outgoing)
                        }
                    }
                    ?.start()

                incoming.text = newText
                incoming.alpha = 0f
                incoming.translationY = dp(30).toFloat()
                incoming.rotationX = 28f
                incoming.animate()
                    .translationY(0f)
                    .rotationX(0f)
                    .alpha(1f)
                    .setDuration(340L)
                    .setInterpolator(MotionUtils.SPRING_SNAPPY)
                    .withEndAction { incoming.isSelected = true }
                    .start()
                if (isCompact) pulseIslandBorder()
                return null
            }

            IslandAnimStyle.FADE -> {
                outgoing?.animate()
                    ?.alpha(0f)
                    ?.translationY(-dp(4).toFloat())
                    ?.setDuration(190L)
                    ?.setInterpolator(MotionUtils.EASE_OUT_CUBIC)
                    ?.withEndAction {
                        if (generationCheck()) {
                            outgoing.visibility = View.GONE
                            resetStageView(outgoing)
                        }
                    }
                    ?.start()

                incoming.text = newText
                incoming.alpha = 0f
                incoming.translationY = dp(5).toFloat()
                incoming.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(420L)
                    .setInterpolator(MotionUtils.EASE_OUT_QUINT)
                    .withEndAction { incoming.isSelected = true }
                    .start()
                return null
            }

            IslandAnimStyle.SLIDE_KINETIC -> {
                outgoing?.animate()
                    ?.translationX(-dp(64).toFloat())
                    ?.alpha(0f)
                    ?.setDuration(170L)
                    ?.setInterpolator(MotionUtils.EASE_OUT_CUBIC)
                    ?.withEndAction {
                        if (generationCheck()) {
                            outgoing.visibility = View.GONE
                            resetStageView(outgoing)
                        }
                    }
                    ?.start()

                incoming.text = newText
                incoming.alpha = 0f
                incoming.translationX = dp(64).toFloat()
                incoming.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(300L)
                    .setInterpolator(MotionUtils.SPRING_CRISP)
                    .withEndAction { incoming.isSelected = true }
                    .start()
                if (isCompact) pulseIslandBorder()
                return null
            }

            IslandAnimStyle.ELASTIC_POP -> {
                outgoing?.animate()
                    ?.scaleX(0.78f)
                    ?.scaleY(0.78f)
                    ?.translationY(-dp(10).toFloat())
                    ?.alpha(0f)
                    ?.setDuration(160L)
                    ?.setInterpolator(MotionUtils.EASE_OUT_CUBIC)
                    ?.withEndAction {
                        if (generationCheck()) {
                            outgoing.visibility = View.GONE
                            resetStageView(outgoing)
                        }
                    }
                    ?.start()

                incoming.text = newText
                incoming.alpha = 0f
                incoming.scaleX = 0.76f
                incoming.scaleY = 0.76f
                incoming.translationY = dp(12).toFloat()
                incoming.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(360L)
                    .setInterpolator(MotionUtils.SPRING_BOUNCY)
                    .withEndAction { incoming.isSelected = true }
                    .start()
                if (isCompact) pulseIslandBorder()
                return null
            }

            IslandAnimStyle.TYPEWRITER -> {
                outgoing?.animate()
                    ?.translationY(-dp(8).toFloat())
                    ?.alpha(0f)
                    ?.setDuration(140L)
                    ?.setInterpolator(MotionUtils.EASE_OUT_CUBIC)
                    ?.withEndAction {
                        if (generationCheck()) {
                            outgoing.visibility = View.GONE
                            resetStageView(outgoing)
                        }
                    }
                    ?.start()

                incoming.alpha = 1f
                incoming.text = ""
                return scope.launch {
                    val len = newText.length
                    if (len == 0) {
                        incoming.text = ""
                        return@launch
                    }
                    val steps = len.coerceIn(1, 18)
                    val stepDelay = (220L / steps).coerceIn(10L, 28L)
                    for (s in 1 until steps) {
                        if (!isActive || !generationCheck()) return@launch
                        val endIdx = ((s.toDouble() / steps) * len).toInt().coerceIn(1, len)
                        incoming.text = newText.substring(0, endIdx) + "▌"
                        delay(stepDelay)
                    }
                    if (isActive && generationCheck()) {
                        incoming.text = newText
                        incoming.isSelected = true
                    }
                }
            }
        }
    }

    private fun populateOverlayLyrics(lines: List<LyricLine>) {
        val root = overlayView ?: return
        val container = root.findViewById<LinearLayout>(R.id.containerOverlayLyrics) ?: return
        val tvEmpty = root.findViewById<TextView>(R.id.tvOverlayEmptyStatus)

        container.removeAllViews()
        overlayLineViews.clear()
        tvEmpty?.visibility = if (lines.isEmpty()) View.VISIBLE else View.GONE

        val selectableBg = TypedValue()
        val hasSelectableBg = container.context.theme.resolveAttribute(
            android.R.attr.selectableItemBackground,
            selectableBg,
            true
        )

        for (i in lines.indices) {
            val line = lines[i]
            val tv = TextView(container.context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                text = line.text
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                setLineSpacing(dp(2).toFloat(), 1f)
                setPadding(dp(4), dp(5), dp(4), dp(5))
                isClickable = true
                isLongClickable = true
                if (hasSelectableBg) {
                    setBackgroundResource(selectableBg.resourceId)
                }
                setOnClickListener { seekToOverlayLine(i) }
                setOnLongClickListener {
                    calibrateOverlaySyncToLine(i)
                    true
                }
            }
            container.addView(tv)
            overlayLineViews.add(tv)
        }
    }

    private fun resolveOverlayTimestampMs(idx: Int): Long {
        val effectiveDur = if (totalDurationMs > 0L) totalDurationMs else MediaNotificationListener.currentDurationMs
        if (totalDurationMs <= 0L && effectiveDur > 0L) {
            totalDurationMs = effectiveDur
        }
        refreshAdaptedTimelineIfNeeded()
        if (effectiveDur > 10_000L && syncedLines.firstOrNull()?.timeMs == -1L) {
            syncedLines = GeniusLyricsFetcher.estimateLineTimestamps(currentLines, effectiveDur)
        }
        val timeline = if (syncedLines.size == currentLines.size) syncedLines else currentLines
        return GeniusLyricsFetcher.resolveLineTimestampMs(timeline, idx, effectiveDur)
    }

    private fun seekToOverlayLine(idx: Int) {
        if (idx !in currentLines.indices) return
        val tappedTime = resolveOverlayTimestampMs(idx)
        val effectiveDur = if (totalDurationMs > 0L) totalDurationMs else MediaNotificationListener.currentDurationMs

        if (tappedTime >= 0L) {
            val seekPos = GeniusLyricsFetcher.computeSeekTargetMs(tappedTime, manualSyncOffsetMs, effectiveDur)
            val sought = MediaNotificationListener.seekTo(seekPos)
            if (sought) {
                val now = SystemClock.elapsedRealtime()
                seekIgnoreUntilSystemMs = now + 650L
                lastKnownPositionMs = seekPos
                lastUpdateTimeSystem = now
                updateProgressViews()
                overlayView?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                if (displayMode != LyricsDisplayMode.PLAIN_TEXT) {
                    lastOverlayWordKey = -1L
                    applyHighlight(idx, scroll = true, animateSwap = true)
                    pulseIslandBorder()
                    if (displayMode == LyricsDisplayMode.WORD_BY_WORD) {
                        val timeline = if (syncedLines.size == currentLines.size) syncedLines else currentLines
                        val beat = WordTimingEngine.computeBeatState(timeline, idx, tappedTime, effectiveDur)
                        if (beat != null) {
                            applyOverlayWordBeat(idx, beat)
                        }
                    }
                }
                updateNotificationContent()
                return
            } else {
                virtualPositionMs = tappedTime
            }
        }
        if (displayMode != LyricsDisplayMode.PLAIN_TEXT) {
            lastOverlayWordKey = -1L
            applyHighlight(idx, scroll = true, animateSwap = true)
        }
        Toast.makeText(this, "Не удалось перемотать трек", Toast.LENGTH_SHORT).show()
    }

    private fun calibrateOverlaySyncToLine(idx: Int) {
        if (idx !in currentLines.indices) return
        val tappedTime = resolveOverlayTimestampMs(idx)
        if (tappedTime >= 0L && totalDurationMs > 0L) {
            manualSyncOffsetMs = tappedTime - getCurrentTrackPosition()
            AppMotionPrefs.setSyncOffsetMs(this, manualSyncOffsetMs)
            overlayView?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            if (displayMode != LyricsDisplayMode.PLAIN_TEXT) {
                lastOverlayWordKey = -1L
                applyHighlight(idx, scroll = true, animateSwap = true)
            }
            Toast.makeText(this, "Синхронизация привязана к этой строке", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyHighlight(idx: Int, scroll: Boolean, animateSwap: Boolean) {
        if (idx < 0 || idx >= currentLines.size) return
        activeIndex = idx

        val root = overlayView ?: return
        val tvNext = root.findViewById<TextView>(R.id.tvOverlayNextLine)
        val scrollView = root.findViewById<NestedScrollView>(R.id.scrollOverlayLyrics)

        if (displayMode == LyricsDisplayMode.PLAIN_TEXT) {
            renderPlainOverlayLines()
            return
        }

        val currentLineText = currentLines[idx].text
        val nextText = currentLines.getOrNull(idx + 1)?.text ?: ""

        setCompactTextAnimated(currentLineText, animateSwap)
        setExpandedTextAnimated(currentLineText, animateSwap)
        tvNext?.text = nextText

        for (i in overlayLineViews.indices) {
            val tv = overlayLineViews[i]
            val lineStr = currentLines.getOrNull(i)?.text ?: ""
            val trimmed = lineStr.trim()
            val isHeader = trimmed.startsWith("[") && trimmed.endsWith("]")
            if (i != idx) {
                tv.text = lineStr
            }
            when {
                isHeader -> {
                    tv.setTextColor(Color.parseColor("#39FF88"))
                    tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
                }
                i == idx -> {
                    tv.setTextColor(Color.WHITE)
                    tv.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD))
                }
                i < idx -> {
                    tv.setTextColor(Color.parseColor("#545D6B"))
                    tv.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL))
                }
                else -> {
                    tv.setTextColor(Color.parseColor("#B3BCC9"))
                    tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
                }
            }
        }

        if (scroll && idx in overlayLineViews.indices && scrollView != null) {
            val targetView = overlayLineViews[idx]
            targetView.post {
                val targetY = (targetView.top - scrollView.height / 3).coerceAtLeast(0)
                scrollView.smoothScrollTo(0, targetY)
            }
        }
    }

    private fun stopSyncLoop() {
        scrollJob?.cancel()
        scrollJob = null
    }

    private fun sendIslandStateBroadcast() {
        try {
            val i = Intent(ACTION_ISLAND_STATE_CHANGED).apply {
                setPackage(applicationContext.packageName)
            }
            sendBroadcast(i)
        } catch (_: Exception) {}
    }

    private fun formatTime(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format("%02d:%02d", min, sec)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        widthAnimator?.cancel()
        snapAnimator?.cancel()
        borderGlowAnimator?.cancel()
        compactTypeJob?.cancel()
        expandedTypeJob?.cancel()
        stopSyncLoop()
        equalizerJob?.cancel()
        loadJob?.cancel()
        scope.cancel()
        try {
            unregisterReceiver(trackReceiver)
        } catch (_: Exception) {}
        overlayLineViews.clear()
        overlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {}
        }
        overlayView = null
        sendIslandStateBroadcast()
    }
}
