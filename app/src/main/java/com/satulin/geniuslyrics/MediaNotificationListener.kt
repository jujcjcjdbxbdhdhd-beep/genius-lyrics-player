package com.satulin.geniuslyrics

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.content.ContextCompat
import kotlin.math.abs

class MediaNotificationListener : NotificationListenerService() {

    companion object {
        const val TAG = "MediaNotifListener"
        const val ACTION_TRACK_CHANGED = "com.satulin.geniuslyrics.ACTION_TRACK_CHANGED"
        const val ACTION_PLAYBACK_STATE = "com.satulin.geniuslyrics.ACTION_PLAYBACK_STATE"

        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_ARTIST = "extra_artist"
        const val EXTRA_RAW_TITLE = "extra_raw_title"
        const val EXTRA_ARTIST_FROM_TITLE = "extra_artist_from_title"
        const val EXTRA_PACKAGE = "extra_package"
        const val EXTRA_IS_PLAYING = "extra_is_playing"
        const val EXTRA_POSITION_MS = "extra_position_ms"
        const val EXTRA_DURATION_MS = "extra_duration_ms"
        const val EXTRA_UPDATED_AT = "extra_updated_at"
        const val EXTRA_FROM_SEEK = "extra_from_seek"
        const val EXTRA_PLAYBACK_SPEED = "extra_playback_speed"

        var currentTitle: String? = null
        var currentArtist: String? = null
        var currentRawTitle: String? = null
        var currentArtistFromTitle: Boolean = false
        var currentPackage: String? = null
        var isPlaying: Boolean = false
        var currentPositionMs: Long = 0L
        var currentDurationMs: Long = 0L
        var lastPositionUpdateTime: Long = 0L
        var currentPlaybackSpeed: Float = 1.0f

        @Volatile
        private var activeInstance: MediaNotificationListener? = null

        fun refreshActiveTrack() {
            activeInstance?.scanActiveMedia()
        }

        fun seekTo(positionMs: Long): Boolean = activeInstance?.performSeekTo(positionMs) ?: false

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
            Pair("лил драгхилл", "LILDRUGHILL"),
            Pair("лил морти", "LIL MORTY"),
            Pair("lil morty", "LIL MORTY"),
            Pair("fortuna 812", "fortuna 812"),
            Pair("фортуна 812", "fortuna 812"),
            Pair("темный принц", "темный принц"),
            Pair("слава кпсс", "Слава КПСС"),
            Pair("пошлая молли", "Пошлая Молли"),
            Pair("dead blonde", "DEAD BLONDE"),
            Pair("король и шут", "Король и Шут"),
            Pair("homixide gang", "Homixide Gang"),
            Pair("lil peep", "Lil Peep"),
            Pair("лил пип", "Lil Peep"),
            Pair("night lovell", "Night Lovell"),
            Pair("21 savage", "21 Savage"),
            Pair("young thug", "Young Thug"),
            Pair("lil baby", "Lil Baby"),
            Pair("a\$ap rocky", "A\$AP Rocky"),
            Pair("asap rocky", "A\$AP Rocky"),
            Pair("асап роки", "A\$AP Rocky"),
            Pair("the weeknd", "The Weeknd"),
            Pair("kendrick lamar", "Kendrick Lamar"),
            Pair("кендрик ламар", "Kendrick Lamar"),
            Pair("trippie redd", "Trippie Redd"),
            Pair("pop smoke", "Pop Smoke"),
            // Single-word aliases
            Pair("рокет", "ROCKET"),
            Pair("рокета", "ROCKET"),
            Pair("rocket", "ROCKET"),
            Pair("лилдрагхилл", "LILDRUGHILL"),
            Pair("lildrughill", "LILDRUGHILL"),
            Pair("фреско", "FRESCO"),
            Pair("fresco", "FRESCO"),
            Pair("глоки52", "GLOCKI52"),
            Pair("glocki52", "GLOCKI52"),
            Pair("бато", "BATO"),
            Pair("bato", "BATO"),
            Pair("янгвей", "YUNGWAY"),
            Pair("yungway", "YUNGWAY"),
            Pair("эляплаг", "elyaplugg!"),
            Pair("elyaplugg!", "elyaplugg!"),
            Pair("elyaplugg", "elyaplugg!"),
            Pair("фортуна", "fortuna 812"),
            Pair("мадкид", "madk1d"),
            Pair("мэдкид", "madk1d"),
            Pair("madk1d", "madk1d"),
            Pair("пирокинезис", "pyrokinesis"),
            Pair("pyrokinesis", "pyrokinesis"),
            Pair("оксимирон", "Oxxxymiron"),
            Pair("окси", "Oxxxymiron"),
            Pair("oxxxymiron", "Oxxxymiron"),
            Pair("кишлак", "Кишлак"),
            Pair("kishlak", "Кишлак"),
            Pair("лида", "Lida"),
            Pair("lida", "Lida"),
            Pair("cmh", "CMH"),
            Pair("dk", "DK"),
            Pair("мазеллов", "MZLFF"),
            Pair("mzlff", "MZLFF"),
            Pair("gspd", "GSPD"),
            Pair("нервы", "Нервы"),
            Pair("кино", "Кино"),
            Pair("цой", "Кино"),
            Pair("киш", "Король и Шут"),
            Pair("xxxtentacion", "XXXTENTACION"),
            Pair("\$uicideboy\$", "\$uicideboy\$"),
            Pair("суисайдбойс", "\$uicideboy\$"),
            Pair("suicideboys", "\$uicideboy\$"),
            Pair("боунс", "Bones"),
            Pair("bones", "Bones"),
            Pair("гостмейн", "Ghostemane"),
            Pair("ghostemane", "Ghostemane"),
            Pair("викенд", "The Weeknd"),
            Pair("эминем", "Eminem"),
            Pair("eminem", "Eminem"),
            Pair("osamason", "OsamaSon"),
            Pair("nettspend", "Nettspend"),
            Pair("summrs", "Summrs"),
            Pair("kankan", "Kankan"),
            Pair("sofaygo", "SoFaygo"),
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
            Pair("токсис", "TOXI\$"),
            Pair("токсик", "TOXI\$"),
            Pair("токса", "TOXI\$"),
            Pair("toxi\$", "TOXI\$"),
            Pair("toxis", "TOXI\$"),
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

        private fun stripEmojis(input: String): String {
            val sb = StringBuilder(input.length)
            var i = 0
            while (i < input.length) {
                val cp = Character.codePointAt(input, i)
                val charCount = Character.charCount(cp)
                val isEmoji = (cp in 0x1F300..0x1FAFF) ||
                    (cp in 0x2600..0x27BF) ||
                    cp == 0xFE0F ||
                    cp == 0x200D
                if (isEmoji) {
                    sb.append(' ')
                } else {
                    sb.append(input, i, i + charCount)
                }
                i += charCount
            }
            return sb.toString()
        }

        fun stripNoise(title: String): String {
            var s = sanitizeText(title)
            if (s.isBlank()) return ""

            // Strip decorative emojis & symbols commonly spammed on SoundCloud
            s = stripEmojis(s)
            // Strip noise in square / CJK brackets [slowed], 【SLOWED】, [prod. ...], [слив], etc.
            s = s.replace(Regex("[\\[【『][^\\]】』]*?(?:slowed|reverb|speed|sped\\s*up|sped|bass\\s*boosted|bass|boosted|prod|feat|ft|remix|edit|nightcore|official|hq|audio|video|clip|клип|текст|lyrics?|leak|snippet|exclusive|loop|hour|bonus|demo|unreleased|live|acoustic|instrumental|remaster|explicit|clean|deluxe|tik\\s*tok|new|слив|сниппет|отрывок|полная|ремикс|замедленная|ускоренная|оригинал|басс|премьера|новинка|visualizer|dir\\.|p\\.|8d)[^\\]】』]*?[\\]】』]", RegexOption.IGNORE_CASE), "")
            // Strip noise in parentheses (slowed + reverb), (prod. by ...), (Explicit), (слив), etc.
            s = s.replace(Regex("\\([^)]*?(?:slowed|reverb|speed|sped\\s*up|sped|bass\\s*boosted|bass|boosted|prod|feat|ft|remix|edit|nightcore|official|hq|audio|video|clip|клип|текст|lyrics?|leak|snippet|exclusive|loop|hour|bonus|demo|unreleased|live|acoustic|instrumental|remaster|explicit|clean|deluxe|tik\\s*tok|new|слив|сниппет|отрывок|полная|ремикс|замедленная|ускоренная|оригинал|басс|премьера|новинка|visualizer|dir\\.|p\\.|8d)[^)]*?\\)", RegexOption.IGNORE_CASE), "")
            // Strip standalone 4-digit year tags like [2024] or (2023) without touching phone numbers like (952)
            s = s.replace(Regex("[\\[\\(]\\s*(?:19|20)\\d{2}\\s*[\\]\\)]"), "")
            // Strip trailing "prod. by ...", "produced by ...", "beat by ...", "ft. ...", "feat. ..."
            // NOTE: intentionally does NOT strip bare "by" so songs like "Stand By Me" or "Drive By" stay intact!
            s = s.replace(Regex("\\b(?:prod\\.?\\s*by|produced\\s+by|prod\\.|beat\\s+by|feat\\.|ft\\.)\\s+.*$", RegexOption.IGNORE_CASE), "")
            // Strip *NEW* / ~LEAK~ tags
            s = s.replace(Regex("[*~]\\s*(?:new|leak|exclusive|snippet|slowed|hq|слив)\\s*[*~]", RegexOption.IGNORE_CASE), "")
            // Strip file extensions .mp3, .wav, .flac
            s = s.replace(Regex("\\.(mp3|wav|flac|m4a)$", RegexOption.IGNORE_CASE), "")
            // Strip quotes
            s = s.replace(Regex("[\"'«»“”]"), "")
            // Collapse spaces
            s = s.replace(Regex("\\s+"), " ")

            return s.trim()
        }

        fun isLikelyFakeUploader(artist: String): Boolean {
            val clean = sanitizeText(artist)
            if (clean.isBlank()) return false
            if (findKnownArtist(clean) != null) return false
            val lower = clean.lowercase().replace('ё', 'е')
            val fakeKeywords = listOf(
                "sliv", "слив", "archive", "архив", "reupload", "slowed", "reverb",
                "nightcore", "beats", "channel", "музыка", "треки", "soundcloud",
                "audio_", "music_", "mp3", "prod_"
            )
            if (fakeKeywords.any { lower.contains(it) }) return true
            if (lower.contains('_') && lower.any { it.isDigit() }) return true
            return false
        }

        fun buildGeniusSearchQuery(
            artist: String,
            title: String,
            rawTitle: String = "",
            artistFromTitle: Boolean = false
        ): String {
            val cleanArtist = canonicalizeArtist(
                sanitizeText(artist)
                    .replace(Regex("\\b(?:feat\\.?|ft\\.)\\b.*$", RegexOption.IGNORE_CASE), "")
                    .trim()
            )
            val cleanTitle = stripNoise(title).ifBlank { sanitizeText(title) }
            val cleanRaw = stripNoise(rawTitle).ifBlank { sanitizeText(rawTitle) }

            if (cleanArtist.isNotBlank() && cleanTitle.isNotBlank()) {
                return if (artistFromTitle || !isLikelyFakeUploader(cleanArtist)) {
                    "$cleanArtist $cleanTitle"
                } else {
                    cleanTitle
                }
            }
            if (cleanTitle.isNotBlank()) return cleanTitle
            return cleanRaw
        }

        fun findKnownArtist(s: String?): String? {
            val clean = sanitizeText(s)
            if (clean.isBlank()) return null
            val lower = clean.lowercase().replace('ё', 'е')
            for (pair in ARTIST_ALIASES) {
                if (lower == pair.first.replace('ё', 'е') || lower == pair.second.lowercase().replace('ё', 'е')) {
                    return pair.second
                }
            }
            return null
        }

        fun canonicalizeArtist(artist: String): String {
            return findKnownArtist(artist) ?: sanitizeText(artist)
        }

        data class ParsedTrack(
            val title: String,
            val artist: String,
            val artistFromTitle: Boolean = false
        )

        fun parseTrack(rawTitle: String, rawArtist: String = ""): ParsedTrack {
            val cleanRawArtist = sanitizeText(rawArtist)
            // Remove leading/trailing emojis before splitting so "🔥 рокет drive home 💀" matches aliases cleanly
            val preCleaned = stripEmojis(sanitizeText(rawTitle))
                .replace(Regex("\\s+"), " ")
                .trim()

            if (preCleaned.isBlank()) {
                val mappedOnlyArtist = findKnownArtist(cleanRawArtist) ?: cleanRawArtist
                return ParsedTrack(title = "", artist = mappedOnlyArtist, artistFromTitle = false)
            }

            // 1. Check separators: " - ", " — ", " – ", " ― ", " | ", " // "
            val seps = listOf(" - ", " — ", " – ", " ― ", " | ", " // ")
            for (sep in seps) {
                if (preCleaned.contains(sep)) {
                    val parts = preCleaned.split(sep, limit = 2)
                    val p1 = stripNoise(parts[0])
                    val p2 = stripNoise(parts[1])

                    // Case A: p2 is a known artist (e.g. "Drive Home - ROCKET" or "Drive Home - рокет")
                    val p2Artist = findKnownArtist(p2)
                    if (p2Artist != null && p1.isNotBlank()) {
                        return ParsedTrack(title = p1, artist = p2Artist, artistFromTitle = true)
                    }

                    // Case B: p1 is a known artist (e.g. "ROCKET - Drive Home" or "рокет - Drive Home")
                    val p1Artist = findKnownArtist(p1)
                    if (p1Artist != null && p2.isNotBlank()) {
                        return ParsedTrack(title = p2, artist = p1Artist, artistFromTitle = true)
                    }

                    // Case C: Standard dash: assume p1 is artist, p2 is title
                    if (p1.isNotBlank() && p2.isNotBlank()) {
                        val mapped = canonicalizeArtist(cleanArtistWord(p1))
                        return ParsedTrack(title = p2, artist = mapped, artistFromTitle = true)
                    }
                }
            }

            // 1b. Tight dash without spaces (e.g. "ROCKET-Drive Home") when one side is a known artist
            if (preCleaned.contains("-") && !preCleaned.contains(" - ")) {
                val parts = preCleaned.split("-", limit = 2)
                val p1 = stripNoise(parts[0])
                val p2 = stripNoise(parts[1])
                val p1Artist = findKnownArtist(p1)
                if (p1Artist != null && p2.isNotBlank()) {
                    return ParsedTrack(title = p2, artist = p1Artist, artistFromTitle = true)
                }
                val p2Artist = findKnownArtist(p2)
                if (p2Artist != null && p1.isNotBlank()) {
                    return ParsedTrack(title = p1, artist = p2Artist, artistFromTitle = true)
                }
            }

            // 2. Check brackets at the end: "Drive Home (рокет)" or "Drive Home [рокет]" or "Drive Home (feat. рокет)"
            val bracketRegex = Regex("[\\(\\[](?:feat\\.?|ft\\.?|prod\\.?|by)?\\s*([a-zA-Z0-9а-яА-ЯёЁ\\s$]+)[\\)\\]]$", RegexOption.IGNORE_CASE)
            val bracketMatch = bracketRegex.find(preCleaned)
            if (bracketMatch != null) {
                val inside = bracketMatch.groupValues[1].trim()
                val artistInBracket = findKnownArtist(inside)
                if (artistInBracket != null) {
                    val rest = preCleaned.substring(0, bracketMatch.range.first).trim()
                    if (rest.isNotBlank()) {
                        return ParsedTrack(title = stripNoise(rest), artist = artistInBracket, artistFromTitle = true)
                    }
                }
            }

            // 3. Check "by <artist>" or "feat <artist>" at end: "Drive Home by рокет"
            val byRegex = Regex("\\s+(?:by|feat\\.?|ft\\.?)\\s+([a-zA-Z0-9а-яА-ЯёЁ\\s$]+)$", RegexOption.IGNORE_CASE)
            val byMatch = byRegex.find(preCleaned)
            if (byMatch != null) {
                val afterBy = byMatch.groupValues[1].trim()
                val artistBy = findKnownArtist(afterBy)
                if (artistBy != null) {
                    val rest = preCleaned.substring(0, byMatch.range.first).trim()
                    if (rest.isNotBlank()) {
                        return ParsedTrack(title = stripNoise(rest), artist = artistBy, artistFromTitle = true)
                    }
                }
            }

            // 4. Prefix check: "рокет drive home" or "тейп gimme the loot"
            val strippedForPrefix = stripNoise(preCleaned)
            val lower = strippedForPrefix.lowercase().replace('ё', 'е')
            for (pair in ARTIST_ALIASES) {
                val alias = pair.first.replace('ё', 'е')
                val canonical = pair.second
                if (lower == alias) {
                    return ParsedTrack(title = strippedForPrefix, artist = canonical, artistFromTitle = true)
                }
                if (lower.startsWith("$alias ")) {
                    val rest = strippedForPrefix.substring(alias.length).trim()
                    if (rest.isNotBlank()) {
                        return ParsedTrack(title = stripNoise(rest), artist = canonical, artistFromTitle = true)
                    }
                }
            }

            // 5. If rawArtist was provided (e.g. SoundCloud uploader or Spotify/Yandex artist)
            if (cleanRawArtist.isNotBlank()) {
                val cleanedArt = cleanArtistWord(cleanRawArtist)
                val mappedArtist = canonicalizeArtist(cleanedArt)
                return ParsedTrack(title = strippedForPrefix, artist = mappedArtist, artistFromTitle = false)
            }

            // 6. Default: title alone
            return ParsedTrack(title = strippedForPrefix, artist = "", artistFromTitle = false)
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
    private var activeSessionToken: MediaSession.Token? = null
    private var mediaSessionManager: MediaSessionManager? = null
    private var seekGuardUntilMs: Long = 0L
    private var pendingSeekTargetMs: Long = -1L
    private val mainHandler = Handler(Looper.getMainLooper())

    private val syncPollRunnable = object : Runnable {
        override fun run() {
            pollActiveControllerSync()
            if (activeMediaController != null) {
                mainHandler.postDelayed(this, 1000L)
            }
        }
    }

    private val mediaControllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            state?.let { updatePlaybackState(it) }
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            metadata?.let { updateMetadata(it, resetPositionOnTrackChange = true) }
        }
    }

    private val sessionsChangedListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        handleActiveSessions(controllers)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        activeInstance = this
        try {
            val mgr = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            mediaSessionManager = mgr
            mgr?.addOnActiveSessionsChangedListener(
                sessionsChangedListener,
                ComponentName(this, MediaNotificationListener::class.java)
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "No security access to MediaSessionManager: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "MediaSessionManager init error: ${e.message}")
        }
        scanActiveMedia()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        cleanupMediaListeners()
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanupMediaListeners()
    }

    private fun cleanupMediaListeners() {
        activeInstance = null
        mainHandler.removeCallbacks(syncPollRunnable)
        try {
            activeMediaController?.unregisterCallback(mediaControllerCallback)
        } catch (_: Exception) {}
        activeMediaController = null
        activeSessionToken = null
        try {
            mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        } catch (_: Exception) {}
        mediaSessionManager = null
    }

    private fun startControllerPolling() {
        mainHandler.removeCallbacks(syncPollRunnable)
        mainHandler.postDelayed(syncPollRunnable, 1000L)
    }

    /**
     * Periodically inspects the bound MediaController's live PlaybackState & MediaMetadata.
     * Catches silent stream buffering, post-start duration population, or drift without waiting
     * for an explicit notification update.
     */
    private fun pollActiveControllerSync() {
        val controller = activeMediaController ?: return
        try {
            val meta = controller.metadata
            var durChanged = false
            if (meta != null) {
                val dur = meta.getLong(MediaMetadata.METADATA_KEY_DURATION)
                if (dur > 0L && dur != currentDurationMs) {
                    currentDurationMs = dur
                    durChanged = true
                }
                val metaTitle = sanitizeText(meta.getString(MediaMetadata.METADATA_KEY_TITLE))
                if (metaTitle.isNotBlank() && metaTitle != currentRawTitle) {
                    updateMetadata(meta, resetPositionOnTrackChange = false)
                }
            }

            val state = controller.playbackState ?: return
            val now = SystemClock.elapsedRealtime()
            if (now < seekGuardUntilMs && pendingSeekTargetMs >= 0L) {
                return
            }

            val (livePlaying, livePos, liveSpeed) = extractNormalizedPlayback(state, now)
            val expectedOurPos = if (isPlaying && lastPositionUpdateTime > 0L) {
                val elapsed = (now - lastPositionUpdateTime).coerceAtLeast(0L)
                currentPositionMs + (elapsed * currentPlaybackSpeed).toLong()
            } else {
                currentPositionMs
            }

            val driftMs = abs(livePos - expectedOurPos)
            if (livePlaying != isPlaying || durChanged || driftMs > 320L || abs(liveSpeed - currentPlaybackSpeed) > 0.02f) {
                isPlaying = livePlaying
                currentPositionMs = livePos
                lastPositionUpdateTime = now
                currentPlaybackSpeed = liveSpeed

                val intent = Intent(ACTION_PLAYBACK_STATE).apply {
                    putExtra(EXTRA_IS_PLAYING, livePlaying)
                    putExtra(EXTRA_POSITION_MS, livePos)
                    putExtra(EXTRA_DURATION_MS, currentDurationMs)
                    putExtra(EXTRA_UPDATED_AT, now)
                    putExtra(EXTRA_PLAYBACK_SPEED, liveSpeed)
                    putExtra(EXTRA_FROM_SEEK, false)
                    setPackage(applicationContext.packageName)
                }
                sendBroadcast(intent)
            }
        } catch (_: Exception) {}
    }

    private fun extractNormalizedPlayback(state: PlaybackState, nowElapsedMs: Long): Triple<Boolean, Long, Float> {
        val stateCode = state.state
        val rawSpeed = state.playbackSpeed
        val activelyPlaying = (stateCode == PlaybackState.STATE_PLAYING || stateCode == PlaybackState.STATE_FAST_FORWARDING) &&
            (rawSpeed > 0.01f || rawSpeed == 0f)
        val effectiveSpeed = if (activelyPlaying) {
            if (rawSpeed in 0.25f..4.0f) rawSpeed else 1.0f
        } else {
            0.0f
        }

        val rawPos = state.position.coerceAtLeast(0L)
        val rawUpdated = state.lastPositionUpdateTime

        // Validate that lastPositionUpdateTime is in the SystemClock.elapsedRealtime() domain
        val extrapolatedPos = if (activelyPlaying && rawUpdated in 1L..nowElapsedMs && (nowElapsedMs - rawUpdated) <= 30_000L) {
            rawPos + ((nowElapsedMs - rawUpdated) * effectiveSpeed).toLong()
        } else {
            rawPos
        }
        val clampedPos = if (currentDurationMs > 0L) {
            extrapolatedPos.coerceIn(0L, currentDurationMs)
        } else {
            extrapolatedPos.coerceAtLeast(0L)
        }
        return Triple(activelyPlaying, clampedPos, if (activelyPlaying) effectiveSpeed else 1.0f)
    }

    fun scanActiveMedia() {
        try {
            val mgr = mediaSessionManager ?: (getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager)
            if (mgr != null) {
                val controllers = mgr.getActiveSessions(ComponentName(this, MediaNotificationListener::class.java))
                if (handleActiveSessions(controllers)) {
                    return
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot query active sessions: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "scanActiveMedia sessions error: ${e.message}")
        }

        try {
            val notifs = activeNotifications ?: return
            val ordered = notifs.sortedByDescending { isNotificationPlaying(it.notification) }
            for (sbn in ordered) {
                if (sbn.notification.isMediaNotification() || isKnownMusicApp(sbn.packageName)) {
                    inspectNotification(sbn)
                    if (!currentTitle.isNullOrBlank()) break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "scanActiveMedia notifications error: ${e.message}")
        }
    }

    private fun handleActiveSessions(controllers: List<MediaController>?): Boolean {
        if (controllers.isNullOrEmpty()) return false
        val preferred = controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers.firstOrNull {
            val t = sanitizeText(it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE))
            t.isNotBlank()
        } ?: return false

        bindController(preferred, preferred.packageName)
        return true
    }

    private fun bindController(controller: MediaController, pkg: String?): Boolean {
        try {
            val token = controller.sessionToken
            if (token != activeSessionToken || activeMediaController == null) {
                try {
                    activeMediaController?.unregisterCallback(mediaControllerCallback)
                } catch (_: Exception) {}
                controller.registerCallback(mediaControllerCallback)
                activeMediaController = controller
                activeSessionToken = token
            }
            if (!pkg.isNullOrBlank()) {
                currentPackage = pkg
            }
            startControllerPolling()
            val meta = controller.metadata
            if (meta != null) {
                val dur = meta.getLong(MediaMetadata.METADATA_KEY_DURATION)
                if (dur > 0L) {
                    currentDurationMs = dur
                }
            }
            controller.playbackState?.let { updatePlaybackState(it) }
            if (meta != null) {
                val metaTitle = sanitizeText(meta.getString(MediaMetadata.METADATA_KEY_TITLE))
                if (metaTitle.isNotBlank()) {
                    updateMetadata(meta, resetPositionOnTrackChange = false)
                    return true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not bind MediaController: ${e.message}")
        }
        return false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn?.let { inspectNotification(it) }
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

        val playing = isNotificationPlaying(notification)
        // Do not let a paused/background notification from another app hijack a currently playing session
        val activePlaying = activeMediaController?.playbackState?.state == PlaybackState.STATE_PLAYING
        val activePkg = currentPackage ?: activeMediaController?.packageName
        if (activePlaying && !playing && !activePkg.isNullOrBlank() && sbn.packageName != activePkg) {
            return
        }

        if (mediaSessionToken != null) {
            if (mediaSessionToken != activeSessionToken || activeMediaController == null) {
                val handled = bindController(MediaController(this, mediaSessionToken), sbn.packageName)
                if (handled) return
            } else {
                val controller = activeMediaController
                val meta = controller?.metadata
                if (meta != null && sanitizeText(meta.getString(MediaMetadata.METADATA_KEY_TITLE)).isNotBlank()) {
                    controller.playbackState?.let { updatePlaybackState(it) }
                    updateMetadata(meta, resetPositionOnTrackChange = false)
                    return
                }
            }
        }

        val rawTitle = sanitizeText(extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        val rawArtist = sanitizeText(extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString())

        if (rawTitle.isBlank()) return

        val pkg = sbn.packageName
        val parsed = parseTrack(rawTitle, rawArtist)
        val trackChanged = parsed.title != currentTitle || parsed.artist != currentArtist

        if (!trackChanged && playing == isPlaying) {
            return
        }

        if (trackChanged) {
            currentPositionMs = 0L
            currentDurationMs = 0L
            lastPositionUpdateTime = SystemClock.elapsedRealtime()
            currentPlaybackSpeed = 1.0f
            pendingSeekTargetMs = -1L
            seekGuardUntilMs = 0L
            AppMotionPrefs.resetSyncOffset()
        }

        currentTitle = parsed.title
        currentArtist = parsed.artist
        currentRawTitle = rawTitle
        currentArtistFromTitle = parsed.artistFromTitle
        currentPackage = pkg
        isPlaying = playing

        Log.d(TAG, "Detected: title='${parsed.title}', artist='${parsed.artist}', raw='$rawTitle' (Pkg: $pkg)")

        val intent = Intent(ACTION_TRACK_CHANGED).apply {
            putExtra(EXTRA_TITLE, parsed.title)
            putExtra(EXTRA_ARTIST, parsed.artist)
            putExtra(EXTRA_RAW_TITLE, rawTitle)
            putExtra(EXTRA_ARTIST_FROM_TITLE, parsed.artistFromTitle)
            putExtra(EXTRA_PACKAGE, pkg)
            putExtra(EXTRA_IS_PLAYING, playing)
            putExtra(EXTRA_POSITION_MS, currentPositionMs)
            putExtra(EXTRA_DURATION_MS, currentDurationMs)
            putExtra(EXTRA_UPDATED_AT, SystemClock.elapsedRealtime())
            putExtra(EXTRA_PLAYBACK_SPEED, currentPlaybackSpeed)
            setPackage(applicationContext.packageName)
        }
        sendBroadcast(intent)
        if (playing) {
            maybeAutoStartIsland()
        }
    }

    private fun updateMetadata(metadata: MediaMetadata, resetPositionOnTrackChange: Boolean = false) {
        val title = sanitizeText(metadata.getString(MediaMetadata.METADATA_KEY_TITLE))
        val artist = sanitizeText(metadata.getString(MediaMetadata.METADATA_KEY_ARTIST))
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)

        val prevDur = currentDurationMs
        if (duration > 0) {
            currentDurationMs = duration
        }

        if (title.isNotBlank()) {
            val parsed = parseTrack(title, artist)
            val trackChanged = parsed.title != currentTitle || parsed.artist != currentArtist
            if (trackChanged) {
                pendingSeekTargetMs = -1L
                seekGuardUntilMs = 0L
                AppMotionPrefs.resetSyncOffset()
                val liveState = activeMediaController?.playbackState
                val now = SystemClock.elapsedRealtime()
                if (liveState != null) {
                    val (_, livePos, liveSpd) = extractNormalizedPlayback(liveState, now)
                    currentPositionMs = if (resetPositionOnTrackChange && livePos > 5000L && duration > 0L && abs(livePos - duration) < 3000L) {
                        0L
                    } else {
                        livePos
                    }
                    lastPositionUpdateTime = now
                    currentPlaybackSpeed = liveSpd
                } else if (resetPositionOnTrackChange) {
                    currentPositionMs = 0L
                    lastPositionUpdateTime = now
                    currentPlaybackSpeed = 1.0f
                }
                if (duration <= 0) {
                    currentDurationMs = 0L
                }
                currentTitle = parsed.title
                currentArtist = parsed.artist
                currentRawTitle = title
                currentArtistFromTitle = parsed.artistFromTitle

                val intent = Intent(ACTION_TRACK_CHANGED).apply {
                    putExtra(EXTRA_TITLE, parsed.title)
                    putExtra(EXTRA_ARTIST, parsed.artist)
                    putExtra(EXTRA_RAW_TITLE, title)
                    putExtra(EXTRA_ARTIST_FROM_TITLE, parsed.artistFromTitle)
                    putExtra(EXTRA_PACKAGE, currentPackage ?: "")
                    putExtra(EXTRA_IS_PLAYING, isPlaying)
                    putExtra(EXTRA_POSITION_MS, currentPositionMs)
                    putExtra(EXTRA_DURATION_MS, currentDurationMs)
                    putExtra(EXTRA_UPDATED_AT, SystemClock.elapsedRealtime())
                    putExtra(EXTRA_PLAYBACK_SPEED, currentPlaybackSpeed)
                    setPackage(applicationContext.packageName)
                }
                sendBroadcast(intent)
                if (isPlaying) {
                    maybeAutoStartIsland()
                }
            } else if (duration > 0L && duration != prevDur) {
                val now = SystemClock.elapsedRealtime()
                val intent = Intent(ACTION_PLAYBACK_STATE).apply {
                    putExtra(EXTRA_IS_PLAYING, isPlaying)
                    putExtra(EXTRA_POSITION_MS, currentPositionMs)
                    putExtra(EXTRA_DURATION_MS, currentDurationMs)
                    putExtra(EXTRA_UPDATED_AT, lastPositionUpdateTime.takeIf { it > 0L } ?: now)
                    putExtra(EXTRA_PLAYBACK_SPEED, currentPlaybackSpeed)
                    putExtra(EXTRA_FROM_SEEK, false)
                    setPackage(applicationContext.packageName)
                }
                sendBroadcast(intent)
            }
        }
    }

    private fun performSeekTo(positionMs: Long): Boolean {
        if (activeMediaController == null) {
            scanActiveMedia()
        }
        val controller = activeMediaController ?: return false
        if (currentDurationMs <= 0L) {
            val metaDur = try {
                controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            } catch (_: Exception) {
                0L
            }
            if (metaDur > 0L) {
                currentDurationMs = metaDur
            }
        }
        val clamped = GeniusLyricsFetcher.computeSeekTargetMs(positionMs, 0L, currentDurationMs)
        return try {
            controller.transportControls.seekTo(clamped)
            val now = SystemClock.elapsedRealtime()
            currentPositionMs = clamped
            lastPositionUpdateTime = now
            pendingSeekTargetMs = clamped
            seekGuardUntilMs = now + 650L

            val intent = Intent(ACTION_PLAYBACK_STATE).apply {
                putExtra(EXTRA_IS_PLAYING, isPlaying)
                putExtra(EXTRA_POSITION_MS, clamped)
                putExtra(EXTRA_DURATION_MS, currentDurationMs)
                putExtra(EXTRA_UPDATED_AT, now)
                putExtra(EXTRA_PLAYBACK_SPEED, currentPlaybackSpeed)
                putExtra(EXTRA_FROM_SEEK, true)
                setPackage(applicationContext.packageName)
            }
            sendBroadcast(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Seek failed: ${e.message}")
            false
        }
    }

    private fun updatePlaybackState(state: PlaybackState) {
        val now = SystemClock.elapsedRealtime()
        val (playing, position, speed) = extractNormalizedPlayback(state, now)

        if (now < seekGuardUntilMs && pendingSeekTargetMs >= 0L) {
            if (abs(position - pendingSeekTargetMs) > 2500L) {
                // Ignore stale pre-seek position while player buffers seek, only track play/pause change
                isPlaying = playing
                currentPlaybackSpeed = speed
                return
            } else {
                pendingSeekTargetMs = -1L
                seekGuardUntilMs = 0L
            }
        }

        if (currentDurationMs <= 0L) {
            val metaDur = try {
                activeMediaController?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            } catch (_: Exception) {
                0L
            }
            if (metaDur > 0L) {
                currentDurationMs = metaDur
            }
        }

        isPlaying = playing
        currentPositionMs = position
        lastPositionUpdateTime = now
        currentPlaybackSpeed = speed

        val intent = Intent(ACTION_PLAYBACK_STATE).apply {
            putExtra(EXTRA_IS_PLAYING, playing)
            putExtra(EXTRA_POSITION_MS, position)
            putExtra(EXTRA_DURATION_MS, currentDurationMs)
            putExtra(EXTRA_UPDATED_AT, now)
            putExtra(EXTRA_PLAYBACK_SPEED, speed)
            putExtra(EXTRA_FROM_SEEK, false)
            setPackage(applicationContext.packageName)
        }
        sendBroadcast(intent)
        if (playing) {
            maybeAutoStartIsland()
        }
    }

    private fun maybeAutoStartIsland() {
        try {
            if (FloatingLyricsService.isIslandEnabled(applicationContext) &&
                !FloatingLyricsService.isRunning &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(applicationContext))
            ) {
                val svc = Intent(applicationContext, FloatingLyricsService::class.java)
                ContextCompat.startForegroundService(applicationContext, svc)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Auto-start island skipped: ${e.message}")
        }
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
