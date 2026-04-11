# UX Planning: Calm Glass Redesign

Design system and implementation plan for HeadUnit Revived's UI overhaul.
Fuses Apple Liquid Glass + Material 3 Expressive for an automotive-optimized "Calm Glass" aesthetic.

## Documents

| File | Purpose | Read When |
|------|---------|-----------|
| [00-DESIGN-SYSTEM.md](00-DESIGN-SYSTEM.md) | Color derivation, typography, corners, spacing, motion tokens | Starting any glass UI work |
| [01-UX-BOUNDARIES.md](01-UX-BOUNDARIES.md) | Where UX focus lives, where it ends, state-to-visual mapping, flow map | Deciding what to build or not build |
| [02-ACCESSIBILITY.md](02-ACCESSIBILITY.md) | A11y architecture, string resources, testing checklist | Writing any UI code |
| [03-IMPLEMENTATION-PLAN.md](03-IMPLEMENTATION-PLAN.md) | Step-by-step execution for Phase 0+1, file list, verification | Building the glass dashboard |
| [04-CURRENT-STATE-AUDIT.md](04-CURRENT-STATE-AUDIT.md) | Current UI inventory, gaps, color migration, layout map | Understanding what exists before changing it |

## Phase Status

- [x] **Phase 0**: Foundation (tokens, GlassView, GlassMotion, accessibility pass)
- [x] **Phase 1**: Home Screen Glass Dashboard (3 panels + gear + status bar)
- [ ] Phase 2: Settings Glass Cards (future)
- [ ] Phase 3: Projection Glass Chrome (future)
- [ ] Phase 4: Connection Flow + Dialogs (future)

## Key Decisions

- 3 glass panels + collapsible gear icon (prominent during first-run)
- API <31 fallback: 85% opacity tinted solid panels (no blur)
- minSdk unchanged: 16 (github) / 21 (playstore)
- All animations automotive-safe: high damping, no loops, <500ms
- Minimum touch target: 56dp (exceeds Material 48dp)
- Minimum body text: 16sp
