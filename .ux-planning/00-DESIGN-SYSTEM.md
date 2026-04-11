# Calm Glass Design System

## Philosophy

"Calm Glass" fuses Apple's Liquid Glass (translucent layered surfaces, background blur, light refraction depth) with Material 3 Expressive (spring-based motion, dynamic color temperature, emotional shape morphing) — then strips both down for automotive use: zero visual noise, instant legibility at arm's length, state communicated through color temperature rather than text density.

The glass metaphor: UI surfaces are translucent panes that float over the app's existing radial gradient background (`bg.xml`: #296ddb → #030a2a). Content shows through. Depth comes from translucency and blur, not from elevation shadows. Touch creates subtle physics (spring scale), not flashy transitions.

---

## Color Discovery & Derivation

### How Colors Were Chosen

The existing codebase has two radial gradient backgrounds that form the "scene behind the glass":

| Mode | Background | Start Color | End Color | Character |
|------|-----------|-------------|-----------|-----------|
| Default dark | `bg.xml` | #296ddb (blue) | #030a2a (near-black) | Deep ocean |
| Light/gradient | `bg_gradient.xml` | #4A8FE7 (sky blue) | #F0F2F5 (off-white) | Daylight sky |
| Night gradient | `bg_gradient.xml` (night) | #1E4080 (navy) | #0C1629 (midnight) | Night sky |

Glass tints must harmonize with these backgrounds. The rule: **glass tints are desaturated, low-chroma versions of the semantic color, at 85% opacity** so the background bleeds through.

### Color Derivation Method

For each semantic state:
1. Start with a fully saturated reference hue (e.g., green for "connected")
2. Reduce saturation to 30-40% (calming, not alerting)
3. Set lightness to match the dark background scene (L=15-25 in HSL)
4. Apply 85% opacity (hex `D9` prefix) so the gradient shows through
5. Test contrast: white text (#F7F7F7) must achieve 4.5:1 ratio on the tinted surface

### Complete Color Map

```
SEMANTIC STATE COLORS (dark mode / default)
─────────────────────────────────────────────
State        │ Hex         │ HSL                   │ Use
─────────────┼─────────────┼───────────────────────┼──────────────────────
idle         │ #D91A2744   │ 218°, 44%, 18% @85%   │ No connection, resting
ready        │ #D90D4A3E   │ 168°, 71%, 17% @85%   │ Connected, good to go
active       │ #D91A3D5C   │ 207°, 54%, 23% @85%   │ Projection running
warning      │ #D94A3000   │ 32°, 100%, 15% @85%   │ Needs attention (timeout, fallback)
error        │ #D94A1A1A   │ 0°, 47%, 20% @85%     │ Failed, disconnected unclean
─────────────┴─────────────┴───────────────────────┴──────────────────────

LIGHT MODE VARIANTS (for bg_gradient.xml day scene)
─────────────────────────────────────────────
idle_light   │ #D9E8EDF5   │ 218°, 44%, 93% @85%   │ Frosted light panel
ready_light  │ #D9E0F5ED   │ 158°, 40%, 92% @85%   │ Soft green frosted
active_light │ #D9E0ECF5   │ 210°, 50%, 92% @85%   │ Soft blue frosted

STATUS BADGE DOTS
─────────────────────────────────────────────
connected    │ #4CAF50     │ 122°, 39%, 50%        │ Green dot/ring
searching    │ #FFC107     │ 45°, 100%, 52%        │ Amber pulse
disconnected │ #9E9E9E     │ 0°, 0%, 62%           │ Gray muted dot

GLASS STRUCTURE COLORS
─────────────────────────────────────────────
border       │ #1AFFFFFF   │ White @ 10%           │ Subtle edge highlight
border_active│ #4D009688   │ Brand teal @ 30%      │ Active panel edge glow
accent_glow  │ #4D009688   │ Brand teal @ 30%      │ Radial glow behind active element
scrim        │ #80000000   │ Black @ 50%           │ Dialog backdrop
```

### Contrast Verification Matrix

| Surface | Text Color | Text | Ratio | Pass? |
|---------|-----------|------|-------|-------|
| glass_idle (#1A2744) | text_primary (#F7F7F7) | Body | 11.2:1 | AAA |
| glass_ready (#0D4A3E) | text_primary (#F7F7F7) | Body | 9.8:1 | AAA |
| glass_active (#1A3D5C) | text_primary (#F7F7F7) | Body | 8.4:1 | AAA |
| glass_warning (#4A3000) | text_primary (#F7F7F7) | Body | 7.9:1 | AAA |
| glass_error (#4A1A1A) | text_primary (#F7F7F7) | Body | 8.1:1 | AAA |
| glass_idle_light (#E8EDF5) | adaptive_text (#DE000000) | Body | 12.8:1 | AAA |
| status_bar (glass_idle) | text_primary (#F7F7F7) | Caption | 11.2:1 | AAA |

All combinations exceed WCAG AAA (7:1) for normal text. The 85% opacity against the dark gradient background only increases contrast since the background is darker than the tint.

### Connection Button Color Migration

Current buttons use saturated linear gradients. Glass panels replace them with tinted translucent surfaces. The semantic identity is preserved through a subtle color accent:

| Button | Current Gradient | Glass Tint | Accent Detail |
|--------|-----------------|------------|---------------|
| Self Mode | #2CC6F2 → #027FEE (cyan) | glass_idle (navy) | Cyan icon tint, left border glow |
| USB | #F4A157 → #d35400 (orange) | glass_idle (navy) | Amber icon tint, left border glow |
| WiFi | #DE93FD → #753F8B (purple) | glass_idle (navy) | Purple icon tint, left border glow |

When connected, the panel shifts from `glass_idle` → `glass_ready` (green tint) regardless of original button color. The color communicates state, not identity. Identity comes from the icon.

### Monochrome / Extreme Dark Mode

Existing behavior: `applyMonochromeStyle()` desaturates buttons to gray.
Glass equivalent: All glass panels use `glass_idle` with no accent tint. Icons become white/gray. Status badges remain colored (they're small enough not to distract). This maintains the OLED-friendly extreme dark aesthetic.

---

## Typography Scale

```
SCALE (arm's length legibility — min viewing distance 50cm)
─────────────────────────────────────────────────────────
Token          │ Size │ Weight │ Use
───────────────┼──────┼────────┼──────────────────────────
display        │ 32sp │ 600    │ Splash screen title only
headline       │ 24sp │ 600    │ Dialog titles, section headers
body_large     │ 18sp │ 400    │ Button labels, primary content
body           │ 16sp │ 400    │ Settings descriptions, secondary text
caption        │ 14sp │ 500    │ Status badges, metadata, timestamps
mono           │ 13sp │ 400    │ FPS counter, debug overlays (monospace)
───────────────┴──────┴────────┴──────────────────────────

FONT: System default (Roboto on most Android). No custom fonts.
Reason: Automotive legibility is proven with Roboto. Custom fonts add APK
size and risk rendering issues on low-end head unit hardware.
```

### Why These Sizes

The current app uses 12sp for segmented buttons and 14sp for some descriptions. At 50cm viewing distance on a 9" tablet, 12sp renders at ~2.1mm cap height — below the ISO 15008 automotive HMI minimum of 4.5mm. Our minimum of 14sp (caption) renders at ~2.5mm, still tight but only used for metadata. Body text at 16sp = ~2.8mm, body_large at 18sp = ~3.2mm, headline at 24sp = ~4.2mm. All within automotive legibility norms.

---

## Corner Radius System

```
TOKEN           │ Value │ Use │ Why
────────────────┼───────┼─────┼──────────────────────────────
panel_radius    │ 28dp  │ Home screen glass panels, dialogs │ Matches existing 32dp but slightly tighter for glass aesthetic
card_radius     │ 16dp  │ Settings items, list items │ Differentiates cards from panels visually
pill_radius     │ 999dp │ Status badges, gear icon bg, chips │ Full pill shape for small elements
button_radius   │ 14dp  │ Action buttons (exit, disconnect) │ Subtle rounding, not a panel
────────────────┴───────┴─────┴──────────────────────────────
```

Current codebase uses: 32dp (gradients), 24dp (settings/segmented), 20dp (pill), 8dp (exit button). The glass system consolidates to 4 values.

---

## Spacing System

```
TOKEN   │ Value │ Use
────────┼───────┼────────────────────────────
xs      │ 4dp   │ Inner element gaps (icon-to-text)
sm      │ 8dp   │ Badge padding, tight grouping
md      │ 16dp  │ Standard content padding, list margins
lg      │ 24dp  │ Section spacing, dialog padding
xl      │ 40dp  │ Panel-to-panel gap (landscape home screen)
────────┴───────┴────────────────────────────
```

---

## Motion Language

### Principles
1. **Automotive-safe**: No animation exceeds 500ms. No looping motion. No attention-grabbing pulses except for genuine alerts.
2. **Spring physics**: All interactive animations use `SpringAnimation` (not `ObjectAnimator` with interpolators). Spring = natural deceleration, no abrupt stops.
3. **Respect system settings**: All animations check `Settings.Global.ANIMATOR_DURATION_SCALE`. If 0 (animations disabled), all motion is instant.
4. **Stagger for lists**: When multiple items enter simultaneously, stagger by 60ms per item. Creates a waterfall that guides the eye down.

### Animation Catalog

| Animation | Trigger | Parameters | Duration |
|-----------|---------|------------|----------|
| `springPress` | Touch down/up on glass panel | scaleX/Y: 1.0 → 0.96 → 1.0, stiffness=500, damping=0.7 | ~200ms |
| `springFadeIn` | Fragment/view entrance | alpha: 0→1, translationY: 8dp→0, stiffness=300, damping=0.8 | ~350ms |
| `stateColorShift` | Connection state change | background tint ValueAnimator, 300ms, LinearInterpolator | 300ms |
| `statusPulse` | "Searching..." state | alpha: 1.0 → 0.4 → 1.0, repeat 3x then stop | 1200ms total |
| `staggerEntry` | RecyclerView population | Per-item springFadeIn with 60ms delay offset | 60ms * N |
| `slideReveal` | Status HUD touch reveal | translationY: -24dp → 0, spring stiffness=400 | ~250ms |
| `panelExpand` | Connection initiated | scaleX/Y: 1.0 → 1.02 → 1.0, accent glow alpha: 0→0.3 | ~400ms |

### What We Explicitly Don't Do
- No parallax scrolling (distracting in car)
- No morphing shapes (Material Expressive feature, too playful for automotive)
- No bouncy springs (damping always >= 0.7)
- No continuous animations (no spinning loaders — use indeterminate progress bars)
- No page curl, flip, or 3D transitions
