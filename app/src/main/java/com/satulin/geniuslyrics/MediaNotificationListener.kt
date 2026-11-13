package com.satulin.geniuslyrics

import android.app.Notification
import android.content.Intent
import android.media.session.MediaSession
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class MediaNotificationListener : NotificationListenerService() {

    companion object {
        const val TAG = "MediaNotifListener"
        const val ACTION_TRACK_CHANGED = "com.satulin.geniuslyrics.ACTION_TRACK_CHANGED"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_ARTIST = "extra_artist"
        const val EXTRA_PACKAGE = "extra_package"
        const val EXTRA_IS_PLAYING = "extra_is_playing"

        var currentTitle: String? = null
        var currentArtist: String? = null
        var currentPackage: String? = null
        var isPlaying: Boolean = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn?.let { inspectNotification(it) }
    }

    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) {
        super.onNotificationRankingUpdate(rankingMap)
        // Check active notifications in case playback state changed
        activeNotifications?.forEach { inspectNotification(it) }
    }

    private fun inspectNotification(sbn: StatusBarNotification) {
        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        // Check if it is a media notification
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

        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val rawArtist = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()

        if (rawTitle.isNullOrBlank()) return

        val title = cleanTitle(rawTitle)
        val artist = rawArtist?.trim() ?: ""
        val pkg = sbn.packageName

        // Determine if song is playing (most players show a Pause action button when playing)
        val playing = isNotificationPlaying(notification)

        // Ignore duplicate identical track events
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
            "com.maxmpz.audioplayer", // Poweramp
            "com.soundcloud.android",
            "org.videolan.vlc",
            "com.aimp.player"
        )
        return knownApps.any { packageName.contains(it, ignoreCase = true) }
    }

    private fun isNotificationPlaying(notification: Notification): Boolean {
        // When audio is playing, notification action usually contains a "Pause" (Пауза) icon/action
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
            .replace(Regex("\\(Official Video\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\[Official Video\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\(Remastered.*?\\)", RegexOption.IGNORE_CASE), "")
            .trim()
    }
}
