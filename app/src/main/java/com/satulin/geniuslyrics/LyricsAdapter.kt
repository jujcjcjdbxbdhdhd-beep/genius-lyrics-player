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
        private val viewIndicator: View = itemView.findViewById(R.id.viewIndicator)

        fun bind(line: LyricLine, isActive: Boolean, fontSize: Float) {
            val trimmed = line.text.trim()
            val isHeader = trimmed.startsWith("[") && trimmed.endsWith("]")

            tvLine.text = line.text
            tvLine.textSize = if (isHeader) fontSize - 3f else fontSize

            if (isHeader) {
                // Section tags like [Припев], [Куплет 1]
                viewIndicator.visibility = View.INVISIBLE
                tvLine.setTextColor(Color.parseColor("#64748B"))
                tvLine.setTypeface(null, Typeface.BOLD)
                tvLine.alpha = 0.75f
                itemView.scaleX = 0.98f
                itemView.scaleY = 0.98f
                itemView.translationX = 0f
            } else if (isActive) {
                // Active sung line: vibrant Neon Mint Cyan, bold, glowing indicator pill
                viewIndicator.visibility = View.VISIBLE
                viewIndicator.alpha = 1.0f
                tvLine.setTextColor(Color.parseColor("#00F5D4"))
                tvLine.setTypeface(null, Typeface.BOLD)
                tvLine.alpha = 1.0f
                itemView.animate()
                    .scaleX(1.04f)
                    .scaleY(1.04f)
                    .translationX(6f)
                    .setDuration(200)
                    .start()
            } else {
                // Inactive line: 100% sharp clear text without blur, readable white
                viewIndicator.visibility = View.INVISIBLE
                tvLine.setTextColor(Color.parseColor("#CBD5E1"))
                tvLine.setTypeface(null, Typeface.NORMAL)
                tvLine.alpha = 0.80f
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
