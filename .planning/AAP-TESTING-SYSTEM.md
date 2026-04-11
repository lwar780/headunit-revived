# AAP Protocol Testing System — Execution Plan

**Goal:** Capture-replay testing that turns user bug reports into reproducible
test cases, with privacy as architecture not afterthought.

**Success metric:** Bug report with .aapsession → replay locally → root cause
in 20 min (was 4 hours).

---

## Verified Facts (from research)

### Intercept Points
- **Inbound (FROM_CAR):** `AapMessageHandlerType.handle()` line 25 — post-decryption
- **Outbound (TO_CAR):** `AapTransport.send()` line 428 — pre-encryption
- Both receive plaintext `AapMessage(channel, flags, type, data: ByteArray)`

### Threading Model
- Read loop runs on `HandlerThread("AapTransport:Handler::Poll")` with `THREAD_PRIORITY_AUDIO`
- Each message gets its own ByteArray allocation from SSL decrypt — **no copy needed** for async write
- Synchronous buffered write is acceptable (already off main thread), async coroutine preferred for slow eMMC

### Existing Infrastructure (reuse, don't rebuild)
- **FileProvider:** Already configured in AndroidManifest.xml + provider_paths.xml (cache + external_files)
- **Share pattern:** `LogExporter.shareLogFile()` — FileProvider + ACTION_SEND + FLAG_GRANT_READ_URI_PERMISSION
- **File rotation:** LogExporter has max-files + max-size eviction pattern
- **SensorManager:** NightModeManager uses TYPE_LIGHT — extend to TYPE_ACCELEROMETER for shake
- **Notifications:** 4 channels exist; 5th for recording is natural
- **Settings/Debug UI:** SettingsFragment lines 989-1077 has log capture controls — extend here
- **No existing tests.** JUnit 4 declared, no test dirs, no test files, no mocking frameworks

### PII Surface (all proto files analyzed)
| Proto File | PII Fields | Sensitivity |
|---|---|---|
| wireless.proto | WiFi password (field 32), IP, SSID, BSSID | CRITICAL |
| sensors.proto | GPS lat/lng (44-45), altitude, speed, bearing | HIGH |
| control.proto | Caller phone#, caller ID, contact thumbnail, BT MAC, device names, vehicle fingerprint | HIGH |
| playback.proto | Song, artist, album, albumart, playlist | MEDIUM |
| navigation.proto | Road names (turn-by-turn) | MEDIUM |
| common.proto | vehicle_id | LOW |
| input.proto | None (touch coords only) | NONE |
| media.proto | None (codec config only) | NONE |

### Design Decisions (user-confirmed)
1. **Anonymizer:** Protobuf-aware for known types, zeroed payload for unknown
2. **Session size:** Rolling buffer — last 5 min always retained, on error capture next 10 min
3. **Storage:** `externalFilesDir("sessions")` for both debug and release
4. **Recommendations integrated:** Skeleton Sessions, Protocol Fingerprint, Transform Pipeline
5. **Strategies integrated:** Phase 0 bootstrap, Channel-aware depth, Dashboard folded into Settings debug section

---

## Architecture

### Recording Pipeline (single message path)

```
AapMessage (plaintext, from handler or send)
    │
    ▼
sanitize(message) ─── pure function: AapMessage → AapMessage
    │                  - Protobuf-aware: deserialize known types, strip PII, reserialize
    │                  - Unknown types: zero payload, keep header
    │                  - WiFi password → empty string
    │                  - GPS → 0/0, MACs → 00:00:00:00:00:00
    │                  - Contact thumbnail → empty bytes
    │                  - Media metadata → strip albumart, keep song/artist
    │                  - Road names → "[REDACTED]"
    │                  - Device names/IDs → SHA256 with session salt
    │
    ▼
skeleton(message) ─── pure function: AapMessage → SessionFrame
    │                  - Channel policy determines depth:
    │                    Ch 0 (Control):  FULL_PAYLOAD
    │                    Ch 1 (Sensor):   FULL_PAYLOAD (already sanitized)
    │                    Ch 2 (Video):    HEADER_ONLY (8B: ch+flags+type+len)
    │                    Ch 4 (Speech):   HEADER_ONLY
    │                    Ch 5 (Nav audio): HEADER_ONLY
    │                    Ch 6 (Media):    HEADER_ONLY
    │                    Ch 7 (Mic):      DROP (never record mic)
    │
    ▼
RollingBuffer.write(frame) ─── in-memory ring buffer
    │                          - Retains last 5 minutes of frames
    │                          - On error signal: flush buffer to disk + continue
    │                            capturing next 10 minutes, then stop
    │                          - Normal stop: discard buffer (no disk write
    │                            unless error occurred)
    │
    ▼ (on flush)
FileOutputStream → externalFilesDir("sessions")/{sessionId}.aapsession
    │               - Buffered write, 8KB buffer
    │               - Metadata sidecar: {sessionId}.meta.json
    │
    ▼
File rotation: 500MB cap, oldest-first eviction, 7-day auto-delete in debug
```

### Rolling Buffer Behavior

```
Normal operation (no error):
  ┌──────────────────────────────────────────┐
  │ [5 min ring buffer in memory, overwrites]│
  │  Frame Frame Frame Frame Frame Frame ... │
  └──────────────────────────────────────────┘
  → User stops recording → buffer discarded, no file written
  → Why: most sessions have no bugs, don't waste storage

Error detected (protocol error, crash, disconnect):
  ┌───────────────┬────────────────────────────┐
  │ FLUSH TO DISK │ CONTINUE CAPTURING 10 MIN  │
  │ (last 5 min)  │ (post-error behavior)      │
  └───────────────┴────────────────────────────┘
  → Writes {sessionId}.aapsession with 5+10 = 15 min window
  → Metadata includes: error type, timestamp, connection type, protocol fingerprint

Manual flush (shake / QS tile / notification tap):
  → Same as error: flush 5 min buffer + capture 10 min forward
  → User triggered = user saw something worth reporting
```

### Session File Format (.aapsession)

```
Header (32 bytes):
  Magic: "AAPS" (4B)
  Version: uint16 (2B) — format version, currently 1
  Flags: uint16 (2B) — bit 0: has-error, bit 1: user-triggered, bit 2: skeleton-mode
  SessionID: UUID (16B)
  Created: uint64 (8B) — epoch millis

Frames (repeated):
  Timestamp: uint64 (8B) — relative to session start, millis
  Direction: uint8 (1B) — 0=FROM_CAR, 1=TO_CAR
  Channel: uint8 (1B)
  Type: uint16 (2B) — AAP message type
  PayloadLen: uint32 (4B)
  Payload: byte[PayloadLen] — sanitized protobuf (or empty for HEADER_ONLY)

Footer (24 bytes):
  FrameCount: uint32 (4B)
  Duration: uint64 (8B) — total session millis
  ErrorOffset: int64 (8B) — frame index of error (-1 if none)
  Checksum: uint32 (4B) — CRC32 of all frames
```

### Metadata Sidecar (.meta.json)

```json
{
  "sessionId": "uuid",
  "version": 1,
  "appVersion": "2.2.0-beta3",
  "androidApi": 28,
  "connectionType": "USB",
  "protocolFingerprint": "sha256-of-exchanged-message-types",
  "carFingerprint": "sha256-of-make-model",
  "hasError": true,
  "errorType": "PROTOCOL_ERROR",
  "errorFrameIndex": 4231,
  "frameCount": 8500,
  "durationMs": 900000,
  "channelDepths": {"0": "FULL", "1": "FULL", "2": "HEADER", "6": "HEADER"},
  "fileSizeBytes": 2340000,
  "created": "2026-04-11T10:30:00Z"
}
```

### Notification (recording active)

```
Channel: "headunit_session_recording" (IMPORTANCE_LOW)
Notification ID: 100
Content: "Recording AAP session" / "Captured error — recording 10 more min"
Actions: [Stop Recording]
Ongoing: true (can't dismiss)
Visibility: PUBLIC (no sensitive content)
```

---

## Phase 0: Test Infrastructure Bootstrap (2h)

**Goal:** `./gradlew testGithubDebugUnitTest` runs and passes with 1 trivial test.

### Tasks

| # | Task | File | Action |
|---|------|------|--------|
| 0.1 | Create test directory tree | `app/src/test/java/com/andrerinas/headunitrevived/testing/` | mkdir |
| 0.2 | Add test dependencies | `app/build.gradle.kts` | Edit: add MockK 1.13.8, Robolectric 4.11.1, Truth 1.1.5, kotest-property 5.8.0 |
| 0.3 | Write canary test | `app/src/test/java/.../testing/CanaryTest.kt` | Create: 1 test asserting true == true |
| 0.4 | Verify build | terminal | `./gradlew testGithubDebugUnitTest --tests "*CanaryTest*"` |
| 0.5 | Verify Robolectric context | `app/src/test/java/.../testing/ContextTest.kt` | Create: test that `context.getExternalFilesDir("sessions")` returns non-null |

**Acceptance:** Both tests pass. Robolectric provides working Android Context.

**Dispatch:** Haiku agent (mechanical scaffold, no reasoning needed).

---

## Phase 1: Session Capture + Privacy Foundation (4h)

**Goal:** Rolling buffer captures sanitized AAP frames in memory. Error signal
flushes to disk. Debug builds auto-enabled, release builds opt-in.

### TDD Sequence — Write Tests FIRST

**TEST 1: SessionAnonymizerTest** (pure function tests, no Android deps)
```
- sanitize(sensorMsg with GPS) → GPS coords are 0/0
- sanitize(controlMsg with BT MAC) → MAC is 00:00:00:00:00:00
- sanitize(controlMsg with caller phone#) → phone# is empty
- sanitize(controlMsg with contact thumbnail) → thumbnail is empty bytes
- sanitize(wirelessMsg with WiFi password) → password is empty string
- sanitize(wirelessMsg with SSID) → SSID is hashed with session salt
- sanitize(wirelessMsg with IP) → IP is hashed with session salt
- sanitize(playbackMsg with albumart) → albumart stripped, song/artist kept
- sanitize(navigationMsg with road name) → road is "[REDACTED]"
- sanitize(controlMsg with device name) → name is SHA256 with session salt
- sanitize(unknownTypeMsg) → payload zeroed, header preserved
- sanitize(inputMsg) → unchanged (no PII in input.proto)
- sanitize(mediaMsg) → unchanged (no PII in media.proto)
- idempotent: sanitize(sanitize(msg)) == sanitize(msg)
- preserves: channel, flags, type, message structure intact
```

**TEST 2: SkeletonFilterTest** (pure function tests)
```
- skeleton(channel=0, controlMsg) → FULL_PAYLOAD (all bytes kept)
- skeleton(channel=1, sensorMsg) → FULL_PAYLOAD
- skeleton(channel=2, videoMsg) → HEADER_ONLY (payload empty, header preserved)
- skeleton(channel=6, mediaMsg) → HEADER_ONLY
- skeleton(channel=7, micMsg) → null (DROP — never record mic)
- skeleton preserves: timestamp, direction, channel, type
```

**TEST 3: RollingBufferTest** (in-memory, no I/O)
```
- write() adds frames, buffer retains last 5 min
- write() evicts frames older than 5 min
- flush() returns all buffered frames in order
- flush() clears the buffer
- estimatedSizeBytes() tracks memory usage
- empty buffer flush() returns empty list
- frames written after flush() start fresh
```

**TEST 4: SessionWriterTest** (needs Robolectric for externalFilesDir)
```
- writeSession(frames, metadata) creates .aapsession + .meta.json in externalFilesDir("sessions")
- .aapsession has correct magic, version, frame count, checksum
- .meta.json is valid JSON with all required fields
- readSession() round-trips: write → read → identical frames
- sessions dir created automatically if missing
- protocolFingerprint computed from exchanged message types
```

**TEST 5: AapProtocolRecorderTest** (integration, Robolectric)
```
- recordMessage() with recording disabled → no-op (buffer not written to)
- recordMessage() calls sanitize → skeleton → buffer.write pipeline
- onError() flushes buffer to disk + starts 10-min forward capture
- onError() writes metadata with error type and frame index
- stopRecording() without error → discards buffer (no file written)
- new connection → new session ID
- BuildConfig.DEBUG=true → recording auto-enabled
- BuildConfig.DEBUG=false → recording requires settings opt-in
```

### Implementation

| # | File | Action | Lines |
|---|------|--------|-------|
| 1.1 | `app/src/main/java/.../testing/SessionAnonymizer.kt` | Create. Pure function `sanitize(msg, salt): AapMessage`. Protobuf-aware per message type. | ~200 |
| 1.2 | `app/src/main/java/.../testing/SkeletonFilter.kt` | Create. Pure function `skeleton(msg): SessionFrame?`. Channel policy map. | ~40 |
| 1.3 | `app/src/main/java/.../testing/SessionFrame.kt` | Create. Data class: timestamp, direction, channel, type, payload. Binary serialization. | ~60 |
| 1.4 | `app/src/main/java/.../testing/RollingBuffer.kt` | Create. Ring buffer with 5-min window. Thread-safe (called from HandlerThread). | ~80 |
| 1.5 | `app/src/main/java/.../testing/SessionWriter.kt` | Create. Writes .aapsession + .meta.json to externalFilesDir. File rotation (500MB cap). | ~120 |
| 1.6 | `app/src/main/java/.../testing/AapProtocolRecorder.kt` | Create. Orchestrator: sanitize → skeleton → buffer. Error signal → flush + forward capture. | ~100 |
| 1.7 | `AapMessageHandlerType.kt` | Edit. Add 1 line in handle(): `recorder?.recordMessage(message, Direction.FROM_CAR)` | +1 |
| 1.8 | `AapTransport.kt` | Edit. Add 1 line in send(): `recorder?.recordMessage(message, Direction.TO_CAR)` | +1 |
| 1.9 | `AapService.kt` | Edit. Create recorder instance, pass to transport/handler, create notification channel, manage lifecycle. | +8-10 |
| 1.10 | `Settings.kt` | Edit. Add `sessionRecordingEnabled` preference (key: `"session-recording-enabled"`, default: false). | +3 |
| 1.11 | `App.kt` | Edit. Create notification channel `"headunit_session_recording"`. | +5 |
| 1.12 | Tests | Create 5 test files per TDD sequence above. | ~400 |

### Acceptance Criteria
- `./gradlew testGithubDebugUnitTest --tests "*SessionAnonymizer*"` — all green
- `./gradlew testGithubDebugUnitTest --tests "*AapProtocolRecorder*"` — all green
- security-reviewer passes on SessionAnonymizer (all PII fields from proto analysis covered)
- kotlin-reviewer passes on all .kt files
- No file written to disk during normal recording (only on error/manual flush)

### Verification Gate
gsd-verifier confirms:
1. Sanitizer strips all PII fields identified in proto analysis
2. Rolling buffer retains exactly 5 min window
3. Error signal produces .aapsession file with pre+post error frames
4. Recording is no-op when disabled
5. Mic channel (7) is never recorded

---

## Phase 2: Replay Harness + Session Management (6h)

**Goal:** Drop .aapsession files into tests. Compare against known-good.
Users manage/preview/share sessions from Settings > Debug.

### TDD Sequence

**TEST 6: AapReplayHarnessTest**
```
- replay(session) feeds FROM_CAR frames to handler, captures TO_CAR responses
- replay() returns ReplayResult with response frames + timing
- replay() detects divergence at exact frame index vs golden
- replay() handles sessions with HEADER_ONLY frames (skip A/V, replay control)
```

**TEST 7: DifferentialAnalyzerTest**
```
- compare(session1, session2) finds first divergence point
- compare() returns null when semantically identical
- protocolEquivalent() ignores timestamps, matches channel+type+payload
- compare() handles different-length sessions
- compare() reports divergence type (missing frame, different payload, extra frame)
```

**TEST 8: SessionReaderTest**
```
- readSession() parses .aapsession file correctly
- readSession() validates magic + checksum
- readSession() rejects corrupted files gracefully (no crash)
- readMetadata() parses .meta.json
- listSessions() returns all sessions sorted by date
- deleteSession() removes both .aapsession and .meta.json
- deleteAllSessions() clears sessions directory
- totalStorageUsed() returns accurate byte count
```

**TEST 9: ShareFlowTest** (Robolectric)
```
- shareSession() creates intent with FileProvider URI
- shareSession() sets correct MIME type
- shareSession() adds FLAG_GRANT_READ_URI_PERMISSION
- shareSession() uses Intent.createChooser
```

### Implementation

| # | File | Action | Lines |
|---|------|--------|-------|
| 2.1 | `app/src/test/java/.../testing/AapReplayHarness.kt` | Create. Replay engine: feeds frames to handler mock, captures responses. | ~150 |
| 2.2 | `app/src/test/java/.../testing/DifferentialAnalyzer.kt` | Create. Session comparison with divergence detection. | ~80 |
| 2.3 | `app/src/main/java/.../testing/SessionReader.kt` | Create. Parse .aapsession + .meta.json. List/delete/storage operations. | ~100 |
| 2.4 | `app/src/main/java/.../testing/SessionSharer.kt` | Create. FileProvider URI + ACTION_SEND intent. Reuse LogExporter pattern. | ~30 |
| 2.5 | `SettingsFragment.kt` | Edit. Add to Debug section: "Session Recording" toggle, "Manage Sessions" button → AlertDialog with ListView. Per-session: tap=preview dialog, long-press=delete. "Share" and "Delete All" buttons. | +40-50 |
| 2.6 | `app/src/main/res/xml/provider_paths.xml` | Edit. Add `<external-files-path name="sessions" path="sessions/" />` if not already covered by existing `path="."`. | +1 (verify) |
| 2.7 | `app/src/test/resources/sessions/golden/` | Create. Synthetic golden session for bootstrap test. | ~1 file |
| 2.8 | `app/build.gradle.kts` | Edit. Verify test deps added in Phase 0 are sufficient. | verify |
| 2.9 | Tests | Create 4 test files per TDD sequence above. | ~300 |

### Acceptance Criteria
- Replay harness replays golden session → identical response sequence
- Differential analyzer catches mutated golden → divergence at known point
- Session list shows in Settings > Debug > Manage Sessions dialog
- Share flow opens Android share sheet with .aapsession file
- Delete All clears all sessions with confirmation dialog
- Preview shows metadata JSON (no raw payload)
- `./gradlew testGithubDebugUnitTest --tests "*Replay*"` — all green
- `./gradlew testGithubDebugUnitTest --tests "*Differential*"` — all green

### Verification Gate
gsd-verifier confirms:
1. Replay produces deterministic output from same input
2. Differential analyzer correctly identifies divergence point
3. Share uses FileProvider (not file:// URI)
4. Delete All actually removes files from disk
5. No new Activities or Fragments created (all in dialogs within SettingsFragment)

---

## Phase 3: Mutation Fuzzing + Shake-to-Share (4h)

**Goal:** Each passing session generates 100+ edge-case tests via mutation.
Shake device to instantly share current session.

### TDD Sequence

**TEST 10: MutationTest** (parameterized across 6 mutation types)
```
- BitFlipMutation(rate=0.01) on golden → replay doesn't crash (Success or ProtocolError)
- TimingJitterMutation(maxMs=500) → no state corruption
- MessageDropMutation(rate=0.05) → graceful degradation
- MessageReorderMutation(windowSize=3) → no crash
- MessageDuplicationMutation(rate=0.1) → no crash
- TruncationMutation(maxTruncate=50%) → no crash
- ALL: result is Success OR ProtocolError, NEVER Crashed/StateCorrupted
```

**TEST 11: ShakeDetectorTest**
```
- Shake above threshold → callback within 1s
- Movement below threshold → no callback (driving vibration filter)
- Cooldown: no double-trigger within 3s
- Disabled when recording is off → no callback
- start()/stop() lifecycle matches service lifecycle
```

**TEST 12: Visual Feedback Test**
```
- On shake detected: brief Toast/overlay confirming "Session captured"
- If recording disabled: Toast explaining "Enable session recording in Settings > Debug"
- Share dialog shows: [Preview] [Share] [Cancel] — no ambiguity
```

### Implementation

| # | File | Action | Lines |
|---|------|--------|-------|
| 3.1 | `app/src/test/java/.../testing/mutations/Mutation.kt` | Create. Interface + 6 implementations. | ~120 |
| 3.2 | `app/src/test/java/.../testing/AapMutationTests.kt` | Create. @Parameterized: 6 mutations x golden = 600+ test runs. | ~60 |
| 3.3 | `app/src/main/java/.../testing/ShakeDetector.kt` | Create. TYPE_ACCELEROMETER listener, threshold=12 m/s^2, cooldown=3s. Lifecycle-aware (start/stop). | ~70 |
| 3.4 | `AapService.kt` | Edit. Register ShakeDetector on connection, trigger flush+share dialog on shake. | +5 |
| 3.5 | `AapProjectionActivity.kt` | Edit. Show share dialog on shake callback (needs Activity context for dialog). | +10 |
| 3.6 | Tests | Create 3 test files per TDD sequence. | ~200 |

### Shake UX Flow
```
User shakes device while projection active
    │
    ▼
ShakeDetector fires callback (threshold: 12 m/s², cooldown: 3s)
    │
    ├─ Recording OFF → Toast: "Enable session recording in Settings > Debug"
    │                   (calm, helpful, not pushy — one line, fades in 3s)
    │
    └─ Recording ON → recorder.onManualFlush()
                       │
                       ▼
                    Brief overlay: "Session captured ✓" (1.5s fade)
                       │
                       ▼
                    AlertDialog:
                    ┌─────────────────────────────┐
                    │ Share Session?               │
                    │                              │
                    │ 15 min captured              │
                    │ Connection: USB              │
                    │ No personal data included    │
                    │                              │
                    │ [Preview] [Share] [Dismiss]  │
                    └─────────────────────────────┘
                       │
                       ├─ Preview → metadata dialog
                       ├─ Share → Android share sheet
                       └─ Dismiss → session saved locally
```

### Acceptance Criteria
- 6 mutation types x golden session = 600+ test runs, zero crashes
- All mutations produce Success or ProtocolError (never Crashed)
- Shake detection: no false triggers at threshold=12 during simulated driving vibration
- Share dialog appears within 1s of shake
- `./gradlew testGithubDebugUnitTest --tests "*Mutation*"` — all green

### Verification Gate
gsd-verifier confirms:
1. Every mutation type tested
2. No Crashed results in any mutation run
3. Shake threshold prevents false triggers
4. Visual feedback is non-intrusive (Toast, not blocking dialog)

---

## Phase 4: State Machine Mining (GATED)

**Hard gate:** `find sessions/ -name "*.aapsession" | wc -l` must return 50+.
Before that, ROI is negative. Do NOT start.

**Goal:** Extract protocol state machine from corpus. Generate novel test paths.

### TDD Sequence (write when gate passes)

**TEST 13: StateMachineExtractorTest**
```
- extractFrom(sessions) identifies distinct protocol states
- extractFrom() maps transitions with frequency counts
- Generated paths satisfy: every transition exists in mined machine
- Property test: 1000 generated paths pass protocol invariants
```

### Implementation (when gate passes)

| # | File | Action |
|---|------|--------|
| 4.1 | `app/src/test/java/.../testing/StateMachineExtractor.kt` | Create. Mine states + transitions from session corpus. |
| 4.2 | `app/src/test/java/.../testing/StateMachinePropertyTests.kt` | Create. Kotest property-based path generation. |

---

## Dependency Map

```
Phase 0 (Bootstrap) ──→ Phase 1 (Capture + Privacy)
                              │
                              ▼
                         Phase 2 (Replay + Session Mgmt)
                              │
                              ▼
                         Phase 3 (Fuzzing + Shake)
                              │
                              ▼ (gated: 50+ sessions)
                         Phase 4 (State Mining)
```

## File Impact Summary

### New Files (13 production + 12 test)
```
Production (app/src/main/java/.../testing/):
├── SessionAnonymizer.kt        # Phase 1 — PII stripping (pure function)
├── SkeletonFilter.kt           # Phase 1 — channel-aware depth (pure function)
├── SessionFrame.kt             # Phase 1 — binary frame format
├── RollingBuffer.kt            # Phase 1 — 5-min ring buffer
├── SessionWriter.kt            # Phase 1 — disk write + rotation
├── AapProtocolRecorder.kt      # Phase 1 — orchestrator
├── SessionReader.kt            # Phase 2 — parse + list + delete
├── SessionSharer.kt            # Phase 2 — FileProvider + share intent
└── ShakeDetector.kt            # Phase 3 — accelerometer gesture

Test (app/src/test/java/.../testing/):
├── CanaryTest.kt               # Phase 0
├── ContextTest.kt              # Phase 0
├── SessionAnonymizerTest.kt    # Phase 1
├── SkeletonFilterTest.kt       # Phase 1
├── RollingBufferTest.kt        # Phase 1
├── SessionWriterTest.kt        # Phase 1
├── AapProtocolRecorderTest.kt  # Phase 1
├── AapReplayHarnessTest.kt     # Phase 2 (includes harness impl)
├── DifferentialAnalyzerTest.kt # Phase 2 (includes analyzer impl)
├── SessionReaderTest.kt        # Phase 2
├── ShareFlowTest.kt            # Phase 2
├── AapMutationTests.kt         # Phase 3
├── mutations/Mutation.kt       # Phase 3 (interface + 6 impls)
└── ShakeDetectorTest.kt        # Phase 3
```

### Edited Files (minimal diffs)
```
AapMessageHandlerType.kt   — +1 line  (recorder hook, inbound)
AapTransport.kt            — +1 line  (recorder hook, outbound)
AapService.kt              — +10 lines (recorder lifecycle, shake detector)
AapProjectionActivity.kt   — +10 lines (shake share dialog)
Settings.kt                — +3 lines  (recording preference)
App.kt                     — +5 lines  (notification channel)
SettingsFragment.kt         — +50 lines (debug section: toggle + session mgmt)
app/build.gradle.kts       — +4 lines  (test dependencies)
provider_paths.xml          — +1 line   (sessions path, if needed)
```

## Agent Dispatch Per Phase

| Phase | Agent | Trigger |
|-------|-------|---------|
| 0 | Haiku (mechanical) | Scaffold dirs, add deps, canary test |
| 1-3 | tdd-guide | Write all tests FIRST |
| 1-3 | Sonnet | Implement to make tests green |
| 1-3 | kotlin-reviewer | Every .kt file (MUST) |
| 1 | security-reviewer | SessionAnonymizer PII coverage |
| 2 | security-reviewer | Share flow (no URI leaks) |
| 1-3 | gsd-verifier | Each phase boundary |
| 1-3 | kotlin-build-resolver | If build breaks |
| Any | docs-lookup (context7) | Protobuf API, NotificationChannel, SensorManager API |

## Risk Register

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Protobuf deserialization fails on unknown message types | HIGH | Blocks anonymizer | Fallback: zero payload, keep header. Test with synthetic unknown types. |
| Rolling buffer OOM on 1-2GB RAM tablets | MEDIUM | App crash | Cap buffer at 10MB. 5 min of control+sensor headers ≈ 2-5MB. |
| Shake false triggers during driving | MEDIUM | User frustration | Threshold 12 m/s² + 3s cooldown + require 3 consecutive spikes within 500ms |
| Session files bloat storage | MEDIUM | User complaints | 500MB cap + 7-day auto-delete + storage display in UI |
| FileProvider URI not readable by all share targets | LOW | Share fails | Use FLAG_GRANT_READ_URI_PERMISSION + createChooser (proven pattern in LogExporter) |
| Phase 4 state mining produces artifacts from small corpus | HIGH if gate bypassed | False confidence | Hard gate at 50 sessions. No override. |
