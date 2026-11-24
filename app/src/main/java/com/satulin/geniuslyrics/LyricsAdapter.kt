package com.satulin.geniuslyrics

import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

private const val PAYLOAD_ACTIVE = "payload_active"
private const val PAYLOAD_WORD_TICK = "payload_word_tick"

private val COLOR_HEADER = Color.parseColor("#39FF88")
private val COLOR_ACTIVE = Color.parseColor("#FFFFFF")
private val COLOR_UPCOMING = Color.parseColor("#B3BCC9")
private val COLOR_PAST = Color.parseColor("#545D6B")
private val COLOR_PLAIN_TEXT = Color.parseColor("#F2F5F9")

class LyricsAdapter(
    private var lines: List<LyricLine> = emptyList(),
    private val onLineClicked: (Int) -> Unit,
    private val onLineLongClicked: ((Int) -> Unit)? = null
) : RecyclerView.Adapter<LyricsAdapter.LyricViewHolder>() {

    var activeLineIndex: Int = -1
        set(value) {
            val prev = field
            if (prev == value) return
            field = value
            if (displayMode == LyricsDisplayMode.PLAIN_TEXT || lines.isEmpty()) return
            val start = when {
                prev < 0 -> 0
                value < 0 -> 0
                else -> minOf(prev, value)
            }.coerceIn(0, lines.lastIndex)
            val end = when {
                prev < 0 -> value
                value < 0 -> prev
                else -> maxOf(prev, value)
            }.coerceIn(start, lines.lastIndex)
            notifyItemRangeChanged(start, end - start + 1, PAYLOAD_ACTIVE)
        }

    var fontSizeSp: Float = 20f
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    var displayMode: LyricsDisplayMode = LyricsDisplayMode.KARAOKE
        set(value) {
            if (field == value) return
            field = value
            currentWordBeat = null
            currentWordBeatLine = -1
            notifyDataSetChanged()
        }

    var motionStyle: IslandAnimStyle = IslandAnimStyle.FLY_IN

    var colorTheme: VibeColorTheme = VibeColorTheme.CYBER_EMERALD
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    private var currentWordBeat: WordTimingEngine.WordBeatState? = null
    private var currentWordBeatLine: Int = -1

    fun updateWordBeat(lineIndex: Int, beat: WordTimingEngine.WordBeatState?) {
        val prevLine = currentWordBeatLine
        val prevWord = currentWordBeat?.activeWordIndex
        val prevDone = currentWordBeat?.isCompleted

        currentWordBeatLine = lineIndex
        currentWordBeat = beat

        if (displayMode != LyricsDisplayMode.WORD_BY_WORD) return
        if (lineIndex in lines.indices && (prevLine != lineIndex || prevWord != beat?.activeWordIndex || prevDone != beat?.isCompleted)) {
            notifyItemChanged(lineIndex, PAYLOAD_WORD_TICK)
        }
    }

    fun replayActiveAnimation() {
        if (displayMode == LyricsDisplayMode.PLAIN_TEXT) return
        if (activeLineIndex in lines.indices) {
            notifyItemChanged(activeLineIndex, PAYLOAD_ACTIVE)
        }
    }

    fun submitLines(newLines: List<LyricLine>) {
        lines = newLines
        currentWordBeat = null
        currentWordBeatLine = -1
        activeLineIndex = if (newLines.isNotEmpty()) 0 else -1
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LyricViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_lyric_line, parent, false)
        return LyricViewHolder(view)
    }

    override fun onBindViewHolder(holder: LyricViewHolder, position: Int) {
        val line = lines[position]
        val beat = if (position == currentWordBeatLine) currentWordBeat else null
        holder.bind(line, position, activeLineIndex, fontSizeSp, displayMode, motionStyle, colorTheme, beat, animateColor = false)
        holder.itemView.setOnClickListener {
            val idx = holder.adapterPosition
            if (idx != RecyclerView.NO_POSITION) {
                onLineClicked(idx)
            }
        }
        holder.itemView.setOnLongClickListener {
            val idx = holder.adapterPosition
            if (idx != RecyclerView.NO_POSITION && onLineLongClicked != null) {
                onLineLongClicked.invoke(idx)
                true
            } else {
                false
            }
        }
    }

    override fun onBindViewHolder(
        holder: LyricViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }
        val line = lines[position]
        val beat = if (position == currentWordBeatLine) currentWordBeat else null
        if (payloads.contains(PAYLOAD_ACTIVE)) {
            holder.applyState(line, position, activeLineIndex, fontSizeSp, displayMode, motionStyle, colorTheme, beat, animateColor = true)
        } else if (payloads.contains(PAYLOAD_WORD_TICK)) {
            holder.applyWordTick(line, position, activeLineIndex, displayMode, colorTheme, beat)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun getItemCount(): Int = lines.size

    class LyricViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvLine: TextView = itemView.findViewById(R.id.tvLyricLine)
        private val viewIndicator: View = itemView.findViewById(R.id.viewIndicator)
        private var colorAnimator: ValueAnimator? = null
        private var typewriterAnimator: ValueAnimator? = null
        private var currentColor: Int = COLOR_UPCOMING

        fun bind(
            line: LyricLine,
            position: Int,
            activeIndex: Int,
            fontSize: Float,
            displayMode: LyricsDisplayMode,
            motionStyle: IslandAnimStyle,
            colorTheme: VibeColorTheme,
            beatState: WordTimingEngine.WordBeatState?,
            animateColor: Boolean
        ) {
            typewriterAnimator?.cancel()
            colorAnimator?.cancel()
            tvLine.animate().cancel()
            viewIndicator.animate().cancel()
            tvLine.text = line.text

            // 100% crisp vector rendering: zero view scaling, zero blur!
            tvLine.alpha = 1.0f
            tvLine.translationX = 0f
            tvLine.translationY = 0f
            tvLine.rotationX = 0f
            itemView.scaleX = 1.0f
            itemView.scaleY = 1.0f
            itemView.translationX = 0f
            tvLine.setTypeface(Typeface.create("sans-serif", Typeface.BOLD))

            applyState(line, position, activeIndex, fontSize, displayMode, motionStyle, colorTheme, beatState, animateColor)
        }

        fun applyWordTick(
            line: LyricLine,
            position: Int,
            activeIndex: Int,
            displayMode: LyricsDisplayMode,
            colorTheme: VibeColorTheme,
            beatState: WordTimingEngine.WordBeatState?
        ) {
            if (displayMode != LyricsDisplayMode.WORD_BY_WORD || position != activeIndex) return
            val trimmed = line.text.trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) return
            if (beatState == null) return

            typewriterAnimator?.cancel()
            colorAnimator?.cancel()
            tvLine.text = MotionUtils.buildWordBeatSpannable(
                line.text,
                beatState,
                colorTheme.secondaryColor,
                colorTheme.primaryColor
            )

            // Crisp micro-pulse on glowing side bar on each beat word
            viewIndicator.animate().cancel()
            val density = itemView.resources.displayMetrics.density
            viewIndicator.pivotY = (viewIndicator.height.takeIf { it > 0 }?.toFloat() ?: (26f * density)) * 0.5f
            viewIndicator.scaleY = 0.85f
            viewIndicator.animate()
                .scaleY(1.0f)
                .setDuration(120L)
                .setInterpolator(MotionUtils.SPRING_CRISP)
                .start()
        }

        fun applyState(
            line: LyricLine,
            position: Int,
            activeIndex: Int,
            fontSize: Float,
            displayMode: LyricsDisplayMode,
            motionStyle: IslandAnimStyle,
            colorTheme: VibeColorTheme,
            beatState: WordTimingEngine.WordBeatState?,
            animateColor: Boolean
        ) {
            val trimmed = line.text.trim()
            val isHeader = trimmed.startsWith("[") && trimmed.endsWith("]")
            val density = itemView.resources.displayMetrics.density
            val headerAccent = colorTheme.primaryColor
            viewIndicator.background?.mutate()?.setTint(colorTheme.primaryColor)

            typewriterAnimator?.cancel()

            if (displayMode == LyricsDisplayMode.PLAIN_TEXT) {
                tvLine.animate().cancel()
                viewIndicator.animate().cancel()
                colorAnimator?.cancel()
                tvLine.alpha = 1.0f
                tvLine.translationX = 0f
                tvLine.translationY = 0f
                tvLine.rotationX = 0f
                tvLine.text = line.text
                viewIndicator.visibility = View.GONE
                val targetColor = if (isHeader) {
                    tvLine.textSize = (fontSize - 4f).coerceAtLeast(12f)
                    headerAccent
                } else {
                    tvLine.textSize = fontSize
                    COLOR_PLAIN_TEXT
                }
                currentColor = targetColor
                tvLine.setTextColor(targetColor)
                return
            }

            val isActive = position == activeIndex
            val isPast = activeIndex >= 0 && position < activeIndex

            val targetColor: Int
            when {
                isHeader -> {
                    viewIndicator.animate().cancel()
                    viewIndicator.scaleY = 1f
                    viewIndicator.alpha = 1f
                    viewIndicator.visibility = View.INVISIBLE
                    tvLine.textSize = (fontSize - 5f).coerceAtLeast(12f)
                    targetColor = headerAccent
                }
                isActive -> {
                    viewIndicator.visibility = View.VISIBLE
                    if (animateColor) {
                        viewIndicator.animate().cancel()
                        viewIndicator.pivotY = (viewIndicator.height.takeIf { it > 0 }?.toFloat() ?: (26f * density)) * 0.5f
                        viewIndicator.scaleY = 0.15f
                        viewIndicator.alpha = 0f
                        viewIndicator.animate()
                            .scaleY(1.0f)
                            .alpha(1.0f)
                            .setDuration(280L)
                            .setInterpolator(MotionUtils.SPRING_BOUNCY)
                            .start()
                    } else {
                        viewIndicator.scaleY = 1f
                        viewIndicator.alpha = 1f
                    }
                    tvLine.textSize = fontSize + 2.5f
                    targetColor = COLOR_ACTIVE
                }
                isPast -> {
                    viewIndicator.animate().cancel()
                    viewIndicator.scaleY = 1f
                    viewIndicator.alpha = 1f
                    viewIndicator.visibility = View.INVISIBLE
                    tvLine.textSize = fontSize
                    targetColor = COLOR_PAST
                }
                else -> {
                    viewIndicator.animate().cancel()
                    viewIndicator.scaleY = 1f
                    viewIndicator.alpha = 1f
                    viewIndicator.visibility = View.INVISIBLE
                    tvLine.textSize = fontSize
                    targetColor = COLOR_UPCOMING
                }
            }

            val isWordBeatActive = (displayMode == LyricsDisplayMode.WORD_BY_WORD && isActive && !isHeader)
            if (!isWordBeatActive) {
                tvLine.text = line.text
            }

            tvLine.animate().cancel()
            if (isActive && animateColor && !isHeader) {
                when (motionStyle) {
                    IslandAnimStyle.FLY_IN -> {
                        tvLine.alpha = 0.2f
                        tvLine.translationY = 16f * density
                        tvLine.translationX = 6f * density
                        tvLine.rotationX = 18f
                        tvLine.animate()
                            .alpha(1f)
                            .translationY(0f)
                            .translationX(0f)
                            .rotationX(0f)
                            .setDuration(320L)
                            .setInterpolator(MotionUtils.SPRING_SNAPPY)
                            .start()
                    }
                    IslandAnimStyle.FADE -> {
                        tvLine.translationX = 0f
                        tvLine.translationY = 4f * density
                        tvLine.rotationX = 0f
                        tvLine.alpha = 0.18f
                        tvLine.animate()
                            .alpha(1f)
                            .translationY(0f)
                            .setDuration(440L)
                            .setInterpolator(MotionUtils.EASE_OUT_QUINT)
                            .start()
                    }
                    IslandAnimStyle.SLIDE_KINETIC -> {
                        tvLine.translationY = 0f
                        tvLine.rotationX = 0f
                        tvLine.translationX = 36f * density
                        tvLine.alpha = 0.4f
                        tvLine.animate()
                            .translationX(0f)
                            .alpha(1f)
                            .setDuration(280L)
                            .setInterpolator(MotionUtils.SPRING_CRISP)
                            .start()
                    }
                    IslandAnimStyle.ELASTIC_POP -> {
                        tvLine.alpha = 0.25f
                        tvLine.rotationX = 0f
                        tvLine.translationX = 14f * density
                        tvLine.translationY = 10f * density
                        tvLine.animate()
                            .alpha(1f)
                            .translationX(0f)
                            .translationY(0f)
                            .setDuration(340L)
                            .setInterpolator(MotionUtils.SPRING_BOUNCY)
                            .start()
                    }
                    IslandAnimStyle.TYPEWRITER -> {
                        tvLine.translationY = 0f
                        tvLine.rotationX = 0f
                        tvLine.translationX = 0f
                        tvLine.alpha = 1f
                        if (displayMode == LyricsDisplayMode.KARAOKE && line.text.isNotEmpty()) {
                            val full = line.text
                            typewriterAnimator = ValueAnimator.ofInt(1, full.length).apply {
                                duration = 260L
                                interpolator = MotionUtils.EASE_OUT_CUBIC
                                addUpdateListener { anim ->
                                    val count = (anim.animatedValue as Int).coerceIn(1, full.length)
                                    tvLine.text = if (count < full.length) {
                                        full.substring(0, count) + "▌"
                                    } else {
                                        full
                                    }
                                }
                                start()
                            }
                        } else {
                            tvLine.alpha = 0.45f
                            tvLine.translationX = 8f * density
                            tvLine.animate()
                                .alpha(1f)
                                .translationX(0f)
                                .setDuration(210L)
                                .setInterpolator(MotionUtils.EASE_OUT_CUBIC)
                                .start()
                        }
                    }
                }
            } else {
                tvLine.alpha = 1f
                tvLine.translationX = 0f
                tvLine.translationY = 0f
                tvLine.rotationX = 0f
            }

            colorAnimator?.cancel()
            if (isWordBeatActive) {
                currentColor = Color.WHITE
                tvLine.text = if (beatState != null) {
                    MotionUtils.buildWordBeatSpannable(
                        line.text,
                        beatState,
                        colorTheme.secondaryColor,
                        colorTheme.primaryColor
                    )
                } else {
                    line.text
                }
                if (beatState == null) {
                    tvLine.setTextColor(Color.parseColor("#6A7382"))
                }
            } else if (animateColor && currentColor != targetColor) {
                val startColor = currentColor
                colorAnimator = ValueAnimator.ofArgb(startColor, targetColor).apply {
                    duration = 250L
                    interpolator = MotionUtils.EASE_OUT_CUBIC
                    addUpdateListener { anim ->
                        val c = anim.animatedValue as Int
                        currentColor = c
                        tvLine.setTextColor(c)
                    }
                    start()
                }
            } else {
                currentColor = targetColor
                tvLine.setTextColor(targetColor)
            }
        }
    }
}
