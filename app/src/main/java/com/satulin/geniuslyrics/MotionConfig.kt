package com.satulin.geniuslyrics

import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.ProgressBar
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

enum class IslandAnimStyle(val id: String, val label: String, val shortLabel: String) {
    FLY_IN("fly_in", "Вылет", "🚀 Вылет"),
    FADE("fade", "Затухание", "✨ Затухание"),
    SLIDE_KINETIC("slide", "Сдвиг", "⚡ Сдвиг"),
    ELASTIC_POP("pop", "Импульс", "💥 Импульс"),
    TYPEWRITER("typewriter", "Печать", "⌨ Печать");

    companion object {
        fun fromId(id: String?): IslandAnimStyle =
            values().firstOrNull { it.id == id } ?: FLY_IN

        fun nextStyle(current: IslandAnimStyle): IslandAnimStyle =
            values()[(current.ordinal + 1) % values().size]
    }
}

enum class LyricsDisplayMode(val id: String, val label: String, val shortLabel: String) {
    KARAOKE("karaoke", "Караоке", "🎤 Караоке"),
    WORD_BY_WORD("word", "По словам", "✨ По словам"),
    PLAIN_TEXT("plain", "Просто текст", "📄 Текст");

    companion object {
        fun fromId(id: String?): LyricsDisplayMode =
            values().firstOrNull { it.id == id } ?: KARAOKE

        fun nextMode(current: LyricsDisplayMode): LyricsDisplayMode =
            values()[(current.ordinal + 1) % values().size]
    }
}

enum class VibeColorTheme(
    val id: String,
    val shortLabel: String,
    val primaryColor: Int,
    val secondaryColor: Int,
    val tertiaryColor: Int,
    val chipFillColor: Int
) {
    CYBER_EMERALD(
        id = "emerald",
        shortLabel = "💚 Изумруд",
        primaryColor = 0xFF39FF88.toInt(),
        secondaryColor = 0xFF00E5FF.toInt(),
        tertiaryColor = 0xFF7B61FF.toInt(),
        chipFillColor = 0xFF153826.toInt()
    ),
    SUNSET_PHONK(
        id = "phonk",
        shortLabel = "🔥 Фонк",
        primaryColor = 0xFFFF3B6B.toInt(),
        secondaryColor = 0xFFFF9E00.toInt(),
        tertiaryColor = 0xFFD900FF.toInt(),
        chipFillColor = 0xFF3D1624.toInt()
    ),
    TOKYO_NEON(
        id = "tokyo",
        shortLabel = "💜 Неон",
        primaryColor = 0xFFFF2A85.toInt(),
        secondaryColor = 0xFF00F5D4.toInt(),
        tertiaryColor = 0xFF9D4EDD.toInt(),
        chipFillColor = 0xFF33183E.toInt()
    ),
    GOLD_GENIUS(
        id = "gold",
        shortLabel = "⚡ Золото",
        primaryColor = 0xFFFFE600.toInt(),
        secondaryColor = 0xFFFF8800.toInt(),
        tertiaryColor = 0xFF39FF88.toInt(),
        chipFillColor = 0xFF3A3212.toInt()
    ),
    ARCTIC_AURORA(
        id = "aurora",
        shortLabel = "❄️ Аврора",
        primaryColor = 0xFF00F0FF.toInt(),
        secondaryColor = 0xFF60A5FA.toInt(),
        tertiaryColor = 0xFF2DD4BF.toInt(),
        chipFillColor = 0xFF132F42.toInt()
    ),
    AUTO_VIBE(
        id = "auto",
        shortLabel = "🌈 Авто-вайб",
        primaryColor = 0xFF00E5FF.toInt(),
        secondaryColor = 0xFF39FF88.toInt(),
        tertiaryColor = 0xFFFF2A85.toInt(),
        chipFillColor = 0xFF1C2D42.toInt()
    );

    fun resolveEffective(sectionName: String = "", lineText: String = ""): VibeColorTheme {
        if (this != AUTO_VIBE) return this
        val sec = sectionName.lowercase()
        val txt = lineText.lowercase()
        return when {
            sec.contains("припев") || sec.contains("chorus") || sec.contains("hook") || sec.contains("drop") -> SUNSET_PHONK
            sec.contains("бридж") || sec.contains("bridge") || sec.contains("соло") -> TOKYO_NEON
            sec.contains("интро") || sec.contains("intro") -> ARCTIC_AURORA
            sec.contains("аутро") || sec.contains("outro") -> GOLD_GENIUS
            txt.contains("огонь") || txt.contains("fire") || txt.contains("кров") || txt.contains("бас") -> SUNSET_PHONK
            txt.contains("ночь") || txt.contains("неон") || txt.contains("люб") || txt.contains("love") -> TOKYO_NEON
            txt.contains("золот") || txt.contains("деньг") || txt.contains("сия") || txt.contains("звезд") -> GOLD_GENIUS
            else -> CYBER_EMERALD
        }
    }

    companion object {
        fun fromId(id: String?): VibeColorTheme =
            values().firstOrNull { it.id == id } ?: CYBER_EMERALD

        fun nextTheme(current: VibeColorTheme): VibeColorTheme =
            values()[(current.ordinal + 1) % values().size]
    }
}

enum class VibeStickerStyle(
    val id: String,
    val shortLabel: String,
    val badgeTitle: String,
    val burstSymbols: List<String>
) {
    VINYL_DISC("vinyl", "💿 Винил", "VINYL SPIN", listOf("💿", "♪", "♫", "✨")),
    FIRE_DROP("fire", "🔥 Огонь", "FIRE DROP", listOf("🔥", "💥", "⚡", "✨")),
    CYBER_CAT("cat", "😼 Вайб-кот", "SYNTH CAT", listOf("😼", "🎧", "🕶️", "✨")),
    NEON_HEART("heart", "💖 Неон-бит", "HEART BEAT", listOf("💖", "💜", "💫", "✨")),
    LIGHTNING_BASS("bolt", "⚡ Разряд", "HIGH VOLT", listOf("⚡", "🔋", "💥", "✨")),
    STAR_CROWN("crown", "👑 Хит", "TOP HIT", listOf("👑", "⭐", "💎", "✨"));

    companion object {
        fun fromId(id: String?): VibeStickerStyle =
            values().firstOrNull { it.id == id } ?: VINYL_DISC

        fun nextSticker(current: VibeStickerStyle): VibeStickerStyle =
            values()[(current.ordinal + 1) % values().size]
    }
}

enum class VibeBackgroundStyle(val id: String, val shortLabel: String) {
    NEBULA_RINGS("nebula", "🌌 Космос"),
    AURORA_CURTAINS("aurora", "🌊 Сияние"),
    SYNTH_GRID("grid", "🕹️ Сетка"),
    CONCERT_SPOTLIGHT("spotlight", "✨ Сцена");

    companion object {
        fun fromId(id: String?): VibeBackgroundStyle =
            values().firstOrNull { it.id == id } ?: NEBULA_RINGS

        fun nextStyle(current: VibeBackgroundStyle): VibeBackgroundStyle =
            values()[(current.ordinal + 1) % values().size]
    }
}

object SpringMath {
    /**
     * Evaluates a physical damped harmonic oscillator curve for normalized time t in [0, 1].
     */
    fun evaluateSpring(t: Float, stiffness: Float = 380f, dampingRatio: Float = 0.68f): Float {
        if (t <= 0f) return 0f
        if (t >= 1f) return 1f
        val zeta = dampingRatio.coerceAtLeast(0.15f).toDouble()
        val omega = sqrt(stiffness.coerceAtLeast(10f).toDouble())
        val timeSec = t.toDouble() * 0.72
        return if (zeta < 1.0) {
            val omegaD = omega * sqrt(1.0 - zeta * zeta)
            val decay = exp(-zeta * omega * timeSec)
            val displacement = decay * (cos(omegaD * timeSec) + (zeta * omega / omegaD) * sin(omegaD * timeSec))
            (1.0 - displacement).toFloat()
        } else {
            val decay = exp(-omega * timeSec)
            val displacement = decay * (1.0 + omega * timeSec)
            (1.0 - displacement).coerceIn(0.0, 1.0).toFloat()
        }
    }
}

class SpringInterpolator(
    private val stiffness: Float = 380f,
    private val dampingRatio: Float = 0.68f
) : TimeInterpolator {
    override fun getInterpolation(input: Float): Float =
        SpringMath.evaluateSpring(input, stiffness, dampingRatio)
}

object WordTimingEngine {
    data class WordToken(
        val start: Int,
        val end: Int,
        val text: String,
        val startFraction: Float,
        val endFraction: Float
    )

    data class WordBeatState(
        val tokens: List<WordToken>,
        val activeWordIndex: Int,
        val activeWordProgress: Float,
        val isCompleted: Boolean
    )

    private val WORD_REGEX = Regex("\\S+")
    private val PUNCTUATION_PAUSE_CHARS = setOf(',', '.', '!', '?', ';', ':', '—')

    fun tokenizeLine(text: String): List<WordToken> {
        val matches = WORD_REGEX.findAll(text).toList()
        if (matches.isEmpty()) return emptyList()

        val weights = DoubleArray(matches.size)
        var totalWeight = 0.0
        for (i in matches.indices) {
            val word = matches[i].value
            val punctuationPause = if (word.isNotEmpty() && word.last() in PUNCTUATION_PAUSE_CHARS) 2.2 else 0.0
            val w = word.length.coerceIn(2, 12).toDouble() + punctuationPause
            weights[i] = w
            totalWeight += w
        }
        val safeTotal = totalWeight.coerceAtLeast(1.0)
        var cumulative = 0.0
        val result = ArrayList<WordToken>(matches.size)
        for (i in matches.indices) {
            val m = matches[i]
            val startFrac = (cumulative / safeTotal).toFloat()
            cumulative += weights[i]
            val endFrac = if (i == matches.lastIndex) 1f else (cumulative / safeTotal).toFloat()
            result.add(
                WordToken(
                    start = m.range.first,
                    end = m.range.last + 1,
                    text = m.value,
                    startFraction = startFrac,
                    endFraction = endFrac
                )
            )
        }
        return result
    }

    fun computeLineVocalWindowMs(
        lines: List<LyricLine>,
        activeIndex: Int,
        totalDurationMs: Long,
        wordCount: Int
    ): Long {
        if (activeIndex !in lines.indices || wordCount <= 0) return 0L
        val lineStart = lines[activeIndex].timeMs.coerceAtLeast(0L)
        val nextTime = lines.getOrNull(activeIndex + 1)?.timeMs ?: -1L
        val vocalCap = (wordCount * 680L).coerceIn(1800L, 7500L)

        return when {
            nextTime > lineStart -> {
                val rawSpan = nextTime - lineStart
                (minOf(rawSpan, vocalCap) * 0.92).toLong().coerceAtLeast(550L)
            }
            totalDurationMs > lineStart -> {
                ((totalDurationMs - lineStart).coerceIn(1500L, 5500L) * 0.92).toLong().coerceAtLeast(550L)
            }
            else -> (wordCount * 420L).coerceIn(1400L, 4500L)
        }
    }

    fun computeBeatState(
        lines: List<LyricLine>,
        activeIndex: Int,
        effectiveMs: Long,
        totalDurationMs: Long
    ): WordBeatState? {
        if (activeIndex !in lines.indices) return null
        val text = lines[activeIndex].text
        val trimmed = text.trim()
        if (trimmed.isBlank() || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            return null
        }

        val tokens = tokenizeLine(text)
        if (tokens.isEmpty()) return null

        val lineStart = lines[activeIndex].timeMs.coerceAtLeast(0L)
        val windowMs = computeLineVocalWindowMs(lines, activeIndex, totalDurationMs, tokens.size).coerceAtLeast(500L)
        val progress = ((effectiveMs - lineStart).toFloat() / windowMs.toFloat()).coerceIn(0f, 1.05f)

        if (progress >= 1.0f) {
            return WordBeatState(
                tokens = tokens,
                activeWordIndex = tokens.lastIndex,
                activeWordProgress = 1f,
                isCompleted = true
            )
        }

        var activeWordIdx = tokens.lastIndex
        for (i in tokens.indices) {
            if (progress <= tokens[i].endFraction) {
                activeWordIdx = i
                break
            }
        }
        val activeToken = tokens[activeWordIdx]
        val tokenSpan = (activeToken.endFraction - activeToken.startFraction).coerceAtLeast(0.01f)
        val localProgress = ((progress - activeToken.startFraction) / tokenSpan).coerceIn(0f, 1f)

        return WordBeatState(
            tokens = tokens,
            activeWordIndex = activeWordIdx,
            activeWordProgress = localProgress,
            isCompleted = false
        )
    }
}

object AppMotionPrefs {
    const val PREFS_NAME = "genius_island_prefs"
    const val KEY_ISLAND_STYLE = "island_anim_style"
    const val KEY_KARAOKE_ENABLED = "karaoke_enabled"
    const val KEY_DISPLAY_MODE = "lyrics_display_mode"
    const val KEY_COLOR_THEME = "vibe_color_theme"
    const val KEY_STICKER_STYLE = "vibe_sticker_style"
    const val KEY_WORD_BG_STYLE = "vibe_word_bg_style"
    const val ACTION_PREFS_CHANGED = "com.satulin.geniuslyrics.ACTION_PREFS_CHANGED"

    @Volatile
    var currentSyncOffsetMs: Long = 0L
        private set

    fun setSyncOffsetMs(context: Context, offsetMs: Long) {
        currentSyncOffsetMs = offsetMs
        notifyPrefsChanged(context)
    }

    fun resetSyncOffset() {
        currentSyncOffsetMs = 0L
    }

    fun getIslandStyle(context: Context): IslandAnimStyle {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ISLAND_STYLE, IslandAnimStyle.FLY_IN.id)
        return IslandAnimStyle.fromId(raw)
    }

    fun setIslandStyle(context: Context, style: IslandAnimStyle) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ISLAND_STYLE, style.id)
            .apply()
        notifyPrefsChanged(context)
    }

    fun getDisplayMode(context: Context): LyricsDisplayMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_DISPLAY_MODE, null)
        if (saved != null) {
            return LyricsDisplayMode.fromId(saved)
        }
        val legacyKaraoke = prefs.getBoolean(KEY_KARAOKE_ENABLED, true)
        return if (legacyKaraoke) LyricsDisplayMode.KARAOKE else LyricsDisplayMode.PLAIN_TEXT
    }

    fun setDisplayMode(context: Context, mode: LyricsDisplayMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DISPLAY_MODE, mode.id)
            .putBoolean(KEY_KARAOKE_ENABLED, mode != LyricsDisplayMode.PLAIN_TEXT)
            .apply()
        notifyPrefsChanged(context)
    }

    fun isKaraokeEnabled(context: Context): Boolean =
        getDisplayMode(context) != LyricsDisplayMode.PLAIN_TEXT

    fun setKaraokeEnabled(context: Context, enabled: Boolean) {
        setDisplayMode(context, if (enabled) LyricsDisplayMode.KARAOKE else LyricsDisplayMode.PLAIN_TEXT)
    }

    fun getColorTheme(context: Context): VibeColorTheme {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_COLOR_THEME, VibeColorTheme.CYBER_EMERALD.id)
        return VibeColorTheme.fromId(raw)
    }

    fun setColorTheme(context: Context, theme: VibeColorTheme) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_COLOR_THEME, theme.id)
            .apply()
        notifyPrefsChanged(context)
    }

    fun getStickerStyle(context: Context): VibeStickerStyle {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_STICKER_STYLE, VibeStickerStyle.VINYL_DISC.id)
        return VibeStickerStyle.fromId(raw)
    }

    fun setStickerStyle(context: Context, sticker: VibeStickerStyle) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STICKER_STYLE, sticker.id)
            .apply()
        notifyPrefsChanged(context)
    }

    fun getWordBgStyle(context: Context): VibeBackgroundStyle {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_WORD_BG_STYLE, VibeBackgroundStyle.NEBULA_RINGS.id)
        return VibeBackgroundStyle.fromId(raw)
    }

    fun setWordBgStyle(context: Context, style: VibeBackgroundStyle) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_WORD_BG_STYLE, style.id)
            .apply()
        notifyPrefsChanged(context)
    }

    private fun notifyPrefsChanged(context: Context) {
        try {
            val intent = Intent(ACTION_PREFS_CHANGED).apply {
                setPackage(context.applicationContext.packageName)
            }
            context.sendBroadcast(intent)
        } catch (_: Exception) {}
    }
}

object MotionUtils {
    val EASE_OUT_EXPO = PathInterpolator(0.16f, 1f, 0.3f, 1f)
    val EASE_OUT_QUINT = PathInterpolator(0.22f, 1f, 0.36f, 1f)
    val EASE_OUT_CUBIC = PathInterpolator(0.22f, 1f, 0.36f, 1f)
    val SPRING_OVERSHOOT = OvershootInterpolator(1.35f)
    val SPRING_JUICY = OvershootInterpolator(1.75f)
    val SPRING_SNAPPY = SpringInterpolator(stiffness = 420f, dampingRatio = 0.72f)
    val SPRING_CRISP = SpringInterpolator(stiffness = 520f, dampingRatio = 0.80f)
    val SPRING_BOUNCY = SpringInterpolator(stiffness = 340f, dampingRatio = 0.56f)

    @SuppressLint("ClickableViewAccessibility")
    fun attachTactilePress(vararg views: View?) {
        for (v in views) {
            v?.setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        view.animate().cancel()
                        view.animate()
                            .scaleX(0.95f)
                            .scaleY(0.95f)
                            .setDuration(80L)
                            .setInterpolator(EASE_OUT_CUBIC)
                            .start()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        view.animate().cancel()
                        view.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(220L)
                            .setInterpolator(SPRING_SNAPPY)
                            .start()
                    }
                }
                false
            }
        }
    }

    fun animateProgressSmooth(pb: ProgressBar?, targetProgress: Int) {
        if (pb == null) return
        val clamped = targetProgress.coerceIn(0, pb.max)
        if (abs(pb.progress - clamped) > 15) {
            (pb.tag as? ObjectAnimator)?.cancel()
            val anim = ObjectAnimator.ofInt(pb, "progress", pb.progress, clamped).apply {
                duration = 180L
                interpolator = EASE_OUT_CUBIC
            }
            pb.tag = anim
            anim.start()
        } else {
            pb.progress = clamped
        }
    }

    fun createBorderGlowAnimator(
        view: View?,
        fillColor: Int,
        glowColor: Int,
        restStrokeColor: Int,
        cornerRadiusPx: Float,
        restStrokeWidthPx: Int,
        glowStrokeWidthPx: Int
    ): ValueAnimator? {
        if (view == null) return null
        val evaluator = ArgbEvaluator()
        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx
            setColor(fillColor)
            setStroke(glowStrokeWidthPx, glowColor)
        }
        view.background = drawable

        return ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 460L
            interpolator = EASE_OUT_CUBIC
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                val color = evaluator.evaluate(1f - f, glowColor, restStrokeColor) as Int
                val strokeW = (restStrokeWidthPx + (glowStrokeWidthPx - restStrokeWidthPx) * f)
                    .toInt()
                    .coerceAtLeast(restStrokeWidthPx)
                drawable.setStroke(strokeW, color)
                view.invalidate()
            }
        }
    }

    fun buildWordBeatSpannable(
        lineText: String,
        beat: WordTimingEngine.WordBeatState,
        activeStartColor: Int = Color.parseColor("#00E5FF"),
        activeEndColor: Int = Color.parseColor("#39FF88")
    ): SpannableString {
        val spannable = SpannableString(lineText)
        if (beat.isCompleted) {
            spannable.setSpan(
                ForegroundColorSpan(Color.WHITE),
                0,
                lineText.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            spannable.setSpan(
                StyleSpan(Typeface.BOLD),
                0,
                lineText.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            return spannable
        }

        val sungColor = Color.WHITE
        val activeWordColor = ArgbEvaluator().evaluate(
            beat.activeWordProgress.coerceIn(0f, 1f),
            activeStartColor,
            activeEndColor
        ) as Int
        val nextWordColor = Color.parseColor("#9AA6B8")
        val upcomingColor = Color.parseColor("#586273")

        for (i in beat.tokens.indices) {
            val tok = beat.tokens[i]
            val start = tok.start.coerceIn(0, lineText.length)
            val end = tok.end.coerceIn(start, lineText.length)
            if (start >= end) continue

            when {
                i < beat.activeWordIndex -> {
                    spannable.setSpan(
                        ForegroundColorSpan(sungColor),
                        start,
                        end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                i == beat.activeWordIndex -> {
                    spannable.setSpan(
                        ForegroundColorSpan(activeWordColor),
                        start,
                        end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    spannable.setSpan(
                        StyleSpan(Typeface.BOLD),
                        start,
                        end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                i == beat.activeWordIndex + 1 -> {
                    spannable.setSpan(
                        ForegroundColorSpan(nextWordColor),
                        start,
                        end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                else -> {
                    spannable.setSpan(
                        ForegroundColorSpan(upcomingColor),
                        start,
                        end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
        }
        return spannable
    }
}
