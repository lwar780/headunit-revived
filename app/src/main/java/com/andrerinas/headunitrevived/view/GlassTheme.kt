package com.andrerinas.headunitrevived.view

import com.andrerinas.headunitrevived.view.GlassView.GlassState

interface GlassTheme {
    fun surfaceColor(state: GlassState): Int
    fun borderColor(state: GlassState): Int
    fun badgeColor(state: GlassState): Int
    val blurRadius: Float
    val cornerRadius: Float
}
