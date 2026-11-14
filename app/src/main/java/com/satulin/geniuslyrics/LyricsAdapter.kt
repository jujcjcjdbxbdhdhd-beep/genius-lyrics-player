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
        holder.bind(line, position == activeLineIndex, fontSizeSp)
        holder.itemView.setOnClickListener {
            onLineClicked(position)
        }
    }

    override fun getItemCount(): Int = lines.size

    class LyricViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvLine: TextView = itemView.findViewById(R.id.tvLyricLine)
        private val viewIndicator: View = itemView.findViewById(R.id.viewIndicator)

        fun bind(line: LyricLine, isActive: Boolean, fontSize: Float) {
            val trimmed = line.text.trim()
            val isHeader = trimmed.startsWith("[") && trimmed.endsWith("]")

            tvLine.text = line.text
            // 100% crisp vector rendering: never scale view, keep alpha 1.0f (NO BLUR!)
            tvLine.alpha = 1.0f
            itemView.scaleX = 1.0f
            itemView.scaleY = 1.0f
            itemView.translationX = 0f

            when {
                isHeader -> {
                    // Spotify section tags: [Интро], [Припев], [Куплет] in subtle Spotify green
                    viewIndicator.visibility = View.INVISIBLE
                    tvLine.textSize = (fontSize - 4f).coerceAtLeast(13f)
                    tvLine.setTextColor(Color.parseColor("#1DB954"))
                    tvLine.setTypeface(Typeface.create("sans-serif", Typeface.BOLD))
                }
                isActive -> {
                    // Spotify active sung line: 100% brilliant white, larger, bold, Spotify green pill
                    viewIndicator.visibility = View.VISIBLE
                    tvLine.textSize = fontSize + 2f
                    tvLine.setTextColor(Color.parseColor("#FFFFFF"))
                    tvLine.setTypeface(Typeface.create("sans-serif", Typeface.BOLD))
                }
                else -> {
                    // Spotify inactive line: crisp, razor-sharp Spotify grey, 100% alpha (zero blur!)
                    viewIndicator.visibility = View.INVISIBLE
                    tvLine.textSize = fontSize
                    tvLine.setTextColor(Color.parseColor("#72777D"))
                    tvLine.setTypeface(Typeface.create("sans-serif", Typeface.BOLD))
                }
            }
        }
    }
}
