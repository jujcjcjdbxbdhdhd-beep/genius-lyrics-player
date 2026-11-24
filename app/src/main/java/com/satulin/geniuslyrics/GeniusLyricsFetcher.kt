package com.satulin.geniuslyrics

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
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
    val sourceName: String = "Spotify",
    val geniusUrl: String = "",
    val lrcDurationMs: Long = 0L
)

object GeniusLyricsFetcher {

    private const val TAG = "GeniusLyricsFetcher"
    private const val CACHE_VERSION = "v6_"
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val memoryCache = mutableMapOf<String, LyricsResult>()

    @Volatile
    private var oldCachesCleaned = false

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
        "динамит" to "dynamite",
        "пули" to "bullets"
    )

    private val REGION_TAG_TOKENS = setOf("rus", "ru", "uk", "us", "usa", "de", "fr", "ua", "kz", "by")
    private val CYRILLIC_REGEX = Regex("[а-яА-ЯёЁ]")
    private val PAREN_BRACKET_REGEX = Regex("\\s*[\\(\\[]([^\\)\\]]*)[\\)\\]]")
    private val ARTIST_SPLIT_REGEX = Regex("\\s+(?:и|with|and|x)\\s+|[,&/×]|\\b(?:ft\\.?|feat\\.?|featuring)\\b", RegexOption.IGNORE_CASE)

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

    private fun extractTitleVariants(t: String): List<String> {
        val base = cleanTitleForComparison(t)
        if (base.isBlank()) return emptyList()

        val variants = linkedSetOf<String>()
        variants.add(base)

        val noParens = base.replace(PAREN_BRACKET_REGEX, "")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (noParens.isNotBlank()) {
            variants.add(noParens)
        }

        // Extract bilingual/translation groups like "(Bullets)" or "(In the Dark)" from Genius titles
        for (m in PAREN_BRACKET_REGEX.findAll(base)) {
            val inside = m.groupValues[1].trim()
            if (inside.length >= 2 &&
                !inside.equals("remix", ignoreCase = true) &&
                !inside.equals("bonus", ignoreCase = true) &&
                !inside.equals("intro", ignoreCase = true) &&
                !inside.equals("outro", ignoreCase = true) &&
                !inside.equals("skit", ignoreCase = true)
            ) {
                variants.add(inside)
            }
        }

        val transBase = transliterateTitleWords(base)
        if (transBase.isNotBlank()) variants.add(transBase)
        if (noParens.isNotBlank()) {
            val transNoParens = transliterateTitleWords(noParens)
            if (transNoParens.isNotBlank()) variants.add(transNoParens)
        }

        return variants.toList()
    }

    private fun isSingleVariantTitleMatch(expRaw: String, candRaw: String): Boolean {
        val exp = normalizeForMatch(expRaw)
        val cand = normalizeForMatch(candRaw)
        if (exp.isBlank() || cand.isBlank()) return false
        if (exp == cand) return true

        val minLen = minOf(exp.length, cand.length)
        val maxLen = maxOf(exp.length, cand.length)
        if (minLen >= 3 && (cand.contains(exp) || exp.contains(cand))) {
            val coverage = minLen.toDouble() / maxLen.toDouble()
            if (coverage >= 0.70) return true
        }

        val pExp = phoneticKey(expRaw)
        val pCand = phoneticKey(candRaw)
        if (pExp.isNotBlank() && pCand.isNotBlank()) {
            if (pExp == pCand) return true
            val pMinLen = minOf(pExp.length, pCand.length)
            val pMaxLen = maxOf(pExp.length, pCand.length)
            if (pMinLen >= 4 && (pCand.contains(pExp) || pExp.contains(pCand))) {
                val pCoverage = pMinLen.toDouble() / pMaxLen.toDouble()
                if (pCoverage >= 0.70) return true
            }
        }
        return false
    }

    fun isTitleMatch(expectedTitle: String, candidateTitle: String): Boolean {
        val expVars = extractTitleVariants(expectedTitle)
        val candVars = extractTitleVariants(candidateTitle)
        if (expVars.isEmpty() || candVars.isEmpty()) return false

        for (ev in expVars) {
            for (cv in candVars) {
                if (isSingleVariantTitleMatch(ev, cv)) return true
            }
        }
        return false
    }

    /**
     * Checks whether the expected title words appear as a clean leading prefix of the candidate title
     * (after stripping parentheticals), used ONLY when artist identity has already been strictly verified.
     */
    fun isTitleWordPrefixMatch(expectedTitle: String, candidateTitle: String): Boolean {
        val expClean = cleanTitleForComparison(expectedTitle)
            .replace(PAREN_BRACKET_REGEX, "")
            .lowercase()
            .replace('ё', 'е')
            .replace(Regex("[^a-z0-9а-я\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val candClean = cleanTitleForComparison(candidateTitle)
            .replace(PAREN_BRACKET_REGEX, "")
            .lowercase()
            .replace('ё', 'е')
            .replace(Regex("[^a-z0-9а-я\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (expClean.length < 4 || candClean.isBlank()) return false
        return candClean == expClean || candClean.startsWith("$expClean ")
    }

    private fun extractArtistTokens(raw: String): List<String> {
        val clean = MediaNotificationListener.sanitizeText(raw)
        if (clean.isBlank()) return emptyList()

        val tokens = linkedSetOf<String>()
        val parts = clean.split(ARTIST_SPLIT_REGEX)
        for (part in parts) {
            val trimmed = part.trim()
            if (trimmed.isBlank()) continue
            tokens.add(trimmed)

            val outside = trimmed.replace(PAREN_BRACKET_REGEX, " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (outside.isNotBlank()) {
                tokens.add(outside)
                tokens.add(MediaNotificationListener.canonicalizeArtist(outside))
            }

            for (m in PAREN_BRACKET_REGEX.findAll(trimmed)) {
                val inside = m.groupValues[1].trim()
                val cleanedInside = inside.replace(Regex("^(?:ft\\.?|feat\\.?)\\s+", RegexOption.IGNORE_CASE), "").trim()
                if (cleanedInside.isNotBlank()) {
                    for (sub in cleanedInside.split(ARTIST_SPLIT_REGEX)) {
                        val subTrim = sub.trim()
                        val subNorm = normalizeForMatch(subTrim)
                        if (subNorm.length >= 2 && subNorm !in REGION_TAG_TOKENS) {
                            tokens.add(subTrim)
                            tokens.add(MediaNotificationListener.canonicalizeArtist(subTrim))
                        }
                    }
                }
            }
            tokens.add(MediaNotificationListener.canonicalizeArtist(trimmed))
        }
        return tokens.filter { normalizeForMatch(it).length >= 2 }
    }

    fun isArtistMatch(expectedArtist: String, candidateArtist: String): Boolean {
        if (expectedArtist.isBlank()) return true
        val expTokens = extractArtistTokens(expectedArtist)
        val candTokens = extractArtistTokens(candidateArtist)
        if (expTokens.isEmpty() || candTokens.isEmpty()) return false

        for (et in expTokens) {
            val exp = normalizeForMatch(et)
            val pExp = phoneticKey(et)
            if (exp.isBlank()) continue

            for (ct in candTokens) {
                val cand = normalizeForMatch(ct)
                if (cand.isBlank()) continue
                if (exp == cand) return true

                val minLen = minOf(exp.length, cand.length)
                val maxLen = maxOf(exp.length, cand.length)
                if (minLen >= 3 && (cand.contains(exp) || exp.contains(cand))) {
                    if (minLen.toDouble() / maxLen.toDouble() >= 0.70) return true
                }

                val pCand = phoneticKey(ct)
                if (pExp.isNotBlank() && pCand.isNotBlank()) {
                    if (pExp == pCand) return true
                    val pMin = minOf(pExp.length, pCand.length)
                    val pMax = maxOf(pExp.length, pCand.length)
                    if (pMin >= 4 && (pCand.contains(pExp) || pExp.contains(pCand))) {
                        if (pMin.toDouble() / pMax.toDouble() >= 0.70) return true
                    }
                }
            }
        }
        return false
    }

    private val REPEAT_TAG_REGEX = Regex("[\\(\\[](?:[xх×]\\s*(\\d)|(\\d)\\s*[xх×])[\\)\\]]\\s*$", RegexOption.IGNORE_CASE)
    private val TEMPO_MODIFIER_REGEX = Regex("(?:slowed|reverb|sped\\s*up|speed\\s*up|nightcore|замедленн|ускоренн)", RegexOption.IGNORE_CASE)

    /**
     * Musical-cadence timestamp estimator for unsynced (Genius) lyrics.
     * Instead of linear character weighting (which causes short hook lines to rush ahead
     * and dense rap verses to lag behind), anchors each line to musical bar duration (~1 bar)
     * with gentle word-count modulation, section-header breath pauses, and repeat-tag multipliers.
     */
    fun estimateLineTimestamps(lines: List<LyricLine>, durationMs: Long): List<LyricLine> {
        if (lines.isEmpty() || durationMs <= 10_000L) return lines

        val firstText = lines.first().text.trim().lowercase()
        val startsWithIntroSection = firstText.startsWith("[интро") || firstText.startsWith("[intro")
        val introMs = if (startsWithIntroSection) {
            (durationMs * 0.042).toLong().coerceIn(2000L, 6200L)
        } else {
            (durationMs * 0.072).toLong().coerceIn(3200L, 10500L)
        }
        val outroMs = (durationMs * 0.065).toLong().coerceIn(3000L, 12000L)
        val activeWindow = (durationMs - introMs - outroMs).coerceAtLeast(5000L)

        val weights = lines.mapIndexed { idx, line ->
            val trimmed = line.text.trim()
            val isHeader = trimmed.startsWith("[") && trimmed.endsWith("]")
            if (isHeader) {
                when {
                    idx == 0 -> 0.35
                    trimmed.lowercase().startsWith("[аутро") || trimmed.lowercase().startsWith("[outro") -> 0.85
                    else -> 0.65
                }
            } else {
                val words = trimmed.split(Regex("\\s+")).count { it.isNotBlank() }.coerceIn(1, 16)
                val chars = trimmed.length.coerceIn(4, 64)
                val repeatMatch = REPEAT_TAG_REGEX.find(trimmed)
                val repeatCount = repeatMatch?.groupValues
                    ?.drop(1)
                    ?.firstOrNull { it.isNotBlank() }
                    ?.toIntOrNull()
                    ?.coerceIn(2, 4) ?: 1
                val repeatMult = when (repeatCount) {
                    2 -> 1.85
                    3 -> 2.60
                    4 -> 3.25
                    else -> 1.0
                }
                val baseBar = 0.74 + (words * 0.055) + (chars / 56.0) * 0.26
                baseBar * repeatMult
            }
        }
        val totalWeight = weights.sum().coerceAtLeast(1.0)
        var cumulative = 0.0
        return lines.mapIndexed { idx, line ->
            val ts = (introMs + (cumulative / totalWeight * activeWindow).toLong())
                .coerceIn(0L, durationMs)
            cumulative += weights[idx]
            line.copy(timeMs = ts)
        }
    }

    /**
     * Adapts lyrics timestamps to the actual playing track's duration.
     * - For unsynced lyrics: estimates musical-bar timestamps across trackDurationMs.
     * - For synced LRC lyrics: fills any untagged intermediate lines and automatically scales
     *   timestamps when playing a slowed/sped-up/pitched version whose duration differs from LRC catalog duration.
     */
    fun adaptLyricsToTrackDuration(
        result: LyricsResult,
        trackDurationMs: Long,
        rawTitle: String = ""
    ): List<LyricLine> {
        val rawLines = result.lines
        if (rawLines.isEmpty()) return rawLines

        if (!result.isSynced) {
            return if (trackDurationMs > 10_000L) {
                estimateLineTimestamps(rawLines, trackDurationMs)
            } else {
                rawLines
            }
        }

        // Fill any missing (-1L) timestamps inside synced LRC by interpolating between neighbors
        val filled = fillMissingSyncedTimestamps(rawLines, trackDurationMs)
        if (trackDurationMs <= 15_000L) return filled

        val lrcDur = result.lrcDurationMs
        val maxLineTs = filled.maxOfOrNull { it.timeMs } ?: -1L
        val hasTempoTag = TEMPO_MODIFIER_REGEX.containsMatchIn(rawTitle) ||
            TEMPO_MODIFIER_REGEX.containsMatchIn(result.title)

        var scaleRatio = 1.0
        if (lrcDur > 15_000L) {
            val diffMs = kotlin.math.abs(trackDurationMs - lrcDur)
            val ratio = trackDurationMs.toDouble() / lrcDur.toDouble()
            if (hasTempoTag && diffMs > 1500L && ratio in 0.58..1.70) {
                scaleRatio = ratio
            } else if (diffMs > 3000L && ratio in 0.72..1.38) {
                val lyricsOverflowTrack = maxLineTs > trackDurationMs - 1200L
                val isTypicalTempoShift = (ratio in 0.75..0.94 || ratio in 1.06..1.35) && maxLineTs >= (lrcDur * 0.62).toLong()
                if (lyricsOverflowTrack || isTypicalTempoShift) {
                    scaleRatio = ratio
                }
            }
        } else if (maxLineTs > trackDurationMs - 800L) {
            val impliedLrcDur = maxLineTs + 5000L
            val ratio = trackDurationMs.toDouble() / impliedLrcDur.toDouble()
            if (ratio in 0.58..0.97) {
                scaleRatio = ratio
            }
        }

        if (kotlin.math.abs(scaleRatio - 1.0) < 0.02) {
            return filled
        }

        val maxAllowedTs = (trackDurationMs - 400L).coerceAtLeast(0L)
        return filled.map { line ->
            if (line.timeMs >= 0L) {
                line.copy(timeMs = (line.timeMs * scaleRatio).toLong().coerceIn(0L, maxAllowedTs))
            } else {
                line
            }
        }
    }

    private fun fillMissingSyncedTimestamps(lines: List<LyricLine>, trackDurationMs: Long): List<LyricLine> {
        if (lines.none { it.timeMs < 0L } || lines.all { it.timeMs < 0L }) return lines
        val mutable = lines.toMutableList()
        var i = 0
        while (i < mutable.size) {
            if (mutable[i].timeMs < 0L) {
                val prevTs = if (i > 0) mutable[i - 1].timeMs.coerceAtLeast(0L) else 0L
                var nextIdx = i + 1
                while (nextIdx < mutable.size && mutable[nextIdx].timeMs < 0L) {
                    nextIdx++
                }
                val nextTs = if (nextIdx < mutable.size) {
                    mutable[nextIdx].timeMs
                } else {
                    maxOf(prevTs + (nextIdx - i) * 2500L, trackDurationMs.takeIf { it > prevTs } ?: (prevTs + 3000L))
                }
                val spanCount = (nextIdx - i + 1).coerceAtLeast(2)
                for (k in i until nextIdx) {
                    val step = k - i + 1
                    val interp = prevTs + ((nextTs - prevTs) * step) / spanCount
                    mutable[k] = mutable[k].copy(timeMs = interp.coerceAtLeast(prevTs))
                }
                i = nextIdx
            } else {
                i++
            }
        }
        return mutable
    }

    /**
     * Computes target player seek position in ms, accounting for manual sync offset
     * and clamping safely inside [0, durationMs - 500].
     */
    fun computeSeekTargetMs(lineTimeMs: Long, manualSyncOffsetMs: Long, durationMs: Long): Long {
        val raw = (lineTimeMs - manualSyncOffsetMs).coerceAtLeast(0L)
        return if (durationMs > 0L) {
            raw.coerceAtMost((durationMs - 500L).coerceAtLeast(0L))
        } else {
            raw
        }
    }

    /**
     * Resolves a lyric line's timestamp from explicit LRC tags, weighted estimation,
     * or linear interpolation when track duration is short. Returns -1L if unavailable.
     */
    fun resolveLineTimestampMs(lines: List<LyricLine>, index: Int, durationMs: Long): Long {
        if (index !in lines.indices) return -1L
        val direct = lines[index].timeMs
        if (direct >= 0L) return direct
        if (durationMs > 10_000L) {
            return estimateLineTimestamps(lines, durationMs).getOrNull(index)?.timeMs ?: -1L
        }
        if (durationMs > 0L) {
            val denom = (lines.size - 1).coerceAtLeast(1).toDouble()
            return (index.toDouble() / denom * durationMs)
                .toLong()
                .coerceIn(0L, (durationMs - 500L).coerceAtLeast(0L))
        }
        return -1L
    }

    private fun scoreSyncedCandidateDuration(lrcDurationMs: Long, trackDurationMs: Long): Int {
        if (trackDurationMs <= 10_000L || lrcDurationMs <= 10_000L) return 0
        val diff = kotlin.math.abs(trackDurationMs - lrcDurationMs)
        return when {
            diff <= 2_500L -> 120
            diff <= 5_000L -> 80
            diff <= 10_000L -> 30
            diff > 25_000L -> -60
            else -> 0
        }
    }

    private fun pickBestResult(
        results: List<LyricsResult?>,
        expectedTitle: String = "",
        expectedArtist: String = "",
        requireArtistMatch: Boolean = false,
        trackDurationMs: Long = 0L
    ): LyricsResult? {
        val valid = results.filterNotNull().filter { it.lines.isNotEmpty() }
        if (valid.isEmpty()) return null

        val strictValid = valid.filter { res ->
            val artistOk = !requireArtistMatch || expectedArtist.isBlank() || isArtistMatch(expectedArtist, res.artist)
            val titleOk = expectedTitle.isBlank() ||
                isTitleMatch(expectedTitle, res.title) ||
                (artistOk && expectedArtist.isNotBlank() && isTitleWordPrefixMatch(expectedTitle, res.title))
            titleOk && artistOk
        }
        if (strictValid.isEmpty()) return null

        // 1. Verified synced lyrics (karaoke timestamps) with real body (>2 lines), ranked by duration match
        val syncedCandidates = strictValid.filter { it.isSynced && it.lines.size >= 3 }
        if (syncedCandidates.isNotEmpty()) {
            return syncedCandidates.maxByOrNull { cand ->
                scoreSyncedCandidateDuration(cand.lrcDurationMs, trackDurationMs) +
                    (if (expectedArtist.isNotBlank() && isArtistMatch(expectedArtist, cand.artist)) 40 else 0)
            }
        }
        // 2. Genius.com plain lyrics
        strictValid.firstOrNull { it.sourceName.contains("Genius", ignoreCase = true) }?.let { return it }
        // 3. Other verified plain/short lyrics
        return strictValid.first()
    }

    private fun buildGeniusQueries(artist: String, title: String, requireArtist: Boolean): List<String> {
        val queries = linkedSetOf<String>()
        val cleanArt = MediaNotificationListener.canonicalizeArtist(artist)
        val cleanTitle = MediaNotificationListener.stripNoise(title).ifBlank { title }
        val transTitle = transliterateTitleWords(cleanTitle)
        val hasCyrillicArtist = cleanArt.isNotBlank() && CYRILLIC_REGEX.containsMatchIn(cleanArt)
        val latinArtist = if (hasCyrillicArtist) transliterateRuToLat(cleanArt).trim() else ""

        if (cleanArt.isNotBlank()) {
            queries.add("$cleanArt $cleanTitle")
            if (hasCyrillicArtist && latinArtist.isNotBlank() && !latinArtist.equals(cleanArt, ignoreCase = true)) {
                queries.add("$latinArtist $cleanTitle")
            }
            if (!transTitle.equals(cleanTitle, ignoreCase = true)) {
                queries.add("$cleanArt $transTitle")
                if (hasCyrillicArtist && latinArtist.isNotBlank()) {
                    queries.add("$latinArtist $transTitle")
                }
            }
        }
        if (!requireArtist || cleanArt.isBlank()) {
            queries.add(cleanTitle)
            if (!transTitle.equals(cleanTitle, ignoreCase = true)) {
                queries.add(transTitle)
            }
        }
        return queries.toList()
    }

    private fun scoreGeniusHit(
        expectedTitle: String,
        originalTitle: String,
        expectedArtist: String,
        hitTitle: String,
        hitPrimaryArtist: String,
        hitAllArtists: String
    ): Int {
        var score = 0
        if (isTitleMatch(expectedTitle, hitTitle) ||
            (!originalTitle.equals(expectedTitle, true) && isTitleMatch(originalTitle, hitTitle))
        ) {
            score += 100
        } else if (expectedArtist.isNotBlank() &&
            (isArtistMatch(expectedArtist, hitPrimaryArtist) || isArtistMatch(expectedArtist, hitAllArtists)) &&
            (isTitleWordPrefixMatch(expectedTitle, hitTitle) || isTitleWordPrefixMatch(originalTitle, hitTitle))
        ) {
            score += 60
        }

        if (expectedArtist.isNotBlank() && isArtistMatch(expectedArtist, hitPrimaryArtist)) {
            score += 50
        } else if (expectedArtist.isNotBlank() && isArtistMatch(expectedArtist, hitAllArtists)) {
            score += 25
        }

        val normExp = normalizeForMatch(cleanTitleForComparison(expectedTitle))
        val normCandNoParens = normalizeForMatch(cleanTitleForComparison(hitTitle).replace(PAREN_BRACKET_REGEX, ""))
        if (normExp.isNotBlank() && normExp == normCandNoParens) {
            score += 30
        }

        val expLower = expectedTitle.lowercase()
        val hitLower = hitTitle.lowercase()
        val penaltyKeywords = listOf("remix", "sped up", "slowed", "live", "acoustic", "instrumental")
        for (kw in penaltyKeywords) {
            if (hitLower.contains(kw) && !expLower.contains(kw)) {
                score -= 40
            }
        }
        return score
    }

    suspend fun getLyrics(
        context: Context,
        artist: String,
        title: String,
        rawTitle: String = "",
        artistFromTitle: Boolean = false,
        forceRefresh: Boolean = false,
        trackDurationMs: Long = 0L
    ): Result<LyricsResult> = withContext(Dispatchers.IO) {
        cleanOldCaches(context)

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

        val artistTrusted = cleanArtist.isNotBlank() && (
            parsed.artistFromTitle ||
            artistFromTitle ||
            MediaNotificationListener.findKnownArtist(primaryArtist) != null ||
            !MediaNotificationListener.isLikelyFakeUploader(primaryArtist)
        )

        val cacheKey = CACHE_VERSION + sanitizeKey("$cleanArtist - $strippedTitle")

        if (!forceRefresh) {
            memoryCache[cacheKey]?.let {
                val titleOk = isTitleMatch(strippedTitle, it.title) ||
                    (artistTrusted && isTitleWordPrefixMatch(strippedTitle, it.title))
                val artistOk = !artistTrusted || isArtistMatch(primaryArtist, it.artist)
                if (it.lines.isNotEmpty() && titleOk && artistOk) {
                    return@withContext Result.success(it)
                }
            }

            val diskCached = readDiskCache(context, cacheKey)
            if (diskCached != null && diskCached.lines.isNotEmpty()) {
                val titleOk = isTitleMatch(strippedTitle, diskCached.title) ||
                    (artistTrusted && isTitleWordPrefixMatch(strippedTitle, diskCached.title))
                val artistOk = !artistTrusted || isArtistMatch(primaryArtist, diskCached.artist)
                if (titleOk && artistOk) {
                    memoryCache[cacheKey] = diskCached
                    return@withContext Result.success(diskCached)
                }
            }
        }

        fun cacheAndReturn(res: LyricsResult): Result<LyricsResult> {
            saveDiskCache(context, cacheKey, res)
            memoryCache[cacheKey] = res
            return Result.success(res)
        }

        // =========================================================================
        // STAGE 1: FAST PARALLEL SEARCH — GENIUS OKHTTP + ALL SYNCED LRC PROVIDERS
        // Checks LRCLIB, NetEase, and QQ concurrently with Genius so synced karaoke
        // timestamps are never discarded in favor of unsynced Genius plain text!
        // =========================================================================
        val (stage1Genius, stage1SyncedOrPlain) = coroutineScope {
            val dGenius = async {
                fetchGeniusOkHttp(primaryArtist, strippedTitle, requireArtist = artistTrusted)
            }
            val dLrclib = async {
                if (primaryArtist.isBlank()) {
                    if (!artistTrusted && strippedTitle.length >= 4) {
                        searchLrclibStrict(
                            query = strippedTitle,
                            expectedArtist = "",
                            expectedTitle = strippedTitle,
                            requireArtist = false,
                            trackDurationMs = trackDurationMs
                        )
                    } else null
                } else {
                    val exact = fetchLrclibExact(primaryArtist, strippedTitle, trackDurationMs)
                        ?: (if (!transTitle.equals(strippedTitle, true)) fetchLrclibExact(primaryArtist, transTitle, trackDurationMs) else null)
                        ?: (if (secondaryArtist.isNotBlank()) fetchLrclibExact(secondaryArtist, strippedTitle, trackDurationMs) else null)
                    val searched = if (exact?.isSynced == true &&
                        (trackDurationMs <= 10_000L || exact.lrcDurationMs <= 0L || kotlin.math.abs(trackDurationMs - exact.lrcDurationMs) <= 4000L)
                    ) {
                        null
                    } else {
                        searchLrclibStrict(
                            query = "$primaryArtist $strippedTitle",
                            expectedArtist = primaryArtist,
                            expectedTitle = strippedTitle,
                            requireArtist = artistTrusted,
                            trackDurationMs = trackDurationMs
                        ) ?: (if (!transTitle.equals(strippedTitle, true)) {
                            searchLrclibStrict(
                                query = "$primaryArtist $transTitle",
                                expectedArtist = primaryArtist,
                                expectedTitle = strippedTitle,
                                requireArtist = artistTrusted,
                                trackDurationMs = trackDurationMs
                            )
                        } else null)
                    }
                    pickBestResult(
                        listOf(exact, searched),
                        expectedTitle = strippedTitle,
                        expectedArtist = primaryArtist,
                        requireArtistMatch = artistTrusted,
                        trackDurationMs = trackDurationMs
                    )
                }
            }
            val dNetEase = async {
                if (primaryArtist.isBlank()) null
                else fetchNetEase(primaryArtist, strippedTitle, requireArtist = artistTrusted, trackDurationMs = trackDurationMs)
            }
            val dQQ = async {
                if (primaryArtist.isBlank()) null
                else fetchQQMusic(primaryArtist, strippedTitle, requireArtist = artistTrusted, trackDurationMs = trackDurationMs)
            }
            val bestLrc = pickBestResult(
                listOf(dLrclib.await(), dNetEase.await(), dQQ.await()),
                expectedTitle = strippedTitle,
                expectedArtist = primaryArtist,
                requireArtistMatch = artistTrusted,
                trackDurationMs = trackDurationMs
            )
            Pair(dGenius.await(), bestLrc)
        }

        if (stage1SyncedOrPlain != null && stage1SyncedOrPlain.isSynced && stage1SyncedOrPlain.lines.size >= 3) {
            val withGeniusUrl = if (stage1Genius?.sourceUrl?.isNotBlank() == true) {
                stage1SyncedOrPlain.copy(geniusUrl = stage1Genius.sourceUrl)
            } else {
                stage1SyncedOrPlain
            }
            return@withContext cacheAndReturn(withGeniusUrl)
        }

        // =========================================================================
        // STAGE 2: STRICT YANDEX RESOLVER + SYNCED LRC RETRY + GENIUS FALLBACK
        // Before settling for unsynced plain lyrics, check if Yandex Music resolves
        // a canonical artist/title that unlocks synced LRC timestamps.
        // =========================================================================
        val yandexResolved = resolveViaYandexMusic(
            primaryArtist = primaryArtist,
            strippedTitle = strippedTitle,
            artistFromTitle = parsed.artistFromTitle || artistFromTitle
        )
        val ymArtist = yandexResolved?.artist?.ifBlank { primaryArtist } ?: primaryArtist
        val ymTitle = yandexResolved?.title?.ifBlank { strippedTitle } ?: strippedTitle

        var ymGeniusResult: LyricsResult? = null
        var ymBestLrc: LyricsResult? = null

        if (yandexResolved != null && (!ymTitle.equals(strippedTitle, true) || !ymArtist.equals(primaryArtist, true))) {
            val (ymG, ymL) = coroutineScope {
                val dg = async {
                    if (stage1Genius != null && stage1Genius.lines.isNotEmpty()) stage1Genius
                    else fetchGeniusOkHttp(ymArtist, ymTitle, requireArtist = true)
                }
                val dl = async {
                    val exact = fetchLrclibExact(ymArtist, ymTitle, trackDurationMs)
                    val searched = if (exact?.isSynced == true) null else {
                        searchLrclibStrict("$ymArtist $ymTitle", ymArtist, ymTitle, requireArtist = true, trackDurationMs = trackDurationMs)
                    }
                    pickBestResult(
                        listOf(exact, searched),
                        expectedTitle = ymTitle,
                        expectedArtist = ymArtist,
                        requireArtistMatch = true,
                        trackDurationMs = trackDurationMs
                    )
                }
                val dn = async {
                    fetchNetEase(ymArtist, ymTitle, requireArtist = true, trackDurationMs = trackDurationMs)
                }
                val dq = async {
                    fetchQQMusic(ymArtist, ymTitle, requireArtist = true, trackDurationMs = trackDurationMs)
                }
                val bestYmLrc = pickBestResult(
                    listOf(dl.await(), dn.await(), dq.await()),
                    expectedTitle = ymTitle,
                    expectedArtist = ymArtist,
                    requireArtistMatch = true,
                    trackDurationMs = trackDurationMs
                )
                Pair(dg.await(), bestYmLrc)
            }
            ymGeniusResult = ymG
            ymBestLrc = ymL

            if (ymBestLrc != null && ymBestLrc.isSynced && ymBestLrc.lines.size >= 3) {
                val gUrl = stage1Genius?.sourceUrl?.ifBlank { ymGeniusResult?.sourceUrl ?: "" } ?: (ymGeniusResult?.sourceUrl ?: "")
                val withUrl = if (gUrl.isNotBlank()) ymBestLrc.copy(geniusUrl = gUrl) else ymBestLrc
                return@withContext cacheAndReturn(withUrl)
            }
        }

        // Now if no synced LRC was found anywhere, return Genius plain text if available
        if (stage1Genius != null && stage1Genius.lines.isNotEmpty()) {
            return@withContext cacheAndReturn(stage1Genius)
        }
        if (ymGeniusResult != null && ymGeniusResult.lines.isNotEmpty()) {
            return@withContext cacheAndReturn(ymGeniusResult)
        }

        // Genius Chromium WebView engine (for Cloudflare-protected pages / snippets)
        try {
            val webViewGenius = fetchGeniusViaWebView(
                context = context,
                primaryArtist = ymArtist.ifBlank { primaryArtist },
                strippedTitle = ymTitle,
                originalTitle = strippedTitle,
                requireArtist = artistTrusted
            )
            if (webViewGenius != null && webViewGenius.lines.isNotEmpty()) {
                return@withContext cacheAndReturn(webViewGenius)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Genius WebView stage error: ${e.message}")
        }

        val stage2Pick = pickBestResult(
            results = listOfNotNull(ymBestLrc, stage1SyncedOrPlain),
            expectedTitle = ymTitle,
            expectedArtist = ymArtist.ifBlank { primaryArtist },
            requireArtistMatch = artistTrusted,
            trackDurationMs = trackDurationMs
        )
        if (stage2Pick != null) return@withContext cacheAndReturn(stage2Pick)

        // Check swapped "Title - Artist" when dash came from title
        if (primaryArtist.isNotBlank() && strippedTitle.isNotBlank() && parsed.artistFromTitle) {
            val inverted = coroutineScope {
                val dl = async { fetchLrclibExact(strippedTitle, primaryArtist, trackDurationMs) }
                val dn = async { fetchNetEase(strippedTitle, primaryArtist, requireArtist = true, trackDurationMs = trackDurationMs) }
                pickBestResult(
                    listOf(dl.await(), dn.await()),
                    expectedTitle = primaryArtist,
                    expectedArtist = strippedTitle,
                    requireArtistMatch = true,
                    trackDurationMs = trackDurationMs
                )
            }
            if (inverted != null) return@withContext cacheAndReturn(inverted)
        }

        // =========================================================================
        // STAGE 3: UNTRUSTED UPLOADER / NO-ARTIST RESCUE ONLY
        // Runs ONLY when artist was NOT trusted (e.g. fake SoundCloud channel like sliv_trekov_52).
        // When artist IS trusted, we NEVER fall back to random title-only songs!
        // =========================================================================
        if (!artistTrusted && strippedTitle.length >= 5) {
            val targetTitle = ymTitle.ifBlank { strippedTitle }
            val stage3Best = coroutineScope {
                val dLrclib = async {
                    searchLrclibStrict(targetTitle, "", targetTitle, requireArtist = false, trackDurationMs = trackDurationMs)
                        ?: (if (!transTitle.equals(targetTitle, true)) searchLrclibStrict(transTitle, "", transTitle, requireArtist = false, trackDurationMs = trackDurationMs) else null)
                }
                val dNetEase = async { fetchNetEase("", targetTitle, requireArtist = false, trackDurationMs = trackDurationMs) }
                val dQQ = async { fetchQQMusic("", targetTitle, requireArtist = false, trackDurationMs = trackDurationMs) }
                pickBestResult(
                    listOf(dLrclib.await(), dNetEase.await(), dQQ.await()),
                    expectedTitle = targetTitle,
                    expectedArtist = "",
                    requireArtistMatch = false,
                    trackDurationMs = trackDurationMs
                )
            }
            if (stage3Best != null) return@withContext cacheAndReturn(stage3Best)
        }

        return@withContext Result.failure(Exception("Текст не найден. Откройте поиск на Genius."))
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
        val knownArtist = primaryArtist.isNotBlank() &&
            (artistFromTitle || MediaNotificationListener.findKnownArtist(primaryArtist) != null)

        val queries = mutableListOf<String>()
        if (primaryArtist.isNotBlank()) {
            queries.add("$primaryArtist $strippedTitle")
        }
        if (!knownArtist || primaryArtist.isBlank()) {
            queries.add(strippedTitle)
        }

        for (q in queries) {
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
                    val artistOk = primaryArtist.isBlank() ||
                        isArtistMatch(primaryArtist, firstArtist) ||
                        isArtistMatch(primaryArtist, allArtistStr)

                    // STRICT: Title MUST always match! Never rewrite "пули" -> "Bring Us Bullets"!
                    if (!titleOk) continue
                    // When primary artist is known, artist MUST also match strictly!
                    if (knownArtist && !artistOk) continue

                    return ResolvedTrack(
                        artist = firstArtist,
                        allArtists = candArtists,
                        title = candTitle
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Yandex resolver error for '$q': ${e.message}")
            }
        }
        return null
    }

    // --- LRCLIB EXACT GET ---

    private fun fetchLrclibExact(artist: String, title: String, trackDurationMs: Long = 0L): LyricsResult? {
        val baseUrl = "https://lrclib.net/api/get?artist_name=" + URLEncoder.encode(artist, "UTF-8") +
                "&track_name=" + URLEncoder.encode(title, "UTF-8")
        val urlWithDur = if (trackDurationMs >= 15_000L) {
            "$baseUrl&duration=${(trackDurationMs / 1000L)}"
        } else {
            baseUrl
        }

        val jsonStr = executeGetString(urlWithDur)
            ?: (if (urlWithDur != baseUrl) executeGetString(baseUrl) else null)
            ?: return null
        if (!jsonStr.startsWith("{")) return null

        val obj = JSONObject(jsonStr)
        val track = obj.optCleanString("trackName")
        val art = obj.optCleanString("artistName")

        if (!isTitleMatch(title, track) || !isArtistMatch(artist, art)) {
            return null
        }

        val durSec = obj.optDouble("duration", 0.0)
        val itemDurMs = if (!durSec.isNaN() && durSec > 0.0) (durSec * 1000.0).toLong() else 0L
        val synced = obj.optCleanString("syncedLyrics")
        val plain = obj.optCleanString("plainLyrics")

        if (synced.isNotBlank()) {
            val lines = parseLrcLines(synced)
            if (lines.size >= 3) {
                return LyricsResult(
                    title = track,
                    artist = art,
                    lines = lines,
                    plainLyrics = plain.ifBlank { lines.joinToString("\n") { it.text } },
                    sourceUrl = "https://lrclib.net",
                    isSynced = true,
                    sourceName = "Spotify (Караоке)",
                    lrcDurationMs = itemDurMs
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
                    sourceName = "Spotify Lyrics",
                    lrcDurationMs = itemDurMs
                )
            }
        }

        return null
    }

    // --- LRCLIB SEARCH WITH STRICT TITLE, ARTIST & DURATION VALIDATION ---

    private fun searchLrclibStrict(
        query: String,
        expectedArtist: String,
        expectedTitle: String,
        requireArtist: Boolean,
        trackDurationMs: Long = 0L
    ): LyricsResult? {
        val url = "https://lrclib.net/api/search?q=" + URLEncoder.encode(query, "UTF-8")
        val jsonStr = executeGetString(url) ?: return null
        if (!jsonStr.startsWith("[")) return null

        val arr = JSONArray(jsonStr)
        if (arr.length() == 0) return null

        fun lrclibMatch(track: String, art: String): Pair<String, String>? {
            val directMatch = isTitleMatch(expectedTitle, track) &&
                (!requireArtist || expectedArtist.isBlank() || isArtistMatch(expectedArtist, art))
            if (directMatch) return Pair(track, art)

            val swappedMatch = expectedArtist.isNotBlank() &&
                isTitleMatch(expectedTitle, art) &&
                isArtistMatch(expectedArtist, track)
            if (swappedMatch) return Pair(art, track)

            return null
        }

        val syncedMatches = mutableListOf<LyricsResult>()
        val plainMatches = mutableListOf<LyricsResult>()

        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val track = item.optCleanString("trackName")
            val art = item.optCleanString("artistName")
            val matchedNames = lrclibMatch(track, art) ?: continue
            val durSec = item.optDouble("duration", 0.0)
            val itemDurMs = if (!durSec.isNaN() && durSec > 0.0) (durSec * 1000.0).toLong() else 0L

            val synced = item.optCleanString("syncedLyrics")
            if (synced.isNotBlank()) {
                val lines = parseLrcLines(synced)
                if (lines.size >= 3) {
                    syncedMatches.add(
                        LyricsResult(
                            title = matchedNames.first,
                            artist = matchedNames.second,
                            lines = lines,
                            plainLyrics = item.optCleanString("plainLyrics").ifBlank { lines.joinToString("\n") { it.text } },
                            sourceUrl = "https://lrclib.net",
                            isSynced = true,
                            sourceName = "Spotify (Караоке)",
                            lrcDurationMs = itemDurMs
                        )
                    )
                    continue
                }
            }

            val plain = item.optCleanString("plainLyrics")
            if (plain.isNotBlank()) {
                val lines = sanitizeLyricsLines(plain)
                if (lines.isNotEmpty()) {
                    plainMatches.add(
                        LyricsResult(
                            title = matchedNames.first,
                            artist = matchedNames.second,
                            lines = lines,
                            plainLyrics = plain,
                            sourceUrl = "https://lrclib.net",
                            isSynced = false,
                            sourceName = "Spotify Lyrics",
                            lrcDurationMs = itemDurMs
                        )
                    )
                }
            }
        }

        if (syncedMatches.isNotEmpty()) {
            return syncedMatches.maxByOrNull { cand ->
                scoreSyncedCandidateDuration(cand.lrcDurationMs, trackDurationMs) +
                    (if (expectedArtist.isNotBlank() && isArtistMatch(expectedArtist, cand.artist)) 40 else 0)
            }
        }

        return plainMatches.firstOrNull()
    }

    // --- NETEASE CLOUD MUSIC ---

    private fun fetchNetEase(
        artist: String,
        title: String,
        requireArtist: Boolean,
        trackDurationMs: Long = 0L
    ): LyricsResult? {
        val query = if (requireArtist && artist.isNotBlank()) "$artist $title" else title
        val searchUrl = "https://music.163.com/api/search/get/?s=" + URLEncoder.encode(query, "UTF-8") + "&type=1&limit=6"
        val jsonStr = executeGetString(searchUrl) ?: return null
        if (!jsonStr.startsWith("{")) return null

        val root = JSONObject(jsonStr)
        val resultObj = root.optJSONObject("result") ?: return null
        val songsArr = resultObj.optJSONArray("songs") ?: return null

        data class NetEaseCandidate(
            val songId: Long,
            val songName: String,
            val primaryArtist: String,
            val durationMs: Long
        )

        val candidates = mutableListOf<NetEaseCandidate>()
        for (i in 0 until songsArr.length()) {
            val song = songsArr.getJSONObject(i)
            val songName = song.optCleanString("name")
            val artistsArr = song.optJSONArray("artists")
            val songArtists = mutableListOf<String>()
            if (artistsArr != null) {
                for (a in 0 until artistsArr.length()) {
                    val aName = artistsArr.getJSONObject(a).optCleanString("name")
                    if (aName.isNotBlank()) songArtists.add(aName)
                }
            }
            val songArtistJoined = songArtists.joinToString(", ")

            val artistOk = !requireArtist || artist.isBlank() ||
                songArtists.any { isArtistMatch(artist, it) } ||
                isArtistMatch(artist, songArtistJoined)
            val titleOk = isTitleMatch(title, songName) ||
                (artistOk && artist.isNotBlank() && isTitleWordPrefixMatch(title, songName))

            if (!titleOk || !artistOk) continue

            val songId = song.optLong("id", -1L)
            if (songId <= 0) continue
            val songDurMs = song.optLong("duration", song.optLong("dt", 0L)).coerceAtLeast(0L)
            candidates.add(
                NetEaseCandidate(
                    songId = songId,
                    songName = songName,
                    primaryArtist = if (songArtists.isNotEmpty()) songArtists.first() else artist,
                    durationMs = songDurMs
                )
            )
        }

        val ordered = candidates.sortedByDescending { scoreSyncedCandidateDuration(it.durationMs, trackDurationMs) }
        var plainFallback: LyricsResult? = null

        for (cand in ordered) {
            val lyricUrl = "https://music.163.com/api/song/lyric?os=pc&id=${cand.songId}&lv=-1&kv=-1&tv=-1"
            val lyricJson = executeGetString(lyricUrl) ?: continue
            val lrcRoot = JSONObject(lyricJson)
            val lrcObj = lrcRoot.optJSONObject("lrc") ?: continue
            val rawLyric = lrcObj.optCleanString("lyric")
            if (rawLyric.length < 30) continue

            val hasTimestamps = Regex("\\[\\d{2}:\\d{2}[.:]\\d{2,3}\\]").containsMatchIn(rawLyric)
            val lines = if (hasTimestamps) parseLrcLines(rawLyric) else sanitizeLyricsLines(rawLyric)
            val isSynced = hasTimestamps && lines.size >= 3

            if (lines.isNotEmpty()) {
                val res = LyricsResult(
                    title = cand.songName,
                    artist = cand.primaryArtist,
                    lines = lines,
                    plainLyrics = lines.joinToString("\n") { it.text },
                    sourceUrl = "https://music.163.com/#/song?id=${cand.songId}",
                    isSynced = isSynced,
                    sourceName = if (isSynced) "Spotify (NetEase LRC)" else "Spotify (NetEase)",
                    lrcDurationMs = cand.durationMs
                )
                if (isSynced) return res
                if (plainFallback == null) plainFallback = res
            }
        }

        return plainFallback
    }

    // --- QQ MUSIC API ---

    private fun fetchQQMusic(
        artist: String,
        title: String,
        requireArtist: Boolean,
        trackDurationMs: Long = 0L
    ): LyricsResult? {
        val query = if (requireArtist && artist.isNotBlank()) "$artist $title" else title
        val searchUrl = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=" + URLEncoder.encode(query, "UTF-8") + "&format=json&n=6"
        val jsonStr = executeGetString(searchUrl, referer = "https://y.qq.com/") ?: return null
        if (!jsonStr.startsWith("{")) return null

        val root = JSONObject(jsonStr)
        val dataObj = root.optJSONObject("data") ?: return null
        val songObj = dataObj.optJSONObject("song") ?: return null
        val listArr = songObj.optJSONArray("list") ?: return null

        data class QQCandidate(
            val songmid: String,
            val songName: String,
            val primaryArtist: String,
            val durationMs: Long
        )

        val candidates = mutableListOf<QQCandidate>()
        for (i in 0 until listArr.length()) {
            val s = listArr.getJSONObject(i)
            val songName = s.optCleanString("songname")
            val singerArr = s.optJSONArray("singer")
            val singers = mutableListOf<String>()
            if (singerArr != null) {
                for (a in 0 until singerArr.length()) {
                    val sName = singerArr.getJSONObject(a).optCleanString("name")
                    if (sName.isNotBlank()) singers.add(sName)
                }
            }
            val singerJoined = singers.joinToString(", ")

            val artistOk = !requireArtist || artist.isBlank() ||
                singers.any { isArtistMatch(artist, it) } ||
                isArtistMatch(artist, singerJoined)
            val titleOk = isTitleMatch(title, songName) ||
                (artistOk && artist.isNotBlank() && isTitleWordPrefixMatch(title, songName))

            if (!titleOk || !artistOk) continue

            val songmid = s.optCleanString("songmid")
            if (songmid.isBlank()) continue
            val intervalSec = s.optLong("interval", 0L).coerceAtLeast(0L)
            candidates.add(
                QQCandidate(
                    songmid = songmid,
                    songName = songName,
                    primaryArtist = if (singers.isNotEmpty()) singers.first() else artist,
                    durationMs = intervalSec * 1000L
                )
            )
        }

        val ordered = candidates.sortedByDescending { scoreSyncedCandidateDuration(it.durationMs, trackDurationMs) }
        var plainFallback: LyricsResult? = null

        for (cand in ordered) {
            val lyricUrl = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=${cand.songmid}&format=json&nobase64=1"
            val lyricJson = executeGetString(lyricUrl, referer = "https://y.qq.com/") ?: continue
            val lrcRoot = JSONObject(lyricJson)
            val rawLyric = lrcRoot.optCleanString("lyric")
            if (rawLyric.length < 30) continue

            val hasTimestamps = Regex("\\[\\d{2}:\\d{2}[.:]\\d{2,3}\\]").containsMatchIn(rawLyric)
            val lines = if (hasTimestamps) parseLrcLines(rawLyric) else sanitizeLyricsLines(rawLyric)
            val isSynced = hasTimestamps && lines.size >= 3

            if (lines.isNotEmpty()) {
                val res = LyricsResult(
                    title = cand.songName,
                    artist = cand.primaryArtist,
                    lines = lines,
                    plainLyrics = lines.joinToString("\n") { it.text },
                    sourceUrl = "https://y.qq.com/n/ryqq/songDetail/${cand.songmid}",
                    isSynced = isSynced,
                    sourceName = if (isSynced) "Spotify (QQ LRC)" else "Spotify (QQ)",
                    lrcDurationMs = cand.durationMs
                )
                if (isSynced) return res
                if (plainFallback == null) plainFallback = res
            }
        }

        return plainFallback
    }

    // --- GENIUS.COM OKHTTP (SONG SEARCH API + DIRECT SLUG FALLBACK) ---

    private fun getGeniusCookieHeader(): String? {
        return try {
            CookieManager.getInstance().getCookie("https://genius.com")?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }

    private fun queryOkHttpGeniusApi(query: String): List<GeniusWebViewFetcher.GeniusHit> {
        val searchUrl = "https://genius.com/api/search/song?per_page=8&q=" +
            URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        val hits = mutableListOf<GeniusWebViewFetcher.GeniusHit>()
        try {
            val builder = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json, text/plain, */*")
                .header("Referer", "https://genius.com/")
                .header("Origin", "https://genius.com")
                .header("X-Requested-With", "XMLHttpRequest")
            getGeniusCookieHeader()?.let { builder.header("Cookie", it) }

            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                if (!body.trim().startsWith("{")) return emptyList()

                val root = JSONObject(body)
                val sections = root.optJSONObject("response")?.optJSONArray("sections") ?: return emptyList()
                for (i in 0 until sections.length()) {
                    val sec = sections.getJSONObject(i)
                    val secType = sec.optCleanString("type")
                    if (secType == "song" || secType == "top_hit") {
                        val hitArr = sec.optJSONArray("hits") ?: continue
                        for (h in 0 until hitArr.length()) {
                            val hit = hitArr.getJSONObject(h)
                            val result = hit.optJSONObject("result") ?: continue
                            val resType = result.optCleanString("_type").ifBlank { hit.optCleanString("type") }
                            if (resType != "song") continue

                            val candTitle = result.optCleanString("title")
                            val candArtist = result.optJSONObject("primary_artist")?.optCleanString("name") ?: ""
                            val candArtistNames = result.optCleanString("artist_names").ifBlank { candArtist }
                            val songUrl = result.optCleanString("url")

                            if (candTitle.isBlank() || !songUrl.startsWith("http")) continue
                            if (candArtist.contains("Genius", ignoreCase = true)) continue
                            if (!songUrl.endsWith("-lyrics", ignoreCase = true)) continue

                            hits.add(
                                GeniusWebViewFetcher.GeniusHit(
                                    title = candTitle,
                                    artist = candArtist,
                                    url = songUrl,
                                    artistNames = candArtistNames
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Genius OkHttp search failed for '$query': ${e.message}")
        }
        return hits.distinctBy { it.url }
    }

    private fun fetchGeniusOkHttp(artist: String, title: String, requireArtist: Boolean): LyricsResult? {
        val queries = buildGeniusQueries(artist, title, requireArtist)
        for (q in queries) {
            val hits = queryOkHttpGeniusApi(q)
            if (hits.isEmpty()) continue

            val candidates = hits.filter { hit ->
                val artistOk = !requireArtist || artist.isBlank() ||
                    isArtistMatch(artist, hit.artist) ||
                    isArtistMatch(artist, hit.artistNames)
                val titleOk = isTitleMatch(title, hit.title) ||
                    (artistOk && artist.isNotBlank() && isTitleWordPrefixMatch(title, hit.title))
                titleOk && artistOk
            }

            val bestHit = candidates.maxByOrNull { hit ->
                scoreGeniusHit(
                    expectedTitle = title,
                    originalTitle = title,
                    expectedArtist = artist,
                    hitTitle = hit.title,
                    hitPrimaryArtist = hit.artist,
                    hitAllArtists = hit.artistNames
                )
            }

            if (bestHit != null) {
                val parsed = fetchGeniusHtmlAndParse(bestHit.url)
                if (parsed != null && parsed.lines.isNotEmpty()) {
                    return parsed.copy(
                        title = bestHit.title.ifBlank { title },
                        artist = bestHit.artist.ifBlank { artist },
                        sourceUrl = bestHit.url,
                        geniusUrl = bestHit.url,
                        sourceName = "Genius.com"
                    )
                }
            }
        }

        // Direct slug fallback (only when search API returned no usable result)
        if (artist.isNotBlank() && title.isNotBlank()) {
            val directUrl = constructDirectGeniusUrl(artist, title)
            val directResult = fetchGeniusHtmlAndParse(directUrl)
            if (directResult != null && directResult.lines.isNotEmpty()) {
                return directResult.copy(
                    title = directResult.title.ifBlank { title },
                    artist = directResult.artist.ifBlank { artist },
                    sourceUrl = directUrl,
                    geniusUrl = directUrl,
                    sourceName = "Genius.com"
                )
            }
        }
        return null
    }

    // --- GENIUS.COM REAL CHROMIUM WEBVIEW ENGINE ---

    private suspend fun fetchGeniusViaWebView(
        context: Context,
        primaryArtist: String,
        strippedTitle: String,
        originalTitle: String,
        requireArtist: Boolean
    ): LyricsResult? {
        val searchQueries = linkedSetOf<String>()
        searchQueries.addAll(buildGeniusQueries(primaryArtist, strippedTitle, requireArtist))
        if (!originalTitle.equals(strippedTitle, ignoreCase = true) && originalTitle.length >= 3) {
            if (primaryArtist.isNotBlank()) {
                searchQueries.add("$primaryArtist $originalTitle")
            }
            if (!requireArtist || primaryArtist.isBlank()) {
                searchQueries.add(originalTitle)
            }
        }

        for (query in searchQueries) {
            val hits = GeniusWebViewFetcher.searchSongs(context, query)
            if (hits.isEmpty()) continue

            val candidates = hits.filter { hit ->
                val artistOk = !requireArtist || primaryArtist.isBlank() ||
                    isArtistMatch(primaryArtist, hit.artist) ||
                    isArtistMatch(primaryArtist, hit.artistNames)
                val titleOk = isTitleMatch(strippedTitle, hit.title) ||
                    isTitleMatch(originalTitle, hit.title) ||
                    (artistOk && primaryArtist.isNotBlank() && (
                        isTitleWordPrefixMatch(strippedTitle, hit.title) ||
                        isTitleWordPrefixMatch(originalTitle, hit.title)
                    ))
                titleOk && artistOk
            }

            val bestHit = candidates.maxByOrNull { hit ->
                scoreGeniusHit(
                    expectedTitle = strippedTitle,
                    originalTitle = originalTitle,
                    expectedArtist = primaryArtist,
                    hitTitle = hit.title,
                    hitPrimaryArtist = hit.artist,
                    hitAllArtists = hit.artistNames
                )
            } ?: continue

            val rawLyrics = GeniusWebViewFetcher.scrapeLyricsFromUrl(context, bestHit.url) ?: continue
            val cleaned = cleanLyrics(rawLyrics)
            val lines = sanitizeLyricsLines(cleaned)
            if (lines.isNotEmpty()) {
                return LyricsResult(
                    title = bestHit.title.ifBlank { strippedTitle },
                    artist = bestHit.artist.ifBlank { primaryArtist },
                    lines = lines,
                    plainLyrics = cleaned,
                    sourceUrl = bestHit.url,
                    geniusUrl = bestHit.url,
                    isSynced = false,
                    sourceName = "Genius.com"
                )
            }
        }
        return null
    }

    private fun fetchGeniusHtmlAndParse(songUrl: String): LyricsResult? {
        return try {
            val reqBuilder = Request.Builder()
                .url(songUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
                .header("Referer", "https://genius.com/")
            getGeniusCookieHeader()?.let { reqBuilder.header("Cookie", it) }

            val html = client.newCall(reqBuilder.build()).execute().use { pageResponse ->
                if (!pageResponse.isSuccessful) return null
                pageResponse.body?.string() ?: return null
            }

            val doc = Jsoup.parse(html)
            val lyricContainers = doc.select("div[data-lyrics-container=true]")
            var rawText = ""

            if (lyricContainers.isNotEmpty()) {
                val sb = StringBuilder()
                for (container in lyricContainers) {
                    container.select("[data-exclude-from-selection=true], [class*=LyricsHeader], script, style, button").remove()
                    container.select("br").append("__NL_TOK__")
                    container.select("p").prepend("__NL_TOK____NL_TOK__")
                    container.select("div").prepend("__NL_TOK__")
                    val text = container.text()
                        .replace(Regex(" *__NL_TOK__ *"), "\n")
                        .replace(Regex("\n{3,}"), "\n\n")
                        .trim()
                    if (text.isNotEmpty()) {
                        sb.append(text).append("\n\n")
                    }
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
                    } catch (_: Exception) {}
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
                geniusUrl = songUrl,
                isSynced = false,
                sourceName = "Genius.com"
            )
        } catch (e: Exception) {
            null
        }
    }

    fun parseManualGeniusLyrics(
        title: String,
        artist: String,
        rawLyrics: String,
        geniusUrl: String
    ): LyricsResult? {
        val cleaned = cleanLyrics(rawLyrics)
        val lines = sanitizeLyricsLines(cleaned)
        if (lines.isEmpty()) return null
        return LyricsResult(
            title = MediaNotificationListener.sanitizeText(title).ifBlank { "Трек" },
            artist = MediaNotificationListener.canonicalizeArtist(artist).ifBlank { "Genius.com" },
            lines = lines,
            plainLyrics = cleaned,
            sourceUrl = geniusUrl,
            geniusUrl = geniusUrl,
            isSynced = false,
            sourceName = "Genius.com"
        )
    }

    fun saveManualLyricsToCache(
        context: Context,
        artist: String,
        title: String,
        rawTitle: String,
        result: LyricsResult
    ) {
        val parsed = MediaNotificationListener.parseTrack(
            rawTitle = if (rawTitle.isNotBlank()) rawTitle else title,
            rawArtist = artist
        )
        val cleanArtist = if (parsed.artist.isNotBlank()) parsed.artist else MediaNotificationListener.canonicalizeArtist(artist)
        val cleanTitle = if (parsed.title.isNotBlank()) parsed.title else MediaNotificationListener.sanitizeText(title)
        val strippedTitle = MediaNotificationListener.stripNoise(cleanTitle).ifBlank { cleanTitle }
        val keyArtist = cleanArtist.ifBlank { result.artist }
        val keyTitle = strippedTitle.ifBlank { result.title }
        if (keyTitle.isBlank()) return
        val cacheKey = CACHE_VERSION + sanitizeKey("$keyArtist - $keyTitle")
        memoryCache[cacheKey] = result
        saveDiskCache(context, cacheKey, result)
    }

    // --- PARSING & UTILITIES ---

    private val LRC_TAG_REGEX = Regex("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?\\]")
    private val LRC_OFFSET_REGEX = Regex("\\[offset:\\s*([+-]?\\d+)\\s*\\]", RegexOption.IGNORE_CASE)
    private val LRC_META_HEADER_REGEX = Regex("^\\[(?:ti|ar|al|by|length|offset|re|ve|id|qq|total|kana|language):.*\\]$", RegexOption.IGNORE_CASE)
    private val LRC_CREDIT_LINE_REGEX = Regex("^(?:作词|作曲|编曲|制作人|混音|母带|录音|和声|吉他|贝斯|鼓|统筹|出品|OP|SP|词[:：]|曲[:：]|LRC\\s*by|Lyrics\\s*by)\\b.*", RegexOption.IGNORE_CASE)

    fun parseLrcLines(lrcText: String): List<LyricLine> {
        var globalOffsetMs = 0L
        LRC_OFFSET_REGEX.find(lrcText)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { off ->
            globalOffsetMs = off.coerceIn(-15_000L, 15_000L)
        }

        val timestamped = mutableListOf<LyricLine>()
        val untagged = mutableListOf<Pair<Int, LyricLine>>()

        for (rawLine in lrcText.lines()) {
            val line = rawLine.trim()
            if (line.isBlank() || line.equals("null", ignoreCase = true) || line.equals("undefined", ignoreCase = true)) {
                continue
            }
            if (LRC_META_HEADER_REGEX.matches(line)) {
                continue
            }

            val matches = LRC_TAG_REGEX.findAll(line).toList()
            if (matches.isNotEmpty()) {
                val lastMatch = matches.last()
                val text = line.substring(lastMatch.range.last + 1).trim()
                if (text.isBlank() ||
                    text.equals("null", ignoreCase = true) ||
                    text.equals("undefined", ignoreCase = true) ||
                    LRC_CREDIT_LINE_REGEX.matches(text)
                ) {
                    continue
                }
                for (m in matches) {
                    val min = m.groupValues[1].toLongOrNull() ?: 0L
                    val sec = m.groupValues[2].toLongOrNull() ?: 0L
                    val msStr = m.groupValues.getOrNull(3) ?: ""
                    val ms = when (msStr.length) {
                        1 -> (msStr.toLongOrNull() ?: 0L) * 100L
                        2 -> (msStr.toLongOrNull() ?: 0L) * 10L
                        3 -> msStr.toLongOrNull() ?: 0L
                        else -> 0L
                    }
                    val totalMs = ((min * 60 + sec) * 1000 + ms - globalOffsetMs).coerceAtLeast(0L)
                    timestamped.add(LyricLine(timeMs = totalMs, text = text))
                }
            } else if (!LRC_CREDIT_LINE_REGEX.matches(line)) {
                untagged.add(Pair(timestamped.size, LyricLine(timeMs = -1L, text = line)))
            }
        }

        if (timestamped.isEmpty()) {
            return untagged.map { it.second }
        }

        val sorted = timestamped.sortedBy { it.timeMs }.toMutableList()
        if (untagged.isNotEmpty()) {
            var offset = 0
            for ((insertAfter, lineObj) in untagged) {
                val idx = (insertAfter + offset).coerceIn(0, sorted.size)
                sorted.add(idx, lineObj)
                offset++
            }
        }
        return sorted
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
                !line.matches(Regex("^\\d+\\s+Contributors?.*", RegexOption.IGNORE_CASE)) &&
                !line.matches(Regex("^\\[?Текст песни.*\\]?$", RegexOption.IGNORE_CASE))
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
            client.newCall(builder.build()).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
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
            .replace(Regex("^\\[?Текст песни.*?\n", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^(Lyrics|Текст песни).*?\n", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun sanitizeKey(key: String): String =
        key.lowercase().replace('ё', 'е').replace(Regex("[^a-z0-9а-я]"), "_")

    private fun cleanOldCaches(context: Context) {
        if (oldCachesCleaned) return
        oldCachesCleaned = true
        try {
            val cacheDir = File(context.cacheDir, "lyrics")
            if (!cacheDir.exists()) return
            cacheDir.listFiles()?.forEach { file ->
                val name = file.name
                if (name.startsWith("v1_") || name.startsWith("v2_") || name.startsWith("v3_") || name.startsWith("v4_") || !name.startsWith(CACHE_VERSION)) {
                    file.delete()
                }
            }
        } catch (_: Exception) {}
    }

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
                put("geniusUrl", result.geniusUrl)
                put("isSynced", result.isSynced)
                put("sourceName", result.sourceName)
                put("lrcDurationMs", result.lrcDurationMs)
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
                sourceName = json.optCleanString("sourceName").ifBlank { "Кэш" },
                geniusUrl = json.optCleanString("geniusUrl"),
                lrcDurationMs = json.optLong("lrcDurationMs", 0L)
            )
        } catch (e: Exception) {
            null
        }
    }
}
