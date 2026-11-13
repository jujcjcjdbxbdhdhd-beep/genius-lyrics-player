package com.satulin.geniuslyrics

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class LyricsResult(
    val title: String,
    val artist: String,
    val lyrics: String,
    val sourceUrl: String? = null,
    val isSynced: Boolean = false
)

object GeniusLyricsFetcher {

    private const val TAG = "GeniusLyricsFetcher"
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
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

            // 1. Search and scrape Genius.com first (prioritizing authentic Genius lyrics)
            try {
                val geniusResult = searchAndScrapeGenius(artist, title)
                if (geniusResult != null && geniusResult.lyrics.isNotBlank()) {
                    saveDiskCache(context, cacheKey, geniusResult)
                    memoryCache[cacheKey] = geniusResult
                    return@withContext Result.success(geniusResult)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Genius scrape error: ${e.message}")
            }

            // 2. Fallback to LRCLIB if Genius is unreachable or doesn't have the song
            try {
                val lrclibResult = fetchFromLrclib(artist, title)
                if (lrclibResult != null && lrclibResult.lyrics.isNotBlank()) {
                    saveDiskCache(context, cacheKey, lrclibResult)
                    memoryCache[cacheKey] = lrclibResult
                    return@withContext Result.success(lrclibResult)
                }
            } catch (e: Exception) {
                Log.w(TAG, "LRCLIB query failed: ${e.message}")
            }

            return@withContext Result.failure(Exception("Текст песни не найден на Genius"))
        }

    private fun searchAndScrapeGenius(artist: String, title: String): LyricsResult? {
        val query = "$artist $title lyrics"
        val encodedQuery = URLEncoder.encode(query, "UTF-8")

        // First attempt: search via Genius search API
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
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } ?: constructDirectGeniusUrl(artist, title)

        Log.d(TAG, "Fetching Genius page: $songUrl")

        // Scrape song page
        val pageRequest = Request.Builder()
            .url(songUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()

        val pageResponse = client.newCall(pageRequest).execute()
        if (!pageResponse.isSuccessful) {
            return null
        }

        val html = pageResponse.body?.string() ?: return null
        val doc = Jsoup.parse(html)

        // Genius lyrics containers: div[data-lyrics-container="true"] or .lyrics
        val lyricContainers = doc.select("div[data-lyrics-container=true]")
        val lyricsText = if (lyricContainers.isNotEmpty()) {
            val sb = StringBuilder()
            for (container in lyricContainers) {
                // Convert <br> tags into newlines before text extraction
                container.select("br").append("\\n")
                container.select("p").prepend("\\n\\n")
                val text = container.text().replace("\\n", "\n")
                sb.append(text).append("\n\n")
            }
            sb.toString().trim()
        } else {
            // Older Genius format
            doc.select(".lyrics").text().trim()
        }

        if (lyricsText.isBlank()) return null

        return LyricsResult(
            title = title,
            artist = artist,
            lyrics = cleanLyrics(lyricsText),
            sourceUrl = songUrl
        )
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
        // Construct canonical fallback URL: https://genius.com/Artist-title-lyrics
        fun slug(text: String): String =
            text.replace(Regex("[^a-zA-Z0-9а-яА-ЯёЁ]+"), "-")
                .trim('-')
                .capitalizeWords()

        val artistSlug = slug(artist)
        val titleSlug = slug(title)
        return "https://genius.com/$artistSlug-$titleSlug-lyrics"
    }

    private fun fetchFromLrclib(artist: String, title: String): LyricsResult? {
        val url = "https://lrclib.net/api/get?artist_name=" +
                URLEncoder.encode(artist, "UTF-8") +
                "&track_name=" + URLEncoder.encode(title, "UTF-8")

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "GeniusLyricsPlayer/1.0 (Android)")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return null

        val body = response.body?.string() ?: return null
        val json = JSONObject(body)

        val plainLyrics = json.optString("plainLyrics", "")
        val syncedLyrics = json.optString("syncedLyrics", "")

        val lyrics = when {
            plainLyrics.isNotBlank() -> plainLyrics
            syncedLyrics.isNotBlank() -> cleanSyncedLyrics(syncedLyrics)
            else -> ""
        }

        if (lyrics.isBlank()) return null

        return LyricsResult(
            title = title,
            artist = artist,
            lyrics = lyrics,
            sourceUrl = "https://genius.com",
            isSynced = syncedLyrics.isNotBlank()
        )
    }

    private fun cleanSyncedLyrics(synced: String): String {
        return synced.lines().joinToString("\n") { line ->
            line.replace(Regex("^\\[\\d{2}:\\d{2}\\.\\d{2,3}\\]"), "").trim()
        }.trim()
    }

    private fun cleanLyrics(raw: String): String {
        // Remove trailing embed text like "123Embed"
        return raw.replace(Regex("\\d*Embed$"), "")
            .replace(Regex("You might also like.*?\n", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun sanitizeKey(key: String): String =
        key.lowercase().replace(Regex("[^a-z0-9а-яё]"), "_")

    private fun String.capitalizeWords(): String =
        split("-").joinToString("-") { it.replaceFirstChar { c -> c.uppercase() } }

    private fun saveDiskCache(context: Context, key: String, result: LyricsResult) {
        try {
            val cacheDir = File(context.cacheDir, "lyrics")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val file = File(cacheDir, "$key.txt")
            file.writeText("${result.title}\n===\n${result.artist}\n===\n${result.lyrics}")
        } catch (e: Exception) {
            Log.w(TAG, "Cache write error: ${e.message}")
        }
    }

    private fun readDiskCache(context: Context, key: String): LyricsResult? {
        return try {
            val cacheDir = File(context.cacheDir, "lyrics")
            val file = File(cacheDir, "$key.txt")
            if (!file.exists()) return null
            val content = file.readText()
            val parts = content.split("\n===\n")
            if (parts.size >= 3) {
                LyricsResult(
                    title = parts[0],
                    artist = parts[1],
                    lyrics = parts.subList(2, parts.size).joinToString("\n===\n")
                )
            } else null
        } catch (e: Exception) {
            null
        }
    }
}
