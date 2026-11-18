package com.satulin.geniuslyrics

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class LyricLine(
    val timeMs: Long = -1L,
    val text: String
)

data class LyricsResult(
    val title: String,
    val artist: String,
    val lines: List<LyricLine>,
    val plainLyrics: String,
    val sourceUrl: String = "",
    val isSynced: Boolean = false,
    val sourceName: String = "Spotify"
)

object GeniusLyricsFetcher {

    private const val TAG = "GeniusLyricsFetcher"
    private const val CACHE_VERSION = "v3_"
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val memoryCache = mutableMapOf<String, LyricsResult>()

    private val RU_TO_LAT = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e",
        'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m",
        'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u",
        'ф' to "f", 'х' to "h", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "sch",
        'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya"
    )

    private val TITLE_WORD_MAP = mapOf(
        "асфальт" to "asphalt",
        "таймлесс" to "timeless",
        "инфинити" to "infinity",
        "кадиллак" to "cadillac",
        "хайролл" to "high roll",
        "хайрол" to "high roll",
        "лут" to "loot",
        "треп" to "trap",
        "бэби" to "baby",
        "бейби" to "baby",
        "лав" to "luv",
        "стэп" to "step",
        "степ" to "step",
        "кальма" to "calmer",
        "calma" to "calmer",
        "5 звезд" to "5ive",
        "5 звёзд" to "5ive",
        "пять звезд" to "5ive",
        "5ive" to "5ive",
        "динамит" to "dynamite"
    )

    data class ResolvedTrack(
        val artist: String,
        val allArtists: List<String>,
        val title: String
    )

    fun JSONObject.optCleanString(name: String): String {
        if (isNull(name)) return ""
        val str = optString(name, "").trim()
        if (str.equals("null", ignoreCase = true) || str.equals("undefined", ignoreCase = true)) {
            return ""
        }
        return str
    }

    fun transliterateRuToLat(s: String): String {
        val sb = StringBuilder(s.length * 2)
        for (ch in s.lowercase()) {
            sb.append(RU_TO_LAT[ch] ?: ch)
        }
        return sb.toString()
    }

    private fun normalizeForMatch(s: String): String {
        return s.lowercase()
            .replace('ё', 'е')
            .replace('$', 's')
            .replace('@', 'a')
            .replace(Regex("[^a-z0-9а-я]"), "")
            .trim()
    }

    private fun phoneticKey(s: String): String {
        return transliterateRuToLat(s)
            .replace('$', 's')
            .replace('@', 'a')
            .replace("ph", "f")
            .replace('c', 'k')
            .replace('y', 'i')
            .replace(Regex("[^a-z0-9]"), "")
            .replace(Regex("(.)\\1+"), "$1")
    }

    private fun cleanTitleForComparison(t: String): String {
        var s = MediaNotificationListener.stripNoise(t)
        if (s.contains(" - ")) {
            val parts = s.split(" - ")
            s = parts.last().trim()
        }
        return s
    }

    fun isTitleMatch(expectedTitle: String, candidateTitle: String): Boolean {
        val cleanExp = cleanTitleForComparison(expectedTitle)
        val cleanCand = cleanTitleForComparison(candidateTitle)
        val exp = normalizeForMatch(cleanExp)
        val cand = normalizeForMatch(cleanCand)
        if (exp.isBlank() || cand.isBlank()) return false
        if (exp == cand) return true
        val minLen = minOf(exp.length, cand.length)
        if (minLen >= 3 && (cand.contains(exp) || exp.contains(cand))) return true

        val pExp = phoneticKey(cleanExp)
        val pCand = phoneticKey(cleanCand)
        if (pExp.isNotBlank() && pCand.isNotBlank()) {
            if (pExp == pCand) return true
            val pMin = minOf(pExp.length, pCand.length)
            if (pMin >= 4 && (pCand.contains(pExp) || pExp.contains(pCand))) return true
        }
        return false
    }

    fun isArtistMatch(expectedArtist: String, candidateArtist: String): Boolean {
        if (expectedArtist.isBlank()) return true
        val canonExp = MediaNotificationListener.canonicalizeArtist(expectedArtist)
        val canonCand = MediaNotificationListener.canonicalizeArtist(candidateArtist)
        val exp = normalizeForMatch(canonExp)
        val cand = normalizeForMatch(canonCand)
        if (exp.isBlank() || cand.isBlank()) return false
        if (exp == cand) return true
        val minLen = minOf(exp.length, cand.length)
        if (minLen >= 3 && (cand.contains(exp) || exp.contains(cand))) return true

        val pExp = phoneticKey(canonExp)
        val pCand = phoneticKey(canonCand)
        if (pExp.isNotBlank() && pCand.isNotBlank()) {
            if (pExp == pCand) return true
            val pMin = minOf(pExp.length, pCand.length)
            if (pMin >= 3 && (pCand.contains(pExp) || pExp.contains(pCand))) return true
        }
        return false
    }

    private fun pickBestResult(results: List<LyricsResult?>): LyricsResult? {
        val valid = results.filterNotNull().filter { it.lines.isNotEmpty() }
        if (valid.isEmpty()) return null
        // 1. Prefer synced lyrics (Spotify/LRC karaoke!)
        valid.firstOrNull { it.isSynced }?.let { return it }
        // 2. Otherwise prefer Genius.com (complete Russian rap lyrics), then anything valid
        valid.firstOrNull { it.sourceName.contains("Genius", ignoreCase = true) }?.let { return it }
        return valid.first()
    }

    suspend fun getLyrics(
        context: Context,
        artist: String,
        title: String,
        rawTitle: String = ""
    ): Result<LyricsResult> = withContext(Dispatchers.IO) {
        val parsed = MediaNotificationListener.parseTrack(
            rawTitle = if (rawTitle.isNotBlank()) rawTitle else title,
            rawArtist = artist
        )

        val cleanArtist = if (parsed.artist.isNotBlank()) parsed.artist else MediaNotificationListener.canonicalizeArtist(artist)
        val cleanTitle = if (parsed.title.isNotBlank()) parsed.title else MediaNotificationListener.sanitizeText(title)
        val strippedTitle = MediaNotificationListener.stripNoise(cleanTitle).ifBlank { cleanTitle }

        if (strippedTitle.isBlank()) {
            return@withContext Result.failure(Exception("Пустое название трека"))
        }

        val cacheKey = CACHE_VERSION + sanitizeKey("$cleanArtist - $strippedTitle")

        // 1. Check memory cache
        memoryCache[cacheKey]?.let {
            if (it.lines.isNotEmpty() && isTitleMatch(strippedTitle, it.title)) {
                return@withContext Result.success(it)
            }
        }

        // 2. Check disk cache
        val diskCached = readDiskCache(context, cacheKey)
        if (diskCached != null && diskCached.lines.isNotEmpty() && isTitleMatch(strippedTitle, diskCached.title)) {
            memoryCache[cacheKey] = diskCached
            return@withContext Result.success(diskCached)
        }

        val transTitle = transliterateTitleWords(strippedTitle)
        val artistParts = if (cleanArtist.isNotBlank()) {
            cleanArtist.split(Regex("[,&/]|\\b(?:ft\\.?|feat\\.?)\\b", RegexOption.IGNORE_CASE))
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
        } else {
            emptyList()
        }
        val primaryArtist = artistParts.firstOrNull() ?: ""
        val secondaryArtist = artistParts.getOrNull(1) ?: ""

        fun cacheAndReturn(res: LyricsResult): Result<LyricsResult> {
            saveDiskCache(context, cacheKey, res)
            memoryCache[cacheKey] = res
            return Result.success(res)
        }

        // =========================================================================
        // STAGE 1: FAST PARALLEL SEARCH (LRCLIB + NetEase + Genius OkHttp)
        // =========================================================================
        val stage1Best = coroutineScope {
            val dLrclibExact = async {
                if (primaryArtist.isBlank()) null
                else fetchLrclibExact(primaryArtist, strippedTitle)
                    ?: (if (!transTitle.equals(strippedTitle, true)) fetchLrclibExact(primaryArtist, transTitle) else null)
                    ?: (if (secondaryArtist.isNotBlank()) fetchLrclibExact(secondaryArtist, strippedTitle) else null)
            }
            val dLrclibSearch = async {
                val q = if (primaryArtist.isNotBlank()) "$primaryArtist $strippedTitle" else strippedTitle
                searchLrclibStrict(q, primaryArtist, strippedTitle, requireArtist = primaryArtist.isNotBlank())
            }
            val dNetEase = async {
                if (primaryArtist.isBlank()) null
                else fetchNetEase(primaryArtist, strippedTitle, requireArtist = true)
            }
            val dGeniusOkHttp = async {
                if (primaryArtist.isBlank()) null
                else fetchGeniusOkHttp(primaryArtist, strippedTitle, requireArtist = true)
            }

            pickBestResult(listOf(dLrclibExact.await(), dLrclibSearch.await(), dNetEase.await(), dGeniusOkHttp.await()))
        }
        if (stage1Best != null) return@withContext cacheAndReturn(stage1Best)

        // =========================================================================
        // STAGE 2: YANDEX MUSIC SMART RESOLVER + QQ MUSIC
        // Resolves Russian slang ("рокет драйв хоум", "френдли таг кальма", "пепел нахуди заново"),
        // missing 'ё', typos, AND fake SoundCloud uploader usernames!
        // =========================================================================
        val yandexResolved = resolveViaYandexMusic(primaryArtist, strippedTitle, parsed.artistFromTitle)

        val stage2Best = coroutineScope {
            val dQQOriginal = async {
                if (primaryArtist.isBlank()) null
                else fetchQQMusic(primaryArtist, strippedTitle, requireArtist = true)
            }

            val dYandexChain = async {
                if (yandexResolved == null) return@async null
                val ymTitle = yandexResolved.title
                val ymArtists = yandexResolved.allArtists.ifEmpty { listOf(yandexResolved.artist) }.filter { it.isNotBlank() }
                val ymPrimary = ymArtists.firstOrNull() ?: ""

                coroutineScope {
                    val yExact = async {
                        var hit: LyricsResult? = null
                        for (ya in ymArtists.take(2)) {
                            hit = fetchLrclibExact(ya, ymTitle)
                            if (hit != null) break
                        }
                        hit
                    }
                    val ySearch = async {
                        searchLrclibStrict("$ymPrimary $ymTitle", ymPrimary, ymTitle, requireArtist = ymPrimary.isNotBlank())
                    }
                    val yNetEase = async {
                        fetchNetEase(ymPrimary, ymTitle, requireArtist = ymPrimary.isNotBlank())
                    }
                    val yQQ = async {
                        fetchQQMusic(ymPrimary, ymTitle, requireArtist = ymPrimary.isNotBlank())
                    }
                    val yGenius = async {
                        if (ymPrimary.isNotBlank()) fetchGeniusOkHttp(ymPrimary, ymTitle, requireArtist = true) else null
                    }
                    pickBestResult(listOf(yExact.await(), ySearch.await(), yNetEase.await(), yQQ.await(), yGenius.await()))
                }
            }

            pickBestResult(listOf(dYandexChain.await(), dQQOriginal.await()))
        }
        if (stage2Best != null) return@withContext cacheAndReturn(stage2Best)

        // =========================================================================
        // STAGE 3: GENIUS.COM REAL CHROMIUM WEBVIEW ENGINE
        // Loads genius.com using Android's real browser engine (immune to OkHttp 403 TLS blocks).
        // Finds unreleased SoundCloud tracks, underground Russian rap, leaks, snippets on genius.com!
        // =========================================================================
        try {
            val webViewGenius = fetchGeniusViaWebView(
                context = context,
                primaryArtist = yandexResolved?.artist?.ifBlank { primaryArtist } ?: primaryArtist,
                strippedTitle = yandexResolved?.title?.ifBlank { strippedTitle } ?: strippedTitle,
                originalTitle = strippedTitle
            )
            if (webViewGenius != null && webViewGenius.lines.isNotEmpty()) {
                return@withContext cacheAndReturn(webViewGenius)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Genius WebView stage error: ${e.message}")
        }

        // =========================================================================
        // STAGE 4: INVERSION FALLBACK + TITLE-ONLY STRICT RESCUE
        // Rescues "Title - Artist" swaps AND SoundCloud re-uploads with fake uploader names
        // while still strictly verifying title match so unrelated songs are NEVER returned!
        // =========================================================================
        val stage4Best = coroutineScope {
            val dInverted = async {
                if (primaryArtist.isNotBlank() && strippedTitle.isNotBlank() && parsed.artistFromTitle) {
                    fetchLrclibExact(strippedTitle, primaryArtist)
                        ?: fetchNetEase(strippedTitle, primaryArtist, requireArtist = true)
                } else null
            }

            val dTitleOnlyLrclib = async {
                if (strippedTitle.length >= 3) {
                    searchLrclibStrict(strippedTitle, "", strippedTitle, requireArtist = false)
                        ?: (if (!transTitle.equals(strippedTitle, true)) searchLrclibStrict(transTitle, "", transTitle, requireArtist = false) else null)
                } else null
            }

            val dTitleOnlyNetEase = async {
                if (strippedTitle.length >= 3) {
                    fetchNetEase("", strippedTitle, requireArtist = false)
                } else null
            }

            val dTitleOnlyQQ = async {
                if (strippedTitle.length >= 3) {
                    fetchQQMusic("", strippedTitle, requireArtist = false)
                } else null
            }

            pickBestResult(listOf(dInverted.await(), dTitleOnlyLrclib.await(), dTitleOnlyNetEase.await(), dTitleOnlyQQ.await()))
        }
        if (stage4Best != null) return@withContext cacheAndReturn(stage4Best)

        return@withContext Result.failure(Exception("Текст не найден. Попробуйте ручной поиск."))
    }

    private fun transliterateTitleWords(title: String): String {
        val lower = title.lowercase()
        for ((k, v) in TITLE_WORD_MAP) {
            if (lower.contains(k)) {
                return lower.replace(k, v)
            }
        }
        val words = title.split(Regex("\\s+")).map { word ->
            val wLower = word.lowercase()
            TITLE_WORD_MAP[wLower] ?: word
        }
        return words.joinToString(" ")
    }

    // --- YANDEX MUSIC SMART TRACK RESOLVER ---

    private fun resolveViaYandexMusic(
        primaryArtist: String,
        strippedTitle: String,
        artistFromTitle: Boolean
    ): ResolvedTrack? {
        val queries = mutableListOf<String>()
        if (primaryArtist.isNotBlank()) {
            queries.add("$primaryArtist $strippedTitle")
        }
        // If artist came from SoundCloud uploader (EXTRA_TEXT), it might be a fake channel name!
        // So also allow resolving by title alone if the combined query fails.
        if (!artistFromTitle || primaryArtist.isBlank()) {
            queries.add(strippedTitle)
        }

        for ((idx, q) in queries.withIndex()) {
            try {
                val url = "https://api.music.yandex.net/search?text=" + URLEncoder.encode(q, "UTF-8") + "&type=track&page=0"
                val jsonStr = executeGetString(url) ?: continue
                if (!jsonStr.startsWith("{")) continue

                val root = JSONObject(jsonStr)
                val tracks = root.optJSONObject("result")
                    ?.optJSONObject("tracks")
                    ?.optJSONArray("results") ?: continue

                for (i in 0 until minOf(tracks.length(), 4)) {
                    val tr = tracks.getJSONObject(i)
                    val candTitle = tr.optCleanString("title")
                    if (candTitle.isBlank()) continue
                    val artistsArr = tr.optJSONArray("artists")
                    val candArtists = mutableListOf<String>()
                    if (artistsArr != null) {
                        for (a in 0 until artistsArr.length()) {
                            val name = artistsArr.getJSONObject(a).optCleanString("name")
                            if (name.isNotBlank()) candArtists.add(name)
                        }
                    }
                    val firstArtist = candArtists.firstOrNull() ?: ""
                    val allArtistStr = candArtists.joinToString(" ")

                    val titleOk = isTitleMatch(strippedTitle, candTitle)
                    val artistOk = primaryArtist.isNotBlank() &&
                        (isArtistMatch(primaryArtist, firstArtist) || isArtistMatch(primaryArtist, allArtistStr))

                    // Accept if title matches OR (on query 0 with known artist, artist matches AND track is #1 Yandex hit)
                    if (titleOk || (idx == 0 && i == 0 && artistOk)) {
                        return ResolvedTrack(
                            artist = firstArtist,
                            allArtists = candArtists,
                            title = candTitle
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Yandex resolver error for '$q': ${e.message}")
            }
        }
        return null
    }

    // --- LRCLIB EXACT GET ---

    private fun fetchLrclibExact(artist: String, title: String): LyricsResult? {
        val url = "https://lrclib.net/api/get?artist_name=" + URLEncoder.encode(artist, "UTF-8") +
                "&track_name=" + URLEncoder.encode(title, "UTF-8")

        val jsonStr = executeGetString(url) ?: return null
        if (!jsonStr.startsWith("{")) return null

        val obj = JSONObject(jsonStr)
        val track = obj.optCleanString("trackName")
        val art = obj.optCleanString("artistName")

        if (!isTitleMatch(title, track) || !isArtistMatch(artist, art)) {
            return null
        }

        val synced = obj.optCleanString("syncedLyrics")
        val plain = obj.optCleanString("plainLyrics")

        if (synced.isNotBlank()) {
            val lines = parseLrcLines(synced)
            if (lines.isNotEmpty()) {
                return LyricsResult(
                    title = track,
                    artist = art,
                    lines = lines,
                    plainLyrics = plain.ifBlank { lines.joinToString("\n") { it.text } },
                    sourceUrl = "https://lrclib.net",
                    isSynced = true,
                    sourceName = "Spotify (Караоке)"
                )
            }
        }

        if (plain.isNotBlank()) {
            val lines = sanitizeLyricsLines(plain)
            if (lines.isNotEmpty()) {
                return LyricsResult(
                    title = track,
                    artist = art,
                    lines = lines,
                    plainLyrics = plain,
                    sourceUrl = "https://lrclib.net",
                    isSynced = false,
                    sourceName = "Spotify Lyrics"
                )
            }
        }

        return null
    }

    // --- LRCLIB SEARCH WITH STRICT TITLE VALIDATION ---

    private fun searchLrclibStrict(
        query: String,
        expectedArtist: String,
        expectedTitle: String,
        requireArtist: Boolean
    ): LyricsResult? {
        val url = "https://lrclib.net/api/search?q=" + URLEncoder.encode(query, "UTF-8")
        val jsonStr = executeGetString(url) ?: return null
        if (!jsonStr.startsWith("[")) return null

        val arr = JSONArray(jsonStr)
        if (arr.length() == 0) return null

        // Pass 1: Synced karaoke match
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val track = item.optCleanString("trackName")
            val art = item.optCleanString("artistName")

            if (!isTitleMatch(expectedTitle, track)) continue
            if (requireArtist && expectedArtist.isNotBlank() && !isArtistMatch(expectedArtist, art)) continue

            val synced = item.optCleanString("syncedLyrics")
            if (synced.isNotBlank()) {
                val lines = parseLrcLines(synced)
                if (lines.isNotEmpty()) {
                    return LyricsResult(
                        title = track,
                        artist = art,
                        lines = lines,
                        plainLyrics = item.optCleanString("plainLyrics").ifBlank { lines.joinToString("\n") { it.text } },
                        sourceUrl = "https://lrclib.net",
                        isSynced = true,
                        sourceName = "Spotify (Караоке)"
                    )
                }
            }
        }

        // Pass 2: Plain lyrics match
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val track = item.optCleanString("trackName")
            val art = item.optCleanString("artistName")

            if (!isTitleMatch(expectedTitle, track)) continue
            if (requireArtist && expectedArtist.isNotBlank() && !isArtistMatch(expectedArtist, art)) continue

            val plain = item.optCleanString("plainLyrics")
            if (plain.isNotBlank()) {
                val lines = sanitizeLyricsLines(plain)
                if (lines.isNotEmpty()) {
                    return LyricsResult(
                        title = track,
                        artist = art,
                        lines = lines,
                        plainLyrics = plain,
                        sourceUrl = "https://lrclib.net",
                        isSynced = false,
                        sourceName = "Spotify Lyrics"
                    )
                }
            }
        }

        return null
    }

    // --- NETEASE CLOUD MUSIC ---

    private fun fetchNetEase(artist: String, title: String, requireArtist: Boolean): LyricsResult? {
        val query = if (requireArtist && artist.isNotBlank()) "$artist $title" else title
        val searchUrl = "https://music.163.com/api/search/get/?s=" + URLEncoder.encode(query, "UTF-8") + "&type=1&limit=5"
        val jsonStr = executeGetString(searchUrl) ?: return null
        if (!jsonStr.startsWith("{")) return null

        val root = JSONObject(jsonStr)
        val resultObj = root.optJSONObject("result") ?: return null
        val songsArr = resultObj.optJSONArray("songs") ?: return null

        for (i in 0 until songsArr.length()) {
            val song = songsArr.getJSONObject(i)
            val songName = song.optCleanString("name")
            val artistsArr = song.optJSONArray("artists")
            val songArtists = mutableListOf<String>()
            if (artistsArr != null) {
                for (a in 0 until artistsArr.length()) {
                    songArtists.add(artistsArr.getJSONObject(a).optCleanString("name"))
                }
            }
            val songArtist = songArtists.joinToString(" ")

            if (!isTitleMatch(title, songName)) continue
            if (requireArtist && artist.isNotBlank() && !isArtistMatch(artist, songArtist) && !isArtistMatch(songArtist, artist)) continue

            val songId = song.optLong("id", -1L)
            if (songId <= 0) continue

            val lyricUrl = "https://music.163.com/api/song/lyric?os=pc&id=$songId&lv=-1&kv=-1&tv=-1"
            val lyricJson = executeGetString(lyricUrl) ?: continue
            val lrcRoot = JSONObject(lyricJson)
            val lrcObj = lrcRoot.optJSONObject("lrc") ?: continue
            val rawLyric = lrcObj.optCleanString("lyric")
            if (rawLyric.length < 30) continue

            val cleanedLyric = rawLyric.lines()
                .filter { line ->
                    !line.startsWith("作词") &&
                    !line.startsWith("作曲") &&
                    !line.startsWith("编曲") &&
                    !line.startsWith("混音")
                }
                .joinToString("\n")

            val isSynced = Regex("\\[\\d{2}:\\d{2}\\.\\d{2,3}\\]").containsMatchIn(cleanedLyric)
            val lines = if (isSynced) parseLrcLines(cleanedLyric) else sanitizeLyricsLines(cleanedLyric)

            if (lines.isNotEmpty()) {
                return LyricsResult(
                    title = songName,
                    artist = if (songArtists.isNotEmpty()) songArtists.first() else artist,
                    lines = lines,
                    plainLyrics = lines.joinToString("\n") { it.text },
                    sourceUrl = "https://music.163.com/#/song?id=$songId",
                    isSynced = isSynced,
                    sourceName = if (isSynced) "Spotify (NetEase LRC)" else "Spotify (NetEase)"
                )
            }
        }

        return null
    }

    // --- QQ MUSIC API ---

    private fun fetchQQMusic(artist: String, title: String, requireArtist: Boolean): LyricsResult? {
        val query = if (requireArtist && artist.isNotBlank()) "$artist $title" else title
        val searchUrl = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=" + URLEncoder.encode(query, "UTF-8") + "&format=json&n=5"
        val jsonStr = executeGetString(searchUrl, referer = "https://y.qq.com/") ?: return null
        if (!jsonStr.startsWith("{")) return null

        val root = JSONObject(jsonStr)
        val dataObj = root.optJSONObject("data") ?: return null
        val songObj = dataObj.optJSONObject("song") ?: return null
        val listArr = songObj.optJSONArray("list") ?: return null

        for (i in 0 until listArr.length()) {
            val s = listArr.getJSONObject(i)
            val songName = s.optCleanString("songname")
            val singerArr = s.optJSONArray("singer")
            val singers = mutableListOf<String>()
            if (singerArr != null) {
                for (a in 0 until singerArr.length()) {
                    singers.add(singerArr.getJSONObject(a).optCleanString("name"))
                }
            }
            val singerName = singers.joinToString(" ")

            if (!isTitleMatch(title, songName)) continue
            if (requireArtist && artist.isNotBlank() && !isArtistMatch(artist, singerName) && !isArtistMatch(singerName, artist)) continue

            val songmid = s.optCleanString("songmid")
            if (songmid.isBlank()) continue

            val lyricUrl = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=$songmid&format=json&nobase64=1"
            val lyricJson = executeGetString(lyricUrl, referer = "https://y.qq.com/") ?: continue
            val lrcRoot = JSONObject(lyricJson)
            val rawLyric = lrcRoot.optCleanString("lyric")
            if (rawLyric.length < 30) continue

            val isSynced = Regex("\\[\\d{2}:\\d{2}\\.\\d{2,3}\\]").containsMatchIn(rawLyric)
            val lines = if (isSynced) parseLrcLines(rawLyric) else sanitizeLyricsLines(rawLyric)

            if (lines.isNotEmpty()) {
                return LyricsResult(
                    title = songName,
                    artist = if (singers.isNotEmpty()) singers.first() else artist,
                    lines = lines,
                    plainLyrics = lines.joinToString("\n") { it.text },
                    sourceUrl = "https://y.qq.com/n/ryqq/songDetail/$songmid",
                    isSynced = isSynced,
                    sourceName = if (isSynced) "Spotify (QQ LRC)" else "Spotify (QQ)"
                )
            }
        }

        return null
    }

    // --- GENIUS.COM OKHTTP (TRANSLITERATED LATIN SLUG + MULTI-SEARCH API) ---

    private fun fetchGeniusOkHttp(artist: String, title: String, requireArtist: Boolean): LyricsResult? {
        // 1. Direct transliterated Latin slug (e.g. "Платина - В темноте" -> "https://genius.com/Platina-v-temnote-lyrics")
        if (artist.isNotBlank() && title.isNotBlank()) {
            val directUrl = constructDirectGeniusUrl(artist, title)
            val directResult = fetchGeniusHtmlAndParse(directUrl)
            if (directResult != null && directResult.lines.isNotEmpty()) {
                return directResult.copy(title = title, artist = artist, sourceName = "Genius.com")
            }
        }

        // 2. Genius multi-search API (checks BOTH 'top_hit' and 'song' sections!)
        val query = if (requireArtist && artist.isNotBlank()) "$artist $title" else title
        val searchUrl = "https://genius.com/api/search/multi?per_page=8&q=" + URLEncoder.encode(query, "UTF-8")
        try {
            val req = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json, text/plain, */*")
                .header("Referer", "https://genius.com/")
                .header("Origin", "https://genius.com")
                .header("X-Requested-With", "XMLHttpRequest")
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            if (!body.trim().startsWith("{")) return null

            val root = JSONObject(body)
            val sections = root.optJSONObject("response")?.optJSONArray("sections") ?: return null
            for (i in 0 until sections.length()) {
                val sec = sections.getJSONObject(i)
                val secType = sec.optCleanString("type")
                if (secType == "song" || secType == "top_hit") {
                    val hits = sec.optJSONArray("hits") ?: continue
                    for (h in 0 until hits.length()) {
                        val hit = hits.getJSONObject(h)
                        val result = hit.optJSONObject("result") ?: continue
                        val resType = result.optCleanString("_type").ifBlank { hit.optCleanString("type") }
                        if (resType != "song") continue

                        val candTitle = result.optCleanString("title")
                        val candArtist = result.optJSONObject("primary_artist")?.optCleanString("name") ?: ""
                        val songUrl = result.optCleanString("url")

                        if (!isTitleMatch(title, candTitle)) continue
                        if (requireArtist && artist.isNotBlank() && !isArtistMatch(artist, candArtist)) continue

                        val parsed = fetchGeniusHtmlAndParse(songUrl)
                        if (parsed != null && parsed.lines.isNotEmpty()) {
                            return parsed.copy(
                                title = candTitle.ifBlank { title },
                                artist = candArtist.ifBlank { artist },
                                sourceName = "Genius.com"
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Genius OkHttp search failed: ${e.message}")
        }
        return null
    }

    // --- GENIUS.COM REAL CHROMIUM WEBVIEW ENGINE ---

    private suspend fun fetchGeniusViaWebView(
        context: Context,
        primaryArtist: String,
        strippedTitle: String,
        originalTitle: String
    ): LyricsResult? {
        val searchAttempts = mutableListOf<Pair<String, Boolean>>()
        if (primaryArtist.isNotBlank()) {
            searchAttempts.add(Pair("$primaryArtist $strippedTitle", true))
        }
        searchAttempts.add(Pair(strippedTitle, false))
        if (!originalTitle.equals(strippedTitle, ignoreCase = true) && originalTitle.length >= 3) {
            searchAttempts.add(Pair(originalTitle, false))
        }

        for ((query, requireArtist) in searchAttempts) {
            val hits = GeniusWebViewFetcher.searchSongs(context, query)
            if (hits.isEmpty()) continue

            val matched = hits.firstOrNull { hit ->
                val titleOk = isTitleMatch(strippedTitle, hit.title) || isTitleMatch(originalTitle, hit.title)
                val artistOk = !requireArtist || primaryArtist.isBlank() || isArtistMatch(primaryArtist, hit.artist)
                titleOk && artistOk
            } ?: continue

            val rawLyrics = GeniusWebViewFetcher.scrapeLyricsFromUrl(context, matched.url) ?: continue
            val cleaned = cleanLyrics(rawLyrics)
            val lines = sanitizeLyricsLines(cleaned)
            if (lines.isNotEmpty()) {
                return LyricsResult(
                    title = matched.title.ifBlank { strippedTitle },
                    artist = matched.artist.ifBlank { primaryArtist },
                    lines = lines,
                    plainLyrics = cleaned,
                    sourceUrl = matched.url,
                    isSynced = false,
                    sourceName = "Genius.com"
                )
            }
        }
        return null
    }

    private fun fetchGeniusHtmlAndParse(songUrl: String): LyricsResult? {
        return try {
            val pageRequest = Request.Builder()
                .url(songUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
                .header("Referer", "https://genius.com/")
                .build()

            val pageResponse = client.newCall(pageRequest).execute()
            if (!pageResponse.isSuccessful) return null

            val html = pageResponse.body?.string() ?: return null

            val doc = Jsoup.parse(html)
            val lyricContainers = doc.select("div[data-lyrics-container=true]")
            var rawText = ""

            if (lyricContainers.isNotEmpty()) {
                val sb = StringBuilder()
                for (container in lyricContainers) {
                    // Strip Genius header/contributor/translation junk!
                    container.select("[data-exclude-from-selection=true], script, style, button").remove()
                    container.select("br").append("\\n")
                    container.select("p").prepend("\\n\\n")
                    val text = container.text().replace("\\n", "\n")
                    sb.append(text).append("\n\n")
                }
                rawText = sb.toString().trim()
            }

            if (rawText.isBlank()) {
                rawText = doc.select(".lyrics").text().trim()
            }

            if (rawText.isBlank()) {
                val nextData = doc.select("script#__NEXT_DATA__").firstOrNull()?.data()
                if (nextData != null) {
                    try {
                        val jsonObj = JSONObject(nextData)
                        val songObj = jsonObj.optJSONObject("props")
                            ?.optJSONObject("pageProps")
                            ?.optJSONObject("song")
                        val lyricsObj = songObj?.optJSONObject("lyrics")
                        val plain = lyricsObj?.optCleanString("plain") ?: ""
                        if (plain.isNotBlank()) {
                            rawText = plain
                        }
                    } catch (ignored: Exception) {}
                }
            }

            if (rawText.isBlank()) return null
            val cleaned = cleanLyrics(rawText)
            val lines = sanitizeLyricsLines(cleaned)
            if (lines.isEmpty()) return null

            LyricsResult(
                title = "",
                artist = "",
                lines = lines,
                plainLyrics = cleaned,
                sourceUrl = songUrl,
                isSynced = false,
                sourceName = "Genius.com"
            )
        } catch (e: Exception) {
            null
        }
    }

    // --- PARSING & UTILITIES ---

    private fun parseLrcLines(lrcText: String): List<LyricLine> {
        val regex = Regex("\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})\\](.*)")
        val result = mutableListOf<LyricLine>()

        for (rawLine in lrcText.lines()) {
            val line = rawLine.trim()
            val match = regex.find(line)
            if (match != null) {
                val min = match.groupValues[1].toLongOrNull() ?: 0L
                val sec = match.groupValues[2].toLongOrNull() ?: 0L
                val msStr = match.groupValues[3]
                val ms = if (msStr.length == 2) msStr.toLong() * 10 else msStr.toLong()
                val totalMs = (min * 60 + sec) * 1000 + ms
                val text = match.groupValues[4].trim()
                if (text.isNotBlank() && !text.equals("null", ignoreCase = true) && !text.equals("undefined", ignoreCase = true)) {
                    result.add(LyricLine(timeMs = totalMs, text = text))
                }
            } else if (line.isNotBlank() &&
                !line.equals("null", ignoreCase = true) &&
                !line.equals("undefined", ignoreCase = true) &&
                !line.startsWith("[ti:") &&
                !line.startsWith("[ar:") &&
                !line.startsWith("[length:") &&
                !line.startsWith("[al:") &&
                !line.startsWith("[by:")
            ) {
                result.add(LyricLine(timeMs = -1L, text = line))
            }
        }
        return result
    }

    private fun sanitizeLyricsLines(text: String): List<LyricLine> {
        return text.lines()
            .map { it.trim() }
            .filter { line ->
                line.isNotBlank() &&
                !line.equals("null", ignoreCase = true) &&
                !line.equals("undefined", ignoreCase = true) &&
                !line.startsWith("Embed", ignoreCase = true) &&
                !line.startsWith("You might also like", ignoreCase = true) &&
                !line.matches(Regex("^\\d+\\s+Contributors?.*", RegexOption.IGNORE_CASE))
            }
            .map { LyricLine(text = it) }
    }

    private fun executeGetString(url: String, referer: String = ""): String? {
        return try {
            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
            if (referer.isNotBlank()) {
                builder.header("Referer", referer)
            }
            val response = client.newCall(builder.build()).execute()
            if (response.isSuccessful) response.body?.string() else null
        } catch (e: Exception) {
            null
        }
    }

    fun constructDirectGeniusUrl(artist: String, title: String): String {
        val raw = "${transliterateRuToLat(artist)}-${transliterateRuToLat(title)}"
        val slugged = raw
            .replace("'", "")
            .replace("$", "s")
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .replaceFirstChar { it.uppercase() }
        return "https://genius.com/$slugged-lyrics"
    }

    private fun cleanLyrics(raw: String): String {
        return raw.replace(Regex("\\d*Embed$"), "")
            .replace(Regex("You might also like.*?\n", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^\\d+\\s+Contributors?.*?\n", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^(Lyrics|Текст песни).*?\n", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun sanitizeKey(key: String): String =
        key.lowercase().replace('ё', 'е').replace(Regex("[^a-z0-9а-я]"), "_")

    private fun saveDiskCache(context: Context, key: String, result: LyricsResult) {
        if (result.lines.isEmpty() || result.plainLyrics.isBlank() || result.plainLyrics.equals("null", ignoreCase = true)) {
            return
        }
        try {
            val cacheDir = File(context.cacheDir, "lyrics")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val file = File(cacheDir, "$key.json")
            val json = JSONObject().apply {
                put("title", result.title)
                put("artist", result.artist)
                put("plainLyrics", result.plainLyrics)
                put("sourceUrl", result.sourceUrl)
                put("isSynced", result.isSynced)
                put("sourceName", result.sourceName)
                val linesArray = JSONArray()
                for (line in result.lines) {
                    linesArray.put(JSONObject().apply {
                        put("timeMs", line.timeMs)
                        put("text", line.text)
                    })
                }
                put("lines", linesArray)
            }
            file.writeText(json.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Cache write error: ${e.message}")
        }
    }

    private fun readDiskCache(context: Context, key: String): LyricsResult? {
        return try {
            val cacheDir = File(context.cacheDir, "lyrics")
            val file = File(cacheDir, "$key.json")
            if (!file.exists()) return null
            val content = file.readText()
            val json = JSONObject(content)
            val linesArray = json.optJSONArray("lines") ?: JSONArray()
            val lines = mutableListOf<LyricLine>()
            for (i in 0 until linesArray.length()) {
                val item = linesArray.getJSONObject(i)
                val t = item.optCleanString("text")
                if (t.isNotBlank() && !t.equals("null", ignoreCase = true)) {
                    lines.add(LyricLine(item.optLong("timeMs", -1L), t))
                }
            }
            if (lines.isEmpty()) return null
            LyricsResult(
                title = json.optCleanString("title"),
                artist = json.optCleanString("artist"),
                lines = lines,
                plainLyrics = json.optCleanString("plainLyrics"),
                sourceUrl = json.optCleanString("sourceUrl"),
                isSynced = json.optBoolean("isSynced", false),
                sourceName = json.optCleanString("sourceName").ifBlank { "Кэш" }
            )
        } catch (e: Exception) {
            null
        }
    }
}
