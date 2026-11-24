package com.satulin.geniuslyrics

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 60fps top audio-reactive wave, symmetric spectrum bars, and beat pulse header
 * displayed at the top of the dedicated Word-by-Word ("По словам") page.
 */
class WordStageVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val wavePathPrimary = Path()
    private val wavePathSecondary = Path()
    private val barRect = RectF()
    private val progressTrackRect = RectF()
    private val progressFillRect = RectF()

    private val primaryWavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val secondaryWavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val waveStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
        color = Color.parseColor("#8800E5FF")
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val progressTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#1F2A3A")
    }
    private val progressFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var isPlaying: Boolean = false
    private var phase: Float = 0f
    private var beatEnergy: Float = 0f
    private var shockwaveProgress: Float = 1f
    private var smoothLineProgress: Float = 0f
    private var targetLineProgress: Float = 0f
    private var activeWordProgress: Float = 0f

    private val numBars = 28
    private val barHeights = FloatArray(numBars) { 0.18f }

    private var lastFrameMs: Long = 0L

    fun setPlayingState(playing: Boolean) {
        if (isPlaying != playing) {
            isPlaying = playing
            invalidate()
        }
    }

    fun updateWordProgress(wordProgress: Float, lineProgress: Float) {
        activeWordProgress = wordProgress.coerceIn(0f, 1f)
        targetLineProgress = lineProgress.coerceIn(0f, 1f)
        invalidate()
    }

    fun triggerWordPulse(intensity: Float = 1.0f) {
        beatEnergy = (beatEnergy + 0.65f * intensity).coerceIn(0.35f, 1.35f)
        shockwaveProgress = 0f
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        primaryWavePaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(
                Color.parseColor("#3800E5FF"),
                Color.parseColor("#2E39FF88"),
                Color.parseColor("#04080D14")
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )

        secondaryWavePaint.shader = LinearGradient(
            w.toFloat(), 0f, 0f, h.toFloat(),
            intArrayOf(
                Color.parseColor("#307B61FF"),
                Color.parseColor("#2800E5FF"),
                Color.parseColor("#00080D14")
            ),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )

        barPaint.shader = LinearGradient(
            0f, h * 0.15f, 0f, h * 0.88f,
            intArrayOf(
                Color.parseColor("#39FF88"),
                Color.parseColor("#00E5FF"),
                Color.parseColor("#7B61FF")
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )

        progressFillPaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            intArrayOf(
                Color.parseColor("#00E5FF"),
                Color.parseColor("#39FF88")
            ),
            null,
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 1f || h <= 1f) return

        val now = System.currentTimeMillis()
        val dt = if (lastFrameMs == 0L) 0.016f else ((now - lastFrameMs).coerceIn(4L, 64L) / 1000f)
        lastFrameMs = now

        val speed = if (isPlaying) (2.6f + beatEnergy * 2.2f) else 0.7f
        phase += dt * speed
        beatEnergy = max(0f, beatEnergy - dt * 2.4f)
        if (shockwaveProgress < 1f) {
            shockwaveProgress = min(1f, shockwaveProgress + dt * 2.1f)
        }
        smoothLineProgress += (targetLineProgress - smoothLineProgress) * min(1f, dt * 14f)

        // 1. Flowing top dual audio ribbons
        drawWaveRibbon(
            canvas = canvas,
            w = w,
            h = h,
            freq = 2.2f,
            phaseShift = phase,
            amp = (8f + beatEnergy * 10f) * density,
            baseY = h * 0.52f,
            fillPaint = secondaryWavePaint,
            stroke = false
        )
        drawWaveRibbon(
            canvas = canvas,
            w = w,
            h = h,
            freq = 1.65f,
            phaseShift = -phase * 1.18f,
            amp = (10f + beatEnergy * 13f) * density,
            baseY = h * 0.56f,
            fillPaint = primaryWavePaint,
            stroke = true
        )

        // 2. Expanding center beat shockwave ring on each spoken word
        val centerX = w * 0.5f
        val centerY = h * 0.50f
        if (shockwaveProgress < 1f) {
            val ringRadius = (12f + shockwaveProgress * 72f) * density
            val alpha = ((1f - shockwaveProgress) * 150f).toInt().coerceIn(0, 255)
            orbPaint.color = Color.argb(alpha, 0, 229, 255)
            orbPaint.strokeWidth = (2.4f * (1f - shockwaveProgress * 0.6f)) * density
            canvas.drawCircle(centerX, centerY, ringRadius, orbPaint)
        }

        // 3. 28 Symmetric Audio Spectrum Bars
        val totalBarsWidth = min(w * 0.84f, 320f * density)
        val startX = (w - totalBarsWidth) * 0.5f
        val slotW = totalBarsWidth / numBars
        val barW = (slotW * 0.54f).coerceAtLeast(2.2f * density)
        val cornerR = barW * 0.5f
        val maxBarH = h * 0.64f
        val midIdx = (numBars - 1) * 0.5f

        for (i in 0 until numBars) {
            val distFromCenter = abs(i - midIdx) / midIdx
            val envelope = (1f - distFromCenter * distFromCenter * 0.72f).coerceIn(0.22f, 1f)
            val target = if (isPlaying) {
                val wave1 = (sin(phase * 1.9f + i * 0.55f) + 1f) * 0.5f
                val wave2 = (cos(phase * 1.25f - i * 0.8f) + 1f) * 0.5f
                val wordBoost = (1f - distFromCenter * 0.65f) * beatEnergy * 0.58f
                val raw = 0.16f + envelope * (0.48f * wave1 + 0.32f * wave2 + wordBoost)
                raw.coerceIn(0.14f, 1f)
            } else {
                0.15f + 0.05f * ((sin(phase + i * 0.4f) + 1f) * 0.5f)
            }

            barHeights[i] += (target - barHeights[i]) * min(1f, dt * 16f)
            val bh = (barHeights[i] * maxBarH).coerceAtLeast(4f * density)
            val bx = startX + i * slotW + (slotW - barW) * 0.5f
            val top = centerY - bh * 0.5f
            val bottom = centerY + bh * 0.5f
            barRect.set(bx, top, bx + barW, bottom)
            canvas.drawRoundRect(barRect, cornerR, cornerR, barPaint)
        }

        // 4. Bottom continuous line-completion neon progress bar
        val barMarginH = 18f * density
        val progH = 3f * density
        val progTop = h - progH - 2f * density
        progressTrackRect.set(barMarginH, progTop, w - barMarginH, progTop + progH)
        canvas.drawRoundRect(progressTrackRect, progH * 0.5f, progH * 0.5f, progressTrackPaint)

        val fillW = (progressTrackRect.width() * smoothLineProgress).coerceAtLeast(0f)
        if (fillW > 1f) {
            progressFillRect.set(barMarginH, progTop, barMarginH + fillW, progTop + progH)
            canvas.drawRoundRect(progressFillRect, progH * 0.5f, progH * 0.5f, progressFillPaint)
        }

        if (isPlaying || beatEnergy > 0.01f || shockwaveProgress < 1f || abs(targetLineProgress - smoothLineProgress) > 0.004f) {
            postInvalidateOnAnimation()
        }
    }

    private fun drawWaveRibbon(
        canvas: Canvas,
        w: Float,
        h: Float,
        freq: Float,
        phaseShift: Float,
        amp: Float,
        baseY: Float,
        fillPaint: Paint,
        stroke: Boolean
    ) {
        val path = if (stroke) wavePathPrimary else wavePathSecondary
        path.reset()
        val steps = 28
        val stepX = w / steps
        path.moveTo(0f, h)
        var firstY = baseY
        for (i in 0..steps) {
            val x = i * stepX
            val normX = i.toFloat() / steps
            val edgeTaper = sin(normX * Math.PI).toFloat()
            val y = baseY + sin(normX * freq * Math.PI * 2.0 + phaseShift).toFloat() * amp * edgeTaper
            if (i == 0) {
                firstY = y
                path.lineTo(0f, firstY)
            } else {
                path.lineTo(x, y)
            }
        }
        if (stroke) {
            canvas.drawPath(path, waveStrokePaint)
        }
        path.lineTo(w, h)
        path.close()
        canvas.drawPath(path, fillPaint)
    }
}
