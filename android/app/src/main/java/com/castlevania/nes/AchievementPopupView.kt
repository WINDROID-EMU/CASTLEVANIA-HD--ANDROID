package com.castlevania.nes

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

class AchievementPopupView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val pillLayout: LinearLayout
    private val badgeLayout: FrameLayout
    private val viewHeader: TextView
    private val viewDetails: LinearLayout
    private val txtTitle: TextView
    private val txtPoints: TextView

    private var transitionRunnable: Runnable? = null
    private var hideRunnable: Runnable? = null
    private var mediaPlayer: MediaPlayer? = null

    init {
        val dp = resources.displayMetrics.density
        visibility = View.GONE

        // Main Xbox Capsule Pill (Stadium shape)
        val pillHeight = (52 * dp).toInt()
        val pillRadius = pillHeight / 2f

        pillLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, pillHeight)

            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0A5C0A")) // Signature Xbox Dark Green
                cornerRadius = pillRadius
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = 10 * dp
            }
        }
        addView(pillLayout)

        // 1. Left Circular Badge
        badgeLayout = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(pillHeight, pillHeight)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#128812")) // Signature Xbox Vibrant Green
            }

            val iconTrophy = ImageView(context).apply {
                setImageResource(R.drawable.ic_xbox_trophy)
                layoutParams = LayoutParams((28 * dp).toInt(), (28 * dp).toInt(), Gravity.CENTER)
            }
            addView(iconTrophy)
        }
        pillLayout.addView(badgeLayout)

        // 2. Text Switcher Container
        val textContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                pillHeight
            )
            setPadding((16 * dp).toInt(), 0, (26 * dp).toInt(), 0)
        }

        // State 1: "Conquista desbloqueada"
        viewHeader = TextView(context).apply {
            text = "Conquista desbloqueada"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
        }
        textContainer.addView(viewHeader)

        // State 2: Achievement Title + Gamerscore / Points
        viewDetails = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
            alpha = 0f
            translationY = 22 * dp
        }

        txtTitle = TextView(context).apply {
            text = ""
            setTextColor(Color.WHITE)
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        viewDetails.addView(txtTitle)

        txtPoints = TextView(context).apply {
            text = ""
            setTextColor(Color.parseColor("#B0FFB0")) // Neon Xbox light green
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        }
        viewDetails.addView(txtPoints)

        textContainer.addView(viewDetails)
        pillLayout.addView(textContainer)
    }

    fun show(title: String, description: String, points: Int) {
        val dp = resources.displayMetrics.density

        // Cancel pending timers/animations
        transitionRunnable?.let { removeCallbacks(it) }
        hideRunnable?.let { removeCallbacks(it) }
        pillLayout.animate().cancel()
        badgeLayout.animate().cancel()
        viewHeader.animate().cancel()
        viewDetails.animate().cancel()

        txtTitle.text = title
        txtPoints.text = if (description.isNotBlank()) {
            "+$points PTS • $description"
        } else {
            "+$points PTS • RetroAchievements"
        }

        // Initial Xbox animation state
        viewHeader.alpha = 1f
        viewHeader.translationY = 0f

        viewDetails.alpha = 0f
        viewDetails.translationY = 22 * dp

        pillLayout.alpha = 0f
        pillLayout.translationY = -60 * dp
        pillLayout.scaleX = 0.9f
        pillLayout.scaleY = 0.9f

        badgeLayout.scaleX = 0.3f
        badgeLayout.scaleY = 0.3f

        visibility = View.VISIBLE

        // 1. Entrance: Pop down and expand with spring overshoot
        pillLayout.animate()
            .translationY(0f)
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(420)
            .setInterpolator(DecelerateInterpolator())
            .start()

        badgeLayout.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(450)
            .setInterpolator(OvershootInterpolator(1.8f))
            .start()

        // Haptic feedback and Sound FX
        triggerHaptic()
        playAchievementSound()

        // 2. The Signature Xbox One Transition: Flip/Slide from "Conquista desbloqueada" to Title & Points
        transitionRunnable = Runnable {
            viewHeader.animate()
                .translationY(-22 * dp)
                .alpha(0f)
                .setDuration(280)
                .setInterpolator(AccelerateInterpolator())
                .start()

            viewDetails.animate()
                .translationY(0f)
                .alpha(1f)
                .setStartDelay(100)
                .setDuration(320)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
        postDelayed(transitionRunnable, 1700)

        // 3. Outro: Slide up and fade away after viewing
        hideRunnable = Runnable {
            pillLayout.animate()
                .translationY(-60 * dp)
                .alpha(0f)
                .scaleX(0.9f)
                .scaleY(0.9f)
                .setDuration(350)
                .setInterpolator(AccelerateInterpolator())
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        visibility = View.GONE
                    }
                })
                .start()
        }
        postDelayed(hideRunnable, 5200)
    }

    private fun playAchievementSound() {
        try {
            val prefs = context.getSharedPreferences("castlevania_settings", Context.MODE_PRIVATE)
            val volumePercent = prefs.getInt("opt_achievement_sound_volume", 100)
            if (volumePercent <= 0) return

            val volume = volumePercent / 100f

            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer.create(context, R.raw.fx)?.apply {
                setVolume(volume, volume)
                isLooping = false
                setOnCompletionListener { mp ->
                    try {
                        mp.release()
                    } catch (_: Exception) {}
                    if (mediaPlayer === mp) {
                        mediaPlayer = null
                    }
                }
                start()
            }
        } catch (_: Exception) {}
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        transitionRunnable?.let { removeCallbacks(it) }
        hideRunnable?.let { removeCallbacks(it) }
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (_: Exception) {}
    }

    private fun triggerHaptic() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(120L, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(120L)
            }
        } catch (_: Exception) {}
    }
}
