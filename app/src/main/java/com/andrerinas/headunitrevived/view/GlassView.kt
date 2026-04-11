package com.andrerinas.headunitrevived.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.andrerinas.headunitrevived.R

class GlassView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    enum class GlassState { IDLE, READY, ACTIVE, WARNING, ERROR }

    private var currentState = GlassState.IDLE
    private var cornerRadius: Float = 0f
    private var tintColorOverride: Int? = null
    
    private val backgroundDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
    }

    init {
        val typedArray = context.obtainStyledAttributes(attrs, R.styleable.GlassView, defStyleAttr, 0)
        val stateInt = typedArray.getInt(R.styleable.GlassView_glassState, 0)
        currentState = GlassState.values()[stateInt]
        
        cornerRadius = typedArray.getDimension(
            R.styleable.GlassView_glassCornerRadius,
            context.resources.getDimension(R.dimen.glass_radius_panel)
        )
        
        if (typedArray.hasValue(R.styleable.GlassView_glassTint)) {
            tintColorOverride = typedArray.getColor(R.styleable.GlassView_glassTint, 0)
        }
        typedArray.recycle()

        // Configure background
        backgroundDrawable.cornerRadius = cornerRadius
        updateVisualsForState(currentState)
        background = backgroundDrawable

        // Apply OutlineProvider for rounded corners + clipToOutline
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
            }
        }
        clipToOutline = true
    }

    private fun updateVisualsForState(state: GlassState) {
        val color = tintColorOverride ?: colorForState(state)
        backgroundDrawable.setColor(color)
        
        val strokeColor = when (state) {
            GlassState.READY, GlassState.ACTIVE -> ContextCompat.getColor(context, R.color.glass_border_active)
            else -> ContextCompat.getColor(context, R.color.glass_border)
        }
        val strokeWidth = if (state == GlassState.READY || state == GlassState.ACTIVE) 2 else 1
        backgroundDrawable.setStroke((strokeWidth * resources.displayMetrics.density).toInt(), strokeColor)
    }

    fun setGlassState(state: GlassState) {
        if (state == currentState) return
        
        val oldColor = tintColorOverride ?: colorForState(currentState)
        val newColor = tintColorOverride ?: colorForState(state)

        ValueAnimator.ofArgb(oldColor, newColor).apply {
            duration = 300
            addUpdateListener { animator ->
                val color = animator.animatedValue as Int
                backgroundDrawable.setColor(color)
                
                // Also update stroke color if transitioning to/from active states
                val strokeColor = when (state) {
                    GlassState.READY, GlassState.ACTIVE -> ContextCompat.getColor(context, R.color.glass_border_active)
                    else -> ContextCompat.getColor(context, R.color.glass_border)
                }
                val strokeWidth = if (state == GlassState.READY || state == GlassState.ACTIVE) 2 else 1
                backgroundDrawable.setStroke((strokeWidth * resources.displayMetrics.density).toInt(), strokeColor)
            }
            start()
        }
        currentState = state
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Apply RenderEffect.createBlurEffect(25f, 25f, CLAMP)
            // Note: This blurs the content of the view itself.
            setRenderEffect(RenderEffect.createBlurEffect(25f, 25f, Shader.TileMode.CLAMP))
        }
    }

    private fun colorForState(state: GlassState): Int = when (state) {
        GlassState.IDLE -> R.color.glass_idle
        GlassState.READY -> R.color.glass_ready
        GlassState.ACTIVE -> R.color.glass_active
        GlassState.WARNING -> R.color.glass_warning
        GlassState.ERROR -> R.color.glass_error
    }.let { ContextCompat.getColor(context, it) }
}
