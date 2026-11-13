package com.satulin.geniuslyrics

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class LyricsAdapter(
    private var lines: List<String> = emptyList(),
    private val onLineClicked: (Int) -> Unit
) : RecyclerView.Adapter<LyricsAdapter.LyricViewHolder>() {

    var activeLineIndex: Int = -1
        set(value) {
            val prev = field
            field = value
            if (prev != -1) notifyItemChanged(prev)
            if (value != -1) notifyItemChanged(value)
        }

    var fontSizeSp: Float = 19f
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    fun submitLines(newLines: List<String>) {
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
        holder.bind(line, position == activeLineIndex, fontSizeSp)
        holder.itemView.setOnClickListener {
            onLineClicked(position)
        }
    }

    override fun getItemCount(): Int = lines.size

    class LyricViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvLine: TextView = itemView.findViewById(R.id.tvLyricLine)

        fun bind(text: String, isActive: Boolean, fontSize: Float) {
            val trimmed = text.trim()
            val isHeader = trimmed.startsWith("[") && trimmed.endsWith("]")

            tvLine.text = text
            tvLine.textSize = if (isHeader) fontSize - 3f else fontSize

            if (isHeader) {
                // Section header like [Chorus], [Verse]
                tvLine.setTextColor(Color.parseColor("#99FFFFFF"))
                tvLine.setTypeface(null, Typeface.BOLD)
                tvLine.alpha = 0.6f
                itemView.scaleX = 0.96f
                itemView.scaleY = 0.96f
            } else if (isActive) {
                // Active sung line: vibrant Genius yellow, crisp bold 100% opacity, slightly scaled up
                tvLine.setTextColor(Color.parseColor("#FFFF64"))
                tvLine.setTypeface(null, Typeface.BOLD)
                tvLine.alpha = 1.0f
                itemView.animate()
                    .scaleX(1.04f)
                    .scaleY(1.04f)
                    .translationX(10f)
                    .setDuration(220)
                    .start()
            } else {
                // Inactive line: 100% sharp clear text without blur, readable white
                tvLine.setTextColor(Color.parseColor("#B3FFFFFF"))
                tvLine.setTypeface(null, Typeface.NORMAL)
                tvLine.alpha = 0.75f
                itemView.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .translationX(0f)
                    .setDuration(180)
                    .start()
            }
        }
    }
}
