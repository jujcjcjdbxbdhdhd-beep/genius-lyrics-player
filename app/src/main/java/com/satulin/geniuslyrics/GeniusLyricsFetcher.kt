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
    val sourceName: String = "Genius"
)

object GeniusLyricsFetcher {

    private const val TAG = "GeniusLyricsFetcher"
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Mobile; rv:124.0) Gecko/124.0 Firefox/124.0"

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
        "кальма" to "calma"
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
        var s = t.replace(Regex("\\(Official.*?\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\[Official.*?\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\(Lyric Video\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\[Lyric Video\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\(Remastered.*?\\)", RegexOption.IGNORE_CASE), "")
            .trim()
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
        val exp = normalizeForMatch(expectedArtist)
        val cand = normalizeForMatch(candidateArtist)
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

        // 3. STEP 1: EXACT MATCH BY (ARTIST + TITLE) ON LRCLIB
        // When we know the exact artist and title (e.g. "ROCKET" and "Drive Home"),
        // calling api/get gives the true track directly. If it exists, STOP IMMEDIATELY!
        if (cleanArtist.isNotBlank() && cleanTitle.isNotBlank()) {
            try {
                val exact = fetchLrclibExact(cleanArtist, cleanTitle)
                if (exact != null && exact.lines.isNotEmpty()) {
                    saveDiskCache(context, cacheKey, exact)
                    memoryCache[cacheKey] = exact
                    return@withContext Result.success(exact)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Exact get error for ($cleanArtist, $cleanTitle): ${e.message}")
            }

            // Also check transliterated title words (e.g. "MACAN" + "asphalt 8")
            val transTitle = transliterateTitleWords(cleanTitle)
            if (!transTitle.equals(cleanTitle, ignoreCase = true)) {
                try {
                    val exactTrans = fetchLrclibExact(cleanArtist, transTitle)
                    if (exactTrans != null && exactTrans.lines.isNotEmpty()) {
                        saveDiskCache(context, cacheKey, exactTrans)
                        memoryCache[cacheKey] = exactTrans
                        return@withContext Result.success(exactTrans)
                    }
                } catch (ignored: Exception) {}
            }
        }

        // 4. STEP 2: DIRECT GENIUS.COM SLUG URL
        // If artist and title are known, test canonical Genius URL slug
        if (cleanArtist.isNotBlank() && cleanTitle.isNotBlank()) {
            try {
                val geniusDirect = scrapeGeniusDirectSlug(cleanArtist, cleanTitle)
                if (geniusDirect != null && geniusDirect.lines.isNotEmpty()) {
                    saveDiskCache(context, cacheKey, geniusDirect)
                    memoryCache[cacheKey] = geniusDirect
                    return@withContext Result.success(geniusDirect)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Direct Genius scrape failed: ${e.message}")
            }
        }

        // 5. STEP 3: SEARCH LRCLIB WITH STRICT VALIDATION
        // Prepare search query candidates
        val searchQueries = mutableListOf<String>()
        if (cleanArtist.isNotBlank() && cleanTitle.isNotBlank()) {
            searchQueries.add("$cleanArtist $cleanTitle")
            val trans = transliterateTitleWords(cleanTitle)
            if (!trans.equals(cleanTitle, ignoreCase = true)) {
                searchQueries.add("$cleanArtist $trans")
            }
        }
        if (rawTitle.isNotBlank() && !rawTitle.equals(cleanTitle, ignoreCase = true)) {
            searchQueries.add(rawTitle)
        }
        searchQueries.add(cleanTitle)

        for (q in searchQueries) {
            try {
                val searched = searchLrclibStrict(q, cleanArtist, cleanTitle)
                if (searched != null && searched.lines.isNotEmpty()) {
                    saveDiskCache(context, cacheKey, searched)
                    memoryCache[cacheKey] = searched
                    return@withContext Result.success(searched)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Search failed for '$q': ${e.message}")
            }
        }

        // 6. STEP 4: GENIUS MULTI-SEARCH WITH TITLE/ARTIST VALIDATION
        for (q in searchQueries.take(2)) {
            try {
                val geniusResult = searchAndScrapeGeniusValidated(q, cleanArtist, cleanTitle)
                if (geniusResult != null && geniusResult.lines.isNotEmpty()) {
                    saveDiskCache(context, cacheKey, geniusResult)
                    memoryCache[cacheKey] = geniusResult
                    return@withContext Result.success(geniusResult)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Genius multi-search failed for '$q': ${e.message}")
            }
        }

        // 7. STEP 5: WEB SEARCH FALLBACK (DuckDuckGo / Search Engine)
        for (q in searchQueries.take(2)) {
            try {
                val web = searchWebLyrics(cleanArtist, cleanTitle, q)
                if (web != null && web.lines.isNotEmpty()) {
                    saveDiskCache(context, cacheKey, web)
                    memoryCache[cacheKey] = web
                    return@withContext Result.success(web)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Web search failed for '$q': ${e.message}")
            }
        }

        return@withContext Result.failure(Exception("Текст не найден. Попробуйте ручной поиск."))
    }

    private fun transliterateTitleWords(title: String): String {
        val words = title.split(Regex("\\s+")).map { word ->
            val lower = word.lowercase()
            TITLE_WORD_MAP[lower] ?: word
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
                    sourceName = "LRC Караоке"
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
                    sourceName = "LRCLIB"
                )
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

            // STRICT TITLE MATCH REQUIRED!
            if (!isTitleMatch(expectedTitle, track)) continue

            // If artist is known, verify artist match!
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
                        sourceName = "LRC Караоке"
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
                        sourceName = "LRCLIB"
                    )
                }
            }
        }

        return null
    }

    // --- GENIUS.COM DIRECT SLUG SCRAPE ---

    private fun scrapeGeniusDirectSlug(artist: String, title: String): LyricsResult? {
        val directUrl = constructDirectGeniusUrl(artist, title)
        val result = fetchGeniusHtmlAndParse(directUrl)
        if (result != null && result.lines.isNotEmpty()) {
            return result.copy(title = title, artist = artist)
        }
        return null
    }

    private fun searchAndScrapeGeniusValidated(query: String, expectedArtist: String, expectedTitle: String): LyricsResult? {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "https://genius.com/api/search/multi?q=$encodedQuery"

        val songUrl = try {
            val request = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("Referer", "https://genius.com/")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (response.isSuccessful && body.contains("\"hits\"")) {
                parseGeniusSongUrlStrict(body, expectedArtist, expectedTitle)
            } else null
        } catch (e: Exception) {
            null
        }

        if (songUrl != null) {
            val lyrics = fetchGeniusHtmlAndParse(songUrl)
            if (lyrics != null && lyrics.lines.isNotEmpty()) {
                return lyrics.copy(title = expectedTitle, artist = expectedArtist)
            }
        }
        return null
    }

    private fun parseGeniusSongUrlStrict(jsonString: String, expectedArtist: String, expectedTitle: String): String? {
        val json = JSONObject(jsonString)
        val sections = json.getJSONObject("response").getJSONArray("sections")
        for (i in 0 until sections.length()) {
            val section = sections.getJSONObject(i)
            if (section.optString("type") == "song") {
                val hits = section.getJSONArray("hits")
                for (j in 0 until hits.length()) {
                    val hitResult = hits.getJSONObject(j).getJSONObject("result")
                    val hitTitle = hitResult.optString("title", "")
                    val primaryArtist = hitResult.optJSONObject("primary_artist")?.optString("name", "") ?: ""

                    if (isTitleMatch(expectedTitle, hitTitle)) {
                        if (expectedArtist.isBlank() || isArtistMatch(expectedArtist, primaryArtist)) {
                            return hitResult.optString("url", "")
                        }
                    }
                }
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

    // --- WEB SEARCH FALLBACK ---

    private fun searchWebLyrics(artist: String, title: String, query: String): LyricsResult? {
        val q = "$query текст песни".trim()
        val searchUrl = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(q, "UTF-8")

        val request = Request.Builder()
            .url(searchUrl)
            .header("User-Agent", USER_AGENT)
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return null
        val html = response.body?.string() ?: return null

        val doc = Jsoup.parse(html)
        val snippets = doc.select(".result__snippet")
        for (snippet in snippets) {
            val text = snippet.text()
            if (text.length > 70 && !text.contains("Скачать", ignoreCase = true)) {
                val lines = sanitizeLyricsLines(text)
                if (lines.size >= 3) {
                    return LyricsResult(
                        title = title,
                        artist = artist,
                        lines = lines,
                        plainLyrics = text,
                        sourceUrl = searchUrl,
                        isSynced = false,
                        sourceName = "Веб-поиск"
                    )
                }
            }
        }
        return null
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

    private fun executeGetString(url: String): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()
            val response = client.newCall(request).execute()
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
