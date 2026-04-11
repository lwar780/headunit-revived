# Accessibility Framework

## Current State: Critical Gaps

The app has **3 content descriptions** across 34 layout files and ~15 programmatic views. This is functionally inaccessible for:
- Screen reader users (TalkBack)
- Switch access users (motor impairments)
- Voice Access users
- Users with low vision relying on high-contrast modes

### Why This Must Be Fixed Before Glass

Liquid Glass design uses translucency, blur, and color temperature to communicate state. These visual channels are completely invisible to:
- Screen readers (they need `contentDescription` and `accessibilityLiveRegion`)
- High contrast mode (blur washes out; solid fallback must maintain contrast)
- Magnification users (blur artifacts at high zoom)

Building glass UI without accessibility makes the app **worse** for these users, not just equally bad.

---

## Accessibility Architecture

### Layer 1: Semantic Labels (Every Interactive Element)

Every View that a user can tap, toggle, slide, or focus MUST have a content description. Static decorative elements get `importantForAccessibility="no"`.

**Home Screen Labels:**
```xml
<!-- Glass panels — dynamic descriptions updated in code -->
self_mode_panel:  "Self Mode. {status}. Double tap to connect."
usb_panel:        "USB Connection. {status}. Double tap to connect."
wifi_panel:       "WiFi Connection. {status}. Double tap to connect."
settings_gear:    "Settings. Double tap to open."
exit_button:      "Exit application."
status_bar:       "Audio status. Output: {output}. Microphone: {mic}."
```

The `{status}` is set dynamically from the ConnectionState mapping:
```kotlin
usbPanel.contentDescription = getString(
    R.string.cd_usb_panel,
    when (state) {
        is Disconnected -> getString(R.string.cd_status_not_connected)
        is Connecting -> getString(R.string.cd_status_connecting)
        is HandshakeComplete -> state.deviceName ?: getString(R.string.cd_status_connected)
        is Error -> getString(R.string.cd_status_error, state.message)
        else -> getString(R.string.cd_status_unknown)
    }
)
```

**Settings Labels:**
```xml
toggle_switch:  "{setting_name}. {on_or_off}. Double tap to toggle."
slider:         "{setting_name}. {current_value}. Swipe left or right to adjust."
setting_item:   "{setting_name}. Current value: {value}. Double tap to change."
category_header: "{category_name} section."
```

**Projection Overlay Labels:**
```xml
loading_overlay:  "Android Auto is starting. Please wait."
reconnecting:     "Connection interrupted. {detail}."
disconnect_btn:   "Disconnect. Double tap to stop Android Auto."
fps_counter:      "Frames per second: {fps}." (live region)
mic_indicator:    "Microphone active. Source: {source}. Level: {level}." (live region)
```

### Layer 2: Live Regions (Dynamic State Changes)

Elements whose content changes and should be announced to screen readers:

```kotlin
// Status badges on glass panels — announce state changes
usbStatusText.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE

// Audio status bar — announce routing changes
audioOutputText.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
micStatusText.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE

// Projection overlays — announce connection state changes
overlayText.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
```

**POLITE** = announced after current speech finishes (status updates, non-urgent)
**ASSERTIVE** = interrupts current speech (connection lost, errors)

### Layer 3: Focus Order (Logical Navigation)

TalkBack reads elements in a default order (top-to-bottom, left-to-right). For the glass dashboard, we need custom ordering:

```
Home Screen focus order:
1. Self Mode panel (with state)
2. USB panel (with state)
3. WiFi panel (with state)
4. Status bar: audio output
5. Status bar: mic source
6. Settings gear
7. Exit button
```

Implemented via `accessibilityTraversalBefore/After` attributes or `ViewCompat.setAccessibilityDelegate()` with custom traversal.

### Layer 4: High Contrast Fallback

When Android's high contrast text is enabled (`Settings > Accessibility > High contrast text`), glass panels must:
- Use fully opaque background (no translucency)
- Remove blur entirely (it reduces readability)
- Ensure text is bold white on solid dark background
- Status badges use thick borders instead of filled circles

Detection:
```kotlin
val isHighContrast = Settings.Secure.getInt(
    contentResolver, "high_text_contrast_enabled", 0
) == 1
```

### Layer 5: Touch Target Compliance

**Minimum**: 56dp x 56dp (exceeds Material's 48dp, appropriate for automotive)

Current violations to fix:
| Element | Current Size | Required | File |
|---------|-------------|----------|------|
| Segmented button text | 12sp touchable text area | 56dp min target | layout_setting_item_segmented.xml |
| Gear icon | (new) | 56dp tap area, 32dp visual | fragment_home.xml |
| Status bar sections | (new) | Each half: 56dp tall, full width tappable | layout_status_bar.xml |
| Exit button | wrap_content | 56dp min height | fragment_home.xml |

### Layer 6: Reduced Motion

When `Settings.Global.ANIMATOR_DURATION_SCALE == 0`:
- All `SpringAnimation` instances complete instantly (duration = 0)
- `springPress()` becomes a no-op (no scale animation)
- `staggerEntry()` shows all items simultaneously
- `stateColorShift()` applies new color immediately without transition
- Status bar appears without slide animation

Detection already built into `SpringAnimation` — it reads `ANIMATOR_DURATION_SCALE` automatically. For `ValueAnimator` (color shifts), we must check manually:

```kotlin
val animationsEnabled = Settings.Global.getFloat(
    contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
) > 0f
```

---

## String Resources Required

```xml
<!-- Content descriptions for home screen -->
<string name="cd_self_mode_panel">Self Mode. %1$s. Double tap to connect.</string>
<string name="cd_usb_panel">USB Connection. %1$s. Double tap to connect.</string>
<string name="cd_wifi_panel">WiFi Connection. %1$s. Double tap to connect.</string>
<string name="cd_settings_gear">Settings</string>
<string name="cd_exit_button">Exit application</string>
<string name="cd_status_bar">Audio status</string>
<string name="cd_audio_output">Audio output: %1$s</string>
<string name="cd_mic_source">Microphone: %1$s</string>

<!-- Dynamic state descriptions -->
<string name="cd_status_not_connected">Not connected</string>
<string name="cd_status_connecting">Connecting</string>
<string name="cd_status_connected">Connected</string>
<string name="cd_status_connected_to">Connected to %1$s</string>
<string name="cd_status_searching">Searching for devices</string>
<string name="cd_status_error">Error: %1$s</string>
<string name="cd_status_ready">Ready</string>

<!-- Projection overlay descriptions -->
<string name="cd_projection_loading">Android Auto is starting. Please wait.</string>
<string name="cd_projection_reconnecting">Connection interrupted. %1$s</string>
<string name="cd_disconnect_button">Disconnect from Android Auto</string>
<string name="cd_fps_counter">Frames per second: %1$d</string>
<string name="cd_mic_indicator">Microphone active. Source: %1$s. Level: %2$s</string>

<!-- Settings descriptions -->
<string name="cd_toggle_on">%1$s. On. Double tap to turn off.</string>
<string name="cd_toggle_off">%1$s. Off. Double tap to turn on.</string>
<string name="cd_slider">%1$s. Current value: %2$s. Swipe to adjust.</string>
```

---

## Testing Checklist

Before shipping any glass UI phase:

- [ ] **TalkBack walkthrough**: Navigate entire screen using swipe gestures only. Every element announced clearly.
- [ ] **Switch Access**: Navigate using 2-switch setup. Focus order is logical. All actions reachable.
- [ ] **Voice Access**: Say "tap Self Mode" — correct element activated.
- [ ] **High contrast mode**: All text legible, all panels have solid fallback.
- [ ] **Magnification (3x)**: Glass panels render correctly when zoomed. No blur artifacts obscure text.
- [ ] **Reduced motion**: All screens work with animations disabled. No functionality lost.
- [ ] **Contrast ratio**: Run Accessibility Scanner on every screen. Zero failures at 4.5:1 for body text, 3:1 for large text.
- [ ] **Touch targets**: All interactive elements >= 56dp x 56dp.
- [ ] **RTL layout**: All glass panels mirror correctly for RTL locales (existing RTL support should carry over from ConstraintLayout, but verify).
