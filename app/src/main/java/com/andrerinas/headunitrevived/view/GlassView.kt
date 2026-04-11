package com.andrerinas.headunitrevived.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Outline
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import com.andrerinas.headunitrevived.R

class GlassView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    enum class GlassState { IDLE, READY, ACTIVE, WARNING, ERROR }

    companion object {
        private var globalTheme: GlassTheme? = null
        
        fun setGlobalTheme(theme: GlassTheme) {
            globalTheme = theme
        }
        
        fun getTheme(context: Context): GlassTheme {
            return globalTheme ?: CalmGlassTheme(context.applicationContext)
        }
    }

    private var currentState = GlassState.IDLE
    private var cornerRadius: Float = 0f
    private var blurRadius: Float = 25f
    private var tintColorOverride: Int? = null
    
    private val backgroundDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
    }

    init {
        val theme = getTheme(context)
        val typedArray = context.obtainStyledAttributes(attrs, R.styleable.GlassView, defStyleAttr, 0)
        
        val stateInt = typedArray.getInt(R.styleable.GlassView_glassState, 0)
        currentState = GlassState.values()[stateInt]
        
        cornerRadius = typedArray.getDimension(
            R.styleable.GlassView_glassCornerRadius,
            theme.cornerRadius
        )
        
        blurRadius = typedArray.getFloat(
            R.styleable.GlassView_glassBlur,
            theme.blurRadius
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
        val theme = getTheme(context)
        val color = tintColorOverride ?: theme.surfaceColor(state)
        backgroundDrawable.setColor(color)
        
        val strokeColor = theme.borderColor(state)
        val strokeWidth = if (state == GlassState.READY || state == GlassState.ACTIVE) 2 else 1
        backgroundDrawable.setStroke((strokeWidth * resources.displayMetrics.density).toInt(), strokeColor)
    }

    fun setGlassState(state: GlassState) {
        if (state == currentState) return
        
        val theme = getTheme(context)
        val oldColor = tintColorOverride ?: theme.surfaceColor(currentState)
        val newColor = tintColorOverride ?: theme.surfaceColor(state)

        ValueAnimator.ofArgb(oldColor, newColor).apply {
            duration = 300
            addUpdateListener { animator ->
                val color = animator.animatedValue as Int
                backgroundDrawable.setColor(color)
                
                val currentTheme = getTheme(context)
                val strokeColor = currentTheme.borderColor(state)
                val strokeWidth = if (state == GlassState.READY || state == GlassState.ACTIVE) 2 else 1
                backgroundDrawable.setStroke((strokeWidth * resources.displayMetrics.density).toInt(), strokeColor)
            }
            start()
        }
        currentState = state
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        applyBlur()
    }

    private fun applyBlur() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blurRadius > 0) {
            setRenderEffect(RenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.CLAMP))
        }
    }
}
