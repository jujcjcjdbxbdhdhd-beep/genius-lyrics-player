package com.satulin.geniuslyrics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.satulin.geniuslyrics.databinding.ActivityMainBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var lyricsAdapter: LyricsAdapter
    private lateinit var layoutManager: LinearLayoutManager

    private var isAutoScrolling = false
    private var scrollSpeedFactor = 1.0f
    private var scrollJob: Job? = null
    private var currentFontSizeSp = 19f

    private var activeTitle: String = ""
    private var activeArtist: String = ""
    private var lyricsLines: List<String> = emptyList()

    private val trackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MediaNotificationListener.ACTION_TRACK_CHANGED) {
                val title = intent.getStringExtra(MediaNotificationListener.EXTRA_TITLE) ?: ""
                val artist = intent.getStringExtra(MediaNotificationListener.EXTRA_ARTIST) ?: ""
                val isPlaying = intent.getBooleanExtra(MediaNotificationListener.EXTRA_IS_PLAYING, true)
                onTrackUpdated(title, artist, isPlaying)
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
            // Tap on any line immediately jumps to it and makes it active
            selectLine(clickedPosition, smoothScroll = true)
        }
        lyricsAdapter.fontSizeSp = currentFontSizeSp
        binding.rvLyrics.adapter = lyricsAdapter
    }

    override fun onResume() {
        super.onResume()
        checkPermissions()
        val filter = IntentFilter(MediaNotificationListener.ACTION_TRACK_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(trackReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(trackReceiver, filter)
        }

        // If track is already playing when opening app
        val currentTitle = MediaNotificationListener.currentTitle
        val currentArtist = MediaNotificationListener.currentArtist
        if (!currentTitle.isNullOrBlank()) {
            onTrackUpdated(
                currentTitle,
                currentArtist ?: "",
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
        // Notification Permission Button
        binding.btnGrantPermission.setOnClickListener {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
        }

        // Auto-Scroll Toggle
        binding.btnToggleScroll.setOnClickListener {
            toggleAutoScroll()
        }

        // Speed Controls
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

        // Font Size Controls
        binding.btnFontDown.setOnClickListener {
            if (currentFontSizeSp > 13f) {
                currentFontSizeSp -= 2f
                lyricsAdapter.fontSizeSp = currentFontSizeSp
            }
        }
        binding.btnFontUp.setOnClickListener {
            if (currentFontSizeSp < 32f) {
                currentFontSizeSp += 2f
                lyricsAdapter.fontSizeSp = currentFontSizeSp
            }
        }

        // Scroll to Top
        binding.btnScrollToTop.setOnClickListener {
            selectLine(0, smoothScroll = true)
        }

        // Manual Search
        binding.btnManualSearch.setOnClickListener {
            performManualSearch()
        }
        binding.etSearchQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performManualSearch()
                true
            } else false
        }

        // Floating Overlay Window Toggle
        binding.btnToggleFloating.setOnClickListener {
            toggleFloatingLyrics()
        }
    }

    private fun toggleAutoScroll() {
        isAutoScrolling = !isAutoScrolling
        if (isAutoScrolling) {
            binding.btnToggleScroll.text = "⏸ Пауза"
            binding.btnToggleScroll.setBackgroundColor(Color.parseColor("#333333"))
            startAutoScroll()
        } else {
            binding.btnToggleScroll.text = "▶ Автопрокрутка"
            binding.btnToggleScroll.setBackgroundColor(Color.parseColor("#282828"))
            stopAutoScroll()
        }
    }

    private fun startAutoScroll() {
        scrollJob?.cancel()
        scrollJob = lifecycleScope.launch {
            while (isActive && isAutoScrolling) {
                if (lyricsLines.isNotEmpty()) {
                    val currentIndex = lyricsAdapter.activeLineIndex
                    val nextIndex = if (currentIndex + 1 < lyricsLines.size) currentIndex + 1 else 0

                    // Calculate reading delay based on length of the current line and speed factor
                    val currentLine = lyricsLines.getOrNull(currentIndex) ?: ""
                    val wordsCount = currentLine.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.size
                    val baseDelay = when {
                        currentLine.startsWith("[") -> 1800L
                        wordsCount <= 3 -> 2400L
                        wordsCount <= 6 -> 3200L
                        wordsCount <= 10 -> 4000L
                        else -> 4800L
                    }

                    val adjustedDelay = (baseDelay / scrollSpeedFactor).toLong().coerceIn(800L, 8000L)
                    delay(adjustedDelay)

                    if (!isActive || !isAutoScrolling) break
                    selectLine(nextIndex, smoothScroll = true)
                } else {
                    delay(500)
                }
            }
        }
    }

    private fun selectLine(position: Int, smoothScroll: Boolean) {
        if (position < 0 || position >= lyricsLines.size) return
        lyricsAdapter.activeLineIndex = position

        // Center the active line on screen
        if (smoothScroll) {
            val offset = (binding.rvLyrics.height * 0.35).toInt()
            layoutManager.scrollToPositionWithOffset(position, offset)
        } else {
            layoutManager.scrollToPositionWithOffset(position, 100)
        }
    }

    private fun stopAutoScroll() {
        scrollJob?.cancel()
        scrollJob = null
    }

    private fun updateSpeedLabel() {
        binding.tvSpeed.text = String.format("%.2fx", scrollSpeedFactor)
    }

    private fun onTrackUpdated(title: String, artist: String, isPlaying: Boolean) {
        if (title.isBlank()) return

        // Update UI status badge
        if (isPlaying) {
            binding.tvStatusBadge.text = "● ИГРАЕТ"
            binding.tvStatusBadge.setTextColor(Color.parseColor("#1DB954"))
        } else {
            binding.tvStatusBadge.text = "● ПАУЗА"
            binding.tvStatusBadge.setTextColor(Color.parseColor("#888888"))
        }

        // Only reload if song actually changed
        if (title == activeTitle && artist == activeArtist) {
            return
        }

        activeTitle = title
        activeArtist = artist

        binding.tvSongTitle.text = title
        binding.tvSongArtist.text = if (artist.isNotBlank()) artist else "Неизвестный исполнитель"

        loadLyrics(artist, title)
    }

    private fun performManualSearch() {
        val query = binding.etSearchQuery.text.toString().trim()
        if (query.isBlank()) {
            Toast.makeText(this, "Введите название песни", Toast.LENGTH_SHORT).show()
            return
        }

        // Hide keyboard
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.etSearchQuery.windowToken, 0)

        binding.tvSongTitle.text = query
        binding.tvSongArtist.text = "Поиск по запросу"
        binding.tvStatusBadge.text = "● ПОИСК"
        binding.tvStatusBadge.setTextColor(Color.parseColor("#FFFF64"))

        loadLyrics("", query)
    }

    private fun loadLyrics(artist: String, title: String) {
        binding.progressBar.visibility = View.VISIBLE
        binding.tvLyricsPlaceholder.visibility = View.VISIBLE
        binding.tvLyricsPlaceholder.text = "Загрузка оригинального текста с Genius..."
        binding.rvLyrics.visibility = View.GONE

        lifecycleScope.launch {
            val result = GeniusLyricsFetcher.getLyrics(applicationContext, artist, title)
            binding.progressBar.visibility = View.GONE

            result.onSuccess { lyricsResult ->
                binding.tvLyricsPlaceholder.visibility = View.GONE
                binding.rvLyrics.visibility = View.VISIBLE

                // Parse into clean non-empty lines
                lyricsLines = lyricsResult.lyrics.lines()
                    .map { it.trimEnd() }
                    .filter { it.isNotBlank() }

                lyricsAdapter.submitLines(lyricsLines)
                selectLine(0, smoothScroll = false)

                // If user had auto-scroll enabled, resume
                if (isAutoScrolling) {
                    startAutoScroll()
                }
            }.onFailure { err ->
                binding.rvLyrics.visibility = View.GONE
                binding.tvLyricsPlaceholder.visibility = View.VISIBLE
                binding.tvLyricsPlaceholder.text = "Не удалось загрузить текст песни:\n\n${err.localizedMessage}\n\nПопробуйте воспользоваться строкой поиска выше."
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

    override fun onDestroy() {
        super.onDestroy()
        stopAutoScroll()
    }
}
