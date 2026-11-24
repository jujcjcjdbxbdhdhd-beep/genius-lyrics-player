package com.satulin.geniuslyrics

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * 60fps top audio-reactive wave, symmetric spectrum bars, custom vector animated stickers,
 * floating beat particles, and line progress glow bar for the Word-by-Word ("По словам") page.
 */
class WordStageVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class FloatingParticle(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        var symbol: String,
        var life: Float,
        var maxLife: Float,
        var sizeSp: Float
    )

    private val density = resources.displayMetrics.density

    private val wavePathPrimary = Path()
    private val wavePathSecondary = Path()
    private val stickerPath = Path()
    private val stickerSubPath = Path()
    private val barRect = RectF()
    private val badgeRect = RectF()
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
    private val stickerFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val stickerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val particleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }

    private var colorTheme: VibeColorTheme = VibeColorTheme.CYBER_EMERALD
    private var stickerStyle: VibeStickerStyle = VibeStickerStyle.VINYL_DISC

    private var isPlaying: Boolean = false
    private var phase: Float = 0f
    private var vinylAngleDeg: Float = 0f
    private var beatEnergy: Float = 0f
    private var shockwaveProgress: Float = 1f
    private var smoothLineProgress: Float = 0f
    private var targetLineProgress: Float = 0f
    private var activeWordProgress: Float = 0f

    private val numBars = 24
    private val barHeights = FloatArray(numBars) { 0.18f }
    private val particles = ArrayList<FloatingParticle>(24)

    private var lastFrameMs: Long = 0L

    fun setPlayingState(playing: Boolean) {
        if (isPlaying != playing) {
            isPlaying = playing
            invalidate()
        }
    }

    fun setColorTheme(theme: VibeColorTheme) {
        if (colorTheme != theme) {
            colorTheme = theme
            rebuildShaders(width, height)
            invalidate()
        }
    }

    fun setStickerStyle(style: VibeStickerStyle, triggerBurst: Boolean = false) {
        stickerStyle = style
        if (triggerBurst) {
            triggerStickerBurst(7)
        }
        invalidate()
    }

    fun updateWordProgress(wordProgress: Float, lineProgress: Float) {
        activeWordProgress = wordProgress.coerceIn(0f, 1f)
        targetLineProgress = lineProgress.coerceIn(0f, 1f)
        invalidate()
    }

    fun triggerWordPulse(intensity: Float = 1.0f) {
        beatEnergy = (beatEnergy + 0.68f * intensity).coerceIn(0.38f, 1.4f)
        shockwaveProgress = 0f
        if (width > 0 && height > 0 && particles.size < 18) {
            spawnParticle(fromSticker = true)
        }
        invalidate()
    }

    fun triggerStickerBurst(count: Int = 6) {
        beatEnergy = 1.3f
        shockwaveProgress = 0f
        if (width > 0 && height > 0) {
            repeat(count.coerceIn(1, 12)) {
                spawnParticle(fromSticker = false)
            }
        }
        invalidate()
    }

    private fun spawnParticle(fromSticker: Boolean) {
        val w = width.toFloat().coerceAtLeast(100f)
        val h = height.toFloat().coerceAtLeast(60f)
        val symbols = stickerStyle.burstSymbols
        val sym = symbols[Random.nextInt(symbols.size)]
        val startX = if (fromSticker) {
            w - 42f * density + (Random.nextFloat() - 0.5f) * 20f * density
        } else {
            w * (0.15f + Random.nextFloat() * 0.72f)
        }
        val startY = h * (0.58f + Random.nextFloat() * 0.22f)
        val vx = (Random.nextFloat() - 0.55f) * 36f * density
        val vy = -(26f + Random.nextFloat() * 34f) * density
        val maxL = 0.85f + Random.nextFloat() * 0.55f
        particles.add(
            FloatingParticle(
                x = startX,
                y = startY,
                vx = vx,
                vy = vy,
                symbol = sym,
                life = 0f,
                maxLife = maxL,
                sizeSp = if (fromSticker) 12.5f else 14.5f
            )
        )
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildShaders(w, h)
    }

    private fun rebuildShaders(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val cPrimary = colorTheme.primaryColor
        val cSecondary = colorTheme.secondaryColor
        val cTertiary = colorTheme.tertiaryColor

        waveStrokePaint.color = withAlpha(cSecondary, 145)

        primaryWavePaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(
                withAlpha(cSecondary, 58),
                withAlpha(cPrimary, 48),
                Color.parseColor("#04080D14")
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )

        secondaryWavePaint.shader = LinearGradient(
            w.toFloat(), 0f, 0f, h.toFloat(),
            intArrayOf(
                withAlpha(cTertiary, 50),
                withAlpha(cSecondary, 40),
                Color.parseColor("#00080D14")
            ),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )

        barPaint.shader = LinearGradient(
            0f, h * 0.14f, 0f, h * 0.88f,
            intArrayOf(cPrimary, cSecondary, cTertiary),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )

        progressFillPaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            intArrayOf(cSecondary, cPrimary),
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

        val speed = if (isPlaying) (2.6f + beatEnergy * 2.2f) else 0.75f
        phase += dt * speed
        vinylAngleDeg = (vinylAngleDeg + dt * (if (isPlaying) (115f + beatEnergy * 140f) else 24f)) % 360f
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

        // Reserve right badge slot for the animated custom vector sticker
        val stickerSlotWidth = 68f * density
        val spectrumAreaW = (w - stickerSlotWidth - 10f * density).coerceAtLeast(w * 0.65f)
        val centerX = spectrumAreaW * 0.52f
        val centerY = h * 0.49f

        // 2. Expanding beat shockwave ring on each spoken word
        if (shockwaveProgress < 1f) {
            val ringRadius = (12f + shockwaveProgress * 68f) * density
            val alpha = ((1f - shockwaveProgress) * 155f).toInt().coerceIn(0, 255)
            orbPaint.color = withAlpha(colorTheme.secondaryColor, alpha)
            orbPaint.strokeWidth = (2.3f * (1f - shockwaveProgress * 0.6f)) * density
            canvas.drawCircle(centerX, centerY, ringRadius, orbPaint)
        }

        // 3. 24 Symmetric Audio Spectrum Bars
        val totalBarsWidth = min(spectrumAreaW * 0.88f, 260f * density)
        val startX = (spectrumAreaW - totalBarsWidth) * 0.5f + 6f * density
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

        // 4. Custom Vector 60fps Animated Sticker Mascot on the right
        val stickerCx = w - 38f * density
        val stickerCy = h * 0.47f
        drawAnimatedVectorSticker(canvas, stickerCx, stickerCy)

        // 5. Physics-driven floating beat & sticker particles
        var pIdx = particles.size - 1
        while (pIdx >= 0) {
            val p = particles[pIdx]
            p.life += dt
            if (p.life >= p.maxLife) {
                particles.removeAt(pIdx)
            } else {
                p.x += p.vx * dt
                p.y += p.vy * dt
                val norm = (p.life / p.maxLife).coerceIn(0f, 1f)
                val alpha = ((1f - norm * norm) * 235f).toInt().coerceIn(0, 255)
                particleTextPaint.textSize = p.sizeSp * density * (0.85f + 0.30f * sin(norm * Math.PI).toFloat())
                particleTextPaint.color = Color.argb(alpha, 255, 255, 255)
                canvas.drawText(p.symbol, p.x, p.y, particleTextPaint)
            }
            pIdx--
        }

        // 6. Bottom continuous line-completion neon progress bar
        val barMarginH = 16f * density
        val progH = 3.2f * density
        val progTop = h - progH - 2f * density
        progressTrackRect.set(barMarginH, progTop, w - barMarginH, progTop + progH)
        canvas.drawRoundRect(progressTrackRect, progH * 0.5f, progH * 0.5f, progressTrackPaint)

        val fillW = (progressTrackRect.width() * smoothLineProgress).coerceAtLeast(0f)
        if (fillW > 1f) {
            progressFillRect.set(barMarginH, progTop, barMarginH + fillW, progTop + progH)
            canvas.drawRoundRect(progressFillRect, progH * 0.5f, progH * 0.5f, progressFillPaint)
        }

        if (isPlaying || beatEnergy > 0.01f || shockwaveProgress < 1f || particles.isNotEmpty() || abs(targetLineProgress - smoothLineProgress) > 0.004f) {
            postInvalidateOnAnimation()
        }
    }

    private fun drawAnimatedVectorSticker(canvas: Canvas, cx: Float, cy: Float) {
        val pulseScale = 1f + beatEnergy * 0.14f
        val baseR = 23f * density
        val cPrimary = colorTheme.primaryColor
        val cSecondary = colorTheme.secondaryColor

        // Soft glowing glass disc backdrop behind the sticker
        stickerFillPaint.color = withAlpha(colorTheme.chipFillColor, 200)
        canvas.drawCircle(cx, cy, baseR * pulseScale, stickerFillPaint)
        stickerStrokePaint.color = withAlpha(cPrimary, (130 + (beatEnergy * 90).toInt()).coerceIn(90, 255))
        stickerStrokePaint.strokeWidth = 1.6f * density
        canvas.drawCircle(cx, cy, baseR * pulseScale, stickerStrokePaint)

        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(pulseScale, pulseScale)

        when (stickerStyle) {
            VibeStickerStyle.VINYL_DISC -> {
                canvas.save()
                canvas.rotate(vinylAngleDeg)
                // Outer dark vinyl disc
                stickerFillPaint.color = Color.parseColor("#10151E")
                canvas.drawCircle(0f, 0f, 18f * density, stickerFillPaint)
                // Glowing grooves
                stickerStrokePaint.strokeWidth = 1.1f * density
                stickerStrokePaint.color = withAlpha(cSecondary, 110)
                canvas.drawCircle(0f, 0f, 14f * density, stickerStrokePaint)
                stickerStrokePaint.color = withAlpha(cPrimary, 140)
                canvas.drawCircle(0f, 0f, 10.5f * density, stickerStrokePaint)
                // Diagonal sheen arc
                badgeRect.set(-15f * density, -15f * density, 15f * density, 15f * density)
                stickerStrokePaint.color = Color.argb(95, 255, 255, 255)
                stickerStrokePaint.strokeWidth = 2.0f * density
                canvas.drawArc(badgeRect, -35f, 45f, false, stickerStrokePaint)
                canvas.drawArc(badgeRect, 145f, 45f, false, stickerStrokePaint)
                // Center neon label + spindle hole
                stickerFillPaint.color = cPrimary
                canvas.drawCircle(0f, 0f, 6.2f * density, stickerFillPaint)
                stickerFillPaint.color = Color.parseColor("#0A0E14")
                canvas.drawCircle(0f, 0f, 2.2f * density, stickerFillPaint)
                canvas.restore()
            }
            VibeStickerStyle.FIRE_DROP -> {
                val sway = sin(phase * 3.2f) * 2.2f * density
                // Outer flame
                stickerPath.reset()
                stickerPath.moveTo(0f + sway, -17f * density)
                stickerPath.cubicTo(14f * density, -6f * density, 15f * density, 12f * density, 0f, 16f * density)
                stickerPath.cubicTo(-15f * density, 12f * density, -14f * density, -6f * density, 0f + sway, -17f * density)
                stickerPath.close()
                stickerFillPaint.color = cPrimary
                canvas.drawPath(stickerPath, stickerFillPaint)
                // Inner core flame
                stickerSubPath.reset()
                stickerSubPath.moveTo(0f - sway * 0.5f, -7f * density)
                stickerSubPath.cubicTo(8f * density, 0f, 8f * density, 11f * density, 0f, 14f * density)
                stickerSubPath.cubicTo(-8f * density, 11f * density, -8f * density, 0f, 0f - sway * 0.5f, -7f * density)
                stickerSubPath.close()
                stickerFillPaint.color = Color.parseColor("#FFE600")
                canvas.drawPath(stickerSubPath, stickerFillPaint)
            }
            VibeStickerStyle.CYBER_CAT -> {
                val nodY = sin(phase * 2.8f) * 1.8f * density
                canvas.translate(0f, nodY)
                // Cat head + pointed ears
                stickerPath.reset()
                stickerPath.moveTo(-12f * density, 6f * density)
                stickerPath.lineTo(-13f * density, -13f * density)
                stickerPath.lineTo(-4f * density, -7f * density)
                stickerPath.lineTo(4f * density, -7f * density)
                stickerPath.lineTo(13f * density, -13f * density)
                stickerPath.lineTo(12f * density, 6f * density)
                stickerPath.quadTo(0f, 14f * density, -12f * density, 6f * density)
                stickerPath.close()
                stickerFillPaint.color = Color.parseColor("#182232")
                canvas.drawPath(stickerPath, stickerFillPaint)
                stickerStrokePaint.color = cSecondary
                stickerStrokePaint.strokeWidth = 1.6f * density
                canvas.drawPath(stickerPath, stickerStrokePaint)
                // Cyber neon visor glasses
                stickerFillPaint.color = cPrimary
                badgeRect.set(-10.5f * density, -2.5f * density, -1.5f * density, 3.2f * density)
                canvas.drawRoundRect(badgeRect, 2f * density, 2f * density, stickerFillPaint)
                badgeRect.set(1.5f * density, -2.5f * density, 10.5f * density, 3.2f * density)
                canvas.drawRoundRect(badgeRect, 2f * density, 2f * density, stickerFillPaint)
                stickerStrokePaint.color = Color.WHITE
                stickerStrokePaint.strokeWidth = 1.2f * density
                canvas.drawLine(-1.5f * density, -0.5f * density, 1.5f * density, -0.5f * density, stickerStrokePaint)
            }
            VibeStickerStyle.NEON_HEART -> {
                val thump = 1f + 0.08f * sin(phase * 4f)
                canvas.scale(thump, thump)
                stickerPath.reset()
                val r = 13f * density
                stickerPath.moveTo(0f, r * 0.95f)
                stickerPath.cubicTo(-r * 1.35f, r * 0.1f, -r * 0.95f, -r * 0.95f, 0f, -r * 0.35f)
                stickerPath.cubicTo(r * 0.95f, -r * 0.95f, r * 1.35f, r * 0.1f, 0f, r * 0.95f)
                stickerPath.close()
                stickerFillPaint.color = cPrimary
                canvas.drawPath(stickerPath, stickerFillPaint)
                stickerStrokePaint.color = Color.WHITE
                stickerStrokePaint.strokeWidth = 1.4f * density
                canvas.drawPath(stickerPath, stickerStrokePaint)
            }
            VibeStickerStyle.LIGHTNING_BASS -> {
                stickerPath.reset()
                stickerPath.moveTo(3f * density, -15f * density)
                stickerPath.lineTo(-9f * density, 1f * density)
                stickerPath.lineTo(-1f * density, 1f * density)
                stickerPath.lineTo(-4f * density, 15f * density)
                stickerPath.lineTo(9f * density, -2f * density)
                stickerPath.lineTo(1f * density, -2f * density)
                stickerPath.close()
                stickerFillPaint.color = Color.parseColor("#FFE600")
                canvas.drawPath(stickerPath, stickerFillPaint)
                stickerStrokePaint.color = cSecondary
                stickerStrokePaint.strokeWidth = 1.4f * density
                canvas.drawPath(stickerPath, stickerStrokePaint)
            }
            VibeStickerStyle.STAR_CROWN -> {
                stickerPath.reset()
                stickerPath.moveTo(-12f * density, 9f * density)
                stickerPath.lineTo(-14f * density, -6f * density)
                stickerPath.lineTo(-6f * density, 1f * density)
                stickerPath.lineTo(0f, -11f * density)
                stickerPath.lineTo(6f * density, 1f * density)
                stickerPath.lineTo(14f * density, -6f * density)
                stickerPath.lineTo(12f * density, 9f * density)
                stickerPath.close()
                stickerFillPaint.color = Color.parseColor("#FFE600")
                canvas.drawPath(stickerPath, stickerFillPaint)
                stickerStrokePaint.color = cPrimary
                stickerStrokePaint.strokeWidth = 1.5f * density
                canvas.drawPath(stickerPath, stickerStrokePaint)
                // Crown jewels
                stickerFillPaint.color = cSecondary
                canvas.drawCircle(0f, -13f * density, 2.2f * density, stickerFillPaint)
                canvas.drawCircle(-14f * density, -8f * density, 1.8f * density, stickerFillPaint)
                canvas.drawCircle(14f * density, -8f * density, 1.8f * density, stickerFillPaint)
            }
        }
        canvas.restore()
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
