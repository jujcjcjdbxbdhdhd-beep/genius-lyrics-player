package com.satulin.geniuslyrics

import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.ProgressBar
import kotlin.math.abs

enum class IslandAnimStyle(val id: String, val label: String, val shortLabel: String) {
    FLY_IN("fly_in", "Вылет", "🚀 Вылет"),
    FADE("fade", "Затухание", "✨ Затухание"),
    SLIDE_KINETIC("slide", "Сдвиг", "⚡ Сдвиг"),
    ELASTIC_POP("pop", "Импульс", "💥 Импульс"),
    TYPEWRITER("typewriter", "Печать", "⌨ Печать");

    companion object {
        fun fromId(id: String?): IslandAnimStyle =
            values().firstOrNull { it.id == id } ?: FLY_IN

        fun nextStyle(current: IslandAnimStyle): IslandAnimStyle =
            values()[(current.ordinal + 1) % values().size]
    }
}

object AppMotionPrefs {
    const val PREFS_NAME = "genius_island_prefs"
    const val KEY_ISLAND_STYLE = "island_anim_style"
    const val KEY_KARAOKE_ENABLED = "karaoke_enabled"
    const val ACTION_PREFS_CHANGED = "com.satulin.geniuslyrics.ACTION_PREFS_CHANGED"

    fun getIslandStyle(context: Context): IslandAnimStyle {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ISLAND_STYLE, IslandAnimStyle.FLY_IN.id)
        return IslandAnimStyle.fromId(raw)
    }

    fun setIslandStyle(context: Context, style: IslandAnimStyle) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ISLAND_STYLE, style.id)
            .apply()
        notifyPrefsChanged(context)
    }

    fun isKaraokeEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_KARAOKE_ENABLED, true)
    }

    fun setKaraokeEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_KARAOKE_ENABLED, enabled)
            .apply()
        notifyPrefsChanged(context)
    }

    private fun notifyPrefsChanged(context: Context) {
        try {
            val intent = Intent(ACTION_PREFS_CHANGED).apply {
                setPackage(context.applicationContext.packageName)
            }
            context.sendBroadcast(intent)
        } catch (_: Exception) {}
    }
}

object MotionUtils {
    val EASE_OUT_EXPO = PathInterpolator(0.16f, 1f, 0.3f, 1f)
    val EASE_OUT_CUBIC = PathInterpolator(0.22f, 1f, 0.36f, 1f)
    val SPRING_OVERSHOOT = OvershootInterpolator(1.35f)
    val SPRING_JUICY = OvershootInterpolator(1.75f)

    @SuppressLint("ClickableViewAccessibility")
    fun attachTactilePress(vararg views: View?) {
        for (v in views) {
            v?.setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        view.animate().cancel()
                        view.animate()
                            .scaleX(0.95f)
                            .scaleY(0.95f)
                            .setDuration(85L)
                            .setInterpolator(EASE_OUT_CUBIC)
                            .start()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        view.animate().cancel()
                        view.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(180L)
                            .setInterpolator(SPRING_OVERSHOOT)
                            .start()
                    }
                }
                false
            }
        }
    }

    fun animateProgressSmooth(pb: ProgressBar?, targetProgress: Int) {
        if (pb == null) return
        val clamped = targetProgress.coerceIn(0, pb.max)
        if (abs(pb.progress - clamped) > 15) {
            (pb.tag as? ObjectAnimator)?.cancel()
            val anim = ObjectAnimator.ofInt(pb, "progress", pb.progress, clamped).apply {
                duration = 180L
                interpolator = EASE_OUT_CUBIC
            }
            pb.tag = anim
            anim.start()
        } else {
            pb.progress = clamped
        }
    }

    fun createBorderGlowAnimator(
        view: View?,
        fillColor: Int,
        glowColor: Int,
        restStrokeColor: Int,
        cornerRadiusPx: Float,
        restStrokeWidthPx: Int,
        glowStrokeWidthPx: Int
    ): ValueAnimator? {
        if (view == null) return null
        val evaluator = ArgbEvaluator()
        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx
            setColor(fillColor)
            setStroke(glowStrokeWidthPx, glowColor)
        }
        view.background = drawable

        return ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 440L
            interpolator = EASE_OUT_CUBIC
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                val color = evaluator.evaluate(1f - f, glowColor, restStrokeColor) as Int
                val strokeW = (restStrokeWidthPx + (glowStrokeWidthPx - restStrokeWidthPx) * f)
                    .toInt()
                    .coerceAtLeast(restStrokeWidthPx)
                drawable.setStroke(strokeW, color)
                view.invalidate()
            }
        }
    }
}
