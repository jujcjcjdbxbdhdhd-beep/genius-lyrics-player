package com.satulin.geniuslyrics

import android.app.Notification
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class MediaNotificationListener : NotificationListenerService() {

    companion object {
        const val TAG = "MediaNotifListener"
        const val ACTION_TRACK_CHANGED = "com.satulin.geniuslyrics.ACTION_TRACK_CHANGED"
        const val ACTION_PLAYBACK_STATE = "com.satulin.geniuslyrics.ACTION_PLAYBACK_STATE"

        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_ARTIST = "extra_artist"
        const val EXTRA_PACKAGE = "extra_package"
        const val EXTRA_IS_PLAYING = "extra_is_playing"
        const val EXTRA_POSITION_MS = "extra_position_ms"
        const val EXTRA_DURATION_MS = "extra_duration_ms"
        const val EXTRA_UPDATED_AT = "extra_updated_at"

        var currentTitle: String? = null
        var currentArtist: String? = null
        var currentPackage: String? = null
        var isPlaying: Boolean = false
        var currentPositionMs: Long = 0L
        var currentDurationMs: Long = 0L
        var lastPositionUpdateTime: Long = 0L
    }

    private var activeMediaController: MediaController? = null

    private val mediaControllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            state?.let { updatePlaybackState(it) }
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            metadata?.let { updateMetadata(it) }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn?.let { inspectNotification(it) }
    }

    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) {
        super.onNotificationRankingUpdate(rankingMap)
        activeNotifications?.forEach { inspectNotification(it) }
    }

    private fun inspectNotification(sbn: StatusBarNotification) {
        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val isMediaStyle = notification.isMediaNotification()
        val mediaSessionToken = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
        } else {
            @Suppress("DEPRECATION")
            extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)
        }

        if (!isMediaStyle && mediaSessionToken == null && !isKnownMusicApp(sbn.packageName)) {
            return
        }

        // Attach MediaController if session token is present
        if (mediaSessionToken != null) {
            try {
                activeMediaController?.unregisterCallback(mediaControllerCallback)
                val controller = MediaController(this, mediaSessionToken)
                controller.registerCallback(mediaControllerCallback)
                activeMediaController = controller

                controller.metadata?.let { updateMetadata(it) }
                controller.playbackState?.let { updatePlaybackState(it) }
            } catch (e: Exception) {
                Log.w(TAG, "Could not bind MediaController: ${e.message}")
            }
        }

        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val rawArtist = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()

        if (rawTitle.isNullOrBlank()) return

        val title = cleanTitle(rawTitle)
        val artist = cleanArtist(rawArtist?.trim() ?: "")
        val pkg = sbn.packageName
        val playing = isNotificationPlaying(notification)

        if (title == currentTitle && artist == currentArtist && playing == isPlaying) {
            return
        }

        currentTitle = title
        currentArtist = artist
        currentPackage = pkg
        isPlaying = playing

        Log.d(TAG, "Detected Track: $title by $artist (App: $pkg, Playing: $playing)")

        val intent = Intent(ACTION_TRACK_CHANGED).apply {
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_ARTIST, artist)
            putExtra(EXTRA_PACKAGE, pkg)
            putExtra(EXTRA_IS_PLAYING, playing)
            putExtra(EXTRA_POSITION_MS, currentPositionMs)
            putExtra(EXTRA_DURATION_MS, currentDurationMs)
            putExtra(EXTRA_UPDATED_AT, SystemClock.elapsedRealtime())
            setPackage(applicationContext.packageName)
        }
        sendBroadcast(intent)
    }

    private fun updateMetadata(metadata: MediaMetadata) {
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)

        if (duration > 0) {
            currentDurationMs = duration
        }

        if (!title.isNullOrBlank()) {
            val cleanedTitle = cleanTitle(title)
            val cleanedArtist = cleanArtist(artist ?: "")
            if (cleanedTitle != currentTitle || cleanedArtist != currentArtist) {
                currentTitle = cleanedTitle
                currentArtist = cleanedArtist
                val intent = Intent(ACTION_TRACK_CHANGED).apply {
                    putExtra(EXTRA_TITLE, cleanedTitle)
                    putExtra(EXTRA_ARTIST, cleanedArtist)
                    putExtra(EXTRA_PACKAGE, currentPackage ?: "")
                    putExtra(EXTRA_IS_PLAYING, isPlaying)
                    putExtra(EXTRA_POSITION_MS, currentPositionMs)
                    putExtra(EXTRA_DURATION_MS, currentDurationMs)
                    putExtra(EXTRA_UPDATED_AT, SystemClock.elapsedRealtime())
                    setPackage(applicationContext.packageName)
                }
                sendBroadcast(intent)
            }
        }
    }

    private fun updatePlaybackState(state: PlaybackState) {
        val playing = state.state == PlaybackState.STATE_PLAYING
        val position = state.position
        val updatedAt = state.lastPositionUpdateTime

        isPlaying = playing
        currentPositionMs = position
        lastPositionUpdateTime = updatedAt

        val intent = Intent(ACTION_PLAYBACK_STATE).apply {
            putExtra(EXTRA_IS_PLAYING, playing)
            putExtra(EXTRA_POSITION_MS, position)
            putExtra(EXTRA_UPDATED_AT, updatedAt)
            setPackage(applicationContext.packageName)
        }
        sendBroadcast(intent)
    }

    private fun Notification.isMediaNotification(): Boolean {
        val template = extras.getString(Notification.EXTRA_TEMPLATE)
        return template != null && template.contains("MediaStyle", ignoreCase = true)
    }

    private fun isKnownMusicApp(packageName: String): Boolean {
        val knownApps = listOf(
            "com.spotify.music",
            "com.google.android.apps.youtube.music",
            "ru.yandex.music",
            "com.vkontakte.android",
            "com.vk.vkclient",
            "com.apple.android.music",
            "com.deezer.android.app",
            "com.maxmpz.audioplayer",
            "com.soundcloud.android",
            "org.videolan.vlc",
            "com.aimp.player"
        )
        return knownApps.any { packageName.contains(it, ignoreCase = true) }
    }

    private fun isNotificationPlaying(notification: Notification): Boolean {
        val actions = notification.actions ?: return true
        for (action in actions) {
            val title = action.title?.toString()?.lowercase() ?: ""
            if (title.contains("pause") || title.contains("пауз")) {
                return true
            }
            if (title.contains("play") || title.contains("воспроизв") || title.contains("играть")) {
                return false
            }
        }
        return true
    }

    private fun cleanTitle(raw: String): String {
        return raw.replace(Regex("\\(feat\\..*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\[feat\\..*?\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\(ft\\..*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\(Official.*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\[Official.*?\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\(Remastered.*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\(Slowed.*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\(Speed Up.*?\\)", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun cleanArtist(raw: String): String {
        return raw.replace(Regex(",.*"), "")
            .replace(Regex("feat\\..*", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            activeMediaController?.unregisterCallback(mediaControllerCallback)
        } catch (ignored: Exception) {}
    }
}
