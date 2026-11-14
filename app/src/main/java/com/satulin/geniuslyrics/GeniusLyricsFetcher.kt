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

        val cacheKey = sanitizeKey("$cleanArtist - $cleanTitle")

        // 1. Check memory cache
        memoryCache[cacheKey]?.let {
            if (it.lines.isNotEmpty() && !it.plainLyrics.equals("null", ignoreCase = true)) {
                return@withContext Result.success(it)
            }
        }

        // 2. Check disk cache
        val diskCached = readDiskCache(context, cacheKey)
        if (diskCached != null && diskCached.lines.isNotEmpty() && !diskCached.plainLyrics.equals("null", ignoreCase = true)) {
            memoryCache[cacheKey] = diskCached
            return@withContext Result.success(diskCached)
        }

        // Prepare smart candidate queries
        val candidates = generateSearchCandidates(cleanArtist, cleanTitle, rawTitle)
        Log.d(TAG, "Search candidates for ($cleanArtist, $cleanTitle): $candidates")

        // 3. PHASE 1: Search LRCLIB for EXACT TIME-SYNCED LYRICS
        for (query in candidates) {
            try {
                val synced = fetchLrclibSyncedByQuery(query.first, query.second)
                if (synced != null && synced.lines.isNotEmpty()) {
                    saveDiskCache(context, cacheKey, synced)
                    memoryCache[cacheKey] = synced
                    return@withContext Result.success(synced)
                }
            } catch (e: Exception) {
                Log.w(TAG, "LRCLIB synced failed for $query: ${e.message}")
            }
        }

        // 4. PHASE 2: GENIUS.COM (Direct Scrape & Multi Search)
        for (query in candidates) {
            try {
                val geniusResult = searchAndScrapeGenius(query.first, query.second)
                if (geniusResult != null && geniusResult.lines.isNotEmpty()) {
                    saveDiskCache(context, cacheKey, geniusResult)
                    memoryCache[cacheKey] = geniusResult
                    return@withContext Result.success(geniusResult)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Genius failed for $query: ${e.message}")
            }
        }

        // 5. PHASE 3: LRCLIB PLAIN LYRICS (Large global database)
        for (query in candidates) {
            try {
                val lrclibPlain = fetchLrclibPlain(query.first, query.second)
                if (lrclibPlain != null && lrclibPlain.lines.isNotEmpty()) {
                    saveDiskCache(context, cacheKey, lrclibPlain)
                    memoryCache[cacheKey] = lrclibPlain
                    return@withContext Result.success(lrclibPlain)
                }
            } catch (e: Exception) {
                Log.w(TAG, "LRCLIB plain failed for $query: ${e.message}")
            }
        }

        // 6. PHASE 4: LYRICS.OVH (Secondary API)
        for (query in candidates.take(4)) {
            if (query.first.isNotBlank() && query.second.isNotBlank()) {
                try {
                    val ovh = fetchLyricsOvh(query.first, query.second)
                    if (ovh != null && ovh.lines.isNotEmpty()) {
                        saveDiskCache(context, cacheKey, ovh)
                        memoryCache[cacheKey] = ovh
                        return@withContext Result.success(ovh)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Lyrics.ovh failed: ${e.message}")
                }
            }
        }

        // 7. PHASE 5: WEB SEARCH FALLBACK (DuckDuckGo / Search Engine)
        for (query in candidates.take(3)) {
            try {
                val web = searchWebLyrics(query.first, query.second)
                if (web != null && web.lines.isNotEmpty()) {
                    saveDiskCache(context, cacheKey, web)
                    memoryCache[cacheKey] = web
                    return@withContext Result.success(web)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Web search failed for $query: ${e.message}")
            }
        }

        return@withContext Result.failure(Exception("Текст не найден на Genius и в базах"))
    }

    private fun generateSearchCandidates(artist: String, title: String, rawTitle: String): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        val seen = mutableSetOf<String>()

        fun addCandidate(a: String, t: String) {
            val ca = MediaNotificationListener.sanitizeText(a)
            val ct = MediaNotificationListener.sanitizeText(t)
            if (ca.isBlank() && ct.isBlank()) return
            val key = "$ca|$ct".lowercase()
            if (seen.add(key)) {
                list.add(Pair(ca, ct))
            }
        }

        val a = cleanWord(artist)
        val t = cleanWord(title)

        // 1. Exact artist and title
        if (a.isNotBlank() && t.isNotBlank()) {
            addCandidate(a, t)
            addCandidate("", "$a $t")
        }

        // 2. Transliterated / Latin converted title words
        if (t.isNotBlank()) {
            val words = t.split(Regex("\\s+")).map { word ->
                val lower = word.lowercase()
                TITLE_WORD_MAP[lower] ?: word
            }
            val transTitle = words.joinToString(" ")
            if (!transTitle.equals(t, ignoreCase = true)) {
                if (a.isNotBlank()) {
                    addCandidate(a, transTitle)
                    addCandidate("", "$a $transTitle")
                } else {
                    addCandidate("", transTitle)
                }
            }
        }

        // 3. Raw title variations from SoundCloud
        if (rawTitle.isNotBlank()) {
            val stripped = rawTitle
                .replace(Regex("\\[.*?\\]|\\(.*?\\)"), "")
                .replace(Regex("\\b(prod|slowed|reverb|speed up|sped up|remix|bass boosted|snippet|edit|nightcore|feat|ft)\\b.*", RegexOption.IGNORE_CASE), "")
                .trim()

            val seps = listOf(" - ", " — ", " – ")
            for (sep in seps) {
                if (stripped.contains(sep)) {
                    val parts = stripped.split(sep, limit = 2)
                    val p1 = cleanWord(parts[0])
                    val p2 = cleanWord(parts[1])
                    val mappedP1 = MediaNotificationListener.canonicalizeArtist(p1)
                    if (mappedP1.isNotBlank() && p2.isNotBlank()) {
                        addCandidate(mappedP1, p2)
                        addCandidate("", "$mappedP1 $p2")
                        addCandidate(p2, mappedP1)
                    }
                }
            }

            if (stripped.isNotBlank()) {
                val parsedRaw = MediaNotificationListener.parseTrack(stripped)
                if (parsedRaw.artist.isNotBlank() && parsedRaw.title.isNotBlank()) {
                    addCandidate(parsedRaw.artist, parsedRaw.title)
                    addCandidate("", "${parsedRaw.artist} ${parsedRaw.title}")
                }
                addCandidate("", cleanWord(stripped))
            }
        }

        // 4. Inverted (title as artist in case SoundCloud uploaded Title - Artist)
        if (a.isNotBlank() && t.isNotBlank()) {
            addCandidate(t, a)
        }

        // 5. Title alone
        if (t.isNotBlank()) {
            addCandidate("", t)
        }

        return list
    }

    private fun cleanWord(word: String): String {
        return MediaNotificationListener.sanitizeText(word)
            .replace(Regex("[\"“”«»_]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    // --- LRCLIB (Synced Karaoke) ---

    private fun fetchLrclibSyncedByQuery(artist: String, title: String): LyricsResult? {
        val url = if (artist.isNotBlank() && title.isNotBlank()) {
            "https://lrclib.net/api/get?artist_name=" + URLEncoder.encode(artist, "UTF-8") +
                    "&track_name=" + URLEncoder.encode(title, "UTF-8")
        } else {
            "https://lrclib.net/api/search?q=" + URLEncoder.encode("$artist $title".trim(), "UTF-8")
        }

        val jsonStr = executeGetString(url) ?: return null
        if (jsonStr.startsWith("[")) {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val synced = item.optCleanString("syncedLyrics")
                if (synced.isNotBlank()) {
                    val lines = parseLrcLines(synced)
                    if (lines.isNotEmpty()) {
                        val track = item.optCleanString("trackName").ifBlank { title }
                        val art = item.optCleanString("artistName").ifBlank { artist }
                        val plain = item.optCleanString("plainLyrics").ifBlank { lines.joinToString("\n") { it.text } }
                        return LyricsResult(
                            title = track,
                            artist = art,
                            lines = lines,
                            plainLyrics = plain,
                            sourceUrl = "https://lrclib.net",
                            isSynced = true,
                            sourceName = "LRC Караоке"
                        )
                    }
                }
            }
        } else if (jsonStr.startsWith("{")) {
            val obj = JSONObject(jsonStr)
            val synced = obj.optCleanString("syncedLyrics")
            if (synced.isNotBlank()) {
                val lines = parseLrcLines(synced)
                if (lines.isNotEmpty()) {
                    val track = obj.optCleanString("trackName").ifBlank { title }
                    val art = obj.optCleanString("artistName").ifBlank { artist }
                    val plain = obj.optCleanString("plainLyrics").ifBlank { lines.joinToString("\n") { it.text } }
                    return LyricsResult(
                        title = track,
                        artist = art,
                        lines = lines,
                        plainLyrics = plain,
                        sourceUrl = "https://lrclib.net",
                        isSynced = true,
                        sourceName = "LRC Караоке"
                    )
                }
            }
        }
        return null
    }

    // --- LRCLIB (Plain Text) ---

    private fun fetchLrclibPlain(artist: String, title: String): LyricsResult? {
        val q = "$artist $title".trim()
        val url = "https://lrclib.net/api/search?q=" + URLEncoder.encode(q, "UTF-8")
        val jsonStr = executeGetString(url) ?: return null

        val arr = JSONArray(jsonStr)
        if (arr.length() == 0) return null

        for (i in 0 until arr.length().coerceAtMost(4)) {
            val item = arr.getJSONObject(i)
            val synced = item.optCleanString("syncedLyrics")
            val plain = item.optCleanString("plainLyrics")

            val lines = if (synced.isNotBlank()) {
                parseLrcLines(synced)
            } else if (plain.isNotBlank()) {
                sanitizeLyricsLines(plain)
            } else {
                emptyList()
            }

            if (lines.isNotEmpty()) {
                val track = item.optCleanString("trackName").ifBlank { title }
                val art = item.optCleanString("artistName").ifBlank { artist }
                return LyricsResult(
                    title = track,
                    artist = art,
                    lines = lines,
                    plainLyrics = plain.ifBlank { lines.joinToString("\n") { it.text } },
                    sourceUrl = "https://lrclib.net",
                    isSynced = synced.isNotBlank(),
                    sourceName = "LRCLIB"
                )
            }
        }
        return null
    }

    // --- GENIUS.COM (Official & Direct Scraping) ---

    private fun searchAndScrapeGenius(artist: String, title: String): LyricsResult? {
        // First try direct canonical slug URL: e.g. https://genius.com/Rocket-drive-home-lyrics
        val directUrls = mutableListOf<String>()
        if (artist.isNotBlank() && title.isNotBlank()) {
            directUrls.add(constructDirectGeniusUrl(artist, title))
        }
        if (title.isNotBlank()) {
            directUrls.add("https://genius.com/${slug(title)}-lyrics")
        }

        for (songUrl in directUrls) {
            val lyrics = fetchGeniusHtmlAndParse(songUrl)
            if (lyrics != null && lyrics.lines.isNotEmpty()) {
                return lyrics.copy(title = title, artist = artist)
            }
        }

        // Second: try Genius search API
        val query = "$artist $title".trim()
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "https://genius.com/api/search/multi?q=$encodedQuery"

        val songUrlFromApi = try {
            val request = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("Referer", "https://genius.com/")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (response.isSuccessful && body.contains("\"hits\"")) {
                parseGeniusSongUrl(body)
            } else null
        } catch (e: Exception) {
            null
        }

        if (songUrlFromApi != null) {
            val lyrics = fetchGeniusHtmlAndParse(songUrlFromApi)
            if (lyrics != null && lyrics.lines.isNotEmpty()) {
                return lyrics.copy(title = title, artist = artist)
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

            // 1. Check data-lyrics-container elements
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

            // 2. Fallback to .lyrics selector
            if (rawText.isBlank()) {
                rawText = doc.select(".lyrics").text().trim()
            }

            // 3. Fallback to __NEXT_DATA__ JSON script
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

    // --- LYRICS.OVH ---

    private fun fetchLyricsOvh(artist: String, title: String): LyricsResult? {
        val url = "https://api.lyrics.ovh/v1/" +
                URLEncoder.encode(artist, "UTF-8") + "/" +
                URLEncoder.encode(title, "UTF-8")

        val str = executeGetString(url) ?: return null
        val json = JSONObject(str)
        val raw = json.optCleanString("lyrics")
        if (raw.isBlank()) return null

        val lines = sanitizeLyricsLines(raw)
        if (lines.isEmpty()) return null

        return LyricsResult(
            title = title,
            artist = artist,
            lines = lines,
            plainLyrics = raw,
            sourceUrl = "https://lyrics.ovh",
            isSynced = false,
            sourceName = "Lyrics.ovh"
        )
    }

    // --- WEB SEARCH FALLBACK ---

    private fun searchWebLyrics(artist: String, title: String): LyricsResult? {
        val query = "$artist $title текст песни".trim()
        val searchUrl = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8")

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
                if (lines.size >= 2) {
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

    private fun parseGeniusSongUrl(jsonString: String): String? {
        val json = JSONObject(jsonString)
        val sections = json.getJSONObject("response").getJSONArray("sections")
        for (i in 0 until sections.length()) {
            val section = sections.getJSONObject(i)
            if (section.optString("type") == "song") {
                val hits = section.getJSONArray("hits")
                if (hits.length() > 0) {
                    val firstHit = hits.getJSONObject(0).getJSONObject("result")
                    return firstHit.getString("url")
                }
            }
        }
        return null
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
