package com.satulin.geniuslyrics

import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class LyricsAdapter(
    private var lines: List<LyricLine> = emptyList(),
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
        holder.bind(line, position == activeLineIndex, fontSizeSp)
        holder.itemView.setOnClickListener {
            onLineClicked(position)
        }
    }

    override fun getItemCount(): Int = lines.size

    class LyricViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvLine: TextView = itemView.findViewById(R.id.tvLyricLine)

        fun bind(line: LyricLine, isActive: Boolean, fontSize: Float) {
            val trimmed = line.text.trim()
            val isHeader = trimmed.startsWith("[") && trimmed.endsWith("]")

            tvLine.text = line.text
            tvLine.textSize = if (isHeader) fontSize - 3f else fontSize

            if (isHeader) {
                // Section header like [Chorus], [Verse]
                tvLine.setTextColor(Color.parseColor("#80FFFFFF"))
                tvLine.setTypeface(null, Typeface.BOLD)
                tvLine.alpha = 0.65f
                itemView.scaleX = 0.98f
                itemView.scaleY = 0.98f
            } else if (isActive) {
                // Active sung line: vibrant Genius Yellow, sharp bold, smoothly scaled & highlighted
                tvLine.setTextColor(Color.parseColor("#FFFF64"))
                tvLine.setTypeface(null, Typeface.BOLD)
                tvLine.alpha = 1.0f
                itemView.animate()
                    .scaleX(1.05f)
                    .scaleY(1.05f)
                    .translationX(12f)
                    .setDuration(240)
                    .start()
            } else {
                // Inactive line: 100% sharp clear text without blur
                tvLine.setTextColor(Color.parseColor("#B3FFFFFF"))
                tvLine.setTypeface(null, Typeface.NORMAL)
                tvLine.alpha = 0.75f
                itemView.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .translationX(0f)
                    .setDuration(200)
                    .start()
            }
        }
    }
}
