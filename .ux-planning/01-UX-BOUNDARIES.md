# UX Focus Areas & Boundaries

## Where UX Must Be Focused (Core Surfaces)

### Surface 1: Home Screen (PRIMARY — highest impact)

**What it is**: The screen users see 95% of the time when not in projection. 3 connection glass panels + settings gear + audio status bar.

**UX goals**:
- Zero-tap state awareness: connection status, audio routing, mic source visible immediately
- Single-tap connection: tap panel → connect (not tap → navigate → pick → wait)
- Calm confidence: the user should feel "everything is working" or know exactly what's wrong

**UX ends here**: The home screen does NOT show:
- Detailed settings (that's the settings screen)
- Projection controls (that's the projection screen)
- Debug information (that's in logs)
- Historical data (past connections, session history)

The home screen is a dashboard, not a control center.

### Surface 2: Projection Overlay (SECONDARY — used during active session)

**What it is**: The chrome layer on top of the Android Auto video stream. Loading state, status HUD, mic indicator, reconnection overlay.

**UX goals**:
- Invisible by default: when projection is working, the overlay is GONE
- Discoverable on demand: touch top edge → status HUD slides down (connection quality, audio channel, FPS)
- Non-blocking errors: reconnection state shows as a glass panel overlay, not a blocking dialog
- Mic feedback: small indicator in corner showing active source + audio level

**UX ends here**: The projection overlay does NOT:
- Allow settings changes (you must exit projection first)
- Show notifications or alerts unrelated to connection/audio
- Provide navigation controls (that's Android Auto's job)
- Display vehicle info (future phase if ever)

### Surface 3: Settings (TERTIARY — used occasionally for configuration)

**What it is**: Grouped glass card containers with categorized settings.

**UX goals**:
- Find any setting in <3 seconds (search bar + grouped categories)
- Understand what a setting does without external docs (descriptive subtitles)
- Safe defaults: dangerous settings (debug, advanced) visually recessed, not prominent

**UX ends here**: Settings does NOT:
- Provide real-time system status (that's the home screen's job)
- Handle connection flows (that's the home screen)
- Show tutorials or onboarding (that's the setup wizard)

### Surface 4: Dialogs (QUATERNARY — transient interactions)

**What it is**: Safety disclaimer, audio offsets, setup wizard, confirmations.

**UX goals**:
- Glass modals that feel part of the app, not OS interruptions
- Responsive sizing (no hardcoded 300dp heights)
- Clear primary action, always

**UX ends here**: Dialogs do NOT:
- Contain multi-step flows (wizard is the exception; it should eventually become a full-screen flow)
- Show scrollable lists (use bottom sheets instead)
- Stack (never more than 1 dialog visible)

---

## Where UX Explicitly Ends (Out of Scope)

| Area | Why It's Out | When It Might Come In |
|------|-------------|----------------------|
| Projection video rendering | This is protocol/decoder work, not UI | Never (it's correct as-is) |
| AAP protocol handling | Backend, invisible to user | Never |
| Bluetooth pairing UI | Android system handles this | Never (OS-level) |
| WiFi configuration | Android system handles this | Never (OS-level) |
| Google Assistant UI | Rendered by phone, not tablet | Never |
| App icon / branding refresh | Separate design task | Phase 5+ if desired |
| Onboarding tutorial | Current setup wizard is adequate | Phase 4 (guided connection flow) |
| Notification redesign | Current BackgroundNotification works | Phase 5+ if needed |
| Tablet-as-dashboard mode | Showing time/weather when not projecting | Future product decision |

---

## UX Flow Map

```
[App Launch]
    │
    ▼
[Splash Screen] ──1s──► [Safety Disclaimer?] ──accept──► [Setup Wizard?] ──complete──►
    │                         (first run only)                (first run only)
    ▼
[Home Screen: Glass Dashboard]
    │
    ├── Tap Self Mode panel ──► [Connect] ──► [Projection]
    │       └── Error? ──► inline red glass overlay on panel
    │
    ├── Tap USB panel ──► [1 device: auto-connect] ──► [Projection]
    │       │                └── [multiple: bottom sheet picker]
    │       └── Error? ──► inline error on panel
    │
    ├── Tap WiFi panel ──► [Scanning animation on panel]
    │       │                └── [found: auto-connect or picker]
    │       └── Error? ──► inline error on panel
    │
    ├── Tap gear icon ──► [Settings Activity]
    │       └── (back) ──► [Home Screen]
    │
    └── Tap exit ──► [Confirm?] ──► [Close]

[Projection Screen]
    │
    ├── Loading ──► glass panel overlay with progress ring
    ├── Active ──► full-screen video, overlay hidden
    │       └── Touch top edge ──► Status HUD slides down (3s auto-hide)
    ├── Reconnecting ──► amber glass overlay + disconnect option
    ├── Mic active ──► corner indicator (auto-hide 3s after silence)
    └── 2-finger swipe left ──► Exit menu (Stop / PiP / Stay)
```

---

## State Machine → Visual Mapping

The CommManager has 7 connection states. Here's how each maps to visual treatment:

```
ConnectionState          │ Panel Glass State │ Badge │ Status Text
─────────────────────────┼───────────────────┼───────┼──────────────────────
Disconnected             │ IDLE (navy)       │ ○ gray│ "Not connected"
Connecting               │ ACTIVE (blue)     │ ◐ pulse│ "Connecting..."
Connected                │ ACTIVE (blue)     │ ◑ pulse│ "Handshaking..."
StartingTransport        │ ACTIVE (blue)     │ ◑ pulse│ "Starting..."
HandshakeComplete        │ READY (green)     │ ● green│ "{device name}"
TransportStarted         │ READY (green)     │ ● green│ "{device name}"
Error(msg)               │ ERROR (red)       │ ✕ red │ "{error message}"
─────────────────────────┴───────────────────┴───────┴──────────────────────
```

The status badge is a small 8dp circle (or X) next to the status text, using the badge colors from the design system.

---

## Settings Gear Behavior: Startup vs Steady-State

Per user requirement: settings gear is prominent during startup, then collapses.

```
First Run (hasCompletedSetupWizard == false):
┌─────────────────────────────────┐
│  [Self] [USB] [WiFi] [⚙ Setup] │  ← 4-panel layout, Settings is full panel
│                                 │     with "Setup" label, brand_teal accent
│  [status bar]                   │
└─────────────────────────────────┘

After Setup Complete (hasCompletedSetupWizard == true):
┌─────────────────────────────────┐
│                            ⚙    │  ← Small gear icon, top-right
│  [Self Mode] [USB] [WiFi]      │  ← 3-panel layout
│                                 │
│  [status bar]                   │
└─────────────────────────────────┘
```

Implementation: `HomeFragment` checks `settings.hasCompletedSetupWizard` in `onViewCreated()`. If false, shows 4-panel layout with settings as full glass panel. If true, shows 3-panel layout with gear icon. This is a layout visibility toggle, not a navigation change — both paths navigate to `SettingsActivity`.
