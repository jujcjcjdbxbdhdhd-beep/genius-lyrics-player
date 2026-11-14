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
        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val memoryCache = mutableMapOf<String, LyricsResult>()

    suspend fun getLyrics(
        context: Context,
        artist: String,
        title: String,
        rawTitle: String = ""
    ): Result<LyricsResult> = withContext(Dispatchers.IO) {
        val cleanArtist = if (artist.equals("null", ignoreCase = true)) "" else artist.trim()
        val cleanTitle = if (title.equals("null", ignoreCase = true)) "" else title.trim()
        val cacheKey = sanitizeKey("$cleanArtist - $cleanTitle")

        // 1. Check memory cache
        memoryCache[cacheKey]?.let {
            return@withContext Result.success(it)
        }

        // 2. Check disk cache
        val diskCached = readDiskCache(context, cacheKey)
        if (diskCached != null) {
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

        // 4. PHASE 2: GENIUS.COM (Original primary lyrics source)
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
        for (query in candidates) {
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

        // 7. PHASE 5: DUCKDUCKGO / OPEN WEB PARSER (Fallback for underground/remix Russian tracks)
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

        return@withContext Result.failure(Exception("Текст не найден. Попробуйте ручной поиск в строке вверху."))
    }

    private fun generateSearchCandidates(artist: String, title: String, rawTitle: String): List<Pair<String, String>> {
        val set = mutableSetOf<Pair<String, String>>()

        // Clean versions
        val a = cleanWord(artist)
        val t = cleanWord(title)

        if (a.isNotBlank() && t.isNotBlank()) {
            set.add(Pair(a, t))
            set.add(Pair(t, a)) // swapped in case artist/title were inverted
        }

        if (t.isNotBlank()) {
            set.add(Pair("", t))
        }

        // Handle raw title from SoundCloud: e.g. "MACAN - ASPHALT 8 (prod stereoRYZE)"
        if (rawTitle.isNotBlank()) {
            val strippedRaw = rawTitle
                .replace(Regex("\\[.*?\\]|\\(.*?\\)"), "")
                .replace(Regex("\\b(prod|slowed|reverb|speed up|sped up|remix|bass boosted|snippet|edit|nightcore|feat|ft)\\b.*", RegexOption.IGNORE_CASE), "")
                .trim()

            val seps = listOf(" - ", " — ", " – ")
            for (sep in seps) {
                if (strippedRaw.contains(sep)) {
                    val parts = strippedRaw.split(sep, limit = 2)
                    val p1 = cleanWord(parts[0])
                    val p2 = cleanWord(parts[1])
                    if (p1.isNotBlank() && p2.isNotBlank()) {
                        set.add(Pair(p1, p2))
                        set.add(Pair("", "$p1 $p2"))
                        set.add(Pair(p2, p1))
                    }
                }
            }
            if (strippedRaw.isNotBlank()) {
                set.add(Pair("", cleanWord(strippedRaw)))
            }
        }

        return set.toList()
    }

    private fun cleanWord(word: String): String {
        return word.replace(Regex("[\"“”«»_]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    // --- LRCLIB (Synced) ---

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
                val synced = item.optString("syncedLyrics", "")
                if (synced.isNotBlank()) {
                    val lines = parseLrcLines(synced)
                    if (lines.isNotEmpty()) {
                        return LyricsResult(
                            title = item.optString("trackName", title),
                            artist = item.optString("artistName", artist),
                            lines = lines,
                            plainLyrics = item.optString("plainLyrics", lines.joinToString("\n") { it.text }),
                            sourceUrl = "https://lrclib.net",
                            isSynced = true,
                            sourceName = "LRC Караоке"
                        )
                    }
                }
            }
        } else {
            val obj = JSONObject(jsonStr)
            val synced = obj.optString("syncedLyrics", "")
            if (synced.isNotBlank()) {
                val lines = parseLrcLines(synced)
                if (lines.isNotEmpty()) {
                    return LyricsResult(
                        title = obj.optString("trackName", title),
                        artist = obj.optString("artistName", artist),
                        lines = lines,
                        plainLyrics = obj.optString("plainLyrics", lines.joinToString("\n") { it.text }),
                        sourceUrl = "https://lrclib.net",
                        isSynced = true,
                        sourceName = "LRC Караоке"
                    )
                }
            }
        }
        return null
    }

    private fun fetchLrclibPlain(artist: String, title: String): LyricsResult? {
        val q = "$artist $title".trim()
        val url = "https://lrclib.net/api/search?q=" + URLEncoder.encode(q, "UTF-8")
        val jsonStr = executeGetString(url) ?: return null

        val arr = JSONArray(jsonStr)
        if (arr.length() == 0) return null

        for (i in 0 until arr.length().coerceAtMost(3)) {
            val item = arr.getJSONObject(i)
            val plain = item.optString("plainLyrics", "")
            val synced = item.optString("syncedLyrics", "")

            val lines = if (synced.isNotBlank()) {
                parseLrcLines(synced)
            } else {
                sanitizeLyricsLines(plain)
            }

            if (lines.isNotEmpty()) {
                return LyricsResult(
                    title = item.optString("trackName", title),
                    artist = item.optString("artistName", artist),
                    lines = lines,
                    plainLyrics = plain,
                    sourceUrl = "https://lrclib.net",
                    isSynced = synced.isNotBlank(),
                    sourceName = "LRCLIB"
                )
            }
        }
        return null
    }

    // --- GENIUS.COM ---

    private fun searchAndScrapeGenius(artist: String, title: String): LyricsResult? {
        val query = "$artist $title".trim()
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "https://genius.com/api/search/multi?q=$encodedQuery"

        val songUrl = try {
            val request = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (response.isSuccessful && body.contains("\"hits\"")) {
                parseGeniusSongUrl(body)
            } else null
        } catch (e: Exception) {
            null
        } ?: constructDirectGeniusUrl(artist, title)

        val pageRequest = Request.Builder()
            .url(songUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()

        val pageResponse = client.newCall(pageRequest).execute()
        if (!pageResponse.isSuccessful) return null

        val html = pageResponse.body?.string() ?: return null
        val doc = Jsoup.parse(html)

        val lyricContainers = doc.select("div[data-lyrics-container=true]")
        val rawText = if (lyricContainers.isNotEmpty()) {
            val sb = StringBuilder()
            for (container in lyricContainers) {
                container.select("br").append("\\n")
                container.select("p").prepend("\\n\\n")
                val text = container.text().replace("\\n", "\n")
                sb.append(text).append("\n\n")
            }
            sb.toString().trim()
        } else {
            doc.select(".lyrics").text().trim()
        }

        if (rawText.isBlank()) return null
        val cleaned = cleanLyrics(rawText)
        val lines = sanitizeLyricsLines(cleaned)

        if (lines.isEmpty()) return null

        return LyricsResult(
            title = title,
            artist = artist,
            lines = lines,
            plainLyrics = cleaned,
            sourceUrl = songUrl,
            isSynced = false,
            sourceName = "Genius.com"
        )
    }

    // --- LYRICS.OVH ---

    private fun fetchLyricsOvh(artist: String, title: String): LyricsResult? {
        val url = "https://api.lyrics.ovh/v1/" +
                URLEncoder.encode(artist, "UTF-8") + "/" +
                URLEncoder.encode(title, "UTF-8")

        val str = executeGetString(url) ?: return null
        val json = JSONObject(str)
        val raw = json.optString("lyrics", "")
        if (raw.isBlank()) return null

        val lines = sanitizeLyricsLines(raw)
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
                if (text.isNotBlank()) {
                    result.add(LyricLine(timeMs = totalMs, text = text))
                }
            } else if (line.isNotBlank() && !line.startsWith("[ti:") && !line.startsWith("[ar:") && !line.startsWith("[length:")) {
                result.add(LyricLine(timeMs = -1L, text = line))
            }
        }
        return result
    }

    private fun sanitizeLyricsLines(text: String): List<LyricLine> {
        return text.lines()
            .map { it.trimEnd() }
            .filter { line ->
                val t = line.trim()
                t.isNotBlank() &&
                !t.equals("null", ignoreCase = true) &&
                !t.startsWith("Embed", ignoreCase = true)
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

    private fun constructDirectGeniusUrl(artist: String, title: String): String {
        fun slug(text: String): String =
            text.replace(Regex("[^a-zA-Z0-9а-яА-ЯёЁ]+"), "-")
                .trim('-')
                .split("-").joinToString("-") { it.replaceFirstChar { c -> c.uppercase() } }

        return "https://genius.com/${slug(artist)}-${slug(title)}-lyrics"
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
                lines.add(LyricLine(item.optLong("timeMs", -1L), item.optString("text", "")))
            }
            LyricsResult(
                title = json.getString("title"),
                artist = json.getString("artist"),
                lines = lines,
                plainLyrics = json.optString("plainLyrics", ""),
                sourceUrl = json.optString("sourceUrl", ""),
                isSynced = json.optBoolean("isSynced", false),
                sourceName = json.optString("sourceName", "Cache")
            )
        } catch (e: Exception) {
            null
        }
    }
}
