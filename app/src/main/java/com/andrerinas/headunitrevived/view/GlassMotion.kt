package com.andrerinas.headunitrevived.view

import android.annotation.SuppressLint
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

object GlassMotion {

    @SuppressLint("ClickableViewAccessibility")
    fun View.applySpringPress() {
        val scaleXAnim = SpringAnimation(this, DynamicAnimation.SCALE_X, 1.0f).apply {
            spring.stiffness = 500f
            spring.dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
        }
        val scaleYAnim = SpringAnimation(this, DynamicAnimation.SCALE_Y, 1.0f).apply {
            spring.stiffness = 500f
            spring.dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
        }

        setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    scaleXAnim.animateToFinalPosition(0.96f)
                    scaleYAnim.animateToFinalPosition(0.96f)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    scaleXAnim.animateToFinalPosition(1.0f)
                    scaleYAnim.animateToFinalPosition(1.0f)
                }
            }
            false // Continue processing (allows click listener to work)
        }
    }

    fun View.springFadeIn(delayMs: Long = 0) {
        alpha = 0f
        translationY = 8.dpToPx(context)
        
        postDelayed({
            SpringAnimation(this, DynamicAnimation.ALPHA, 1.0f).apply {
                spring.stiffness = 300f
                spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
            }.start()
            SpringAnimation(this, DynamicAnimation.TRANSLATION_Y, 0f).apply {
                spring.stiffness = 300f
                spring.dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
            }.start()
        }, delayMs)
    }

    private fun Float.dpToPx(context: android.content.Context): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            this,
            context.resources.displayMetrics
        )
    }

    private fun Int.dpToPx(context: android.content.Context): Float = this.toFloat().dpToPx(context)
}
