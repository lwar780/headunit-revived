package com.andrerinas.headunitrevived.view

import android.content.Context
import androidx.core.content.ContextCompat
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.view.GlassView.GlassState

class CalmGlassTheme(private val context: Context) : GlassTheme {
    
    override fun surfaceColor(state: GlassState): Int = when (state) {
        GlassState.IDLE -> R.color.glass_idle
        GlassState.READY -> R.color.glass_ready
        GlassState.ACTIVE -> R.color.glass_active
        GlassState.WARNING -> R.color.glass_warning
        GlassState.ERROR -> R.color.glass_error
    }.let { ContextCompat.getColor(context, it) }

    override fun borderColor(state: GlassState): Int = when (state) {
        GlassState.READY, GlassState.ACTIVE -> R.color.glass_border_active
        else -> R.color.glass_border
    }.let { ContextCompat.getColor(context, it) }

    override fun badgeColor(state: GlassState): Int = when (state) {
        GlassState.READY -> R.color.status_connected
        GlassState.ACTIVE -> R.color.status_searching
        else -> R.color.status_disconnected
    }.let { ContextCompat.getColor(context, it) }

    override val blurRadius: Float = 25f
    
    override val cornerRadius: Float = context.resources.getDimension(R.dimen.glass_radius_panel)
}
