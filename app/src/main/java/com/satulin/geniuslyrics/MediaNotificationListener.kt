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

        // Complete Russian & International hip-hop, rap, underground & SoundCloud artist alias dictionary
        val ARTIST_ALIASES: List<Pair<String, String>> = listOf(
            // Multi-word aliases first (so longest match wins)
            Pair("friendly thug 52 ngg", "FRIENDLY THUG 52 NGG"),
            Pair("friendly thug", "FRIENDLY THUG 52 NGG"),
            Pair("френдли таг 52 ngg", "FRIENDLY THUG 52 NGG"),
            Pair("френдли таг", "FRIENDLY THUG 52 NGG"),
            Pair("thug 52", "FRIENDLY THUG 52 NGG"),
            Pair("52 ngg", "FRIENDLY THUG 52 NGG"),
            Pair("big baby tape", "Big Baby Tape"),
            Pair("биг бейби тейп", "Big Baby Tape"),
            Pair("биг бэйби тейп", "Big Baby Tape"),
            Pair("bb tape", "Big Baby Tape"),
            Pair("scally milano", "Scally Milano"),
            Pair("скалли милано", "Scally Milano"),
            Pair("boulevard depo", "Boulevard Depo"),
            Pair("бульвар депо", "Boulevard Depo"),
            Pair("bushido zho", "Bushido Zho"),
            Pair("бушидо жо", "Bushido Zho"),
            Pair("бушидо", "Bushido Zho"),
            Pair("163onmyneck", "163ONMYNECK"),
            Pair("онмайнек", "163ONMYNECK"),
            Pair("163", "163ONMYNECK"),
            Pair("gone.fludd", "GONE.Fludd"),
            Pair("gone fludd", "GONE.Fludd"),
            Pair("гон флад", "GONE.Fludd"),
            Pair("гон фладд", "GONE.Fludd"),
            Pair("og buda", "OG Buda"),
            Pair("ог буда", "OG Buda"),
            Pair("три дня дождя", "Три дня дождя"),
            Pair("мэйби бэйби", "Мэйби Бэйби"),
            Pair("мейби бейби", "Мэйби Бэйби"),
            Pair("maybe baby", "Мэйби Бэйби"),
            Pair("мияги и эндшпиль", "Miyagi & Andy Panda"),
            Pair("miyagi & andy panda", "Miyagi & Andy Panda"),
            Pair("kai angel", "Kai Angel"),
            Pair("кай ангел", "Kai Angel"),
            Pair("sayonara boy", "Элджей"),
            Pair("alblak 52", "ALBLAK 52"),
            Pair("алблак 52", "ALBLAK 52"),
            Pair("алблак", "ALBLAK 52"),
            Pair("soda luv", "Soda Luv"),
            Pair("сода лав", "Soda Luv"),
            Pair("thomas mraz", "Thomas Mraz"),
            Pair("томас мраз", "Thomas Mraz"),
            Pair("playboi carti", "Playboi Carti"),
            Pair("плейбой карти", "Playboi Carti"),
            Pair("кен карсон", "Ken Carson"),
            Pair("ken carson", "Ken Carson"),
            Pair("дестрой лонли", "Destroy Lonely"),
            Pair("destroy lonely", "Destroy Lonely"),
            Pair("тревис скотт", "Travis Scott"),
            Pair("travis scott", "Travis Scott"),
            Pair("лил узи верт", "Lil Uzi Vert"),
            Pair("lil uzi vert", "Lil Uzi Vert"),
            Pair("лил узи", "Lil Uzi Vert"),
            Pair("метро бумин", "Metro Boomin"),
            Pair("metro boomin", "Metro Boomin"),
            Pair("дон толивер", "Don Toliver"),
            Pair("don toliver", "Don Toliver"),
            Pair("чиф киф", "Chief Keef"),
            Pair("chief keef", "Chief Keef"),
            Pair("джус ворлд", "Juice WRLD"),
            Pair("juice wrld", "Juice WRLD"),
            Pair("пепел нахуди", "Pepel Nahudi"),
            Pair("pepel nahudi", "Pepel Nahudi"),
            Pair("беби мело", "Baby Melo"),
            Pair("бейби мело", "Baby Melo"),
            Pair("baby melo", "Baby Melo"),
            Pair("аглистефан", "uglystephan"),
            Pair("uglystephan", "uglystephan"),
            Pair("вайт панк", "White Punk"),
            Pair("white punk", "White Punk"),
            Pair("худ рич лука", "Hood Rich Luka"),
            Pair("hood rich luka", "Hood Rich Luka"),
            Pair("лил кристал", "LIL KRYSTALL"),
            Pair("lil krystalll", "LIL KRYSTALL"),
            Pair("lil krystall", "LIL KRYSTALL"),
            Pair("серега пират", "Серега Пират"),
            Pair("serega pirat", "Серега Пират"),
            // Single-word aliases
            Pair("рокет", "ROCKET"),
            Pair("рокета", "ROCKET"),
            Pair("rocket", "ROCKET"),
            Pair("тейп", "Big Baby Tape"),
            Pair("тейпа", "Big Baby Tape"),
            Pair("буда", "OG Buda"),
            Pair("буды", "OG Buda"),
            Pair("будочка", "OG Buda"),
            Pair("кизару", "kizaru"),
            Pair("kizaru", "kizaru"),
            Pair("киза", "kizaru"),
            Pair("макан", "MACAN"),
            Pair("макана", "MACAN"),
            Pair("macan", "MACAN"),
            Pair("платина", "Платина"),
            Pair("platina", "Платина"),
            Pair("платины", "Платина"),
            Pair("скалли", "Scally Milano"),
            Pair("скали", "Scally Milano"),
            Pair("майот", "MAYOT"),
            Pair("майота", "MAYOT"),
            Pair("mayot", "MAYOT"),
            Pair("фараон", "PHARAOH"),
            Pair("pharaoh", "PHARAOH"),
            Pair("фара", "PHARAOH"),
            Pair("морген", "MORGENSHTERN"),
            Pair("моргенштерн", "MORGENSHTERN"),
            Pair("morgenshtern", "MORGENSHTERN"),
            Pair("шадоурейз", "shadowraze"),
            Pair("shadowraze", "shadowraze"),
            Pair("вайпер", "Viperr"),
            Pair("вайперр", "Viperr"),
            Pair("viperr", "Viperr"),
            Pair("9mice", "9mice"),
            Pair("9майс", "9mice"),
            Pair("депо", "Boulevard Depo"),
            Pair("фладд", "GONE.Fludd"),
            Pair("флад", "GONE.Fludd"),
            Pair("херонвотер", "Heronwater"),
            Pair("херронвотер", "Heronwater"),
            Pair("heronwater", "Heronwater"),
            Pair("херон", "Heronwater"),
            Pair("салуки", "SALUKI"),
            Pair("saluki", "SALUKI"),
            Pair("скриптонит", "Скриптонит"),
            Pair("скрип", "Скриптонит"),
            Pair("skryptonite", "Скриптонит"),
            Pair("токсис", "TOXI$"),
            Pair("токсик", "TOXI$"),
            Pair("токса", "TOXI$"),
            Pair("toxi$", "TOXI$"),
            Pair("toxis", "TOXI$"),
            Pair("ловв66", "LOVV66"),
            Pair("лов66", "LOVV66"),
            Pair("lovv66", "LOVV66"),
            Pair("сими", "Seemee"),
            Pair("seemee", "Seemee"),
            Pair("яникс", "Yanix"),
            Pair("yanix", "Yanix"),
            Pair("лайзер", "Lizer"),
            Pair("lizer", "Lizer"),
            Pair("флеш", "Flesh"),
            Pair("flesh", "Flesh"),
            Pair("обла", "Obladaet"),
            Pair("обладаэт", "Obladaet"),
            Pair("obladaet", "Obladaet"),
            Pair("маркул", "Markul"),
            Pair("markul", "Markul"),
            Pair("лсп", "ЛСП"),
            Pair("lsp", "ЛСП"),
            Pair("фейс", "Face"),
            Pair("face", "Face"),
            Pair("федук", "Feduk"),
            Pair("feduk", "Feduk"),
            Pair("элджей", "Элджей"),
            Pair("allj", "Элджей"),
            Pair("мияги", "Miyagi"),
            Pair("miyagi", "Miyagi"),
            Pair("эндшпиль", "Andy Panda"),
            Pair("хаски", "Хаски"),
            Pair("дора", "Дора"),
            Pair("дори", "Дора"),
            Pair("dora", "Дора"),
            Pair("инстасамка", "INSTASAMKA"),
            Pair("instasamka", "INSTASAMKA"),
            Pair("аник", "ANIKV"),
            Pair("anikv", "ANIKV"),
            Pair("аарне", "Aarne"),
            Pair("aarne", "Aarne"),
            Pair("карти", "Playboi Carti"),
            Pair("carti", "Playboi Carti"),
            Pair("йит", "Yeat"),
            Pair("ит", "Yeat"),
            Pair("yeat", "Yeat"),
            Pair("дрейк", "Drake"),
            Pair("drake", "Drake"),
            Pair("тревис", "Travis Scott"),
            Pair("travis", "Travis Scott"),
            Pair("канье", "Kanye West"),
            Pair("kanye", "Kanye West"),
            Pair("узи", "Lil Uzi Vert"),
            Pair("uzi", "Lil Uzi Vert"),
            Pair("фьючер", "Future"),
            Pair("future", "Future"),
            Pair("ганна", "Gunna"),
            Pair("gunna", "Gunna"),
            Pair("баста", "Баста"),
            Pair("basta", "Баста"),
            Pair("гуф", "Guf"),
            Pair("guf", "Guf"),
            Pair("айсгергерт", "ICEGERGERT"),
            Pair("icegergert", "ICEGERGERT"),
            Pair("пинки", "PINQ"),
            Pair("pinq", "PINQ"),
            Pair("курсед", "zxcursed"),
            Pair("zxcursed", "zxcursed"),
            Pair("акума", "akumaqqe"),
            Pair("akumaqqe", "akumaqqe")
        )

        fun sanitizeText(text: String?): String {
            if (text == null) return ""
            val trimmed = text.trim()
            if (trimmed.equals("null", ignoreCase = true) || trimmed.equals("undefined", ignoreCase = true)) {
                return ""
            }
            return trimmed
        }

        fun stripNoise(title: String): String {
            var s = sanitizeText(title)
            if (s.isBlank()) return ""

            // Strip noise in square brackets [slowed], [prod. ...], [remastered], etc.
            s = s.replace(Regex("\\[.*?(slowed|reverb|speed|sped|bass|prod|feat|ft|remix|edit|nightcore|official|hq|audio|video|clip|клип|текст|lyrics|leak|snippet|exclusive|loop|hour|bonus|demo|unreleased|live|acoustic|instrumental|remaster).*?\\]", RegexOption.IGNORE_CASE), "")
            // Strip noise in parentheses (slowed + reverb), (prod. by ...), etc.
            s = s.replace(Regex("\\(.*?(slowed|reverb|speed|sped|bass|prod|feat|ft|remix|edit|nightcore|official|hq|audio|video|clip|клип|текст|lyrics|leak|snippet|exclusive|loop|hour|bonus|demo|unreleased|live|acoustic|instrumental|remaster).*?\\)", RegexOption.IGNORE_CASE), "")
            // Strip trailing "prod. by ...", "ft. ...", "feat. ..."
            s = s.replace(Regex("\\b(prod\\.?|by|feat\\.?|ft\\.?)\\s+.*$", RegexOption.IGNORE_CASE), "")
            // Strip file extensions .mp3, .wav, .flac
            s = s.replace(Regex("\\.(mp3|wav|flac|m4a)$", RegexOption.IGNORE_CASE), "")
            // Strip quotes
            s = s.replace(Regex("[\"\'«»“”]"), "")

            return s.trim()
        }

        fun findKnownArtist(s: String?): String? {
            val clean = sanitizeText(s)
            if (clean.isBlank()) return null
            val lower = clean.lowercase()
            for (pair in ARTIST_ALIASES) {
                if (lower == pair.first || lower == pair.second.lowercase()) {
                    return pair.second
                }
            }
            return null
        }

        fun canonicalizeArtist(artist: String): String {
            return findKnownArtist(artist) ?: sanitizeText(artist)
        }

        data class ParsedTrack(val title: String, val artist: String)

        fun parseTrack(rawTitle: String, rawArtist: String = ""): ParsedTrack {
            val cleanRawArtist = sanitizeText(rawArtist)
            var cleanTitle = sanitizeText(rawTitle)

            if (cleanTitle.isBlank()) {
                val mappedOnlyArtist = findKnownArtist(cleanRawArtist) ?: cleanRawArtist
                return ParsedTrack(title = "", artist = mappedOnlyArtist)
            }

            // 1. Check dash separator: " - ", " — ", " – " (Could be Artist - Title OR Title - Artist!)
            val seps = listOf(" - ", " — ", " – ")
            for (sep in seps) {
                if (cleanTitle.contains(sep)) {
                    val parts = cleanTitle.split(sep, limit = 2)
                    val p1 = parts[0].trim()
                    val p2 = parts[1].trim()

                    // Case A: p2 is a known artist (e.g. "Drive Home - ROCKET" or "Drive Home - рокет")
                    val p2Artist = findKnownArtist(p2)
                    if (p2Artist != null && p1.isNotBlank()) {
                        return ParsedTrack(title = stripNoise(p1), artist = p2Artist)
                    }

                    // Case B: p1 is a known artist (e.g. "ROCKET - Drive Home" or "рокет - Drive Home")
                    val p1Artist = findKnownArtist(p1)
                    if (p1Artist != null && p2.isNotBlank()) {
                        return ParsedTrack(title = stripNoise(p2), artist = p1Artist)
                    }

                    // Case C: Standard dash: assume p1 is artist, p2 is title
                    if (p1.isNotBlank() && p2.isNotBlank()) {
                        val mapped = canonicalizeArtist(cleanArtistWord(p1))
                        return ParsedTrack(title = stripNoise(p2), artist = mapped)
                    }
                }
            }

            // 2. Check brackets at the end: "Drive Home (рокет)" or "Drive Home [рокет]" or "Drive Home (feat. рокет)"
            val bracketRegex = Regex("[\\(\\[](?:feat\\.?|ft\\.?|prod\\.?|by)?\\s*([a-zA-Z0-9а-яА-ЯёЁ\\s$]+)[\\)\\]]$", RegexOption.IGNORE_CASE)
            val bracketMatch = bracketRegex.find(cleanTitle)
            if (bracketMatch != null) {
                val inside = bracketMatch.groupValues[1].trim()
                val artistInBracket = findKnownArtist(inside)
                if (artistInBracket != null) {
                    val rest = cleanTitle.substring(0, bracketMatch.range.first).trim()
                    if (rest.isNotBlank()) {
                        return ParsedTrack(title = stripNoise(rest), artist = artistInBracket)
                    }
                }
            }

            // 3. Check "by <artist>" or "feat <artist>" at end: "Drive Home by рокет"
            val byRegex = Regex("\\s+(?:by|feat\\.?|ft\\.?)\\s+([a-zA-Z0-9а-яА-ЯёЁ\\s$]+)$", RegexOption.IGNORE_CASE)
            val byMatch = byRegex.find(cleanTitle)
            if (byMatch != null) {
                val afterBy = byMatch.groupValues[1].trim()
                val artistBy = findKnownArtist(afterBy)
                if (artistBy != null) {
                    val rest = cleanTitle.substring(0, byMatch.range.first).trim()
                    if (rest.isNotBlank()) {
                        return ParsedTrack(title = stripNoise(rest), artist = artistBy)
                    }
                }
            }

            // 4. Prefix check: "рокет drive home" or "тейп gimme the loot"
            val lower = cleanTitle.lowercase()
            for (pair in ARTIST_ALIASES) {
                val alias = pair.first
                val canonical = pair.second
                if (lower == alias) {
                    return ParsedTrack(title = stripNoise(cleanTitle), artist = canonical)
                }
                if (lower.startsWith("$alias ")) {
                    val rest = cleanTitle.substring(alias.length).trim()
                    if (rest.isNotBlank()) {
                        return ParsedTrack(title = stripNoise(rest), artist = canonical)
                    }
                }
            }

            // 5. If rawArtist was provided (e.g. SoundCloud uploader or Spotify/Yandex artist)
            if (cleanRawArtist.isNotBlank()) {
                val mappedArtist = canonicalizeArtist(cleanArtistWord(cleanRawArtist))
                return ParsedTrack(title = stripNoise(cleanTitle), artist = mappedArtist)
            }

            // 6. Default: title alone
            return ParsedTrack(title = stripNoise(cleanTitle), artist = "")
        }

        private fun cleanArtistWord(raw: String): String {
            val s = sanitizeText(raw)
            if (s.isBlank()) return ""
            return s.replace(Regex(",.*"), "")
                .replace(Regex("feat\\..*", RegexOption.IGNORE_CASE), "")
                .replace(Regex("ft\\..*", RegexOption.IGNORE_CASE), "")
                .trim()
        }
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

        val rawTitle = sanitizeText(extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        val rawArtist = sanitizeText(extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString())

        if (rawTitle.isBlank()) return

        val pkg = sbn.packageName
        val parsed = parseTrack(rawTitle, rawArtist)
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
        val title = sanitizeText(metadata.getString(MediaMetadata.METADATA_KEY_TITLE))
        val artist = sanitizeText(metadata.getString(MediaMetadata.METADATA_KEY_ARTIST))
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)

        if (duration > 0) {
            currentDurationMs = duration
        }

        if (title.isNotBlank()) {
            val parsed = parseTrack(title, artist)
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
            "com.vkontakte.android",
            "com.vk.music",
            "ru.yandex.music",
            "com.apple.android.music",
            "com.google.android.apps.youtube.music",
            "deezer.android.app"
        )
        return knownApps.any { packageName.startsWith(it) }
    }

    private fun isNotificationPlaying(notification: Notification): Boolean {
        val actions = notification.actions ?: return true
        for (action in actions) {
            val title = action.title?.toString()?.lowercase() ?: ""
            if (title.contains("pause") || title.contains("пауза")) {
                return true
            }
            if (title.contains("play") || title.contains("воспроизвести") || title.contains("играть")) {
                return false
            }
        }
        return true
    }
}
