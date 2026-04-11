# Current UI State Audit

## Executive Summary

HeadUnit Revived uses Material 3 DayNight theme with custom gradient buttons, a RecyclerView-based settings system, and 3 projection view backends (SurfaceView, TextureView, GLSurfaceView). The UI is functional but dated: flat gradients, minimal animation, hardcoded dimensions, near-zero accessibility, and no visibility into the audio/connection pipeline.

---

## Critical Gaps (Pre-Requisites for Glass)

### G1: ZERO ACCESSIBILITY (CRITICAL)

**Evidence**: 3 `contentDescription` attributes across 34 layout files.
- `item_auto_connect.xml`: drag handle + move up/down buttons (3 descriptions)
- `layout_setting_info_banner.xml`: `importantForAccessibility="no"` (correct exclusion)
- Everything else: nothing.

**Impact**: Screen reader users cannot use the app. Switch access users cannot navigate. Voice Access users cannot activate controls.

**Specific violations**:
- Home screen: 4 MaterialButtons with gradient backgrounds, zero descriptions
- Splash screen: Logo ImageView, no description
- Safety disclaimer: Warning icon, no description
- Settings toggles: Switch controls, no descriptions
- Projection overlay: All programmatic TextViews (FPS, mic indicator), no live regions

### G2: HARDCODED DIMENSIONS (HIGH)

**Evidence**:
- `dialog_audio_offsets.xml`: 300dp container height, 200dp slider width, 270-degree rotation hack
- `list_item_device.xml`: 80dp fixed row height
- `layout_setting_item_segmented.xml`: -2dp negative margin hack for button overlap
- `fragment_home.xml`: 40dp margins (landscape), 20dp margins (portrait)
- `AapProjectionActivity.kt` lines 200-240: Hardcoded textSize=12f, padding(10,5,10,5), margins(20,20,0,0)
- `dimens.xml`: Only 5 values defined, all 16dp

**Impact**: UI breaks on non-standard screen sizes. Cannot scale for automotive head units (which vary wildly from 7" to 15").

### G3: NO CONNECTION STATE ON HOME SCREEN (HIGH)

**Evidence**: `fragment_home.xml` contains 4 gradient buttons with icons. No TextViews for status. No StateFlow observers in `HomeFragment.kt` for `commManager.connectionState`.

**Current behavior**: User taps USB button → navigates to UsbListFragment → selects device → navigates back to home → no indication of whether connection succeeded until projection starts (or doesn't).

### G4: INVISIBLE AUDIO ROUTING (HIGH)

**Evidence**: No UI element anywhere shows current audio output device or mic input source. `MicRecorder.kt` has a `MicStatusListener` (recently added) for the projection overlay, but nothing on the home screen. Audio output routing is never queried or displayed.

### G5: FLAT SETTINGS LIST (MEDIUM)

**Evidence**: `SettingsFragment.kt` uses `SettingsAdapter` (ListAdapter) with 7 sealed item types. All items rendered in a single flat RecyclerView. No grouping containers. No search. No visual hierarchy between critical settings (display, connection) and niche settings (keymap, vehicle info).

### G6: NO MOTION LANGUAGE (MEDIUM)

**Evidence**:
- Navigation: 250ms slide transitions (4 files in `res/anim/`)
- Button: `ani_button.xml` exists but appears unused
- No `SpringAnimation`, no `MotionLayout`, no custom `ItemAnimator`
- Projection overlay state changes: instant visibility toggle (VISIBLE/GONE)

### G7: MIXED MATERIAL COMPONENTS (LOW)

**Evidence**: Theme parent is `Theme.Material3.DayNight.NoActionBar` but:
- Dialog theme uses `Theme.MaterialComponents.DayNight.Dialog.Alert` (Material 2)
- Custom `button_selectable_background.xml` bypasses Material ripple system
- Segmented buttons are manual MaterialButton arrangement with shape overlays, not `MaterialButtonToggleGroup`
- `@color/brand_teal` hardcoded as primary/secondary/accent (bypasses Material 3 dynamic color)

---

## Existing Assets to Preserve

| Asset | Path | Keep/Modify/Replace |
|-------|------|---------------------|
| Radial gradient background | `drawable/bg.xml` | **KEEP** — this IS the scene behind glass |
| Gradient background (light) | `drawable/bg_gradient.xml` | **KEEP** — light theme scene |
| Gradient background (night) | `drawable-night/bg_gradient.xml` | **KEEP** — night theme scene |
| Pill translucent | `drawable/bg_pill_translucent.xml` | **REPLACE** with glass_pill_bg |
| Setting panel shapes | `drawable/bg_setting_*.xml` (4 files) | **KEEP** for now (Phase 2 replaces) |
| Button gradients | `drawable/gradient_*.xml` (5 files) | **KEEP** but unused by Phase 1 (glass panels replace them on home) |
| Navigation animations | `anim/slide_*.xml` (4 files) | **KEEP** — adequate for fragment transitions |
| Material icons | `drawable/ic_*_white.xml` | **KEEP** — white icons work on glass panels |
| Notification icon | `drawable/ic_stat_aa.xml` | **KEEP** — not part of glass redesign |
| App icon | `mipmap-*/ic_launcher*` | **KEEP** — not part of Phase 0/1 |

---

## Color System Current → Glass Migration

### Current Colors (values/colors.xml)

```
BRAND
  brand_teal          #009688    → Preserved as accent. Used for glass_accent_glow, active borders.
  color_projection_blue #3198ef  → Splash theme only. Not used in glass panels.

TEXT
  text_primary        #f7f7f7    → KEEP. Primary text on glass surfaces.
  text_second          #bdbebd    → KEEP. Secondary/caption text.
  adaptive_text_color  #DE000000  → KEEP. Light-mode text.

BUTTONS (replaced by glass panels)
  color_usb_orange     #f57c00    → Icon tint accent only (subtle, not panel color)
  color_wifi_purple    #8e44ad    → Icon tint accent only
  color_settings_darkblue #2c3e50 → Not used (settings becomes gear icon)

SURFACES
  setting_background_color #F0F0F0 (light) / #2C2C2C (night) → Replaced by glass_idle
  divider_color        #1F000000 / #1FFFFFFF                  → Replaced by glass_border
  black_overlay         #66000000                              → Replaced by glass scrim (#80000000)
```

### Night Mode (values-night/colors.xml)

Current overrides are minimal (7 colors). Glass system adds:
```
glass_idle        → darker tint for night
glass_ready       → darker green tint
glass_active      → darker blue tint
glass_border      → brighter (#2AFFFFFF) for visibility against darker bg
```

---

## Layout Architecture Map

```
Activities:
  SplashActivity ─── activity_splash.xml (Phase 0: a11y fix only)
  MainActivity ───── activity_main.xml (no change — contains NavHostFragment)
  SettingsActivity ── activity_settings.xml (no change — Phase 2)
  AapProjectionActivity ── activity_headunit.xml (Phase 0: a11y, Phase 3: glass chrome)

Fragments (Phase 0+1 scope):
  HomeFragment ───── fragment_home.xml      ◄── REWRITE (glass dashboard)
                     fragment_home.xml (port) ◄── REWRITE (portrait variant)

Fragments (unchanged in Phase 0+1):
  SettingsFragment, DarkModeFragment, KeymapFragment, AutoConnectFragment,
  AutoStartFragment, AboutFragment, UsbListFragment, NetworkListFragment,
  WirelessConnectionFragment, VehicleInfoFragment, AddNetworkAddressFragment

Dialogs (Phase 0: a11y only):
  SafetyDisclaimerDialog ── dialog_safety_disclaimer.xml (add contentDescription)
  Audio offsets dialog ──── dialog_audio_offsets.xml (Phase 4 glass rewrite)

Settings items (Phase 0: a11y only):
  layout_setting_item.xml, layout_setting_item_toggle.xml,
  layout_setting_item_slider.xml, layout_setting_item_segmented.xml
```

---

## Projection View Stack (No Changes)

The projection rendering is architecturally sound and not part of the glass redesign:

```
AapProjectionActivity
├── FrameLayout#container
│   ├── [ProjectionView | TextureProjectionView | GlProjectionView] (runtime selected)
│   ├── OverlayTouchView (transparent, captures touch for AA input)
│   └── loading_overlay (LinearLayout — Phase 3 replaces with glass)
└── Programmatic overlays (Phase 0 adds a11y):
    ├── FPS counter (TextView, top-left, elevation 100)
    └── Mic indicator (TextView, top-right, elevation 100)
```
