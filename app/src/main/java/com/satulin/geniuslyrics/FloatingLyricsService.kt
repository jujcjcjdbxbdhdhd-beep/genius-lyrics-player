package com.satulin.geniuslyrics

import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class FloatingLyricsService : Service() {

    companion object {
        @Volatile
        var isRunning: Boolean = false
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var isAutoScrolling = true
    private var scrollJob: Job? = null
    private var loadJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var currentTitle: String = ""
    private var currentArtist: String = ""
    private var currentLines: List<LyricLine> = emptyList()
    private var syncedLines: List<LyricLine> = emptyList()
    private var isSyncedSource: Boolean = false
    private var activeIndex: Int = -1
    private var lineCharStarts: IntArray = IntArray(0)

    private var isMediaPlaying: Boolean = false
    private var lastKnownPositionMs: Long = 0L
    private var totalDurationMs: Long = 0L
    private var lastUpdateTimeSystem: Long = 0L
    private var virtualPositionMs: Long = 0L

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

                    updatePlaybackTime(pos, dur, updated, isPlaying)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        isRunning = true
        if (overlayView != null) return

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val layoutParamsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutParamsType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 120
        }

        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.overlay_lyrics, null)

        val header = overlayView?.findViewById<View>(R.id.overlayHeader)
        val btnClose = overlayView?.findViewById<ImageButton>(R.id.btnOverlayClose)
        val btnScroll = overlayView?.findViewById<ImageButton>(R.id.btnOverlayScroll)

        btnClose?.setOnClickListener {
            stopSelf()
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

        // Draggable window implementation
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        header?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager?.updateViewLayout(overlayView, params)
                    true
                }
                else -> false
            }
        }

        windowManager?.addView(overlayView, params)

        // Register for both track changes and playback state updates
        val filter = IntentFilter().apply {
            addAction(MediaNotificationListener.ACTION_TRACK_CHANGED)
            addAction(MediaNotificationListener.ACTION_PLAYBACK_STATE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(trackReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(trackReceiver, filter)
        }

        // Load current song if already playing
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
        startSyncLoop()
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

    private fun updateTrack(title: String, artist: String, rawTitle: String, artistFromTitle: Boolean) {
        val cleanTitle = MediaNotificationListener.sanitizeText(title)
        val cleanArtist = MediaNotificationListener.sanitizeText(artist)
        if (cleanTitle.isBlank()) return
        if (cleanTitle == currentTitle && cleanArtist == currentArtist && currentLines.isNotEmpty()) {
            return
        }

        currentTitle = cleanTitle
        currentArtist = cleanArtist
        currentLines = emptyList()
        syncedLines = emptyList()
        activeIndex = -1
        virtualPositionMs = 0L

        val tvTitle = overlayView?.findViewById<TextView>(R.id.tvOverlayTitle)
        val tvCurrent = overlayView?.findViewById<TextView>(R.id.tvOverlayCurrentLine)
        val tvNext = overlayView?.findViewById<TextView>(R.id.tvOverlayNextLine)
        val tvLyrics = overlayView?.findViewById<TextView>(R.id.tvOverlayLyrics)
        val scrollView = overlayView?.findViewById<NestedScrollView>(R.id.scrollOverlayLyrics)

        tvTitle?.text = if (cleanArtist.isNotBlank()) "$cleanArtist — $cleanTitle" else cleanTitle
        tvCurrent?.text = "Поиск текста..."
        tvNext?.text = ""
        tvLyrics?.text = "Загрузка текста..."
        scrollView?.scrollTo(0, 0)

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
                syncedLines = if (!isSyncedSource && totalDurationMs > 10_000L) {
                    GeniusLyricsFetcher.estimateLineTimestamps(currentLines, totalDurationMs)
                } else {
                    currentLines
                }

                // Build index of character start offsets for highlighted spanning
                val starts = IntArray(currentLines.size)
                var pos = 0
                for (i in currentLines.indices) {
                    starts[i] = pos
                    pos += currentLines[i].text.length + 1
                }
                lineCharStarts = starts

                val dispArtist = lyricsResult.artist.ifBlank { cleanArtist }
                val dispTitle = lyricsResult.title.ifBlank { cleanTitle }
                tvTitle?.text = if (dispArtist.isNotBlank()) "$dispArtist — $dispTitle" else dispTitle

                applyHighlight(0, scroll = false)
            }.onFailure {
                if (!isActive) return@onFailure
                tvCurrent?.text = "Текст не найден"
                tvNext?.text = "Откройте приложение для поиска на Genius"
                tvLyrics?.text = "Текст не найден."
            }
        }
    }

    private fun startSyncLoop() {
        scrollJob?.cancel()
        scrollJob = scope.launch {
            while (isActive && isAutoScrolling) {
                if (currentLines.isNotEmpty() && isMediaPlaying) {
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
                        getCurrentTrackPosition()
                    } else {
                        virtualPositionMs += 150L
                        val lastTs = timeline.lastOrNull()?.timeMs ?: 30_000L
                        if (virtualPositionMs > lastTs + 3000L) virtualPositionMs = 0L
                        virtualPositionMs
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
                        applyHighlight(bestIdx, scroll = true)
                    }
                }
                delay(150)
            }
        }
    }

    private fun applyHighlight(idx: Int, scroll: Boolean) {
        if (idx < 0 || idx >= currentLines.size) return
        activeIndex = idx

        val tvCurrent = overlayView?.findViewById<TextView>(R.id.tvOverlayCurrentLine)
        val tvNext = overlayView?.findViewById<TextView>(R.id.tvOverlayNextLine)
        val tvLyrics = overlayView?.findViewById<TextView>(R.id.tvOverlayLyrics)
        val scrollView = overlayView?.findViewById<NestedScrollView>(R.id.scrollOverlayLyrics)

        tvCurrent?.text = currentLines[idx].text
        val nextText = currentLines.getOrNull(idx + 1)?.text ?: ""
        tvNext?.text = nextText

        val sb = SpannableStringBuilder()
        for (i in currentLines.indices) {
            val start = sb.length
            val lineText = currentLines[i].text
            sb.append(lineText)
            val end = sb.length
            val color = when {
                lineText.trim().startsWith("[") && lineText.trim().endsWith("]") -> Color.parseColor("#1DB954")
                i == idx -> Color.WHITE
                i < idx -> Color.parseColor("#5E6368")
                else -> Color.parseColor("#B3B8BE")
            }
            sb.setSpan(ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (i == idx) {
                sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (i < currentLines.lastIndex) sb.append('\n')
        }
        tvLyrics?.text = sb

        if (scroll && idx < lineCharStarts.size && tvLyrics != null && scrollView != null) {
            tvLyrics.post {
                val layout = tvLyrics.layout ?: return@post
                val charOffset = lineCharStarts[idx].coerceIn(0, sb.length)
                val lineNum = layout.getLineForOffset(charOffset)
                val topY = layout.getLineTop(lineNum)
                val targetY = (topY - scrollView.height / 3).coerceAtLeast(0)
                scrollView.smoothScrollTo(0, targetY)
            }
        }
    }

    private fun stopSyncLoop() {
        scrollJob?.cancel()
        scrollJob = null
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        stopSyncLoop()
        loadJob?.cancel()
        scope.cancel()
        try {
            unregisterReceiver(trackReceiver)
        } catch (_: Exception) {}
        overlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {}
        }
        overlayView = null
    }
}
