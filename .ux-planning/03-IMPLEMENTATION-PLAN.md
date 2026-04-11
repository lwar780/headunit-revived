# Implementation Plan: Phase 0 + 1

## Decisions (Confirmed)
- **Home layout**: 3 glass connection panels + gear icon (prominent during first-run, collapses after setup)
- **API fallback**: Tinted solid panels (85% opacity) on API <31, full RenderEffect blur on 31+
- **Build scope**: Phase 0 (Foundation) + Phase 1 (Home Screen) together
- **minSdk**: Keep 16 (github) / 21 (playstore) unchanged

---

## Step-by-Step Execution

### Step 1: Design Token Resources

**Create** files (all independent, can be written in parallel):

| File | Content |
|------|---------|
| `res/values/dimens_glass.xml` | All dimension tokens from `00-DESIGN-SYSTEM.md` |
| `res/values/colors_glass.xml` | All semantic colors from color map |
| `res/values-night/colors_glass.xml` | Night-mode color overrides |
| `res/values/styles_glass.xml` | GlassPanel, TextAppearance.Glass.* styles |
| `res/values/attrs_glass.xml` | GlassView custom attributes styleable |

**Dependency**: None. These are pure resources.

### Step 2: GlassView Custom Component

**Create** `app/src/main/java/com/andrerinas/headunitrevived/ui/GlassView.kt`

Core implementation:
```kotlin
class GlassView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    enum class GlassState { IDLE, READY, ACTIVE, WARNING, ERROR }

    private var currentState = GlassState.IDLE
    private val cornerRadius: Float  // from attrs or dimens_glass

    init {
        // Read custom attrs (glassState, glassCornerRadius, glassTint)
        // Apply OutlineProvider for rounded corners + clipToOutline
        // Set initial background from glass_idle color
    }

    fun setGlassState(state: GlassState) {
        if (state == currentState) return
        val oldColor = colorForState(currentState)
        val newColor = colorForState(state)
        // Animate background tint from old → new (300ms ValueAnimator)
        // Respect ANIMATOR_DURATION_SCALE
        currentState = state
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Build.VERSION.SDK_INT >= 31) {
            // Apply RenderEffect.createBlurEffect(25f, 25f, CLAMP)
            // Note: only blurs content BEHIND this view if view is translucent
        }
        // Fallback: solid tinted background (already set)
    }

    private fun colorForState(state: GlassState): Int = when (state) {
        IDLE -> R.color.glass_idle
        READY -> R.color.glass_ready
        ACTIVE -> R.color.glass_active
        WARNING -> R.color.glass_warning
        ERROR -> R.color.glass_error
    }.let { ContextCompat.getColor(context, it) }
}
```

**Create** `res/values/attrs_glass.xml`:
```xml
<declare-styleable name="GlassView">
    <attr name="glassState" format="enum">
        <enum name="idle" value="0"/>
        <enum name="ready" value="1"/>
        <enum name="active" value="2"/>
        <enum name="warning" value="3"/>
        <enum name="error" value="4"/>
    </attr>
    <attr name="glassCornerRadius" format="dimension"/>
    <attr name="glassTint" format="color"/>
</declare-styleable>
```

**Dependency**: Step 1 (colors and dimens).

### Step 3: GlassMotion Utilities

**Create** `app/src/main/java/com/andrerinas/headunitrevived/ui/GlassMotion.kt`

```kotlin
object GlassMotion {
    fun View.springPress() {
        // OnTouchListener: ACTION_DOWN → SpringAnimation scaleX/Y to 0.96
        //                  ACTION_UP/CANCEL → SpringAnimation scaleX/Y to 1.0
        // Stiffness=500, DampingRatio=0.7
    }

    fun View.springFadeIn(delayMs: Long = 0) {
        alpha = 0f
        translationY = 8.dpToPx()
        postDelayed(delayMs) {
            SpringAnimation(this, DynamicAnimation.ALPHA, 1f).apply {
                spring.stiffness = 300f; spring.dampingRatio = 0.8f
            }.start()
            SpringAnimation(this, DynamicAnimation.TRANSLATION_Y, 0f).apply {
                spring.stiffness = 300f; spring.dampingRatio = 0.8f
            }.start()
        }
    }
}
```

**Modify** `app/build.gradle.kts`:
- Add `implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")` to dependencies

**Dependency**: None (utility class).

### Step 4: Accessibility Pass

**Modify** `res/values/strings.xml` — add all content description strings from `02-ACCESSIBILITY.md`

**Modify** layout files — add contentDescription attributes:
- `fragment_home.xml` (will be rewritten in Step 5, include there)
- `activity_splash.xml` → logo ImageView
- `dialog_safety_disclaimer.xml` → warning icon
- `layout_setting_item_toggle.xml` → Switch (set dynamically in adapter)
- `activity_headunit.xml` → loading overlay, disconnect button

**Modify** `AapProjectionActivity.kt`:
- Lines ~200-210: FPS counter TextView → add `accessibilityLiveRegion = POLITE`
- Lines ~227-240: Mic indicator TextView → add `accessibilityLiveRegion = POLITE`

**Modify** `SettingsAdapter.kt`:
- In toggle ViewHolder bind: set `switch.contentDescription` dynamically

**Dependency**: None. Can run in parallel with Steps 2-3.

### Step 5: Home Screen Glass Dashboard

**Rewrite** `res/layout/fragment_home.xml`:

```
ConstraintLayout (root)
├── ImageButton#settings_gear
│   android:src="@drawable/ic_settings_white"
│   android:background="@drawable/glass_pill_bg"
│   layout_constraint: top=parent, end=parent, margin=16dp
│   contentDescription="@string/cd_settings_gear"
│   visibility: determined by setup wizard state in code
│
├── ConstraintLayout#panels_container
│   maxWidth=1200dp, centered
│   ├── GlassView#self_mode_panel (weight in chain)
│   │   app:glassState="idle"
│   │   app:glassCornerRadius="@dimen/glass_radius_panel"
│   │   ├── ImageView#self_mode_icon (56dp, white, centered-top)
│   │   ├── TextView#self_mode_label (body_large, centered)
│   │   └── TextView#self_mode_status (caption, live_region=polite)
│   │
│   ├── GlassView#usb_panel (same structure)
│   └── GlassView#wifi_panel (same structure)
│
├── GlassView#status_bar
│   app:glassState="idle"
│   app:glassCornerRadius="@dimen/glass_radius_pill"
│   layout_constraint: bottom=exit_button.top, start/end=parent, margin=16dp
│   height=@dimen/glass_status_bar_height
│   ├── ImageView (speaker icon, 20dp, start)
│   ├── TextView#audio_output (caption, live_region=polite)
│   ├── View (divider, 1dp width, 16dp height, vertical)
│   ├── ImageView (mic icon, 20dp)
│   └── TextView#mic_status (caption, live_region=polite)
│
└── MaterialButton#exit_button
    layout_constraint: bottom=parent, end=parent, margin=16dp
    style=glass_button, cornerRadius=@dimen/glass_radius_button
```

**Rewrite** `res/layout-port/fragment_home.xml`:
Same components, but:
- Panels in 2x2 grid (Self+USB top, WiFi+[empty or centered] bottom)
- Status bar stacks vertically if narrow

**Create** glass panel drawables:
- `res/drawable/glass_panel_idle.xml` — shape: glass_idle fill, glass_border 1dp stroke, 28dp corners
- `res/drawable/glass_panel_ready.xml` — shape: glass_ready fill, glass_border_active 2dp stroke
- `res/drawable/glass_panel_active.xml` — shape: glass_active fill
- `res/drawable/glass_panel_warning.xml` — shape: glass_warning fill
- `res/drawable/glass_panel_error.xml` — shape: glass_error fill
- `res/drawable/glass_status_bar_bg.xml` — shape: glass_idle fill, pill corners
- `res/drawable/glass_pill_bg.xml` — shape: glass_idle fill, 999dp corners (for gear icon)

**Dependency**: Steps 1, 2, 3 (tokens, GlassView, GlassMotion).

### Step 6: HomeFragment Logic

**Modify** `app/src/main/java/com/andrerinas/headunitrevived/main/HomeFragment.kt`:

Key changes:
1. **Remove** 4th settings button handler. Add gear icon click → `SettingsActivity`.
2. **Add** setup wizard state check: if `!settings.hasCompletedSetupWizard`, show 4-panel layout with settings as full panel. Else 3-panel + gear.
3. **Add** StateFlow observer for `commManager.connectionState`:
   ```kotlin
   viewLifecycleOwner.lifecycleScope.launch {
       commManager.connectionState.collect { state ->
           updatePanelState(usbPanel, usbStatusText, state, ConnectionMethod.USB)
           updatePanelState(wifiPanel, wifiStatusText, state, ConnectionMethod.WIFI)
       }
   }
   ```
4. **Add** `updatePanelState()` function: maps ConnectionState → GlassState + status text + content description.
5. **Add** audio routing query:
   ```kotlin
   private fun updateAudioStatus() {
       if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
           val audioManager = requireContext().getSystemService<AudioManager>()
           val outputs = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
           val activeOutput = outputs?.firstOrNull { it.isSink } // heuristic
           audioOutputText.text = formatOutputDevice(activeOutput)
       } else {
           audioOutputText.text = getString(R.string.audio_output_unknown)
       }
   }
   ```
6. **Add** `AudioDeviceCallback` registration in `onResume()` / unregistration in `onPause()` for real-time route changes.
7. **Apply** `GlassMotion.springPress()` to all 3 panels.
8. **Apply** `GlassMotion.springFadeIn()` with stagger on panel entrance.

**Dependency**: Step 5 (layout), Step 2 (GlassView), Step 3 (GlassMotion).

---

## Critical Files Summary

| File | Action | Phase |
|------|--------|-------|
| `res/values/dimens_glass.xml` | CREATE | 0 |
| `res/values/colors_glass.xml` | CREATE | 0 |
| `res/values-night/colors_glass.xml` | CREATE | 0 |
| `res/values/styles_glass.xml` | CREATE | 0 |
| `res/values/attrs_glass.xml` | CREATE | 0 |
| `ui/GlassView.kt` | CREATE | 0 |
| `ui/GlassMotion.kt` | CREATE | 0 |
| `app/build.gradle.kts` | MODIFY (add dependency) | 0 |
| `res/values/strings.xml` | MODIFY (add a11y strings) | 0 |
| `activity_splash.xml` | MODIFY (add contentDescription) | 0 |
| `dialog_safety_disclaimer.xml` | MODIFY (add contentDescription) | 0 |
| `layout_setting_item_toggle.xml` | MODIFY (add contentDescription) | 0 |
| `activity_headunit.xml` | MODIFY (add contentDescription + liveRegion) | 0 |
| `AapProjectionActivity.kt` | MODIFY (a11y on programmatic views) | 0 |
| `SettingsAdapter.kt` | MODIFY (dynamic contentDescription on toggles) | 0 |
| `res/layout/fragment_home.xml` | REWRITE | 1 |
| `res/layout-port/fragment_home.xml` | REWRITE | 1 |
| `res/drawable/glass_panel_*.xml` (6 files) | CREATE | 1 |
| `res/drawable/glass_status_bar_bg.xml` | CREATE | 1 |
| `res/drawable/glass_pill_bg.xml` | CREATE | 1 |
| `main/HomeFragment.kt` | MODIFY (state observers, audio, motion) | 1 |

---

## Existing Code to Reuse

| What | Where | How |
|------|-------|-----|
| Connection state machine | `connection/CommManager.kt` — `connectionState: StateFlow<ConnectionState>` | Observe in HomeFragment, map to GlassState |
| MicRecorder status | `aap/MicRecorder.kt` — `MicStatusListener.onMicStatus()` | Wire to status bar mic text |
| Settings preferences | `utils/Settings.kt` — `hasCompletedSetupWizard`, `showFpsCounter`, etc. | Check for gear icon vs full panel |
| Theme application | `app/BaseActivity.kt` — `applyTheme()` with extreme dark / gradient overlays | Glass colors must respect current theme overlay |
| Monochrome mode | `HomeFragment.kt` — `applyMonochromeStyle()` | Extend to glass panels: use glass_idle for all, no accent tints |
| Radial gradient bg | `res/drawable/bg.xml` (#296ddb → #030a2a) | This IS the content behind the glass. Don't change it. |
| Navigation transitions | `res/anim/slide_*.xml` (250ms) | Keep for fragment navigation. Add spring entrance for panel content. |

---

## Verification

1. **Build**: `./gradlew assembleGithubDebug` passes with no errors
2. **Visual check (API 31+ emulator)**: Glass panels show blur effect over gradient background
3. **Visual check (API 28 emulator)**: Panels show solid tinted backgrounds, same layout, no blur
4. **TalkBack walkthrough**: Swipe through entire home screen — every element announced with correct state
5. **State transitions**: Connect USB device → USB panel transitions from IDLE (navy) to READY (green) with animated color shift
6. **Status bar**: Audio output route shown correctly (test with BT headphones connected vs built-in speaker)
7. **Spring press**: Tap and hold glass panel — scales to 0.96x, springs back on release
8. **Reduced motion**: Set `ANIMATOR_DURATION_SCALE=0` — all transitions instant, app fully functional
9. **Extreme dark mode**: Glass panels use #000000-adjacent tints, no bright colors leak
10. **First-run mode**: Clear app data → settings shows as full panel with "Setup" label
