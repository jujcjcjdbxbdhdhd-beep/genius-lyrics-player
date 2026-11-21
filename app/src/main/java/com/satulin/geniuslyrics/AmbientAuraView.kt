package com.satulin.geniuslyrics

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

class AmbientAuraView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val bgColor = Color.parseColor("#06080B")
    private val orbPaints = Array(3) { Paint(Paint.ANTI_ALIAS_FLAG) }
    private val orbShaders = arrayOfNulls<RadialGradient>(3)
    private val orbMatrix = Matrix()

    private var animator: ValueAnimator? = null
    private var phase: Float = 0f
    private var pulseStrength: Float = 0f
    private var isPlaying: Boolean = false

    private val orbBaseAlphas = intArrayOf(72, 56, 52)
    private val orbBaseRadii = FloatArray(3)

    init {
        val emeraldColors = intArrayOf(
            Color.argb(255, 29, 185, 84),
            Color.argb(120, 30, 215, 96),
            Color.TRANSPARENT
        )
        val cyanColors = intArrayOf(
            Color.argb(255, 0, 212, 255),
            Color.argb(110, 0, 180, 235),
            Color.TRANSPARENT
        )
        val violetColors = intArrayOf(
            Color.argb(255, 107, 78, 255),
            Color.argb(110, 88, 60, 220),
            Color.TRANSPARENT
        )
        val stops = floatArrayOf(0f, 0.45f, 1f)
        val palettes = arrayOf(emeraldColors, cyanColors, violetColors)

        for (i in 0 until 3) {
            val shader = RadialGradient(0f, 0f, 1f, palettes[i], stops, Shader.TileMode.CLAMP)
            orbShaders[i] = shader
            orbPaints[i].shader = shader
            orbPaints[i].alpha = orbBaseAlphas[i]
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val baseDim = max(w, h).toFloat().coerceAtLeast(1f)
        orbBaseRadii[0] = baseDim * 0.52f
        orbBaseRadii[1] = baseDim * 0.44f
        orbBaseRadii[2] = baseDim * 0.48f
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAuraLoop()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    fun setPlaying(playing: Boolean) {
        isPlaying = playing
    }

    fun triggerPulse(strength: Float = 1f) {
        pulseStrength = max(pulseStrength, strength.coerceIn(0f, 1.3f))
    }

    private fun startAuraLoop() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 16_000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val step = if (isPlaying) 0.011f else 0.0045f
                phase = (phase + step) % 1000f
                if (pulseStrength > 0.002f) {
                    pulseStrength *= 0.90f
                } else {
                    pulseStrength = 0f
                }
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(bgColor)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val twoPi = (PI * 2.0).toFloat()
        val angle = (phase % 1f) * twoPi
        val pulse = pulseStrength

        // Orb 0: Emerald (top-left sweeping aura)
        val cx0 = w * (0.24f + 0.16f * cos(angle))
        val cy0 = h * (0.20f + 0.12f * sin(angle * 1.15f))
        val r0 = orbBaseRadii[0] * (1f + 0.22f * pulse)
        drawOrb(canvas, 0, cx0, cy0, r0, (orbBaseAlphas[0] + (58f * pulse)).toInt().coerceIn(0, 190))

        // Orb 1: Electric Cyan (mid-right breath aura)
        val cx1 = w * (0.78f + 0.14f * sin(angle * 0.9f + 1.2f))
        val cy1 = h * (0.48f + 0.15f * cos(angle * 0.8f + 2.1f))
        val r1 = orbBaseRadii[1] * (1f + 0.18f * pulse)
        drawOrb(canvas, 1, cx1, cy1, r1, (orbBaseAlphas[1] + (42f * pulse)).toInt().coerceIn(0, 175))

        // Orb 2: Deep Violet (bottom-center depth glow)
        val cx2 = w * (0.46f + 0.20f * cos(angle * 0.7f + 3.4f))
        val cy2 = h * (0.82f + 0.10f * sin(angle + 1.9f))
        val r2 = orbBaseRadii[2] * (1f + 0.08f * pulse)
        drawOrb(canvas, 2, cx2, cy2, r2, orbBaseAlphas[2])
    }

    private fun drawOrb(canvas: Canvas, index: Int, cx: Float, cy: Float, radius: Float, alpha: Int) {
        val shader = orbShaders[index] ?: return
        orbMatrix.setScale(radius, radius)
        orbMatrix.postTranslate(cx, cy)
        shader.setLocalMatrix(orbMatrix)
        val paint = orbPaints[index]
        paint.alpha = alpha
        canvas.drawCircle(cx, cy, radius, paint)
    }
}
