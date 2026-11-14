package com.satulin.geniuslyrics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import com.satulin.geniuslyrics.databinding.ActivityMainBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var lyricsAdapter: LyricsAdapter
    private lateinit var layoutManager: LinearLayoutManager

    private var isAutoScrolling = true
    private var isUserManuallyScrolling = false
    private var scrollSpeedFactor = 1.0f
    private var scrollJob: Job? = null
    private var reSyncJob: Job? = null
    private var currentFontSizeSp = 20f

    private var activeTitle: String = ""
    private var activeArtist: String = ""
    private var activeRawTitle: String = ""
    private var currentLyricsResult: LyricsResult? = null
    private var lyricsLines: List<LyricLine> = emptyList()

    private var isMediaPlaying = false
    private var lastKnownPositionMs = 0L
    private var totalDurationMs = 0L
    private var lastUpdateTimeSystem = 0L

    private val trackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                MediaNotificationListener.ACTION_TRACK_CHANGED -> {
                    val title = MediaNotificationListener.sanitizeText(intent.getStringExtra(MediaNotificationListener.EXTRA_TITLE))
                    val artist = MediaNotificationListener.sanitizeText(intent.getStringExtra(MediaNotificationListener.EXTRA_ARTIST))
                    val rawTitle = MediaNotificationListener.sanitizeText(intent.getStringExtra(MediaNotificationListener.EXTRA_RAW_TITLE))
                    val isPlaying = intent.getBooleanExtra(MediaNotificationListener.EXTRA_IS_PLAYING, true)
                    val pos = intent.getLongExtra(MediaNotificationListener.EXTRA_POSITION_MS, 0L)
                    val dur = intent.getLongExtra(MediaNotificationListener.EXTRA_DURATION_MS, 0L)
                    val updated = intent.getLongExtra(MediaNotificationListener.EXTRA_UPDATED_AT, SystemClock.elapsedRealtime())

                    updatePlaybackTime(pos, dur, updated, isPlaying)
                    onTrackUpdated(title, artist, rawTitle, isPlaying)
                }
                MediaNotificationListener.ACTION_PLAYBACK_STATE -> {
                    val isPlaying = intent.getBooleanExtra(MediaNotificationListener.EXTRA_IS_PLAYING, true)
                    val pos = intent.getLongExtra(MediaNotificationListener.EXTRA_POSITION_MS, 0L)
                    val dur = intent.getLongExtra(MediaNotificationListener.EXTRA_DURATION_MS, totalDurationMs)
                    val updated = intent.getLongExtra(MediaNotificationListener.EXTRA_UPDATED_AT, SystemClock.elapsedRealtime())

                    updatePlaybackTime(pos, dur, updated, isPlaying)
                    updatePlayStatusBadge(isPlaying)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupUI()
        checkPermissions()
    }

    private fun setupRecyclerView() {
        layoutManager = LinearLayoutManager(this)
        binding.rvLyrics.layoutManager = layoutManager

        lyricsAdapter = LyricsAdapter { clickedPosition ->
            isUserManuallyScrolling = false
            binding.btnSyncCurrentLine.visibility = View.GONE
            selectLine(clickedPosition, smoothScroll = true, force = true)
        }
        lyricsAdapter.fontSizeSp = currentFontSizeSp
        binding.rvLyrics.adapter = lyricsAdapter

        // Detect user manual dragging: show Spotify "Return to Sync" pill
        binding.rvLyrics.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    isUserManuallyScrolling = true
                    if (lyricsLines.isNotEmpty()) {
                        binding.btnSyncCurrentLine.visibility = View.VISIBLE
                    }
                    scheduleAutoReSync()
                }
            }
        })

        binding.btnSyncCurrentLine.setOnClickListener {
            isUserManuallyScrolling = false
            binding.btnSyncCurrentLine.visibility = View.GONE
            reSyncJob?.cancel()
            val target = lyricsAdapter.activeLineIndex.coerceAtLeast(0)
            selectLine(target, smoothScroll = true, force = true)
        }
    }

    private fun scheduleAutoReSync() {
        reSyncJob?.cancel()
        reSyncJob = lifecycleScope.launch {
            delay(4000)
            if (isActive) {
                isUserManuallyScrolling = false
                binding.btnSyncCurrentLine.visibility = View.GONE
                val target = lyricsAdapter.activeLineIndex.coerceAtLeast(0)
                selectLine(target, smoothScroll = true, force = true)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkPermissions()

        val filter = IntentFilter().apply {
            addAction(MediaNotificationListener.ACTION_TRACK_CHANGED)
            addAction(MediaNotificationListener.ACTION_PLAYBACK_STATE)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(trackReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(trackReceiver, filter)
        }

        val currentTitle = MediaNotificationListener.sanitizeText(MediaNotificationListener.currentTitle)
        val currentArtist = MediaNotificationListener.sanitizeText(MediaNotificationListener.currentArtist)
        val currentRawTitle = MediaNotificationListener.sanitizeText(MediaNotificationListener.currentRawTitle)

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
                MediaNotificationListener.isPlaying
            )
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(trackReceiver)
        } catch (ignored: Exception) {}
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
                scrollSpeedFactor -= 0.25f
                updateSpeedLabel()
            }
        }
        binding.btnSpeedUp.setOnClickListener {
            if (scrollSpeedFactor < 3.0f) {
                scrollSpeedFactor += 0.25f
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
            binding.btnSyncCurrentLine.visibility = View.GONE
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

        binding.btnToggleFloating.setOnClickListener {
            toggleFloatingLyrics()
        }
    }

    private fun updatePlaybackTime(positionMs: Long, durationMs: Long, updatedAt: Long, playing: Boolean) {
        lastKnownPositionMs = positionMs
        if (durationMs > 0) {
            totalDurationMs = durationMs
        }
        lastUpdateTimeSystem = if (updatedAt > 0) updatedAt else SystemClock.elapsedRealtime()
        isMediaPlaying = playing
    }

    private fun getCurrentTrackPosition(): Long {
        if (!isMediaPlaying || lastUpdateTimeSystem == 0L) {
            return lastKnownPositionMs
        }
        val elapsed = SystemClock.elapsedRealtime() - lastUpdateTimeSystem
        return lastKnownPositionMs + elapsed
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
        scrollJob = lifecycleScope.launch {
            while (isActive && isAutoScrolling) {
                if (lyricsLines.isNotEmpty()) {
                    val isSynced = currentLyricsResult?.isSynced == true

                    // Update Spotify time counters & progress bar
                    val currentMs = getCurrentTrackPosition()
                    binding.tvCurrentTime.text = formatTime(currentMs)
                    if (totalDurationMs > 0) {
                        binding.tvTotalTime.text = formatTime(totalDurationMs)
                        val progress = ((currentMs.toDouble() / totalDurationMs) * 1000).toInt().coerceIn(0, 1000)
                        binding.pbTrackProgress.progress = progress
                    }

                    if (isSynced && isMediaPlaying) {
                        var bestIndex = -1
                        for (i in lyricsLines.indices) {
                            val lineTime = lyricsLines[i].timeMs
                            if (lineTime in 0..currentMs) {
                                bestIndex = i
                            } else if (lineTime > currentMs) {
                                break
                            }
                        }

                        if (bestIndex != -1 && bestIndex != lyricsAdapter.activeLineIndex) {
                            selectLine(bestIndex, smoothScroll = true)
                        }

                        delay(90)
                    } else {
                        // Smooth auto-scroll for unsynced tracks
                        val currentIndex = lyricsAdapter.activeLineIndex
                        val nextIndex = if (currentIndex + 1 < lyricsLines.size) currentIndex + 1 else 0

                        val currentLine = lyricsLines.getOrNull(currentIndex)?.text ?: ""
                        val wordsCount = currentLine.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.size
                        val baseDelay = when {
                            currentLine.startsWith("[") -> 1600L
                            wordsCount <= 3 -> 2200L
                            wordsCount <= 6 -> 3000L
                            wordsCount <= 10 -> 3800L
                            else -> 4600L
                        }

                        val adjustedDelay = (baseDelay / scrollSpeedFactor).toLong().coerceIn(600L, 7000L)
                        delay(adjustedDelay)

                        if (!isActive || !isAutoScrolling) break
                        selectLine(nextIndex, smoothScroll = true)
                    }
                } else {
                    delay(400)
                }
            }
        }
    }

    // Spotify smooth center gliding scroll
    private fun selectLine(position: Int, smoothScroll: Boolean, force: Boolean = false) {
        if (position < 0 || position >= lyricsLines.size) return
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

    private fun onTrackUpdated(title: String, artist: String, rawTitle: String, isPlaying: Boolean) {
        val cleanTitle = MediaNotificationListener.sanitizeText(title)
        val cleanArtist = MediaNotificationListener.sanitizeText(artist)
        val cleanRaw = MediaNotificationListener.sanitizeText(rawTitle)

        if (cleanTitle.isBlank()) return
        updatePlayStatusBadge(isPlaying)

        if (cleanTitle == activeTitle && cleanArtist == activeArtist) {
            return
        }

        activeTitle = cleanTitle
        activeArtist = cleanArtist
        activeRawTitle = cleanRaw

        binding.tvSongTitle.text = cleanTitle
        binding.tvSongArtist.text = if (cleanArtist.isNotBlank()) cleanArtist else "SoundCloud • Spotify"

        loadLyrics(cleanArtist, cleanTitle, cleanRaw)
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

        binding.tvSongTitle.text = targetTitle
        binding.tvSongArtist.text = if (targetArtist.isNotBlank()) targetArtist else "Spotify / Genius"
        binding.tvStatusBadge.text = "● ПОИСК"
        binding.tvStatusBadge.setTextColor(Color.parseColor("#1DB954"))

        loadLyrics(targetArtist, targetTitle, query)
    }

    private fun loadLyrics(artist: String, title: String, rawTitle: String) {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvLyricsPlaceholder.visibility = View.VISIBLE
        binding.tvLyricsPlaceholder.text = "Ищем текст в Spotify, NetEase и Genius..."
        binding.rvLyrics.visibility = View.GONE
        binding.btnSyncCurrentLine.visibility = View.GONE
        isUserManuallyScrolling = false

        lifecycleScope.launch {
            val result = GeniusLyricsFetcher.getLyrics(applicationContext, artist, title, rawTitle)
            binding.progressBar.visibility = View.GONE

            result.onSuccess { lyricsResult ->
                currentLyricsResult = lyricsResult
                binding.tvLyricsPlaceholder.visibility = View.GONE
                binding.rvLyrics.visibility = View.VISIBLE

                lyricsLines = lyricsResult.lines
                lyricsAdapter.submitLines(lyricsLines)
                selectLine(0, smoothScroll = false, force = true)

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

                if (isAutoScrolling) {
                    startAutoScrollLoop()
                }
            }.onFailure { _ ->
                binding.rvLyrics.visibility = View.GONE
                binding.tvLyricsPlaceholder.visibility = View.VISIBLE
                binding.tvLyricsPlaceholder.text = "Текст не найден.\n\nПопробуйте ввести точное название трека вручную в строке поиска выше."
            }
        }
    }

    private fun toggleFloatingLyrics() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Разрешите отображение поверх других приложений", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }

        val intent = Intent(this, FloatingLyricsService::class.java)
        startService(intent)
        Toast.makeText(this, "Плавающее окно активировано!", Toast.LENGTH_SHORT).show()
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
        reSyncJob?.cancel()
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
