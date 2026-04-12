package com.andrerinas.headunitrevived.utils

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * PlatformGuard provides safe, cached checks for API-specific features.
 * Ensures we don't leak modern API calls to legacy devices (back to Android 4.1).
 */
object PlatformGuard {

    /** True if the device supports Hardware Acceleration (API 11+) */
    val hasHardwareAcceleration: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB

    /** True if the device supports View Elevation and Z-indexing (API 21+) */
    val hasElevation: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP

    /** True if the device supports Round Rect Outlines (API 21+) */
    val hasRoundOutlines: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP

    /** True if the device supports the modern RenderEffect blur (API 31+) */
    val hasRenderEffectBlur: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** True if the device supports the Audio Device Callback (API 23+) */
    val hasAudioDeviceCallback: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M

    /** 
     * True if the device supports Picture-in-Picture mode (Handheld API 26+)
     * We also check the system feature flag for maximum reliability.
     */
    fun hasPipSupport(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        } else {
            false
        }
    }

    /** True if the device supports Notification Channels (API 26+) */
    val hasNotificationChannels: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    /** True if the device supports the modern VibrationEffect API (API 26+) */
    val hasModernVibration: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    /** True if the device supports the modern Parcelable API (API 33+) */
    val hasTiramisu: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** True if the device supports Runtime Permissions (API 23+) */
    val hasRuntimePermissions: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M

    /** True if the device supports Foreground Service Types (API 29+) */
    val hasServiceTypes: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** True if the device supports Android 5.0 Lollipop APIs (API 21+) */
    val hasLollipop: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP

    /** True if the device supports Android 8.0 Oreo APIs (API 26+) */
    val hasOreo: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    /** True if the device supports Android 6.0 Marshmallow APIs (API 23+) */
    val hasMarshmallow: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
    }
