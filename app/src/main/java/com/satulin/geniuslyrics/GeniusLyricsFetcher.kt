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
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val memoryCache = mutableMapOf<String, LyricsResult>()

    suspend fun getLyrics(context: Context, artist: String, title: String): Result<LyricsResult> =
        withContext(Dispatchers.IO) {
            val cacheKey = sanitizeKey("$artist - $title")

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

            // 3. PRIORITY 1: Check LRCLIB for EXACT TIME-SYNCED lyrics (line-by-line karaoked)
            try {
                val lrclibSynced = fetchLrclibSynced(artist, title)
                if (lrclibSynced != null && lrclibSynced.lines.isNotEmpty()) {
                    saveDiskCache(context, cacheKey, lrclibSynced)
                    memoryCache[cacheKey] = lrclibSynced
                    return@withContext Result.success(lrclibSynced)
                }
            } catch (e: Exception) {
                Log.w(TAG, "LRCLIB synced check: ${e.message}")
            }

            // 4. PRIORITY 2: GENIUS.COM (Official primary requested lyrics source)
            try {
                val geniusResult = searchAndScrapeGenius(artist, title)
                if (geniusResult != null && geniusResult.lines.isNotEmpty()) {
                    saveDiskCache(context, cacheKey, geniusResult)
                    memoryCache[cacheKey] = geniusResult
                    return@withContext Result.success(geniusResult)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Genius scrape error: ${e.message}")
            }

            // 5. PRIORITY 3: LRCLIB Plain lyrics (large open database)
            try {
                val lrclibPlain = fetchLrclibPlain(artist, title)
                if (lrclibPlain != null && lrclibPlain.lines.isNotEmpty()) {
                    saveDiskCache(context, cacheKey, lrclibPlain)
                    memoryCache[cacheKey] = lrclibPlain
                    return@withContext Result.success(lrclibPlain)
                }
            } catch (e: Exception) {
                Log.w(TAG, "LRCLIB plain check: ${e.message}")
            }

            // 6. PRIORITY 4: Lyrics.ovh API (International database)
            try {
                val ovhResult = fetchLyricsOvh(artist, title)
                if (ovhResult != null && ovhResult.lines.isNotEmpty()) {
                    saveDiskCache(context, cacheKey, ovhResult)
                    memoryCache[cacheKey] = ovhResult
                    return@withContext Result.success(ovhResult)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Lyrics.ovh check: ${e.message}")
            }

            // 7. PRIORITY 5: Textyl / Open Russian/International Search Fallback
            try {
                val fallbackResult = searchFallbackLyrics(artist, title)
                if (fallbackResult != null && fallbackResult.lines.isNotEmpty()) {
                    saveDiskCache(context, cacheKey, fallbackResult)
                    memoryCache[cacheKey] = fallbackResult
                    return@withContext Result.success(fallbackResult)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Fallback search check: ${e.message}")
            }

            return@withContext Result.failure(Exception("Текст не найден ни на Genius, ни в резервных базах"))
        }

    // --- SOURCE 1 & 3: LRCLIB (Synced & Plain) ---

    private fun fetchLrclibSynced(artist: String, title: String): LyricsResult? {
        val queryUrl = "https://lrclib.net/api/get?artist_name=" +
                URLEncoder.encode(artist, "UTF-8") +
                "&track_name=" + URLEncoder.encode(title, "UTF-8")

        val json = executeGetJson(queryUrl) ?: return null
        val synced = json.optString("syncedLyrics", "")
        if (synced.isNotBlank()) {
            val lines = parseLrcLines(synced)
            if (lines.isNotEmpty()) {
                val plain = json.optString("plainLyrics", lines.joinToString("\n") { it.text })
                return LyricsResult(
                    title = title,
                    artist = artist,
                    lines = lines,
                    plainLyrics = plain,
                    sourceUrl = "https://lrclib.net",
                    isSynced = true,
                    sourceName = "LRC Synced"
                )
            }
        }
        return null
    }

    private fun fetchLrclibPlain(artist: String, title: String): LyricsResult? {
        val queryUrl = "https://lrclib.net/api/search?q=" +
                URLEncoder.encode("$artist $title", "UTF-8")

        val responseStr = executeGetString(queryUrl) ?: return null
        val jsonArray = JSONArray(responseStr)
        if (jsonArray.length() == 0) return null

        val firstObj = jsonArray.getJSONObject(0)
        val plain = firstObj.optString("plainLyrics", "")
        val synced = firstObj.optString("syncedLyrics", "")

        val lines = if (synced.isNotBlank()) {
            parseLrcLines(synced)
        } else {
            plain.lines().map { LyricLine(text = it.trimEnd()) }.filter { it.text.isNotBlank() }
        }

        if (lines.isEmpty()) return null

        return LyricsResult(
            title = title,
            artist = artist,
            lines = lines,
            plainLyrics = plain,
            sourceUrl = "https://lrclib.net",
            isSynced = synced.isNotBlank(),
            sourceName = "LRCLIB"
        )
    }

    // --- SOURCE 2: GENIUS.COM ---

    private fun searchAndScrapeGenius(artist: String, title: String): LyricsResult? {
        val query = "$artist $title lyrics"
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

        val lines = cleaned.lines()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
            .map { LyricLine(text = it) }

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

    // --- SOURCE 4: LYRICS.OVH ---

    private fun fetchLyricsOvh(artist: String, title: String): LyricsResult? {
        if (artist.isBlank() || title.isBlank()) return null
        val url = "https://api.lyrics.ovh/v1/" +
                URLEncoder.encode(artist, "UTF-8") + "/" +
                URLEncoder.encode(title, "UTF-8")

        val json = executeGetJson(url) ?: return null
        val raw = json.optString("lyrics", "")
        if (raw.isBlank()) return null

        val lines = raw.lines()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
            .map { LyricLine(text = it) }

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

    // --- SOURCE 5: FALLBACK GOOGLE SEARCH EXTRACTOR ---

    private fun searchFallbackLyrics(artist: String, title: String): LyricsResult? {
        val query = "$artist $title текст песни слова"
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
            if (text.length > 80 && (text.contains("[") || text.lines().size > 2)) {
                val lines = text.lines().map { LyricLine(text = it.trim()) }.filter { it.text.isNotBlank() }
                if (lines.size >= 3) {
                    return LyricsResult(
                        title = title,
                        artist = artist,
                        lines = lines,
                        plainLyrics = text,
                        sourceUrl = searchUrl,
                        isSynced = false,
                        sourceName = "Web Search"
                    )
                }
            }
        }
        return null
    }

    // --- HELPER METHODS ---

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
            } else if (line.isNotBlank() && !line.startsWith("[ti:") && !line.startsWith("[ar:")) {
                result.add(LyricLine(timeMs = -1L, text = line))
            }
        }
        return result
    }

    private fun executeGetJson(url: String): JSONObject? {
        val str = executeGetString(url) ?: return null
        return try {
            JSONObject(str)
        } catch (e: Exception) {
            null
        }
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
