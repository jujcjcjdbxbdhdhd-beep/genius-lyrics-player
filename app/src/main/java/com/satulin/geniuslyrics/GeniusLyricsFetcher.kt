package com.satulin.geniuslyrics

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
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
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val memoryCache = mutableMapOf<String, LyricsResult>()

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
        "пять" to "5ive",
        "5ive" to "5ive",
        "динамит" to "dynamite"
    )

    fun JSONObject.optCleanString(name: String): String {
        if (isNull(name)) return ""
        val str = optString(name, "").trim()
        if (str.equals("null", ignoreCase = true) || str.equals("undefined", ignoreCase = true)) {
            return ""
        }
        return str
    }

    private fun normalizeForMatch(s: String): String {
        return s.lowercase()
            .replace(Regex("[^a-z0-9а-яё]"), "")
            .trim()
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
        val exp = normalizeForMatch(cleanTitleForComparison(expectedTitle))
        val cand = normalizeForMatch(cleanTitleForComparison(candidateTitle))
        if (exp.isBlank() || cand.isBlank()) return false
        if (exp == cand) return true
        if (cand.contains(exp) || exp.contains(cand)) return true
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
        if (cand.contains(exp) || exp.contains(cand)) return true
        return false
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

        if (cleanTitle.isBlank()) {
            return@withContext Result.failure(Exception("Пустое название трека"))
        }

        val cacheKey = sanitizeKey("$cleanArtist - $cleanTitle")

        // 1. Check memory cache (with title validation!)
        memoryCache[cacheKey]?.let {
            if (it.lines.isNotEmpty() && isTitleMatch(cleanTitle, it.title)) {
                return@withContext Result.success(it)
            }
        }

        // 2. Check disk cache (with title validation!)
        val diskCached = readDiskCache(context, cacheKey)
        if (diskCached != null && diskCached.lines.isNotEmpty() && isTitleMatch(cleanTitle, diskCached.title)) {
            memoryCache[cacheKey] = diskCached
            return@withContext Result.success(diskCached)
        }

        // Generate clean title candidates:
        // A. stripped title (noise stripped: "Drive Home (prod. ...)" -> "Drive Home")
        val strippedTitle = MediaNotificationListener.stripNoise(cleanTitle).ifBlank { cleanTitle }
        // B. transliterated title ("асфальт 8" -> "asphalt 8", "calma" -> "calmer")
        val transTitle = transliterateTitleWords(strippedTitle)

        val titleCandidates = mutableListOf<String>()
        titleCandidates.add(strippedTitle)
        if (!transTitle.equals(strippedTitle, ignoreCase = true)) {
            titleCandidates.add(transTitle)
        }
        if (!cleanTitle.equals(strippedTitle, ignoreCase = true)) {
            titleCandidates.add(cleanTitle)
        }

        // Generate artist candidates (multi-collab splitting: e.g. "Bushido Zho & Aarne" -> ["Bushido Zho", "Bushido Zho & Aarne", "Aarne"]):
        val artistCandidates = mutableListOf<String>()
        if (cleanArtist.isNotBlank()) {
            val primaryArtist = cleanArtist.split(Regex("[,&/]|\\b(ft\\.?|feat\\.?)\\b", RegexOption.IGNORE_CASE))[0].trim()
            if (primaryArtist.isNotBlank()) {
                artistCandidates.add(primaryArtist)
            }
            if (!primaryArtist.equals(cleanArtist, ignoreCase = true)) {
                artistCandidates.add(cleanArtist)
            }
            val parts = cleanArtist.split(Regex("[,&/]|\\b(ft\\.?|feat\\.?)\\b", RegexOption.IGNORE_CASE))
            if (parts.size > 1) {
                val secondary = parts[1].trim()
                if (secondary.isNotBlank() && !artistCandidates.contains(secondary)) {
                    artistCandidates.add(secondary)
                }
            }
        } else {
            artistCandidates.add("")
        }

        // 3. STEP 1: LRCLIB EXACT GET (Direct Spotify / Apple Music synced lyrics)
        for (a in artistCandidates) {
            for (t in titleCandidates) {
                if (a.isNotBlank() && t.isNotBlank()) {
                    try {
                        val exact = fetchLrclibExact(a, t)
                        if (exact != null && exact.lines.isNotEmpty()) {
                            saveDiskCache(context, cacheKey, exact)
                            memoryCache[cacheKey] = exact
                            return@withContext Result.success(exact)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "LRCLIB exact error for ($a, $t): ${e.message}")
                    }
                }
            }
        }

        // 4. STEP 2: NETEASE CLOUD MUSIC (Massive Spotify synced LRC database)
        for (a in artistCandidates) {
            for (t in titleCandidates) {
                if (t.isNotBlank()) {
                    try {
                        val neteaseResult = fetchNetEase(a, t)
                        if (neteaseResult != null && neteaseResult.lines.isNotEmpty()) {
                            saveDiskCache(context, cacheKey, neteaseResult)
                            memoryCache[cacheKey] = neteaseResult
                            return@withContext Result.success(neteaseResult)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "NetEase search error: ${e.message}")
                    }
                }
            }
        }

        // 5. STEP 3: QQ MUSIC API (Tencent Music / Spotify partner catalog)
        for (a in artistCandidates) {
            for (t in titleCandidates) {
                if (t.isNotBlank()) {
                    try {
                        val qqResult = fetchQQMusic(a, t)
                        if (qqResult != null && qqResult.lines.isNotEmpty()) {
                            saveDiskCache(context, cacheKey, qqResult)
                            memoryCache[cacheKey] = qqResult
                            return@withContext Result.success(qqResult)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "QQ Music error: ${e.message}")
                    }
                }
            }
        }

        // 6. STEP 4: LRCLIB PARAMETRIC SEARCH
        for (a in artistCandidates) {
            for (t in titleCandidates) {
                if (a.isNotBlank() && t.isNotBlank()) {
                    try {
                        val paramResult = fetchLrclibParametric(a, t)
                        if (paramResult != null && paramResult.lines.isNotEmpty()) {
                            saveDiskCache(context, cacheKey, paramResult)
                            memoryCache[cacheKey] = paramResult
                            return@withContext Result.success(paramResult)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "LRCLIB parametric search error: ${e.message}")
                    }
                }
            }
        }

        // 7. STEP 5: DIRECT GENIUS SLUG URL
        for (a in artistCandidates) {
            for (t in titleCandidates) {
                if (a.isNotBlank() && t.isNotBlank()) {
                    try {
                        val geniusDirect = scrapeGeniusDirectSlug(a, t)
                        if (geniusDirect != null && geniusDirect.lines.isNotEmpty()) {
                            saveDiskCache(context, cacheKey, geniusDirect)
                            memoryCache[cacheKey] = geniusDirect
                            return@withContext Result.success(geniusDirect)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Direct Genius scrape failed: ${e.message}")
                    }
                }
            }
        }

        // 8. STEP 6: INVERSION FALLBACK (In case track was formatted as "Title - Artist")
        if (cleanArtist.isNotBlank() && strippedTitle.isNotBlank()) {
            val invertedArtist = strippedTitle
            val invertedTitle = cleanArtist
            try {
                val invExact = fetchLrclibExact(invertedArtist, invertedTitle)
                if (invExact != null && invExact.lines.isNotEmpty()) {
                    saveDiskCache(context, cacheKey, invExact)
                    memoryCache[cacheKey] = invExact
                    return@withContext Result.success(invExact)
                }
                val invNetEase = fetchNetEase(invertedArtist, invertedTitle)
                if (invNetEase != null && invNetEase.lines.isNotEmpty()) {
                    saveDiskCache(context, cacheKey, invNetEase)
                    memoryCache[cacheKey] = invNetEase
                    return@withContext Result.success(invNetEase)
                }
            } catch (ignored: Exception) {}
        }

        // 9. STEP 7: GENERAL SEARCH QUERIES ON LRCLIB & NETEASE
        val searchQueries = mutableListOf<String>()
        if (cleanArtist.isNotBlank()) {
            searchQueries.add("$cleanArtist $strippedTitle")
        }
        if (!transTitle.equals(strippedTitle, ignoreCase = true) && cleanArtist.isNotBlank()) {
            searchQueries.add("$cleanArtist $transTitle")
        }
        if (rawTitle.isNotBlank() && !rawTitle.equals(strippedTitle, ignoreCase = true)) {
            searchQueries.add(MediaNotificationListener.stripNoise(rawTitle))
        }
        searchQueries.add(strippedTitle)

        for (q in searchQueries) {
            try {
                val searched = searchLrclibStrict(q, cleanArtist, strippedTitle)
                if (searched != null && searched.lines.isNotEmpty()) {
                    saveDiskCache(context, cacheKey, searched)
                    memoryCache[cacheKey] = searched
                    return@withContext Result.success(searched)
                }
            } catch (e: Exception) {
                Log.w(TAG, "LRCLIB search failed for '$q': ${e.message}")
            }
        }

        return@withContext Result.failure(Exception("Текст не найден. Попробуйте ручной поиск."))
    }

    private fun transliterateTitleWords(title: String): String {
        val lower = title.lowercase()
        // Check multi-word phrases first
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

    // --- NETEASE CLOUD MUSIC (Massive Synced LRC Database) ---

    private fun fetchNetEase(artist: String, title: String): LyricsResult? {
        val query = if (artist.isNotBlank()) "$artist $title" else title
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
            if (artist.isNotBlank() && !isArtistMatch(artist, songArtist) && !isArtistMatch(songArtist, artist)) continue

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

    // --- QQ MUSIC API (Tencent Music / Spotify Partner Catalog) ---

    private fun fetchQQMusic(artist: String, title: String): LyricsResult? {
        val query = if (artist.isNotBlank()) "$artist $title" else title
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
            if (artist.isNotBlank() && !isArtistMatch(artist, singerName) && !isArtistMatch(singerName, artist)) continue

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
                    sourceName = if (isSynced) "Spotify (QQ Music LRC)" else "Spotify (QQ Music)"
                )
            }
        }

        return null
    }

    // --- LRCLIB PARAMETRIC SEARCH ---

    private fun fetchLrclibParametric(artist: String, title: String): LyricsResult? {
        val url = "https://lrclib.net/api/search?track_name=" + URLEncoder.encode(title, "UTF-8") +
                "&artist_name=" + URLEncoder.encode(artist, "UTF-8")
        val jsonStr = executeGetString(url) ?: return null
        if (!jsonStr.startsWith("[")) return null

        val arr = JSONArray(jsonStr)
        if (arr.length() == 0) return null

        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val track = item.optCleanString("trackName")
            val art = item.optCleanString("artistName")

            if (!isTitleMatch(title, track) || !isArtistMatch(artist, art)) continue

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

    // --- LRCLIB SEARCH WITH STRICT TITLE VALIDATION ---

    private fun searchLrclibStrict(query: String, expectedArtist: String, expectedTitle: String): LyricsResult? {
        val url = "https://lrclib.net/api/search?q=" + URLEncoder.encode(query, "UTF-8")
        val jsonStr = executeGetString(url) ?: return null
        if (!jsonStr.startsWith("[")) return null

        val arr = JSONArray(jsonStr)
        if (arr.length() == 0) return null

        // Pass 1: Look for exact title + artist match with synced karaoke
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val track = item.optCleanString("trackName")
            val art = item.optCleanString("artistName")

            if (!isTitleMatch(expectedTitle, track)) continue
            if (expectedArtist.isNotBlank() && !isArtistMatch(expectedArtist, art)) continue

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

        // Pass 2: Look for title + artist match with plain lyrics
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val track = item.optCleanString("trackName")
            val art = item.optCleanString("artistName")

            if (!isTitleMatch(expectedTitle, track)) continue
            if (expectedArtist.isNotBlank() && !isArtistMatch(expectedArtist, art)) continue

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

    // --- GENIUS DIRECT SLUG & SCRAPING ---

    private fun scrapeGeniusDirectSlug(artist: String, title: String): LyricsResult? {
        val directUrl = constructDirectGeniusUrl(artist, title)
        val result = fetchGeniusHtmlAndParse(directUrl)
        if (result != null && result.lines.isNotEmpty()) {
            return result.copy(title = title, artist = artist, sourceName = "Genius.com")
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
                !line.startsWith("You might also like", ignoreCase = true)
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

    private fun slug(text: String): String =
        text.replace(Regex("[^a-zA-Z0-9а-яА-ЯёЁ]+"), "-")
            .trim('-')
            .split("-")
            .filter { it.isNotBlank() }
            .joinToString("-") { it.replaceFirstChar { c -> c.uppercase() } }

    private fun constructDirectGeniusUrl(artist: String, title: String): String {
        val sArtist = slug(artist)
        val sTitle = slug(title)
        return "https://genius.com/$sArtist-$sTitle-lyrics"
    }

    private fun cleanLyrics(raw: String): String {
        return raw.replace(Regex("\\d*Embed$"), "")
            .replace(Regex("You might also like.*?\n", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^(Lyrics|Текст песни).*?\n", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun sanitizeKey(key: String): String =
        key.lowercase().replace(Regex("[^a-z0-9а-яё]"), "_")

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
