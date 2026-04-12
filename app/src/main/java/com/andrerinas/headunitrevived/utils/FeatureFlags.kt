package com.andrerinas.headunitrevived.utils

import android.content.Context
import android.content.SharedPreferences

/**
 * Shared infrastructure for Trip Intelligence feature flags.
 * Backed by 'settings' SharedPreferences.
 */
class FeatureFlags(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var tripIntelligence: Boolean
        get() = prefs.getBoolean("feature-trip-intelligence", false)
        set(value) = prefs.edit().putBoolean("feature-trip-intelligence", value).apply()

    var voiceAnnouncements: Boolean
        get() = prefs.getBoolean("feature-voice-announcements", false)
        set(value) = prefs.edit().putBoolean("feature-voice-announcements", value).apply()

    var offlineGracefulDegradation: Boolean
        get() = prefs.getBoolean("feature-offline-graceful-degradation", true)
        set(value) = prefs.edit().putBoolean("feature-offline-graceful-degradation", value).apply()

    var tripJournal: Boolean
        get() = prefs.getBoolean("feature-trip-journal", true)
        set(value) = prefs.edit().putBoolean("feature-trip-journal", value).apply()
}
