# Trip Lifecycle Intelligence & Offline Resilience

**Date:** 2026-04-13
**Status:** Draft
**Estimated Effort:** 2 weeks, ~1200 lines

## Overview

Add proactive intelligence to Android Auto head unit: detect trip lifecycle (ignition, parking, engine-off), provide voice announcements for navigation/traffic/fuel, handle signal loss gracefully, and log trips locally.

**Core principle:** Work within AAP protocol constraints. No companion app, no phone-side integration, no custom protocols.

**Scope:**
1. Trip state machine (detect ignition/parking/engine-off from AAP signals)
2. Proactive voice announcements (traffic, fuel, meeting conflicts)
3. Graceful offline UI (acknowledge signal loss, show stale data timestamp)
4. Local trip journal (AA app with read-only trip log)

**Not in scope:**
- Phone sync, calendar integration, cloud backup
- Tile caching, offline maps, dead reckoning
- OSM integration (doesn't work with AAP)

---

## Architecture

### High-Level Structure

Four new services bind to `AapService`, subscribe to existing flows, emit events via `TripEventBus`:

```
AapService (existing)
  ├─ connectionState: StateFlow<ConnectionState>
  ├─ locationUpdates: Flow<LocationUpdate>
  ├─ audioFocusState: StateFlow<AudioFocusState>
  └─ videoChannelActive: StateFlow<Boolean>
       │
       ├──> TripIntelligenceService
       │      └─ TripStateMachine (IDLE/IGNITION/DRIVING/PARKING/ENGINE_OFF)
       │
       ├──> VoiceAnnouncementService
       │      └─ PriorityQueue (critical/informational)
       │
       ├──> OfflineUiService
       │      └─ Signal loss overlay
       │
       └──> TripJournalService
              ├─ SQLite (trips table)
              └─ JournalActivity (AA app)
```

**Communication:**
- Services subscribe to `AapService` flows (one-way)
- Services emit to `TripEventBus` (optional pub/sub for cross-service events)
- No direct service-to-service dependencies

**Lifecycle:**
- Services bind in `AapService.onCreate()` if feature flags enabled
- Services unbind in `AapService.onDestroy()`
- Lazy initialization: check `FeatureFlags` before starting

---

## Feature Flags

```kotlin
object FeatureFlags {
    // SharedPreferences backed, toggleable via Settings UI

    val tripIntelligence: Boolean      // Master flag
    val voiceAnnouncements: Boolean    // Sub-flag
    val offlineGracefulDegradation: Boolean
    val tripJournal: Boolean
}
```

Each service checks its flag on creation:
```kotlin
if (!FeatureFlags.tripIntelligence) {
    stopSelf()
    return
}
```

---

## Component 1: Trip State Machine

**File:** `TripIntelligenceService.kt` (~300 lines)

### State Definitions

```
IDLE → IGNITION_ON → DRIVING → PARKING → ENGINE_OFF → IDLE
```

**State transitions:**

| From | To | Trigger |
|------|-----|---------|
| IDLE | IGNITION_ON | SCREEN_ON + (USB attach OR WiFi connected) + no connection in last 30s |
| IGNITION_ON | DRIVING | Speed > 10 km/h sustained for 5s |
| DRIVING | PARKING | Speed < 5 km/h sustained for 10s + Maps still active |
| PARKING | ENGINE_OFF | SCREEN_OFF + connection drops |
| ENGINE_OFF | IDLE | Immediate (cleanup state) |
| IGNITION_ON | IDLE | False alarm: connection drops within 10s |

**Inputs (from AapService):**
- `connectionState`: Connected, Connecting, Disconnected
- `locationUpdates`: GPS location + speed
- `videoChannelActive`: true if Maps rendering

**Outputs (to TripEventBus):**
- `TripEvent.IgnitionDetected(timestamp)`
- `TripEvent.DrivingStarted(timestamp)`
- `TripEvent.ParkingDetected(timestamp, location)`
- `TripEvent.TripEnded(startTime, endTime, distance)`

### Edge Cases

**False ignition (SCREEN_ON without driving):**
- If no speed change within 60s of IGNITION_ON → revert to IDLE
- Example: user turns on screen to check settings, doesn't drive

**Brief disconnection (tunnel/parking garage):**
- If DRIVING → connection drops < 30s → stay in DRIVING
- Only transition to ENGINE_OFF if SCREEN_OFF also detected

**Maps not active (non-navigation use):**
- Detect Maps via video channel + last active app tracking
- If Maps active but no navigation: allow trip but skip "Resume navigation?" prompt

---

## Component 2: Proactive Voice Agent

**File:** `VoiceAnnouncementService.kt` (~400 lines)

### Priority Queue

Two queues:
- **Critical:** Low fuel, meeting conflict, accident ahead → interrupt immediately
- **Informational:** Traffic delay, parking suggestions → wait for silence gap

**Audio ducking:**
```kotlin
audioManager.requestAudioFocus(
    focusRequest,
    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
)
```

Critical announcements duck audio (lower volume), informational wait for existing audio to finish.

### Announcement Types

| Type | Trigger | Example |
|------|---------|---------|
| Resume navigation | IGNITION_ON + Maps was active last trip | "Resume navigation to [destination]? Say yes" |
| Traffic delay | LocationUpdate + infer route delay from speed drop | "Traffic ahead, 15 min delay. Say reroute or continue" |
| Low fuel | Fuel estimate < 50km range + gas station nearby | "Low fuel, gas station in 1 mile on right" |
| Parking suggestion | PARKING state + Maps shows destination reached | "Show nearby parking? Say yes" |

**Inference logic:**

**Traffic delay:**
- Compare current speed to historical average for this road segment
- If speed < 50% of average for 3 consecutive location updates → infer traffic
- Announce delay estimate based on distance remaining

**Low fuel:**
- Read fuel level from OBD2 if available (via `SensorEvent` from AAP)
- Estimate range: (current fuel / tank capacity) × avg MPG × remaining
- Query nearby gas stations from last known POI data


**Implementation notes:**
- OBD2 fuel level not always available → skip low fuel announcements if missing
- POI data comes from last Maps search results (limited to what Maps exposed)

### Voice Command Handling

User responds with:
- "Yes" / "Resume" / "Continue" → trigger action
- "No" / "Cancel" → dismiss announcement
- No response within 10s → auto-dismiss informational, repeat critical once

Commands trigger actions:
- "Resume" → send deep link to Maps with last destination
- "Reroute" → send Maps deep link with `avoid_traffic=true` param

---

## Component 3: Graceful Offline UI

**File:** `OfflineUiService.kt` (~200 lines)

### Signal Loss Detection

Monitor location updates:
- If no `LocationUpdate` received for 10s → assume signal lost
- Cache last received traffic state (JSON, ~5KB)
- Show overlay on video projection

### Overlay UI

Semi-transparent overlay on `AapProjectionActivity`:
```kotlin
TextView (top-right corner):
  "⚠️ Signal Lost"
  "Last updated: 3 min ago"

  Background: Color.argb(180, 0, 0, 0)
  TextColor: Color.YELLOW
```

**Overlay behavior:**
- Appears 10s after last location update
- Updates timestamp every 30s
- Dismisses immediately when location resumes

### Cached Traffic State

Store last received traffic data:
```kotlin
data class CachedTraffic(
    val timestamp: Long,
    val incidents: List<Incident>,  // from Maps notifications
    val congestion: Map<String, Float>  // road segment → speed ratio
)
```

When signal lost:
- Display cached traffic on map (if Maps exposed it)
- Show "Data from [timestamp]" disclaimer
- Don't extrapolate or predict future state

**Storage:**
- In-memory only (no disk persistence)
- Cleared on trip end
- Max 5KB JSON

---

## Component 4: Trip Journal

**File:** `TripJournalService.kt` (~300 lines)

### Database Schema

```sql
CREATE TABLE trips (
    id INTEGER PRIMARY KEY,
    start_time INTEGER NOT NULL,  -- Unix timestamp
    end_time INTEGER,
    start_location TEXT,  -- "lat,lon"
    end_location TEXT,
    distance_km REAL,
    duration_sec INTEGER,
    events_json TEXT,  -- JSON array of voice announcements given
    created_at INTEGER DEFAULT (strftime('%s','now'))
);

CREATE INDEX idx_start_time ON trips(start_time DESC);
```

**Data retention:**
- Auto-delete trips older than 90 days
- Cleanup runs on app start + weekly background task

### Event Logging

On `TripEvent.TripEnded`:
```kotlin
val events = listOf(
    VoiceEvent("Resume navigation?", timestamp),
    VoiceEvent("Traffic ahead 15 min", timestamp),
    VoiceEvent("Low fuel warning", timestamp)
)

db.insert("trips", values = ContentValues().apply {
    put("start_time", startTime)
    put("end_time", endTime)
    put("start_location", "${lat},${lon}")
    put("distance_km", distance)
    put("events_json", gson.toJson(events))
})
```

### AA App (JournalActivity)

**Manifest registration:**
```xml
<activity android:name=".journal.JournalActivity"
          android:label="Trip Journal"
          android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
        <category android:name="android.intent.category.APP_MAPS" />
    </intent-filter>
</activity>
```

**UI (ListTemplate):**
```kotlin
ListTemplate.Builder()
    .setTitle("Trip Journal")
    .setSingleList(ItemList.Builder().apply {
        trips.forEach { trip ->
            addItem(Row.Builder()
                .setTitle("${trip.startTime.format()} - ${trip.duration} min")
                .addText("${trip.distanceKm} km")
                .addText("${trip.events.size} events")
                .setOnClickListener { showTripDetails(trip) }
                .build())
        }
    }.build())
    .build()
```

**Details screen (PaneTemplate):**
- Shows trip timeline: start → events → end
- Events displayed with timestamp + text
- No editing, no deletion from head unit

---

## Data Flow Examples

### Flow 1: Ignition Detection → Voice Announcement

1. User turns on screen + connects phone via USB
2. `AapService` emits `connectionState = Connected`
3. `TripIntelligenceService` receives event
4. State machine: IDLE → IGNITION_ON
5. Check: Was Maps active last trip? (from persisted state)
6. If yes: emit `TripEvent.IgnitionDetected(shouldResumeNav = true)`
7. `VoiceAnnouncementService` receives event
8. Queue critical announcement: "Resume navigation to Home?"
9. Request audio focus (AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
10. Speak via TTS
11. Listen for voice response: "Yes" → send deep link to Maps

### Flow 2: Signal Loss → Overlay Display

1. User drives into tunnel
2. GPS location updates stop arriving (10s timeout)
3. `OfflineUiService` detects silence
4. Cache last traffic state from memory (5KB JSON)
5. Show overlay on `AapProjectionActivity`: "⚠️ Signal Lost / Last updated: 0 sec ago"
6. Update timestamp every 30s: "Last updated: 1 min ago"
7. User exits tunnel
8. GPS location resumes
9. Hide overlay immediately
10. Clear cached traffic state

### Flow 3: Trip End → Journal Write

1. User parks, turns off screen
2. `AapService` emits `SCREEN_OFF` + `connectionState = Disconnected`
3. `TripIntelligenceService` state machine: PARKING → ENGINE_OFF
4. Calculate trip stats: duration, distance from accumulated location updates
5. Emit `TripEvent.TripEnded(startTime, endTime, distance, events)`
6. `TripJournalService` receives event
7. Write to SQLite:
   ```sql
   INSERT INTO trips VALUES (
     null, 1681392000, 1681393800, "37.7749,-122.4194", "37.8044,-122.2712",
     15.3, 1800, '[{"type":"resume","time":1681392010},{"type":"traffic","time":1681392600}]',
     1681393800
   )
   ```
8. Cleanup: delete trips older than 90 days
9. Service unbinds from `AapService`

---

## Testing Strategy

### Unit Tests (per service)

**TripIntelligenceService:**
- Mock `AapService` flows
- Emit fake SCREEN_ON + connectionState = Connected
- Assert state transitions: IDLE → IGNITION_ON
- Emit speed > 10 km/h for 5s
- Assert: IGNITION_ON → DRIVING
- Emit SCREEN_OFF + Disconnected
- Assert: DRIVING → ENGINE_OFF → IDLE

**VoiceAnnouncementService:**
- Mock `TripEventBus`
- Emit `TripEvent.IgnitionDetected(shouldResumeNav = true)`
- Assert: announcement queued with priority = CRITICAL
- Mock `AudioManager.requestAudioFocus` returns success
- Assert: TTS speaks within 3s

**OfflineUiService:**
- Mock location updates
- Emit 3 updates, then stop
- Wait 10s
- Assert: overlay visible on `AapProjectionActivity`
- Assert: overlay text = "Last updated: 10 sec ago"

**TripJournalService:**
- Mock `TripEventBus`
- Emit `TripEvent.TripEnded(...)`
- Assert: database insert called with correct values
- Query trips table
- Assert: 1 row inserted

### Integration Tests

**End-to-end trip lifecycle:**
1. Simulate SCREEN_ON + USB_ATTACHED
2. Assert: `TripIntelligenceService` state = IGNITION_ON
3. Assert: `VoiceAnnouncementService` speaks "Resume navigation?"
4. Emit location updates with speed > 10 km/h for 5s
5. Assert: state = DRIVING
6. Stop location updates for 15s
7. Assert: `OfflineUiService` shows overlay
8. Resume location updates
9. Assert: overlay hidden
10. Emit SCREEN_OFF + Disconnected
11. Assert: `TripJournalService` writes trip to database
12. Assert: trip has correct start_time, end_time, distance

### Manual Validation

**Real-world scenarios:**
1. Drive through 3 different tunnels → verify overlay appears/dismisses correctly
2. Trigger low fuel warning (if OBD2 available) → verify voice announcement
3. Park for 5 min → verify state transitions to PARKING
4. Open Journal AA app → verify last 10 trips display correctly
5. Disable `tripIntelligence` flag → verify services don't start

---

## Deployment & Rollout

### Phase 1: Trip State Machine (Week 1)
- Deploy `TripIntelligenceService` with all flags enabled
- Monitor: ignition detection accuracy, false alarm rate
- Success gate: 95% true positive, <5% false positive

### Phase 2: Voice Announcements (Week 2)
- Enable `voiceAnnouncements` flag for beta testers
- Monitor: announcement latency, TTS success rate, user commands
- Success gate: 90% announcements speak within 5s, 80% TTS success

### Phase 3: Offline UI + Journal (Week 2)
- Enable `offlineGracefulDegradation` and `tripJournal` for all
- Monitor: overlay display timing, journal writes, database size
- Success gate: overlay shows within 12s of signal loss, journal <10MB

### Rollback
Each flag can be disabled remotely:
- Push updated default to `FeatureFlags` SharedPreferences
- Services check flags on every major operation
- Disable `tripIntelligence` → stops new trip detection, ongoing trips complete
- Disable journal → stops new writes, existing data remains queryable

### Metrics

**Trip Intelligence:**
- Ignition detection: true positive rate, false positive rate
- State transitions: mean time in each state, invalid transitions count
- User voice commands: response rate (yes/no/timeout breakdown)

**Voice Announcements:**
- Queue depth: critical vs informational count
- Speak latency: time from queue → TTS start
- TTS failures: count, error types

**Offline UI:**
- Overlay triggers: count per day, false positives
- Signal loss duration: mean, median, 95th percentile
- Cached traffic usage: hit rate when overlay visible

**Journal:**
- Trip writes: count per day, database size
- Cleanup runs: trips deleted per run
- AA app usage: opens per week, avg trips viewed per session

---

## Implementation Notes

### Parallel Build Strategy

Three independent subtrees:
```
feature/trip-intelligence/
  └─ TripIntelligenceService.kt
  └─ TripStateMachine.kt
  └─ test/TripIntelligenceTest.kt

feature/voice-announcements/
  └─ VoiceAnnouncementService.kt
  └─ PriorityQueue.kt
  └─ test/VoiceAnnouncementTest.kt

feature/offline-journal/
  └─ OfflineUiService.kt
  └─ TripJournalService.kt
  └─ JournalActivity.kt
  └─ test/OfflineJournalTest.kt
```

**Shared dependencies (built first):**
```
shared/
  └─ TripEvent.kt (sealed class)
  └─ FeatureFlags.kt
```

**Merge order:**
1. Shared → main
2. Trip intelligence → main
3. Voice announcements → main (rebases on trip intelligence)
4. Offline journal → main (rebases on voice announcements)

### Data Assumptions

**Never assume:**
- Location updates are continuous (can stop anytime)
- Maps is active (user might use Waze, Spotify, etc.)
- OBD2 data is available (most phones don't have it)
- Network is available (might be airplane mode)

**Always check:**
```kotlin
locationUpdate?.let { location ->
    // Use location
} ?: run {
    // Handle missing location
}
```

### Error Recovery

**TTS unavailable:**
- Log announcement to journal instead
- Show silent notification on phone
- Don't retry repeatedly (avoid spam)

**Database write fails:**
- Log error once
- Skip current trip write
- Don't crash service

**Audio focus denied:**
- Queue announcement for later
- Retry on next silence gap
- Max 3 retries, then drop

---

## Estimates

| Component | Lines | Time | Dependencies |
|-----------|-------|------|--------------|
| Trip State Machine | 300 | 3 days | AapService flows |
| Voice Announcements | 400 | 4 days | Trip events, AudioManager |
| Offline UI | 200 | 2 days | Location updates |
| Trip Journal | 300 | 3 days | Trip events, SQLite |
| Feature Flags | 100 | 1 day | SharedPreferences |
| Tests | 400 | 3 days | All components |
| **Total** | **1700** | **~2 weeks** | |

---

## Success Criteria

**Must have:**
- ✅ Ignition detection works 95%+ of the time
- ✅ Voice announcements speak within 5s (critical) or 30s (informational)
- ✅ Overlay appears within 12s of signal loss
- ✅ Journal writes all trips with correct timestamps
- ✅ No crashes, no ANRs, no memory leaks
- ✅ All unit tests pass, integration tests pass
- ✅ Feature flags toggle each component independently

**Nice to have:**
- OBD2 fuel level used (if available)
- Journal AA app loads in <2s

**Out of scope (defer to future):**
- Phone sync, cloud backup
- Offline map tiles, navigation
- Dead reckoning, sensor fusion
- Export to CSV, external APIs
