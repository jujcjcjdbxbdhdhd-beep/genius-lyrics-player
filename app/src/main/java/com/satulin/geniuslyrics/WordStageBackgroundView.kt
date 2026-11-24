package com.satulin.geniuslyrics

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * 60fps GPU-friendly full-screen ambient background for the Word-by-Word ("По словам") page.
 * Renders 4 distinct atmospheric visual modes tinted dynamically by the active VibeColorTheme
 * without BlurMaskFilter or per-frame saveLayer allocations.
 */
class WordStageBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class StarMote(
        var nx: Float,
        var ny: Float,
        val speed: Float,
        val radiusDp: Float,
        val phaseOffset: Float,
        val colorSlot: Int
    )

    private val density = resources.displayMetrics.density

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val orbPaints = Array(3) { Paint(Paint.ANTI_ALIAS_FLAG) }
    private val orbShaders = arrayOfNulls<RadialGradient>(3)
    private val orbMatrix = Matrix()
    private val orbRadii = FloatArray(3)

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val motePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val auroraPaints = Array(3) { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL } }
    private val auroraPath = Path()
    private val spotlightLeftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val spotlightRightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val spotlightPath = Path()
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var haloShader: RadialGradient? = null

    private var colorTheme: VibeColorTheme = VibeColorTheme.CYBER_EMERALD
    private var bgStyle: VibeBackgroundStyle = VibeBackgroundStyle.NEBULA_RINGS
    private var isPlaying: Boolean = false
    private var isStageActive: Boolean = false
    private var attachedToWin: Boolean = false

    private var phase: Float = 0f
    private var beatEnergy: Float = 0f
    private var ringWaveA: Float = 1f
    private var ringWaveB: Float = 1f
    private var lastFrameMs: Long = 0L

    private val motes = Array(24) { idx ->
        StarMote(
            nx = Random.nextFloat(),
            ny = Random.nextFloat(),
            speed = 0.025f + Random.nextFloat() * 0.045f,
            radiusDp = 1.4f + Random.nextFloat() * 2.6f,
            phaseOffset = idx * 0.71f,
            colorSlot = idx % 3
        )
    }

    init {
        rebuildShaders(1, 1)
    }

    fun setColorTheme(theme: VibeColorTheme) {
        if (colorTheme != theme) {
            colorTheme = theme
            rebuildShaders(width, height)
            if (shouldAnimate()) invalidate()
        }
    }

    fun setBackgroundStyle(style: VibeBackgroundStyle) {
        if (bgStyle != style) {
            bgStyle = style
            rebuildShaders(width, height)
            if (shouldAnimate()) {
                lastFrameMs = 0L
                postInvalidateOnAnimation()
            }
        }
    }

    fun setPlayingState(playing: Boolean) {
        if (isPlaying != playing) {
            isPlaying = playing
            if (shouldAnimate()) {
                postInvalidateOnAnimation()
            }
        }
    }

    fun setActive(active: Boolean) {
        if (isStageActive != active) {
            isStageActive = active
            lastFrameMs = 0L
            if (shouldAnimate()) {
                postInvalidateOnAnimation()
            }
        }
    }

    fun triggerWordBeat(intensity: Float = 1.0f) {
        beatEnergy = max(beatEnergy, (0.55f + intensity * 0.45f).coerceIn(0.35f, 1.3f))
        if (ringWaveA >= 0.35f) {
            ringWaveB = ringWaveA
            ringWaveA = 0f
        } else {
            ringWaveA = 0f
        }
        if (shouldAnimate()) {
            invalidate()
        }
    }

    private fun shouldAnimate(): Boolean =
        isStageActive && attachedToWin && visibility == VISIBLE

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attachedToWin = true
        lastFrameMs = 0L
        if (shouldAnimate()) postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        attachedToWin = false
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (shouldAnimate()) {
            lastFrameMs = 0L
            postInvalidateOnAnimation()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val dim = max(w, h).toFloat().coerceAtLeast(1f)
        orbRadii[0] = dim * 0.56f
        orbRadii[1] = dim * 0.48f
        orbRadii[2] = dim * 0.52f
        rebuildShaders(w, h)
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun blendDark(color: Int, tintFraction: Float): Int {
        val baseR = 5f
        val baseG = 7f
        val baseB = 11f
        val f = tintFraction.coerceIn(0f, 1f)
        val r = (baseR + (Color.red(color) - baseR) * f).toInt().coerceIn(0, 255)
        val g = (baseG + (Color.green(color) - baseG) * f).toInt().coerceIn(0, 255)
        val b = (baseB + (Color.blue(color) - baseB) * f).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    private fun rebuildShaders(w: Int, h: Int) {
        val safeW = w.coerceAtLeast(1).toFloat()
        val safeH = h.coerceAtLeast(1).toFloat()
        val c0 = colorTheme.primaryColor
        val c1 = colorTheme.secondaryColor
        val c2 = colorTheme.tertiaryColor

        val topColor = when (bgStyle) {
            VibeBackgroundStyle.NEBULA_RINGS -> blendDark(c2, 0.14f)
            VibeBackgroundStyle.AURORA_CURTAINS -> blendDark(c1, 0.16f)
            VibeBackgroundStyle.SYNTH_GRID -> blendDark(c2, 0.18f)
            VibeBackgroundStyle.CONCERT_SPOTLIGHT -> Color.parseColor("#04060A")
        }
        val midColor = when (bgStyle) {
            VibeBackgroundStyle.NEBULA_RINGS -> blendDark(c1, 0.08f)
            VibeBackgroundStyle.AURORA_CURTAINS -> blendDark(c0, 0.09f)
            VibeBackgroundStyle.SYNTH_GRID -> blendDark(c1, 0.10f)
            VibeBackgroundStyle.CONCERT_SPOTLIGHT -> blendDark(c0, 0.07f)
        }
        val botColor = blendDark(c0, 0.12f)

        bgPaint.shader = LinearGradient(
            0f, 0f, safeW * 0.25f, safeH,
            intArrayOf(topColor, midColor, botColor),
            floatArrayOf(0f, 0.54f, 1f),
            Shader.TileMode.CLAMP
        )

        val stops = floatArrayOf(0f, 0.45f, 1f)
        val orbColors = arrayOf(
            intArrayOf(withAlpha(c0, 175), withAlpha(c0, 68), Color.TRANSPARENT),
            intArrayOf(withAlpha(c1, 165), withAlpha(c1, 62), Color.TRANSPARENT),
            intArrayOf(withAlpha(c2, 160), withAlpha(c2, 58), Color.TRANSPARENT)
        )
        for (i in 0 until 3) {
            val shader = RadialGradient(0f, 0f, 1f, orbColors[i], stops, Shader.TileMode.CLAMP)
            orbShaders[i] = shader
            orbPaints[i].shader = shader
        }

        haloShader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(withAlpha(c1, 145), withAlpha(c0, 65), Color.TRANSPARENT),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP
        )
        haloPaint.shader = haloShader

        auroraPaints[0].shader = LinearGradient(
            0f, 0f, safeW, safeH * 0.7f,
            intArrayOf(Color.TRANSPARENT, withAlpha(c0, 70), withAlpha(c1, 52), Color.TRANSPARENT),
            floatArrayOf(0f, 0.35f, 0.68f, 1f),
            Shader.TileMode.CLAMP
        )
        auroraPaints[1].shader = LinearGradient(
            safeW, 0f, 0f, safeH * 0.8f,
            intArrayOf(Color.TRANSPARENT, withAlpha(c1, 64), withAlpha(c2, 55), Color.TRANSPARENT),
            floatArrayOf(0f, 0.3f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )
        auroraPaints[2].shader = LinearGradient(
            0f, safeH * 0.2f, safeW, safeH,
            intArrayOf(Color.TRANSPARENT, withAlpha(c2, 56), withAlpha(c0, 48), Color.TRANSPARENT),
            floatArrayOf(0f, 0.38f, 0.72f, 1f),
            Shader.TileMode.CLAMP
        )

        spotlightLeftPaint.shader = LinearGradient(
            0f, 0f, safeW * 0.65f, safeH * 0.82f,
            intArrayOf(withAlpha(c1, 115), withAlpha(c0, 42), Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        spotlightRightPaint.shader = LinearGradient(
            safeW, 0f, safeW * 0.35f, safeH * 0.82f,
            intArrayOf(withAlpha(c0, 110), withAlpha(c2, 42), Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f),
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

        val speed = if (isPlaying) (0.72f + beatEnergy * 0.55f) else 0.22f
        phase = (phase + dt * speed) % 1000f
        beatEnergy = max(0f, beatEnergy - dt * 2.1f)
        if (ringWaveA < 1f) ringWaveA = min(1f, ringWaveA + dt * 1.15f)
        if (ringWaveB < 1f) ringWaveB = min(1f, ringWaveB + dt * 1.05f)

        canvas.drawRect(0f, 0f, w, h, bgPaint)

        when (bgStyle) {
            VibeBackgroundStyle.NEBULA_RINGS -> drawNebulaRings(canvas, w, h, dt)
            VibeBackgroundStyle.AURORA_CURTAINS -> drawAuroraCurtains(canvas, w, h, dt)
            VibeBackgroundStyle.SYNTH_GRID -> drawSynthGrid(canvas, w, h)
            VibeBackgroundStyle.CONCERT_SPOTLIGHT -> drawConcertSpotlight(canvas, w, h, dt)
        }

        if (shouldAnimate()) {
            postInvalidateOnAnimation()
        }
    }

    private fun drawOrb(canvas: Canvas, index: Int, cx: Float, cy: Float, radius: Float, alpha: Int) {
        val shader = orbShaders[index] ?: return
        orbMatrix.setScale(radius, radius)
        orbMatrix.postTranslate(cx, cy)
        shader.setLocalMatrix(orbMatrix)
        val paint = orbPaints[index]
        paint.alpha = alpha.coerceIn(0, 255)
        canvas.drawCircle(cx, cy, radius, paint)
    }

    private fun drawBeatHalo(canvas: Canvas, cx: Float, cy: Float, radius: Float, alpha: Int) {
        val shader = haloShader ?: return
        orbMatrix.setScale(radius, radius)
        orbMatrix.postTranslate(cx, cy)
        shader.setLocalMatrix(orbMatrix)
        haloPaint.alpha = alpha.coerceIn(0, 255)
        canvas.drawCircle(cx, cy, radius, haloPaint)
    }

    private fun drawBeatRings(canvas: Canvas, cx: Float, cy: Float, maxR: Float) {
        if (ringWaveA < 1f) {
            val rA = maxR * (0.16f + ringWaveA * 0.84f)
            val aA = ((1f - ringWaveA) * (120f + beatEnergy * 55f)).toInt().coerceIn(0, 200)
            ringPaint.color = withAlpha(colorTheme.primaryColor, aA)
            ringPaint.strokeWidth = (2.4f - ringWaveA * 1.2f) * density
            canvas.drawCircle(cx, cy, rA, ringPaint)
        }
        if (ringWaveB < 1f) {
            val rB = maxR * (0.12f + ringWaveB * 0.88f)
            val aB = ((1f - ringWaveB) * 95f).toInt().coerceIn(0, 180)
            ringPaint.color = withAlpha(colorTheme.secondaryColor, aB)
            ringPaint.strokeWidth = (1.8f - ringWaveB * 0.9f) * density
            canvas.drawCircle(cx, cy, rB, ringPaint)
        }
    }

    private fun drawMotes(canvas: Canvas, w: Float, h: Float, dt: Float, speedScale: Float) {
        val twoPi = (PI * 2.0).toFloat()
        for (m in motes) {
            m.ny -= m.speed * speedScale * dt * (if (isPlaying) 1.25f else 0.45f)
            if (m.ny < -0.04f) {
                m.ny = 1.04f
                m.nx = Random.nextFloat()
            }
            val driftX = sin(phase * 1.4f + m.phaseOffset) * 10f * density
            val x = m.nx * w + driftX
            val y = m.ny * h
            val shimmer = (0.35f + 0.65f * ((sin(phase * 2.1f + m.phaseOffset) + 1f) * 0.5f))
            val alpha = ((42 + beatEnergy * 48f) * shimmer).toInt().coerceIn(12, 130)
            val baseC = when (m.colorSlot) {
                0 -> colorTheme.primaryColor
                1 -> colorTheme.secondaryColor
                else -> colorTheme.tertiaryColor
            }
            motePaint.color = withAlpha(baseC, alpha)
            canvas.drawCircle(x, y, m.radiusDp * density * (1f + beatEnergy * 0.16f), motePaint)
        }
    }

    private fun drawNebulaRings(canvas: Canvas, w: Float, h: Float, dt: Float) {
        val p = phase
        val cx0 = w * (0.22f + 0.14f * cos(p * 0.7f))
        val cy0 = h * (0.26f + 0.10f * sin(p * 0.8f))
        drawOrb(canvas, 0, cx0, cy0, orbRadii[0] * (1f + 0.16f * beatEnergy), (82 + beatEnergy * 46).toInt())

        val cx1 = w * (0.78f + 0.12f * sin(p * 0.6f + 1.4f))
        val cy1 = h * (0.55f + 0.11f * cos(p * 0.7f + 2.0f))
        drawOrb(canvas, 1, cx1, cy1, orbRadii[1] * (1f + 0.18f * beatEnergy), (78 + beatEnergy * 42).toInt())

        val cx2 = w * (0.45f + 0.16f * cos(p * 0.5f + 3.1f))
        val cy2 = h * (0.84f + 0.08f * sin(p * 0.9f))
        drawOrb(canvas, 2, cx2, cy2, orbRadii[2] * (1f + 0.10f * beatEnergy), 72)

        val stageCx = w * 0.5f
        val stageCy = h * 0.54f
        drawBeatHalo(canvas, stageCx, stageCy, min(w, h) * (0.36f + beatEnergy * 0.12f), (75 + beatEnergy * 60).toInt())
        drawBeatRings(canvas, stageCx, stageCy, min(w, h) * 0.58f)
        drawMotes(canvas, w, h, dt, 1.0f)
    }

    private fun drawAuroraCurtains(canvas: Canvas, w: Float, h: Float, dt: Float) {
        val steps = 16
        val stepX = w / steps
        for (band in 0 until 3) {
            val baseY = h * (0.30f + band * 0.19f)
            val thickness = h * (0.20f + band * 0.04f)
            val amp = (28f + band * 12f + beatEnergy * 18f) * density
            val freq = 1.3f + band * 0.4f
            val ph = phase * (0.85f + band * 0.25f) * (if (band % 2 == 0) 1f else -1f)

            auroraPath.reset()
            for (i in 0..steps) {
                val x = i * stepX
                val nx = i.toFloat() / steps
                val waveY = baseY + sin(nx * freq * PI * 2.0 + ph).toFloat() * amp
                if (i == 0) auroraPath.moveTo(x, waveY) else auroraPath.lineTo(x, waveY)
            }
            for (i in steps downTo 0) {
                val x = i * stepX
                val nx = i.toFloat() / steps
                val waveY = baseY + thickness + cos(nx * (freq + 0.3f) * PI * 2.0 + ph * 0.9f).toFloat() * amp * 0.8f
                auroraPath.lineTo(x, waveY)
            }
            auroraPath.close()
            auroraPaints[band].alpha = (165 + beatEnergy * 55).toInt().coerceIn(0, 240)
            canvas.drawPath(auroraPath, auroraPaints[band])
        }

        drawBeatHalo(canvas, w * 0.5f, h * 0.54f, min(w, h) * (0.34f + beatEnergy * 0.14f), (68 + beatEnergy * 55).toInt())
        drawMotes(canvas, w, h, dt, 0.75f)
    }

    private fun drawSynthGrid(canvas: Canvas, w: Float, h: Float) {
        val horizonY = h * 0.50f
        val stageCx = w * 0.5f

        // Glowing horizon sun orb behind the hero word
        drawBeatHalo(canvas, stageCx, horizonY, min(w, h) * (0.42f + beatEnergy * 0.14f), (110 + beatEnergy * 70).toInt())
        drawBeatRings(canvas, stageCx, h * 0.54f, min(w, h) * 0.56f)

        // Top subtle atmospheric nebula
        drawOrb(canvas, 2, w * 0.5f, h * 0.24f, orbRadii[1], 62)

        // 3D perspective floor grid from horizonY to h
        canvas.save()
        canvas.clipRect(0f, horizonY, w, h)

        val gridHeight = (h - horizonY).coerceAtLeast(1f)
        val scrollT = (phase * 0.55f) % 1f

        // Perspective radiating floor rays
        linePaint.strokeWidth = 1.2f * density
        val numRays = 12
        for (i in -numRays / 2..numRays / 2) {
            val spreadX = stageCx + i * (w * 0.18f)
            val topRayX = stageCx + i * (w * 0.025f)
            val alpha = (44 + beatEnergy * 34).toInt().coerceIn(20, 110)
            linePaint.color = withAlpha(colorTheme.secondaryColor, alpha)
            canvas.drawLine(topRayX, horizonY, spreadX, h, linePaint)
        }

        // Exponential depth horizontal scanlines advancing toward viewer
        val numRows = 11
        for (r in 0 until numRows) {
            val depth = ((r.toFloat() + scrollT) / numRows.toFloat()).coerceIn(0f, 1f)
            val eased = depth.pow(2.2f)
            val y = horizonY + eased * gridHeight
            val alpha = ((24f + eased * 82f) + beatEnergy * 35f).toInt().coerceIn(14, 145)
            linePaint.color = withAlpha(colorTheme.primaryColor, alpha)
            linePaint.strokeWidth = (0.9f + eased * 1.1f) * density
            canvas.drawLine(0f, y, w, y, linePaint)
        }

        // Horizon neon baseline
        linePaint.color = withAlpha(colorTheme.primaryColor, (125 + beatEnergy * 85).toInt().coerceIn(80, 230))
        linePaint.strokeWidth = 2f * density
        canvas.drawLine(0f, horizonY, w, horizonY, linePaint)

        canvas.restore()
    }

    private fun drawConcertSpotlight(canvas: Canvas, w: Float, h: Float, dt: Float) {
        val swayA = sin(phase * 0.75f) * w * 0.16f
        val swayB = cos(phase * 0.65f + 1.1f) * w * 0.16f
        val targetY = h * 0.88f

        // Left volumetric beam
        val leftBaseCx = w * 0.55f + swayA
        spotlightPath.reset()
        spotlightPath.moveTo(w * 0.10f, -h * 0.05f)
        spotlightPath.lineTo(w * 0.22f, -h * 0.05f)
        spotlightPath.lineTo(leftBaseCx + w * 0.24f, targetY)
        spotlightPath.lineTo(leftBaseCx - w * 0.24f, targetY)
        spotlightPath.close()
        spotlightLeftPaint.alpha = (150 + beatEnergy * 75).toInt().coerceIn(90, 235)
        canvas.drawPath(spotlightPath, spotlightLeftPaint)

        // Right volumetric beam
        val rightBaseCx = w * 0.45f + swayB
        spotlightPath.reset()
        spotlightPath.moveTo(w * 0.78f, -h * 0.05f)
        spotlightPath.lineTo(w * 0.90f, -h * 0.05f)
        spotlightPath.lineTo(rightBaseCx + w * 0.24f, targetY)
        spotlightPath.lineTo(rightBaseCx - w * 0.24f, targetY)
        spotlightPath.close()
        spotlightRightPaint.alpha = (145 + beatEnergy * 75).toInt().coerceIn(90, 235)
        canvas.drawPath(spotlightPath, spotlightRightPaint)

        // Center stage glow halo behind hero word
        drawBeatHalo(canvas, w * 0.5f, h * 0.54f, min(w, h) * (0.40f + beatEnergy * 0.14f), (95 + beatEnergy * 65).toInt())
        drawBeatRings(canvas, w * 0.5f, h * 0.54f, min(w, h) * 0.54f)
        drawMotes(canvas, w, h, dt, 0.8f)
    }
}
