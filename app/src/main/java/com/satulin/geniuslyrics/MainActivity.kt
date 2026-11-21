package com.satulin.geniuslyrics

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.satulin.geniuslyrics.databinding.ActivityMainBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONTokener
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.sin

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_OPEN_GENIUS_WEB = "extra_open_genius_web"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var lyricsAdapter: LyricsAdapter
    private lateinit var layoutManager: LinearLayoutManager

    private var pendingStartIsland = false

    private var isAutoScrolling = true
    private var isUserManuallyScrolling = false
    private var scrollSpeedFactor = 1.0f
    private var scrollJob: Job? = null
    private var reSyncJob: Job? = null
    private var lyricsLoadJob: Job? = null
    private var currentFontSizeSp = 20f

    private var activeTitle: String = ""
    private var activeArtist: String = ""
    private var activeRawTitle: String = ""
    private var activeArtistFromTitle: Boolean = false
    private var currentLyricsResult: LyricsResult? = null
    private var lyricsLines: List<LyricLine> = emptyList()
    private var displayedLines: List<LyricLine> = emptyList()

    private var isMediaPlaying = false
    private var lastKnownPositionMs = 0L
    private var totalDurationMs = 0L
    private var lastUpdateTimeSystem = 0L
    private var manualSyncOffsetMs = 0L
    private var virtualPositionMs = 0L
    private var isManualSearchMode = false
    private var seekIgnoreUntilSystemMs = 0L
    private var displayMode = LyricsDisplayMode.KARAOKE
    private var currentStyle = IslandAnimStyle.FLY_IN
    private var eqJob: Job? = null
    private var eqPhase: Double = 0.0
    private var lastBeatWordKey: Long = -1L
    private var cardStrokeAnimator: ValueAnimator? = null

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
                    binding.ambientAura.setPlaying(isPlaying)
                    onTrackUpdated(title, artist, rawTitle, artistFromTitle, isPlaying)
                }
                MediaNotificationListener.ACTION_PLAYBACK_STATE -> {
                    val isPlaying = intent.getBooleanExtra(MediaNotificationListener.EXTRA_IS_PLAYING, true)
                    val pos = intent.getLongExtra(MediaNotificationListener.EXTRA_POSITION_MS, 0L)
                    val dur = intent.getLongExtra(MediaNotificationListener.EXTRA_DURATION_MS, totalDurationMs)
                    val updated = intent.getLongExtra(MediaNotificationListener.EXTRA_UPDATED_AT, SystemClock.elapsedRealtime())
                    val fromSeek = intent.getBooleanExtra(MediaNotificationListener.EXTRA_FROM_SEEK, false)

                    binding.ambientAura.setPlaying(isPlaying)
                    val now = SystemClock.elapsedRealtime()
                    if (fromSeek) {
                        seekIgnoreUntilSystemMs = now + 650L
                        updatePlaybackTime(pos, dur, updated, isPlaying)
                        updatePlayStatusBadge(isPlaying)
                        updateProgressUI()
                        syncActiveLineToPosition(smoothScroll = true)
                    } else if (now < seekIgnoreUntilSystemMs && abs(pos - lastKnownPositionMs) > 2500L) {
                        isMediaPlaying = isPlaying
                        if (dur > 0L) totalDurationMs = dur
                        updatePlayStatusBadge(isPlaying)
                    } else {
                        updatePlaybackTime(pos, dur, updated, isPlaying)
                        updatePlayStatusBadge(isPlaying)
                        updateProgressUI()
                    }
                }
                FloatingLyricsService.ACTION_ISLAND_STATE_CHANGED -> {
                    updateIslandButtonState()
                }
                AppMotionPrefs.ACTION_PREFS_CHANGED -> {
                    displayMode = AppMotionPrefs.getDisplayMode(this@MainActivity)
                    currentStyle = AppMotionPrefs.getIslandStyle(this@MainActivity)
                    lyricsAdapter.displayMode = displayMode
                    lyricsAdapter.motionStyle = currentStyle
                    applyDisplayModeUI(animate = true)
                    updateMainStyleChipsUI()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        displayMode = AppMotionPrefs.getDisplayMode(this)
        currentStyle = AppMotionPrefs.getIslandStyle(this)
        setupRecyclerView()
        setupUI()
        applyDisplayModeUI(animate = false)
        updateMainStyleChipsUI()
        checkPermissions()
        updateIslandButtonState()

        if (intent?.getBooleanExtra(EXTRA_OPEN_GENIUS_WEB, false) == true) {
            intent.removeExtra(EXTRA_OPEN_GENIUS_WEB)
            openGeniusWeb()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.getBooleanExtra(EXTRA_OPEN_GENIUS_WEB, false) == true) {
            intent.removeExtra(EXTRA_OPEN_GENIUS_WEB)
            openGeniusWeb()
        }
    }

    private fun setupRecyclerView() {
        layoutManager = LinearLayoutManager(this)
        binding.rvLyrics.layoutManager = layoutManager
        (binding.rvLyrics.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

        lyricsAdapter = LyricsAdapter(
            onLineClicked = { clickedPosition ->
                isUserManuallyScrolling = false
                showSyncPill(false)
                reSyncJob?.cancel()

                val isKaraokeActive = (displayMode != LyricsDisplayMode.PLAIN_TEXT)
                val effectiveDur = if (totalDurationMs > 0L) totalDurationMs else MediaNotificationListener.currentDurationMs
                if (totalDurationMs <= 0L && effectiveDur > 0L) {
                    totalDurationMs = effectiveDur
                }
                if (!isManualSearchMode && effectiveDur > 10_000L && currentLyricsResult?.isSynced != true && displayedLines.firstOrNull()?.timeMs == -1L) {
                    displayedLines = GeniusLyricsFetcher.estimateLineTimestamps(lyricsLines, effectiveDur)
                }

                val tappedTime = GeniusLyricsFetcher.resolveLineTimestampMs(displayedLines, clickedPosition, effectiveDur)
                if (!isManualSearchMode && tappedTime >= 0L) {
                    val seekPos = GeniusLyricsFetcher.computeSeekTargetMs(tappedTime, manualSyncOffsetMs, effectiveDur)
                    val sought = MediaNotificationListener.seekTo(seekPos)
                    if (sought) {
                        val now = SystemClock.elapsedRealtime()
                        seekIgnoreUntilSystemMs = now + 650L
                        lastKnownPositionMs = seekPos
                        lastUpdateTimeSystem = now
                        updateProgressUI()
                        binding.rvLyrics.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        binding.ambientAura.triggerPulse(1.0f)
                        if (isKaraokeActive) {
                            if (displayMode == LyricsDisplayMode.WORD_BY_WORD) {
                                val beat = WordTimingEngine.computeBeatState(displayedLines, clickedPosition, tappedTime, effectiveDur)
                                lyricsAdapter.updateWordBeat(clickedPosition, beat)
                            }
                            selectLine(clickedPosition, smoothScroll = true, force = true)
                        }
                    } else {
                        virtualPositionMs = tappedTime
                        if (isKaraokeActive) {
                            selectLine(clickedPosition, smoothScroll = true, force = true)
                        }
                        Toast.makeText(this, "Не удалось перемотать — проверьте доступ к уведомлениям", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    if (tappedTime >= 0L) {
                        virtualPositionMs = tappedTime
                    }
                    if (isKaraokeActive) {
                        selectLine(clickedPosition, smoothScroll = true, force = true)
                    }
                }
            },
            onLineLongClicked = { clickedPosition ->
                val effectiveDur = if (totalDurationMs > 0L) totalDurationMs else MediaNotificationListener.currentDurationMs
                val tappedTime = GeniusLyricsFetcher.resolveLineTimestampMs(displayedLines, clickedPosition, effectiveDur)
                if (!isManualSearchMode && tappedTime >= 0L) {
                    manualSyncOffsetMs = tappedTime - getCurrentTrackPosition()
                    binding.rvLyrics.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    if (displayMode != LyricsDisplayMode.PLAIN_TEXT) {
                        selectLine(clickedPosition, smoothScroll = true, force = true)
                    }
                    Toast.makeText(this, "Синхронизация привязана к этой строке", Toast.LENGTH_SHORT).show()
                }
            }
        )
        lyricsAdapter.fontSizeSp = currentFontSizeSp
        lyricsAdapter.displayMode = displayMode
        lyricsAdapter.motionStyle = currentStyle
        binding.rvLyrics.adapter = lyricsAdapter

        // Detect user manual dragging: show "Return to Sync" pill
        binding.rvLyrics.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING && displayMode != LyricsDisplayMode.PLAIN_TEXT) {
                    isUserManuallyScrolling = true
                    if (lyricsLines.isNotEmpty()) {
                        showSyncPill(true)
                    }
                    scheduleAutoReSync()
                }
            }
        })

        binding.btnSyncCurrentLine.setOnClickListener {
            isUserManuallyScrolling = false
            showSyncPill(false)
            reSyncJob?.cancel()
            val target = lyricsAdapter.activeLineIndex.coerceAtLeast(0)
            selectLine(target, smoothScroll = true, force = true)
        }
    }

    private fun scheduleAutoReSync() {
        reSyncJob?.cancel()
        reSyncJob = lifecycleScope.launch {
            delay(4000)
            if (isActive && displayMode != LyricsDisplayMode.PLAIN_TEXT) {
                isUserManuallyScrolling = false
                showSyncPill(false)
                val target = lyricsAdapter.activeLineIndex.coerceAtLeast(0)
                selectLine(target, smoothScroll = true, force = true)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkPermissions()
        updateIslandButtonState()
        displayMode = AppMotionPrefs.getDisplayMode(this)
        currentStyle = AppMotionPrefs.getIslandStyle(this)
        lyricsAdapter.displayMode = displayMode
        lyricsAdapter.motionStyle = currentStyle
        applyDisplayModeUI(animate = false)
        updateMainStyleChipsUI()

        if (pendingStartIsland && (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this))) {
            pendingStartIsland = false
            startLiveIslandService()
        }

        val filter = IntentFilter().apply {
            addAction(MediaNotificationListener.ACTION_TRACK_CHANGED)
            addAction(MediaNotificationListener.ACTION_PLAYBACK_STATE)
            addAction(FloatingLyricsService.ACTION_ISLAND_STATE_CHANGED)
            addAction(AppMotionPrefs.ACTION_PREFS_CHANGED)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(trackReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(trackReceiver, filter)
        }

        MediaNotificationListener.refreshActiveTrack()

        val currentTitle = MediaNotificationListener.sanitizeText(MediaNotificationListener.currentTitle)
        val currentArtist = MediaNotificationListener.sanitizeText(MediaNotificationListener.currentArtist)
        val currentRawTitle = MediaNotificationListener.sanitizeText(MediaNotificationListener.currentRawTitle)
        val currentArtistFromTitle = MediaNotificationListener.currentArtistFromTitle

        if (currentTitle.isNotBlank()) {
            updatePlaybackTime(
                MediaNotificationListener.currentPositionMs,
                MediaNotificationListener.currentDurationMs,
                MediaNotificationListener.lastPositionUpdateTime,
                MediaNotificationListener.isPlaying
            )
            onTrackUpdated(
                currentTitle,
                currentArtist,
                if (currentRawTitle.isNotBlank()) currentRawTitle else currentTitle,
                currentArtistFromTitle,
                MediaNotificationListener.isPlaying
            )
        }
        binding.ambientAura.setPlaying(MediaNotificationListener.isPlaying)
        startMainEqualizerLoop()
    }

    override fun onPause() {
        super.onPause()
        eqJob?.cancel()
        try {
            unregisterReceiver(trackReceiver)
        } catch (_: Exception) {}
    }

    private fun checkPermissions() {
        val hasNotificationAccess = isNotificationServiceEnabled()
        binding.cardPermission.visibility = if (hasNotificationAccess) View.GONE else View.VISIBLE
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(this)
        return enabledPackages.contains(packageName)
    }

    private fun setupUI() {
        binding.btnGrantPermission.setOnClickListener {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
        }

        binding.btnToggleScroll.setOnClickListener {
            toggleAutoScroll()
        }

        binding.btnSpeedDown.setOnClickListener {
            if (scrollSpeedFactor > 0.5f) {
                scrollSpeedFactor = (scrollSpeedFactor - 0.25f).coerceAtLeast(0.5f)
                updateSpeedLabel()
            }
        }
        binding.btnSpeedUp.setOnClickListener {
            if (scrollSpeedFactor < 3.0f) {
                scrollSpeedFactor = (scrollSpeedFactor + 0.25f).coerceAtMost(3.0f)
                updateSpeedLabel()
            }
        }

        binding.btnFontDown.setOnClickListener {
            if (currentFontSizeSp > 14f) {
                currentFontSizeSp -= 2f
                lyricsAdapter.fontSizeSp = currentFontSizeSp
            }
        }
        binding.btnFontUp.setOnClickListener {
            if (currentFontSizeSp < 34f) {
                currentFontSizeSp += 2f
                lyricsAdapter.fontSizeSp = currentFontSizeSp
            }
        }

        binding.btnScrollToTop.setOnClickListener {
            isUserManuallyScrolling = false
            showSyncPill(false)
            reSyncJob?.cancel()
            manualSyncOffsetMs = 0L
            virtualPositionMs = 0L
            selectLine(0, smoothScroll = true, force = true)
        }

        binding.btnManualSearch.setOnClickListener {
            performManualSearch()
        }
        binding.etSearchQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performManualSearch()
                true
            } else false
        }

        binding.btnRefreshLyrics.setOnClickListener {
            if (activeTitle.isNotBlank()) {
                Toast.makeText(this, "Обновляем текст...", Toast.LENGTH_SHORT).show()
                manualSyncOffsetMs = 0L
                virtualPositionMs = 0L
                loadLyrics(
                    artist = activeArtist,
                    title = activeTitle,
                    rawTitle = activeRawTitle,
                    artistFromTitle = activeArtistFromTitle,
                    forceRefresh = true
                )
            } else {
                MediaNotificationListener.refreshActiveTrack()
                Toast.makeText(this, "Проверяем активный плеер...", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnToggleFloating.setOnClickListener {
            toggleFloatingLyrics()
        }

        binding.btnToggleSearch.setOnClickListener {
            toggleSearchBar()
        }

        binding.tabKaraoke.setOnClickListener {
            selectDisplayMode(LyricsDisplayMode.KARAOKE)
        }
        binding.tabWordByWord.setOnClickListener {
            selectDisplayMode(LyricsDisplayMode.WORD_BY_WORD)
        }
        binding.tabPlainText.setOnClickListener {
            selectDisplayMode(LyricsDisplayMode.PLAIN_TEXT)
        }

        binding.mainStyleFly.setOnClickListener { selectMotionStyle(IslandAnimStyle.FLY_IN) }
        binding.mainStyleFade.setOnClickListener { selectMotionStyle(IslandAnimStyle.FADE) }
        binding.mainStyleSlide.setOnClickListener { selectMotionStyle(IslandAnimStyle.SLIDE_KINETIC) }
        binding.mainStylePop.setOnClickListener { selectMotionStyle(IslandAnimStyle.ELASTIC_POP) }
        binding.mainStyleTypewriter.setOnClickListener { selectMotionStyle(IslandAnimStyle.TYPEWRITER) }

        binding.btnOpenGeniusWeb.setOnClickListener {
            openGeniusWeb()
        }

        binding.btnPlaceholderGeniusWeb.setOnClickListener {
            openGeniusWeb()
        }

        MotionUtils.attachTactilePress(
            binding.tabKaraoke,
            binding.tabWordByWord,
            binding.tabPlainText,
            binding.btnOpenGeniusWeb,
            binding.btnToggleFloating,
            binding.btnToggleSearch,
            binding.btnManualSearch,
            binding.mainStyleFly,
            binding.mainStyleFade,
            binding.mainStyleSlide,
            binding.mainStylePop,
            binding.mainStyleTypewriter,
            binding.btnToggleScroll,
            binding.btnSpeedDown,
            binding.btnSpeedUp,
            binding.btnFontDown,
            binding.btnFontUp,
            binding.btnScrollToTop,
            binding.btnSyncCurrentLine,
            binding.btnRefreshLyrics,
            binding.btnImportFromGeniusWeb,
            binding.btnPlaceholderGeniusWeb
        )

        setupGeniusWebView()
    }

    private fun selectDisplayMode(mode: LyricsDisplayMode) {
        if (binding.layoutGeniusWebView.visibility == View.VISIBLE) {
            closeGeniusWeb()
        }
        displayMode = mode
        AppMotionPrefs.setDisplayMode(this, mode)
        binding.layoutModeTabs.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        binding.ambientAura.triggerPulse(0.8f)
        applyDisplayModeUI(animate = true)
    }

    private fun selectMotionStyle(style: IslandAnimStyle) {
        currentStyle = style
        AppMotionPrefs.setIslandStyle(this, style)
        lyricsAdapter.motionStyle = style
        updateMainStyleChipsUI()
        binding.scrollMainStyles.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        binding.ambientAura.triggerPulse(1.0f)
        lyricsAdapter.replayActiveAnimation()
    }

    private fun toggleSearchBar() {
        val bar = binding.layoutSearch
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        val density = resources.displayMetrics.density
        bar.animate().cancel()
        if (bar.visibility != View.VISIBLE) {
            bar.visibility = View.VISIBLE
            bar.alpha = 0f
            bar.translationY = -12f * density
            bar.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(220L)
                .setInterpolator(MotionUtils.EASE_OUT_EXPO)
                .start()
            binding.etSearchQuery.requestFocus()
            imm?.showSoftInput(binding.etSearchQuery, InputMethodManager.SHOW_IMPLICIT)
        } else {
            imm?.hideSoftInputFromWindow(binding.etSearchQuery.windowToken, 0)
            bar.animate()
                .alpha(0f)
                .translationY(-10f * density)
                .setDuration(150L)
                .setInterpolator(MotionUtils.EASE_OUT_CUBIC)
                .withEndAction {
                    bar.visibility = View.GONE
                    bar.alpha = 1f
                    bar.translationY = 0f
                }
                .start()
        }
    }

    private fun makeGlassChipDrawable(fillColor: Int, strokeColor: Int, radiusDp: Int): GradientDrawable {
        val density = resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * density
            setColor(fillColor)
            setStroke((1 * density).toInt().coerceAtLeast(1), strokeColor)
        }
    }

    private fun updateMainStyleChipsUI() {
        val chipMap = listOf(
            IslandAnimStyle.FLY_IN to binding.mainStyleFly,
            IslandAnimStyle.FADE to binding.mainStyleFade,
            IslandAnimStyle.SLIDE_KINETIC to binding.mainStyleSlide,
            IslandAnimStyle.ELASTIC_POP to binding.mainStylePop,
            IslandAnimStyle.TYPEWRITER to binding.mainStyleTypewriter
        )
        val activeFill = Color.parseColor("#183E28")
        val activeStroke = Color.parseColor("#39FF88")
        val activeText = Color.parseColor("#39FF88")
        val idleFill = Color.parseColor("#B8151B24")
        val idleStroke = Color.parseColor("#22FFFFFF")
        val idleText = Color.parseColor("#9DA7B5")

        for ((style, tv) in chipMap) {
            val selected = (style == currentStyle)
            tv.background = makeGlassChipDrawable(
                if (selected) activeFill else idleFill,
                if (selected) activeStroke else idleStroke,
                radiusDp = 18
            )
            tv.setTextColor(if (selected) activeText else idleText)
        }
    }

    private fun applyDisplayModeUI(animate: Boolean) {
        lyricsAdapter.displayMode = displayMode
        lyricsAdapter.motionStyle = currentStyle

        val tabs = listOf(
            LyricsDisplayMode.KARAOKE to binding.tabKaraoke,
            LyricsDisplayMode.WORD_BY_WORD to binding.tabWordByWord,
            LyricsDisplayMode.PLAIN_TEXT to binding.tabPlainText
        )
        for ((mode, tabView) in tabs) {
            val selected = (mode == displayMode)
            if (selected) {
                val (fill, stroke, txtColor) = when (mode) {
                    LyricsDisplayMode.KARAOKE -> Triple(Color.parseColor("#1ED760"), Color.parseColor("#39FF88"), Color.BLACK)
                    LyricsDisplayMode.WORD_BY_WORD -> Triple(Color.parseColor("#00D4FF"), Color.parseColor("#6EE7FF"), Color.BLACK)
                    LyricsDisplayMode.PLAIN_TEXT -> Triple(Color.parseColor("#2A3342"), Color.parseColor("#4C5B73"), Color.WHITE)
                }
                tabView.background = makeGlassChipDrawable(fill, stroke, radiusDp = 18)
                tabView.setTextColor(txtColor)
            } else {
                tabView.background = null
                tabView.setTextColor(Color.parseColor("#9DA7B5"))
            }
        }

        val isKaraokeActive = (displayMode != LyricsDisplayMode.PLAIN_TEXT)
        binding.scrollMainStyles.visibility = if (isKaraokeActive) View.VISIBLE else View.GONE
        binding.tvPlainTextModeLabel.visibility = if (isKaraokeActive) View.GONE else View.VISIBLE

        if (!isKaraokeActive) {
            showSyncPill(false)
            stopAutoScroll()
        } else {
            if (isAutoScrolling) {
                startAutoScrollLoop()
            }
            syncActiveLineToPosition(smoothScroll = animate)
        }
    }

    private fun startMainEqualizerLoop() {
        eqJob?.cancel()
        val bars = listOf(binding.eqMain1, binding.eqMain2, binding.eqMain3, binding.eqMain4)
        val offsets = doubleArrayOf(0.0, 1.35, 2.7, 4.05)
        eqJob = lifecycleScope.launch {
            while (isActive) {
                eqPhase += 0.34
                for (i in bars.indices) {
                    val bar = bars[i]
                    val h = bar.height
                    if (h > 0) bar.pivotY = h.toFloat()
                    val targetScale = if (isMediaPlaying) {
                        val p = (sin(eqPhase + offsets[i]) + 1.0) * 0.5
                        val harmonic = (sin(eqPhase * 1.9 + offsets[i] * 0.7) + 1.0) * 0.5
                        (0.24 + 0.74 * (0.65 * p + 0.35 * harmonic)).toFloat().coerceIn(0.22f, 1.0f)
                    } else {
                        0.22f
                    }
                    bar.animate()
                        .scaleY(targetScale)
                        .setDuration(75L)
                        .setInterpolator(MotionUtils.EASE_OUT_CUBIC)
                        .start()
                }
                delay(70)
            }
        }
    }

    private fun showSyncPill(visible: Boolean) {
        val pill = binding.btnSyncCurrentLine
        pill.animate().cancel()
        if (visible) {
            if (pill.visibility == View.VISIBLE && pill.alpha > 0.9f) return
            val offsetPx = 22f * resources.displayMetrics.density
            pill.visibility = View.VISIBLE
            pill.alpha = 0f
            pill.translationY = offsetPx
            pill.scaleX = 0.88f
            pill.scaleY = 0.88f
            pill.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(240L)
                .setInterpolator(MotionUtils.SPRING_OVERSHOOT)
                .start()
        } else {
            if (pill.visibility == View.GONE) return
            val offsetPx = 16f * resources.displayMetrics.density
            pill.animate()
                .alpha(0f)
                .translationY(offsetPx)
                .scaleX(0.92f)
                .scaleY(0.92f)
                .setDuration(160L)
                .setInterpolator(MotionUtils.EASE_OUT_CUBIC)
                .withEndAction {
                    pill.visibility = View.GONE
                    pill.alpha = 1f
                    pill.translationY = 0f
                    pill.scaleX = 1f
                    pill.scaleY = 1f
                }
                .start()
        }
    }

    private fun animateTrackHeaderSwap() {
        val dy = 10f * resources.displayMetrics.density
        for (tv in listOf(binding.tvSongTitle, binding.tvSongArtist)) {
            tv.animate().cancel()
            tv.alpha = 0f
            tv.translationY = dy
            tv.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(260L)
                .setInterpolator(MotionUtils.EASE_OUT_EXPO)
                .start()
        }
        cardStrokeAnimator?.cancel()
        cardStrokeAnimator = ValueAnimator.ofArgb(
            Color.parseColor("#39FF88"),
            Color.parseColor("#283447")
        ).apply {
            duration = 520L
            interpolator = MotionUtils.EASE_OUT_CUBIC
            addUpdateListener { anim ->
                binding.cardNowPlaying.strokeColor = anim.animatedValue as Int
            }
            start()
        }
    }

    private fun animateLyricsListEntrance() {
        val density = resources.displayMetrics.density
        binding.rvLyrics.post {
            val count = binding.rvLyrics.childCount.coerceAtMost(14)
            for (i in 0 until count) {
                val child = binding.rvLyrics.getChildAt(i) ?: continue
                child.animate().cancel()
                child.alpha = 0f
                child.translationY = 18f * density
                child.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(i * 26L)
                    .setDuration(300L)
                    .setInterpolator(MotionUtils.EASE_OUT_EXPO)
                    .start()
            }
        }
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

    private fun updateProgressUI() {
        if (isManualSearchMode) return
        val currentMs = getCurrentTrackPosition()
        binding.tvCurrentTime.text = formatTime(currentMs)
        if (totalDurationMs > 0L) {
            binding.tvTotalTime.text = formatTime(totalDurationMs)
            val progress = ((currentMs.toDouble() / totalDurationMs) * 1000).toInt().coerceIn(0, 1000)
            MotionUtils.animateProgressSmooth(binding.pbTrackProgress, progress)
        } else {
            binding.tvTotalTime.text = "--:--"
            binding.pbTrackProgress.progress = 0
        }
    }

    private fun toggleAutoScroll() {
        isAutoScrolling = !isAutoScrolling
        if (isAutoScrolling) {
            binding.btnToggleScroll.text = "⏸ Пауза"
            binding.btnToggleScroll.setTextColor(Color.parseColor("#1DB954"))
            startAutoScrollLoop()
        } else {
            binding.btnToggleScroll.text = "▶ Автопрокрутка"
            binding.btnToggleScroll.setTextColor(Color.parseColor("#B3B3B3"))
            stopAutoScroll()
        }
    }

    private fun startAutoScrollLoop() {
        scrollJob?.cancel()
        if (displayMode == LyricsDisplayMode.PLAIN_TEXT) return
        scrollJob = lifecycleScope.launch {
            while (isActive && isAutoScrolling && displayMode != LyricsDisplayMode.PLAIN_TEXT) {
                val tickDelayMs = if (displayMode == LyricsDisplayMode.WORD_BY_WORD) 50L else 100L
                if (lyricsLines.isNotEmpty()) {
                    updateProgressUI()

                    // Upgrade to weighted timestamps smoothly once duration becomes known
                    val isSynced = currentLyricsResult?.isSynced == true
                    if (!isSynced && !isManualSearchMode && totalDurationMs > 10_000L && displayedLines.firstOrNull()?.timeMs == -1L) {
                        displayedLines = GeniusLyricsFetcher.estimateLineTimestamps(lyricsLines, totalDurationMs)
                    }

                    val canAdvance = isMediaPlaying || isManualSearchMode
                    if (canAdvance) {
                        val timelineLines: List<LyricLine>
                        val effectiveMs: Long
                        val effectiveDur: Long

                        if (!isManualSearchMode && totalDurationMs > 10_000L) {
                            timelineLines = if ((displayedLines.firstOrNull()?.timeMs ?: -1L) >= 0L) {
                                displayedLines
                            } else {
                                GeniusLyricsFetcher.estimateLineTimestamps(lyricsLines, totalDurationMs).also {
                                    displayedLines = it
                                }
                            }
                            effectiveMs = (getCurrentTrackPosition() + manualSyncOffsetMs).coerceAtLeast(0L)
                            effectiveDur = totalDurationMs
                        } else {
                            val virtualDur = (lyricsLines.size * 2800L).coerceAtLeast(30_000L)
                            timelineLines = if ((displayedLines.firstOrNull()?.timeMs ?: -1L) >= 0L) {
                                displayedLines
                            } else {
                                GeniusLyricsFetcher.estimateLineTimestamps(lyricsLines, virtualDur).also {
                                    displayedLines = it
                                }
                            }
                            virtualPositionMs += ((tickDelayMs.toFloat()) * scrollSpeedFactor).toLong().coerceAtLeast(10L)
                            val lastTs = timelineLines.lastOrNull()?.timeMs ?: virtualDur
                            if (virtualPositionMs > lastTs + 3000L) {
                                virtualPositionMs = 0L
                            }
                            effectiveMs = virtualPositionMs
                            effectiveDur = virtualDur
                        }

                        val bestIndex = findBestLineIndex(timelineLines, effectiveMs)
                        if (bestIndex != -1 && bestIndex != lyricsAdapter.activeLineIndex) {
                            selectLine(bestIndex, smoothScroll = true)
                            binding.ambientAura.triggerPulse(0.75f)
                        }

                        if (displayMode == LyricsDisplayMode.WORD_BY_WORD && bestIndex >= 0) {
                            val beat = WordTimingEngine.computeBeatState(timelineLines, bestIndex, effectiveMs, effectiveDur)
                            lyricsAdapter.updateWordBeat(bestIndex, beat)
                            if (beat != null && !beat.isCompleted) {
                                val wordKey = (bestIndex.toLong() shl 16) or beat.activeWordIndex.toLong()
                                if (wordKey != lastBeatWordKey) {
                                    lastBeatWordKey = wordKey
                                    binding.ambientAura.triggerPulse(0.45f)
                                }
                            }
                        }
                    }
                }
                delay(tickDelayMs)
            }
        }
    }

    private fun findBestLineIndex(timeline: List<LyricLine>, effectiveMs: Long): Int {
        var bestIndex = -1
        for (i in timeline.indices) {
            val lineTime = timeline[i].timeMs
            if (lineTime in 0..effectiveMs) {
                bestIndex = i
            } else if (lineTime > effectiveMs) {
                break
            }
        }
        return bestIndex
    }

    private fun syncActiveLineToPosition(smoothScroll: Boolean) {
        if (displayMode == LyricsDisplayMode.PLAIN_TEXT || isManualSearchMode || displayedLines.isEmpty()) return
        val effectiveMs = (getCurrentTrackPosition() + manualSyncOffsetMs).coerceAtLeast(0L)
        val bestIndex = findBestLineIndex(displayedLines, effectiveMs)
        if (bestIndex != -1) {
            if (displayMode == LyricsDisplayMode.WORD_BY_WORD) {
                val effectiveDur = if (totalDurationMs > 0L) totalDurationMs else (displayedLines.size * 2800L).coerceAtLeast(30_000L)
                val beat = WordTimingEngine.computeBeatState(displayedLines, bestIndex, effectiveMs, effectiveDur)
                lyricsAdapter.updateWordBeat(bestIndex, beat)
            }
            if (bestIndex != lyricsAdapter.activeLineIndex) {
                selectLine(bestIndex, smoothScroll = smoothScroll, force = false)
            }
        }
    }

    // Spotify smooth center gliding scroll
    private fun selectLine(position: Int, smoothScroll: Boolean, force: Boolean = false) {
        if (position < 0 || position >= displayedLines.size) return
        lyricsAdapter.activeLineIndex = position

        if ((!isUserManuallyScrolling || force) && smoothScroll) {
            try {
                val scroller = SpotifySmoothScroller(this, 0.35f).apply {
                    targetPosition = position
                }
                layoutManager.startSmoothScroll(scroller)
            } catch (e: Exception) {
                layoutManager.scrollToPositionWithOffset(position, (binding.rvLyrics.height * 0.35).toInt())
            }
        } else if (!isUserManuallyScrolling || force) {
            val offset = (binding.rvLyrics.height * 0.35).toInt().coerceAtLeast(60)
            layoutManager.scrollToPositionWithOffset(position, offset)
        }
    }

    private fun stopAutoScroll() {
        scrollJob?.cancel()
        scrollJob = null
    }

    private fun updateSpeedLabel() {
        binding.tvSpeed.text = String.format("%.2fx", scrollSpeedFactor)
    }

    private fun updatePlayStatusBadge(isPlaying: Boolean) {
        if (isPlaying) {
            binding.tvStatusBadge.text = "● ИГРАЕТ"
            binding.tvStatusBadge.setTextColor(Color.parseColor("#1DB954"))
        } else {
            binding.tvStatusBadge.text = "● ПАУЗА"
            binding.tvStatusBadge.setTextColor(Color.parseColor("#72777D"))
        }
    }

    private fun onTrackUpdated(
        title: String,
        artist: String,
        rawTitle: String,
        artistFromTitle: Boolean = false,
        isPlaying: Boolean
    ) {
        val cleanTitle = MediaNotificationListener.sanitizeText(title)
        val cleanArtist = MediaNotificationListener.sanitizeText(artist)
        val cleanRaw = MediaNotificationListener.sanitizeText(rawTitle)

        if (cleanTitle.isBlank()) return
        updatePlayStatusBadge(isPlaying)

        if (cleanTitle == activeTitle && cleanArtist == activeArtist && !isManualSearchMode) {
            return
        }

        isManualSearchMode = false
        manualSyncOffsetMs = 0L
        virtualPositionMs = 0L
        seekIgnoreUntilSystemMs = 0L

        activeTitle = cleanTitle
        activeArtist = cleanArtist
        activeRawTitle = cleanRaw
        activeArtistFromTitle = artistFromTitle

        binding.tvSongTitle.text = cleanTitle
        binding.tvSongArtist.text = if (cleanArtist.isNotBlank()) cleanArtist else "SoundCloud • Spotify"
        animateTrackHeaderSwap()
        updateProgressUI()

        if (binding.layoutGeniusWebView.visibility == View.VISIBLE) {
            openGeniusWeb()
        }

        loadLyrics(cleanArtist, cleanTitle, cleanRaw, artistFromTitle)
    }

    private fun performManualSearch() {
        val rawInput = binding.etSearchQuery.text.toString().trim()
        val query = MediaNotificationListener.sanitizeText(rawInput)
        if (query.isBlank()) {
            Toast.makeText(this, "Введите название песни", Toast.LENGTH_SHORT).show()
            return
        }

        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.etSearchQuery.windowToken, 0)

        val parsed = MediaNotificationListener.parseTrack(query)
        val targetArtist = parsed.artist
        val targetTitle = if (parsed.title.isNotBlank()) parsed.title else query

        isManualSearchMode = true
        manualSyncOffsetMs = 0L
        virtualPositionMs = 0L
        seekIgnoreUntilSystemMs = 0L
        totalDurationMs = 0L
        lastKnownPositionMs = 0L

        activeTitle = targetTitle
        activeArtist = targetArtist
        activeRawTitle = query
        activeArtistFromTitle = parsed.artistFromTitle

        binding.tvSongTitle.text = targetTitle
        binding.tvSongArtist.text = if (targetArtist.isNotBlank()) targetArtist else "Genius / Spotify"
        binding.tvStatusBadge.text = "● ПОИСК"
        binding.tvStatusBadge.setTextColor(Color.parseColor("#1DB954"))
        binding.tvCurrentTime.text = "00:00"
        binding.tvTotalTime.text = "--:--"
        binding.pbTrackProgress.progress = 0

        loadLyrics(targetArtist, targetTitle, query, parsed.artistFromTitle)
    }

    private fun loadLyrics(
        artist: String,
        title: String,
        rawTitle: String,
        artistFromTitle: Boolean = false,
        forceRefresh: Boolean = false
    ) {
        lyricsLoadJob?.cancel()
        displayedLines = emptyList()
        lyricsLines = emptyList()
        currentLyricsResult = null

        binding.progressBar.visibility = View.VISIBLE
        binding.tvLyricsPlaceholder.visibility = View.VISIBLE
        binding.btnPlaceholderGeniusWeb.visibility = View.GONE
        binding.tvLyricsPlaceholder.text = "Ищем текст на Genius и в базе караоке..."
        binding.rvLyrics.visibility = View.GONE
        showSyncPill(false)
        isUserManuallyScrolling = false

        lyricsLoadJob = lifecycleScope.launch {
            val result = GeniusLyricsFetcher.getLyrics(
                context = applicationContext,
                artist = artist,
                title = title,
                rawTitle = rawTitle,
                artistFromTitle = artistFromTitle,
                forceRefresh = forceRefresh
            )
            if (!isActive) return@launch
            binding.progressBar.visibility = View.GONE

            result.onSuccess { lyricsResult ->
                if (!isActive) return@onSuccess
                currentLyricsResult = lyricsResult
                binding.tvLyricsPlaceholder.visibility = View.GONE
                binding.btnPlaceholderGeniusWeb.visibility = View.GONE
                binding.rvLyrics.visibility = View.VISIBLE

                lyricsLines = lyricsResult.lines
                displayedLines = if (!lyricsResult.isSynced && !isManualSearchMode && totalDurationMs > 10_000L) {
                    GeniusLyricsFetcher.estimateLineTimestamps(lyricsLines, totalDurationMs)
                } else {
                    lyricsLines
                }
                lyricsAdapter.submitLines(displayedLines)
                if (displayMode != LyricsDisplayMode.PLAIN_TEXT) {
                    selectLine(0, smoothScroll = false, force = true)
                }
                animateLyricsListEntrance()

                binding.tvSourceBadge.text = lyricsResult.sourceName
                val displayArtist = when {
                    lyricsResult.artist.isNotBlank() && !lyricsResult.artist.equals("null", ignoreCase = true) -> lyricsResult.artist
                    artist.isNotBlank() && !artist.equals("null", ignoreCase = true) -> artist
                    else -> "SoundCloud"
                }
                val displayTitle = when {
                    lyricsResult.title.isNotBlank() && !lyricsResult.title.equals("null", ignoreCase = true) -> lyricsResult.title
                    title.isNotBlank() && !title.equals("null", ignoreCase = true) -> title
                    else -> "Трек"
                }

                binding.tvSongTitle.text = displayTitle
                binding.tvSongArtist.text = displayArtist

                if (binding.layoutGeniusWebView.visibility == View.VISIBLE) {
                    updateJumpToGeniusBanner()
                }

                if (displayMode != LyricsDisplayMode.PLAIN_TEXT && isAutoScrolling) {
                    startAutoScrollLoop()
                }
            }.onFailure { _ ->
                if (!isActive) return@onFailure
                binding.rvLyrics.visibility = View.GONE
                binding.tvLyricsPlaceholder.visibility = View.VISIBLE
                binding.btnPlaceholderGeniusWeb.visibility = View.VISIBLE
                val searchedLabel = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" — ")
                binding.tvLyricsPlaceholder.text = "Текст не найден для «$searchedLabel».\n\nНажмите кнопку ниже — откроется готовый поиск на Genius.com!"
            }
        }
    }

    private fun getVerifiedGeniusUrl(): String {
        val gUrl = currentLyricsResult?.geniusUrl ?: ""
        if (gUrl.contains("genius.com/", ignoreCase = true) && gUrl.endsWith("-lyrics", ignoreCase = true)) {
            return gUrl
        }
        val src = currentLyricsResult?.sourceUrl ?: ""
        if (src.contains("genius.com/", ignoreCase = true) && src.endsWith("-lyrics", ignoreCase = true)) {
            return src
        }
        return ""
    }

    private fun updateJumpToGeniusBanner() {
        val verifiedUrl = getVerifiedGeniusUrl()
        val currentLoadedUrl = binding.webViewGeniusPage.url ?: ""
        val alreadyOnSongPage = currentLoadedUrl.contains("-lyrics", ignoreCase = true)
        if (verifiedUrl.isNotBlank() && !alreadyOnSongPage) {
            binding.btnJumpToGeniusSong.visibility = View.VISIBLE
            binding.btnJumpToGeniusSong.setOnClickListener {
                openGeniusWeb(directSongUrl = verifiedUrl)
            }
        } else {
            binding.btnJumpToGeniusSong.visibility = View.GONE
        }
    }

    private fun updateGeniusWebNavButtons() {
        val canGoBack = try {
            binding.webViewGeniusPage.canGoBack()
        } catch (_: Exception) {
            false
        }
        binding.btnGeniusWebBack.isEnabled = canGoBack
        binding.btnGeniusWebBack.alpha = if (canGoBack) 1.0f else 0.35f
    }

    private fun openGeniusWeb(customQuery: String? = null, directSongUrl: String? = null) {
        val query = customQuery?.trim()?.ifBlank { null } ?: run {
            MediaNotificationListener.buildGeniusSearchQuery(
                artist = activeArtist,
                title = activeTitle,
                rawTitle = activeRawTitle,
                artistFromTitle = activeArtistFromTitle
            )
        }

        binding.etGeniusWebQuery.setText(query)
        binding.tvGeniusWebTitle.text = if (query.isNotBlank()) "Genius • $query" else "Genius.com"
        val sheet = binding.layoutGeniusWebView
        if (sheet.visibility != View.VISIBLE) {
            sheet.animate().cancel()
            sheet.visibility = View.VISIBLE
            sheet.alpha = 0f
            sheet.translationY = 48f * resources.displayMetrics.density
            sheet.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(260L)
                .setInterpolator(MotionUtils.EASE_OUT_EXPO)
                .start()
        }
        binding.pbGeniusWeb.visibility = View.VISIBLE
        binding.pbGeniusWeb.progress = 20
        binding.btnImportFromGeniusWeb.visibility = View.GONE

        val urlToLoad = when {
            !directSongUrl.isNullOrBlank() -> directSongUrl
            query.isNotBlank() -> "https://genius.com/search?q=" + URLEncoder.encode(query, "UTF-8").replace("+", "%20")
            else -> "https://genius.com/"
        }

        binding.webViewGeniusPage.loadUrl(urlToLoad)
        updateJumpToGeniusBanner()
        updateGeniusWebNavButtons()
    }

    private fun closeGeniusWeb() {
        binding.webViewGeniusPage.stopLoading()
        val sheet = binding.layoutGeniusWebView
        if (sheet.visibility != View.VISIBLE) return
        sheet.animate().cancel()
        sheet.animate()
            .alpha(0f)
            .translationY(48f * resources.displayMetrics.density)
            .setDuration(190L)
            .setInterpolator(MotionUtils.EASE_OUT_CUBIC)
            .withEndAction {
                sheet.visibility = View.GONE
                sheet.alpha = 1f
                sheet.translationY = 0f
            }
            .start()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupGeniusWebView() {
        binding.webViewGeniusPage.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            val defaultUa = settings.userAgentString
            if (!defaultUa.isNullOrBlank()) {
                settings.userAgentString = defaultUa.replace("; wv", "")
            }

            try {
                val cm = CookieManager.getInstance()
                cm.setAcceptCookie(true)
                cm.setAcceptThirdPartyCookies(this, true)
            } catch (_: Exception) {}

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    binding.pbGeniusWeb.progress = newProgress
                    binding.pbGeniusWeb.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
                }

                override fun onReceivedTitle(view: WebView?, title: String?) {
                    super.onReceivedTitle(view, title)
                    if (!title.isNullOrBlank() &&
                        !title.contains("404", ignoreCase = true) &&
                        !title.contains("just a moment", ignoreCase = true)
                    ) {
                        binding.tvGeniusWebTitle.text = title.replace("| Genius", "").trim()
                    }
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false
                    val lower = url.lowercase()
                    if (lower.contains("genius.com") || lower.contains("cloudflare.com")) {
                        return false
                    }
                    if (request?.hasGesture() == true) {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        } catch (_: Exception) {}
                    }
                    return true
                }

                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    updateGeniusWebNavButtons()
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    binding.pbGeniusWeb.visibility = View.GONE
                    updateGeniusWebNavButtons()
                    updateJumpToGeniusBanner()

                    // Inject query into any empty search box on genius.com
                    val currentQ = binding.etGeniusWebQuery.text?.toString()?.trim() ?: ""
                    if (currentQ.isNotBlank()) {
                        val escapedQ = currentQ.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
                        val fillJs = """
                            (function() {
                                var q = "$escapedQ";
                                var inputs = document.querySelectorAll('input[name="q"], input[type="search"]');
                                for (var i = 0; i < inputs.length; i++) {
                                    if (!inputs[i].value) {
                                        inputs[i].value = q;
                                    }
                                }
                            })();
                        """.trimIndent()
                        view?.evaluateJavascript(fillJs, null)
                    }

                    // Show "Import this lyrics into player" floating pill on any Genius song page
                    val isLikelySongUrl = !url.isNullOrBlank() &&
                        url.contains("genius.com/", ignoreCase = true) &&
                        url.substringBefore("?").endsWith("-lyrics", ignoreCase = true)
                    if (isLikelySongUrl) {
                        binding.btnImportFromGeniusWeb.visibility = View.VISIBLE
                    } else {
                        view?.evaluateJavascript(
                            "(function(){ return document.querySelectorAll('div[data-lyrics-container=\"true\"]').length > 0 ? '1' : '0'; })()"
                        ) { res ->
                            val hasContainers = res?.contains("1") == true
                            binding.btnImportFromGeniusWeb.visibility = if (hasContainers) View.VISIBLE else View.GONE
                        }
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?
                ) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    val statusCode = errorResponse?.statusCode ?: 0
                    val reqUrl = request?.url?.toString() ?: ""
                    if (statusCode == 404 && request?.isForMainFrame == true && reqUrl.endsWith("-lyrics")) {
                        val q = binding.etGeniusWebQuery.text?.toString()?.trim() ?: ""
                        if (q.isNotBlank()) {
                            val searchUrl = "https://genius.com/search?q=" + URLEncoder.encode(q, "UTF-8").replace("+", "%20")
                            view?.loadUrl(searchUrl)
                        }
                    }
                }
            }
        }

        binding.btnGeniusWebBack.setOnClickListener {
            if (binding.webViewGeniusPage.canGoBack()) {
                binding.webViewGeniusPage.goBack()
            }
        }

        binding.btnGeniusWebSearch.setOnClickListener {
            val q = binding.etGeniusWebQuery.text?.toString()?.trim() ?: ""
            if (q.isNotBlank()) {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(binding.etGeniusWebQuery.windowToken, 0)
                openGeniusWeb(customQuery = q)
            }
        }

        binding.etGeniusWebQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val q = binding.etGeniusWebQuery.text?.toString()?.trim() ?: ""
                if (q.isNotBlank()) {
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.hideSoftInputFromWindow(binding.etGeniusWebQuery.windowToken, 0)
                    openGeniusWeb(customQuery = q)
                }
                true
            } else false
        }

        binding.btnImportFromGeniusWeb.setOnClickListener {
            binding.webViewGeniusPage.evaluateJavascript(GeniusWebViewFetcher.EXTRACT_LYRICS_JS) { rawJs ->
                val rawText = try {
                    if (rawJs == null || rawJs == "null") "" else (JSONTokener(rawJs).nextValue() as? String ?: "")
                } catch (_: Exception) {
                    rawJs?.trim('"') ?: ""
                }

                val songUrl = binding.webViewGeniusPage.url ?: ""
                val imported = GeniusLyricsFetcher.parseManualGeniusLyrics(
                    title = activeTitle.ifBlank { binding.tvGeniusWebTitle.text?.toString() ?: "" },
                    artist = activeArtist,
                    rawLyrics = rawText,
                    geniusUrl = songUrl
                )

                if (imported != null && imported.lines.isNotEmpty()) {
                    GeniusLyricsFetcher.saveManualLyricsToCache(
                        context = applicationContext,
                        artist = activeArtist,
                        title = activeTitle,
                        rawTitle = activeRawTitle,
                        result = imported
                    )
                    currentLyricsResult = imported
                    lyricsLines = imported.lines
                    displayedLines = if (!isManualSearchMode && totalDurationMs > 10_000L) {
                        GeniusLyricsFetcher.estimateLineTimestamps(lyricsLines, totalDurationMs)
                    } else {
                        lyricsLines
                    }
                    lyricsAdapter.submitLines(displayedLines)
                    if (displayMode != LyricsDisplayMode.PLAIN_TEXT) {
                        selectLine(0, smoothScroll = false, force = true)
                    }
                    animateLyricsListEntrance()
                    binding.tvLyricsPlaceholder.visibility = View.GONE
                    binding.btnPlaceholderGeniusWeb.visibility = View.GONE
                    binding.rvLyrics.visibility = View.VISIBLE
                    binding.tvSourceBadge.text = imported.sourceName
                    closeGeniusWeb()
                    if (displayMode != LyricsDisplayMode.PLAIN_TEXT && isAutoScrolling) startAutoScrollLoop()
                    Toast.makeText(this, "Текст с Genius загружен в плеер!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Откройте страницу песни с текстом на Genius", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnCloseGeniusWeb.setOnClickListener {
            closeGeniusWeb()
        }

        binding.btnReloadGeniusWeb.setOnClickListener {
            binding.webViewGeniusPage.reload()
        }

        binding.btnExternalBrowserGenius.setOnClickListener {
            val currentUrl = binding.webViewGeniusPage.url ?: "https://genius.com"
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl))
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Не удалось открыть браузер", Toast.LENGTH_SHORT).show()
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.layoutGeniusWebView.visibility == View.VISIBLE) {
                    if (binding.webViewGeniusPage.canGoBack()) {
                        binding.webViewGeniusPage.goBack()
                    } else {
                        closeGeniusWeb()
                    }
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    private fun toggleFloatingLyrics() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            pendingStartIsland = true
            Toast.makeText(this, "Разрешите отображение поверх других приложений", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }

        if (FloatingLyricsService.isRunning) {
            FloatingLyricsService.setIslandEnabled(this, false)
            stopService(Intent(this, FloatingLyricsService::class.java))
            updateIslandButtonState(forceActive = false)
            Toast.makeText(this, "Живой остров выключен", Toast.LENGTH_SHORT).show()
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
            startLiveIslandService()
        }
    }

    private fun startLiveIslandService() {
        FloatingLyricsService.setIslandEnabled(this, true)
        val intent = Intent(this, FloatingLyricsService::class.java)
        ContextCompat.startForegroundService(this, intent)
        updateIslandButtonState(forceActive = true)
        Toast.makeText(this, "Живой остров включён — работает в фоне поверх всех приложений!", Toast.LENGTH_SHORT).show()
    }

    private fun updateIslandButtonState(forceActive: Boolean? = null) {
        val active = forceActive ?: FloatingLyricsService.isRunning
        if (active) {
            binding.btnToggleFloating.text = "● Остров ВКЛ"
            binding.btnToggleFloating.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#143520"))
            binding.btnToggleFloating.setTextColor(Color.parseColor("#39FF88"))
            binding.btnToggleFloating.strokeColor = ColorStateList.valueOf(Color.parseColor("#1ED760"))
        } else {
            binding.btnToggleFloating.text = "● Остров"
            binding.btnToggleFloating.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#161D28"))
            binding.btnToggleFloating.setTextColor(Color.WHITE)
            binding.btnToggleFloating.strokeColor = ColorStateList.valueOf(Color.parseColor("#2E394A"))
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format("%02d:%02d", min, sec)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAutoScroll()
        eqJob?.cancel()
        cardStrokeAnimator?.cancel()
        reSyncJob?.cancel()
        lyricsLoadJob?.cancel()
    }

    // Spotify-style smooth decelerating scroller that centers the active line
    class SpotifySmoothScroller(context: Context, private val targetRatio: Float = 0.35f) :
        LinearSmoothScroller(context) {

        override fun calculateDtToFit(viewStart: Int, viewEnd: Int, boxStart: Int, boxEnd: Int, snapPreference: Int): Int {
            val boxHeight = boxEnd - boxStart
            val targetY = boxStart + (boxHeight * targetRatio).toInt()
            return targetY - viewStart
        }

        override fun calculateTimeForDeceleration(dx: Int): Int {
            return (super.calculateTimeForDeceleration(dx) * 1.5).toInt().coerceIn(300, 550)
        }

        override fun calculateSpeedPerPixel(displayMetrics: DisplayMetrics): Float {
            return 160f / displayMetrics.densityDpi
        }
    }
}
