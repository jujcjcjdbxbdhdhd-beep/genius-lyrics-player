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

private val COLOR_HEADER = Color.parseColor("#1DB954")
private val COLOR_ACTIVE = Color.parseColor("#FFFFFF")
private val COLOR_UPCOMING = Color.parseColor("#B3B8BE")
private val COLOR_PAST = Color.parseColor("#5E6368")

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
            if (lines.isEmpty()) return
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

    fun submitLines(newLines: List<LyricLine>) {
        lines = newLines
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
        holder.bind(line, position, activeLineIndex, fontSizeSp, animateColor = false)
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
        if (payloads.contains(PAYLOAD_ACTIVE)) {
            val line = lines[position]
            holder.applyState(line, position, activeLineIndex, fontSizeSp, animateColor = true)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun getItemCount(): Int = lines.size

    class LyricViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvLine: TextView = itemView.findViewById(R.id.tvLyricLine)
        private val viewIndicator: View = itemView.findViewById(R.id.viewIndicator)
        private var colorAnimator: ValueAnimator? = null
        private var currentColor: Int = COLOR_UPCOMING

        fun bind(
            line: LyricLine,
            position: Int,
            activeIndex: Int,
            fontSize: Float,
            animateColor: Boolean
        ) {
            tvLine.text = line.text
            // 100% crisp vector rendering: zero view scaling, zero blur!
            tvLine.alpha = 1.0f
            itemView.scaleX = 1.0f
            itemView.scaleY = 1.0f
            itemView.translationX = 0f
            tvLine.setTypeface(Typeface.create("sans-serif", Typeface.BOLD))

            applyState(line, position, activeIndex, fontSize, animateColor)
        }

        fun applyState(
            line: LyricLine,
            position: Int,
            activeIndex: Int,
            fontSize: Float,
            animateColor: Boolean
        ) {
            val trimmed = line.text.trim()
            val isHeader = trimmed.startsWith("[") && trimmed.endsWith("]")
            val isActive = position == activeIndex
            val isPast = activeIndex >= 0 && position < activeIndex

            val targetColor: Int
            when {
                isHeader -> {
                    viewIndicator.visibility = View.INVISIBLE
                    tvLine.textSize = (fontSize - 5f).coerceAtLeast(12f)
                    targetColor = COLOR_HEADER
                }
                isActive -> {
                    viewIndicator.visibility = View.VISIBLE
                    tvLine.textSize = fontSize + 2.5f
                    targetColor = COLOR_ACTIVE
                }
                isPast -> {
                    viewIndicator.visibility = View.INVISIBLE
                    tvLine.textSize = fontSize
                    targetColor = COLOR_PAST
                }
                else -> {
                    viewIndicator.visibility = View.INVISIBLE
                    tvLine.textSize = fontSize
                    targetColor = COLOR_UPCOMING
                }
            }

            colorAnimator?.cancel()
            if (animateColor && currentColor != targetColor) {
                val startColor = currentColor
                colorAnimator = ValueAnimator.ofArgb(startColor, targetColor).apply {
                    duration = 220L
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
