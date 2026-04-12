package com.andrerinas.headunitrevived.view

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * Lightweight performance-focused container. 
 * Stripped of all design and blur overhead for maximum responsiveness.
 */
open class GlassView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    enum class GlassState { IDLE, READY, ACTIVE, WARNING, ERROR }

    fun setGlassState(state: GlassState) {
        // No-op for performance
    }

    fun setShowProgress(show: Boolean) {
        // No-op for performance
    }

    fun setScanning(scanning: Boolean) {
        // No-op for performance
    }
}
