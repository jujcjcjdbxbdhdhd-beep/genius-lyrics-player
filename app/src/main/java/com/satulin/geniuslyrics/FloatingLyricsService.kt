package com.satulin.geniuslyrics

import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class FloatingLyricsService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var isAutoScrolling = false
    private var scrollJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    private val trackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == MediaNotificationListener.ACTION_TRACK_CHANGED) {
                val title = intent.getStringExtra(MediaNotificationListener.EXTRA_TITLE) ?: ""
                val artist = intent.getStringExtra(MediaNotificationListener.EXTRA_ARTIST) ?: ""
                updateTrack(title, artist)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
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
        val scrollView = overlayView?.findViewById<NestedScrollView>(R.id.scrollOverlayLyrics)

        btnClose?.setOnClickListener {
            stopSelf()
        }

        btnScroll?.setOnClickListener {
            isAutoScrolling = !isAutoScrolling
            if (isAutoScrolling) {
                btnScroll.setImageResource(android.R.drawable.ic_media_pause)
                startAutoScroll(scrollView)
            } else {
                btnScroll.setImageResource(android.R.drawable.ic_media_play)
                stopAutoScroll()
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

        // Register for song updates
        val filter = IntentFilter(MediaNotificationListener.ACTION_TRACK_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(trackReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(trackReceiver, filter)
        }

        // Load current song if already playing
        val currentTitle = MediaNotificationListener.currentTitle
        val currentArtist = MediaNotificationListener.currentArtist
        if (!currentTitle.isNullOrBlank()) {
            updateTrack(currentTitle, currentArtist ?: "")
        }
    }

    private fun updateTrack(title: String, artist: String) {
        val tvTitle = overlayView?.findViewById<TextView>(R.id.tvOverlayTitle)
        val tvLyrics = overlayView?.findViewById<TextView>(R.id.tvOverlayLyrics)
        val scrollView = overlayView?.findViewById<NestedScrollView>(R.id.scrollOverlayLyrics)

        tvTitle?.text = if (artist.isNotBlank()) "$artist — $title" else title
        tvLyrics?.text = "Загрузка текста с Genius..."
        scrollView?.scrollTo(0, 0)

        scope.launch {
            val result = GeniusLyricsFetcher.getLyrics(applicationContext, artist, title)
            result.onSuccess { lyricsResult ->
                tvLyrics?.text = lyricsResult.plainLyrics
            }.onFailure { err ->
                tvLyrics?.text = "Текст не найден: ${err.localizedMessage}"
            }
        }
    }

    private fun startAutoScroll(scrollView: NestedScrollView?) {
        scrollJob?.cancel()
        scrollJob = scope.launch {
            while (isActive && isAutoScrolling) {
                delay(100)
                scrollView?.smoothScrollBy(0, 3)
            }
        }
    }

    private fun stopAutoScroll() {
        scrollJob?.cancel()
        scrollJob = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAutoScroll()
        try {
            unregisterReceiver(trackReceiver)
        } catch (ignored: Exception) {}
        overlayView?.let { windowManager?.removeView(it) }
    }
}
