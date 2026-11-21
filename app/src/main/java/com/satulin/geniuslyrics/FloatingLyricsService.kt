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
import kotlin.random.Random

class FloatingLyricsService : Service() {

    enum class IslandState {
        MINI, COMPACT, EXPANDED
    }

    companion object {
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

    private var currentState: IslandState = IslandState.COMPACT
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

                    updatePlaybackTime(pos, dur, updated, isPlaying)
                    updateTrack(title, artist, rawTitle, artistFromTitle)
                }
                MediaNotificationListener.ACTION_PLAYBACK_STATE -> {
                    val isPlaying = intent.getBooleanExtra(MediaNotificationListener.EXTRA_IS_PLAYING, true)
                    val pos = intent.getLongExtra(MediaNotificationListener.EXTRA_POSITION_MS, 0L)
                    val dur = intent.getLongExtra(MediaNotificationListener.EXTRA_DURATION_MS, totalDurationMs)
                    val updated = intent.getLongExtra(MediaNotificationListener.EXTRA_UPDATED_AT, SystemClock.elapsedRealtime())
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

        // Register for track & playback updates
        val filter = IntentFilter().apply {
            addAction(MediaNotificationListener.ACTION_TRACK_CHANGED)
            addAction(MediaNotificationListener.ACTION_PLAYBACK_STATE)
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

        root.findViewById<TextView>(R.id.tvCompactLyric)?.isSelected = true
        root.findViewById<TextView>(R.id.tvOverlayNextLine)?.setOnClickListener {
            val nextIdx = activeIndex + 1
            if (nextIdx in currentLines.indices) {
                seekToOverlayLine(nextIdx)
            }
        }

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
            recalculateActiveLineNow()
            val secStr = String.format("%+.1fс", manualSyncOffsetMs / 1000.0)
            Toast.makeText(this, "Синхронизация: $secStr", Toast.LENGTH_SHORT).show()
        }

        btnSyncFwd?.setOnClickListener {
            manualSyncOffsetMs += 500L
            recalculateActiveLineNow()
            val secStr = String.format("%+.1fс", manualSyncOffsetMs / 1000.0)
            Toast.makeText(this, "Синхронизация: $secStr", Toast.LENGTH_SHORT).show()
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
            root.findViewById<TextView>(R.id.tvCompactLyric)?.isSelected = true
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
                duration = 240L
                interpolator = DecelerateInterpolator()
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

        val lineText = if (activeIndex in currentLines.indices) {
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
        lastUpdateTimeSystem = if (updatedAt > 0L) updatedAt else SystemClock.elapsedRealtime()
        isMediaPlaying = playing
    }

    private fun getCurrentTrackPosition(): Long {
        if (!isMediaPlaying || lastUpdateTimeSystem == 0L) {
            return lastKnownPositionMs.coerceAtLeast(0L)
        }
        val elapsed = (SystemClock.elapsedRealtime() - lastUpdateTimeSystem).coerceAtLeast(0L)
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
            pbCompact?.progress = progress
            pbOverlay?.progress = progress
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
        seekIgnoreUntilSystemMs = 0L
        overlayLineViews.clear()

        val root = overlayView
        val tvTitle = root?.findViewById<TextView>(R.id.tvOverlayTitle)
        val tvSub = root?.findViewById<TextView>(R.id.tvOverlaySubtitle)
        val tvMini = root?.findViewById<TextView>(R.id.tvMiniLabel)
        val tvCompact = root?.findViewById<TextView>(R.id.tvCompactLyric)
        val tvCurrent = root?.findViewById<TextView>(R.id.tvOverlayCurrentLine)
        val tvNext = root?.findViewById<TextView>(R.id.tvOverlayNextLine)
        val tvEmpty = root?.findViewById<TextView>(R.id.tvOverlayEmptyStatus)
        val container = root?.findViewById<LinearLayout>(R.id.containerOverlayLyrics)
        val scrollView = root?.findViewById<NestedScrollView>(R.id.scrollOverlayLyrics)

        tvTitle?.text = cleanTitle
        tvSub?.text = if (cleanArtist.isNotBlank()) cleanArtist else "SoundCloud • Spotify"
        tvMini?.text = "♪ $cleanTitle"
        tvCompact?.text = "Поиск: $cleanTitle…"
        tvCompact?.isSelected = true
        tvCurrent?.text = "Поиск текста..."
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
                artistFromTitle = artistFromTitle
            )
            if (!isActive) return@launch
            result.onSuccess { lyricsResult ->
                if (!isActive) return@onSuccess
                currentLines = lyricsResult.lines
                isSyncedSource = lyricsResult.isSynced
                currentSourceName = lyricsResult.sourceName
                syncedLines = if (!isSyncedSource && totalDurationMs > 10_000L) {
                    GeniusLyricsFetcher.estimateLineTimestamps(currentLines, totalDurationMs)
                } else {
                    currentLines
                }

                populateOverlayLyrics(currentLines)

                val dispArtist = lyricsResult.artist.ifBlank { cleanArtist }
                val dispTitle = lyricsResult.title.ifBlank { cleanTitle }
                tvTitle?.text = dispTitle
                tvSub?.text = if (dispArtist.isNotBlank()) "$dispArtist • ${lyricsResult.sourceName}" else lyricsResult.sourceName
                tvMini?.text = "♪ $dispTitle"

                applyHighlight(0, scroll = false, animateSwap = false)
                if (getCurrentTrackPosition() > 0L) {
                    recalculateActiveLineNow(advanceVirtual = false)
                }
                updateNotificationContent()
            }.onFailure {
                if (!isActive) return@onFailure
                overlayLineViews.clear()
                container?.removeAllViews()
                tvCompact?.text = "$cleanTitle — нет на Genius"
                tvCompact?.isSelected = true
                tvCurrent?.text = "Текст не найден"
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
                    val bars: List<View?> = when (currentState) {
                        IslandState.MINI -> listOf(
                            root.findViewById(R.id.eqMini1),
                            root.findViewById(R.id.eqMini2),
                            root.findViewById(R.id.eqMini3)
                        )
                        IslandState.COMPACT -> listOf(
                            root.findViewById(R.id.eqCompact1),
                            root.findViewById(R.id.eqCompact2),
                            root.findViewById(R.id.eqCompact3)
                        )
                        IslandState.EXPANDED -> listOf(
                            root.findViewById(R.id.eqExp1),
                            root.findViewById(R.id.eqExp2),
                            root.findViewById(R.id.eqExp3)
                        )
                    }
                    for (bar in bars) {
                        if (bar == null) continue
                        val h = bar.height
                        if (h > 0) bar.pivotY = h.toFloat()
                        val targetScale = if (isMediaPlaying) {
                            Random.nextFloat() * 0.72f + 0.28f
                        } else {
                            0.25f
                        }
                        bar.animate()
                            .scaleY(targetScale)
                            .setDuration(130L)
                            .start()
                    }
                }
                delay(140)
            }
        }
    }

    private fun startSyncLoop() {
        scrollJob?.cancel()
        scrollJob = scope.launch {
            while (isActive && isAutoScrolling) {
                updateProgressViews()
                if (currentLines.isNotEmpty() && isMediaPlaying) {
                    recalculateActiveLineNow(advanceVirtual = true)
                }
                delay(150)
            }
        }
    }

    private fun recalculateActiveLineNow(advanceVirtual: Boolean = false) {
        if (currentLines.isEmpty()) return

        if (!isSyncedSource && totalDurationMs > 10_000L && syncedLines.firstOrNull()?.timeMs == -1L) {
            syncedLines = GeniusLyricsFetcher.estimateLineTimestamps(currentLines, totalDurationMs)
        }

        val timeline = if ((syncedLines.firstOrNull()?.timeMs ?: -1L) >= 0L) {
            syncedLines
        } else {
            val virtualDur = (currentLines.size * 2800L).coerceAtLeast(30_000L)
            GeniusLyricsFetcher.estimateLineTimestamps(currentLines, virtualDur).also {
                syncedLines = it
            }
        }

        val effectiveMs = if (totalDurationMs > 10_000L) {
            (getCurrentTrackPosition() + manualSyncOffsetMs).coerceAtLeast(0L)
        } else {
            if (advanceVirtual) {
                virtualPositionMs += 150L
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
            applyHighlight(bestIdx, scroll = true, animateSwap = true)
        }
    }

    private fun animateLineText(tv: TextView?, newText: String, animateSwap: Boolean) {
        if (tv == null) return
        tv.animate().cancel()
        tv.text = newText
        tv.isSelected = true
        if (animateSwap) {
            tv.alpha = 0f
            tv.translationY = dp(8).toFloat()
            tv.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(210L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            tv.alpha = 1f
            tv.translationY = 0f
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
        if (!isSyncedSource && effectiveDur > 10_000L && syncedLines.firstOrNull()?.timeMs == -1L) {
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
                applyHighlight(idx, scroll = true, animateSwap = true)
                updateNotificationContent()
                return
            } else {
                virtualPositionMs = tappedTime
            }
        }
        applyHighlight(idx, scroll = true, animateSwap = true)
        Toast.makeText(this, "Не удалось перемотать трек", Toast.LENGTH_SHORT).show()
    }

    private fun calibrateOverlaySyncToLine(idx: Int) {
        if (idx !in currentLines.indices) return
        val tappedTime = resolveOverlayTimestampMs(idx)
        if (tappedTime >= 0L && totalDurationMs > 0L) {
            manualSyncOffsetMs = tappedTime - getCurrentTrackPosition()
            overlayView?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            applyHighlight(idx, scroll = true, animateSwap = true)
            Toast.makeText(this, "Синхронизация привязана к этой строке", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyHighlight(idx: Int, scroll: Boolean, animateSwap: Boolean) {
        if (idx < 0 || idx >= currentLines.size) return
        activeIndex = idx

        val root = overlayView ?: return
        val tvCompact = root.findViewById<TextView>(R.id.tvCompactLyric)
        val tvCurrent = root.findViewById<TextView>(R.id.tvOverlayCurrentLine)
        val tvNext = root.findViewById<TextView>(R.id.tvOverlayNextLine)
        val scrollView = root.findViewById<NestedScrollView>(R.id.scrollOverlayLyrics)

        val currentLineText = currentLines[idx].text
        val nextText = currentLines.getOrNull(idx + 1)?.text ?: ""

        animateLineText(tvCompact, currentLineText, animateSwap)
        animateLineText(tvCurrent, currentLineText, animateSwap)
        tvNext?.text = nextText

        for (i in overlayLineViews.indices) {
            val tv = overlayLineViews[i]
            val trimmed = currentLines.getOrNull(i)?.text?.trim() ?: ""
            val isHeader = trimmed.startsWith("[") && trimmed.endsWith("]")
            when {
                isHeader -> {
                    tv.setTextColor(Color.parseColor("#1DB954"))
                    tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
                }
                i == idx -> {
                    tv.setTextColor(Color.WHITE)
                    tv.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD))
                }
                i < idx -> {
                    tv.setTextColor(Color.parseColor("#5E6368"))
                    tv.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL))
                }
                else -> {
                    tv.setTextColor(Color.parseColor("#B3B8BE"))
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
