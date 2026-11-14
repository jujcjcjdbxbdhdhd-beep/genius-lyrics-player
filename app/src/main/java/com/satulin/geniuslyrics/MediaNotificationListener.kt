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
        const val EXTRA_RAW_TITLE = "extra_raw_title"
        const val EXTRA_PACKAGE = "extra_package"
        const val EXTRA_IS_PLAYING = "extra_is_playing"
        const val EXTRA_POSITION_MS = "extra_position_ms"
        const val EXTRA_DURATION_MS = "extra_duration_ms"
        const val EXTRA_UPDATED_AT = "extra_updated_at"

        var currentTitle: String? = null
        var currentArtist: String? = null
        var currentRawTitle: String? = null
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

        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val rawArtist = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
            ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim()
            ?: ""

        if (rawTitle.isBlank() || rawTitle.equals("null", ignoreCase = true)) return

        val pkg = sbn.packageName
        val parsed = parseTrackInfo(rawTitle, rawArtist, pkg)
        val playing = isNotificationPlaying(notification)

        if (parsed.title == currentTitle && parsed.artist == currentArtist && playing == isPlaying) {
            return
        }

        currentTitle = parsed.title
        currentArtist = parsed.artist
        currentRawTitle = rawTitle
        currentPackage = pkg
        isPlaying = playing

        Log.d(TAG, "Detected: title='${parsed.title}', artist='${parsed.artist}', raw='$rawTitle' (Pkg: $pkg)")

        val intent = Intent(ACTION_TRACK_CHANGED).apply {
            putExtra(EXTRA_TITLE, parsed.title)
            putExtra(EXTRA_ARTIST, parsed.artist)
            putExtra(EXTRA_RAW_TITLE, rawTitle)
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
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim() ?: ""
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)?.trim() ?: ""
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)

        if (duration > 0) {
            currentDurationMs = duration
        }

        if (title.isNotBlank() && !title.equals("null", ignoreCase = true)) {
            val parsed = parseTrackInfo(title, artist, currentPackage ?: "")
            if (parsed.title != currentTitle || parsed.artist != currentArtist) {
                currentTitle = parsed.title
                currentArtist = parsed.artist
                currentRawTitle = title

                val intent = Intent(ACTION_TRACK_CHANGED).apply {
                    putExtra(EXTRA_TITLE, parsed.title)
                    putExtra(EXTRA_ARTIST, parsed.artist)
                    putExtra(EXTRA_RAW_TITLE, title)
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
            "com.soundcloud.android",
            "com.spotify.music",
            "com.google.android.apps.youtube.music",
            "ru.yandex.music",
            "com.vkontakte.android",
            "com.vk.vkclient",
            "com.apple.android.music",
            "com.deezer.android.app",
            "com.maxmpz.audioplayer",
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

    data class ParsedTrack(val title: String, val artist: String)

    private fun parseTrackInfo(rawTitle: String, rawArtist: String, packageName: String): ParsedTrack {
        // Sanitize string "null"
        val cleanRawArtist = if (rawArtist.equals("null", ignoreCase = true)) "" else rawArtist

        // Remove junk tags first: (prod. by ...), [bass boosted], (slowed + reverb), etc.
        var cleanedTitle = stripJunkTags(rawTitle)

        // If title contains " - " or " — ", like in SoundCloud: "FRIENDLY THUG 52 NGG - Calma"
        val dashSeparators = listOf(" - ", " — ", " – ")
        for (sep in dashSeparators) {
            if (cleanedTitle.contains(sep)) {
                val parts = cleanedTitle.split(sep, limit = 2)
                val partArtist = parts[0].trim()
                val partTitle = parts[1].trim()

                // In SoundCloud: Artist is usually part 1, and Title is part 2
                if (partArtist.isNotBlank() && partTitle.isNotBlank()) {
                    return ParsedTrack(
                        title = partTitle,
                        artist = cleanArtist(partArtist)
                    )
                }
            }
        }

        // If no dash separator, but artist was provided in notification text (Spotify/Yandex)
        if (cleanRawArtist.isNotBlank()) {
            return ParsedTrack(
                title = cleanedTitle,
                artist = cleanArtist(cleanRawArtist)
            )
        }

        // Single title without dash and without separate artist
        return ParsedTrack(title = cleanedTitle, artist = "")
    }

    private fun stripJunkTags(raw: String): String {
        return raw
            // Remove brackets containing metadata: [slowed], [reverb], (prod. by ...), [bass boosted]
            .replace(Regex("\\[.*?(slowed|reverb|speed|sped|bass|prod|remix|edit|nightcore|official|hq).*?\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\(.*?(slowed|reverb|speed|sped|bass|prod|remix|edit|nightcore|official|hq).*?\\)", RegexOption.IGNORE_CASE), "")
            // General brackets at end
            .replace(Regex("\\(prod\\..*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\[prod\\..*?\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\(feat\\..*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\[feat\\..*?\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\(ft\\..*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\[ft\\..*?\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\(Official Video\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\[Official Video\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\(Remastered.*?\\)", RegexOption.IGNORE_CASE), "")
            // Remove stray file extensions or SoundCloud suffixes
            .replace(Regex("\\.(mp3|wav|flac|m4a)$", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun cleanArtist(raw: String): String {
        if (raw.equals("null", ignoreCase = true)) return ""
        return raw.replace(Regex(",.*"), "")
            .replace(Regex("feat\\..*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("ft\\..*", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            activeMediaController?.unregisterCallback(mediaControllerCallback)
        } catch (ignored: Exception) {}
    }
}
