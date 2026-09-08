# Fix 11 latent bugs: audio sequencing, busy-spin, transport OOM, main-thread ANR, service recovery

**Branch:** `feature/bugfix-pass` → `master`
**Base commit:** `eeb61c2` (fast-forwards cleanly)
**Commits:** 4 (`18254bd`, `bd03a52`, `97bb78a`, `430eb40`)
**Scope:** `app/src/main` only. 7 files, +85 / −16.

---

## Status / caveats

- **Not compile-verified locally.** The dev machine is missing Android Build-Tools `36.0.0`
  (AGP's default for `compileSdk 36`; installed: `35.0.1`, `36.1.0`) and has a cold Gradle
  dependency cache, and installing SDK components on this machine is not permitted. Every edit
  was reviewed by hand for compile correctness. **Please run a full `./gradlew assembleDebug`
  before merge.**
- No API, protocol, or wire-format changes. `AsrVersion` / `TopicId` / `PacketSerializer`
  formats are untouched.
- No dependency changes.
- The pre-existing uncommitted `.idea/misc.xml` edit is **not** part of this branch.

---

## What was fixed

### 1. Audio raw-PCM stream reused the telemetry topic's sequence counter
**File:** `audio/AudioRuntime.kt` (`seq` / new `rawSeq` @ L36, use @ L212)
**Severity:** High (silent data-integrity signal corruption)

`readLoop()` publishes two packets per buffer: an `AUDIO` telemetry packet and a raw-PCM
packet on `RESERVED_10`. Both called `seq.getAndIncrement()` on the **same** `AtomicInteger`,
so within each iteration one topic got an even seq and the other an odd seq. A receiver doing
per-topic gap detection (the header contract: *"seq — per-topic sequence number (bridge
detects gaps)"*) sees every topic's sequence jump by 2 forever and reports a **permanent
~50 % packet-loss / continuous-gap condition on both audio streams**.

**Fix:** dedicated `private val rawSeq = AtomicInteger(0)` for the `RESERVED_10` stream, so
each topic's sequence increments by exactly 1. Matches how every other module (IMU, GPS,
battery, camera, environment) already scopes one counter per topic.

**Risk:** none — the raw stream's sequence now simply starts at 0 and counts up.

---

### 2. Microphone capture loop busy-spins on an `AudioRecord` error
**File:** `audio/AudioRuntime.kt` (L155–163)
**Severity:** High (100 % CPU / battery drain, no recovery)

```kotlin
val read = recorder.read(buffer, 0, buffer.size)
if (read <= 0) continue          // BEFORE
```

`AudioRecord.read()` returns **negative error codes** (`ERROR_INVALID_OPERATION = -3`,
`ERROR_BAD_VALUE = -2`, `ERROR_DEAD_OBJECT = -6`, `ERROR = -1`). On e.g. `ERROR_DEAD_OBJECT`
(recorder invalidated — route change, another app grabbed the mic, device hiccup) the loop
`continue`s immediately, forever, with no sleep and no recovery: a pegged CPU core until the
service is stopped.

**Fix:**
```kotlin
if (read == 0) continue                 // genuine "no data yet"
if (read < 0) {                          // error code → fatal
    snapshot = snapshot.copy(isRunning = false, lastError = "AudioRecord.read error $read")
    Log.e(TAG, "Microphone read returned error code $read — stopping capture loop")
    state = ModuleState.ERROR
    break
}
```
The module goes to `ERROR` (visible in module status / heartbeat) and the thread exits.
`stop()` still releases the recorder normally.

**Risk:** a transient error now stops capture instead of hanging. Restart is via the existing
`module.start` command / profile re-enable. Auto-retry with back-off could be a follow-up if
desired, but a spinning thread was strictly worse.

---

### 3. Inbound packet length is trusted → crash on a corrupt stream
**File:** `transport/TcpTransport.kt` (`readLoop` L197–204, new const L253–257)
**Severity:** High (remote-triggerable process crash)

```kotlin
val payloadLen = ByteBuffer.wrap(header, 20, 4).order(LITTLE_ENDIAN).int
val fullPacket = ByteArray(AsrVersion.HEADER_SIZE + payloadLen)   // BEFORE: unchecked
```

`payloadLen` comes straight off the wire. A corrupt or desynchronised stream (or a hostile
peer on the Wi-Fi transport) can make this:
- **negative** → `NegativeArraySizeException` (a `RuntimeException`, caught, but drops the
  connection every 200 ms in a tight reconnect loop), or
- **huge** (e.g. `0x7FFFFFFF`) → `OutOfMemoryError`. That's an `Error`, **not** an
  `Exception`, so the `catch (e: Exception)` below does **not** catch it → the read thread
  dies and, depending on allocation timing, the **process is killed**.

**Fix:** validate before allocating:
```kotlin
if (payloadLen < 0 || payloadLen > MAX_PAYLOAD_BYTES) {   // MAX = 8 MiB
    Log.w(TAG, "[TCP] Invalid inbound payload length $payloadLen — aborting read loop")
    break
}
```
Inbound packets are only `COMMAND` JSON (a few hundred bytes); 8 MiB is a deliberately
generous ceiling. On violation the read loop breaks and the outer loop re-syncs.

**Risk:** a legitimate inbound payload > 8 MiB would now be rejected — none exist in the
protocol. `break` leaves the socket open for the outer loop to retry; a hard socket close
would force a full reconnect but also perturbs the write thread, so the lighter-touch
recovery was chosen.

---

### 4. `reconnectCount` counts the first connection as a reconnect
**File:** `transport/TcpTransport.kt` (`hasConnectedOnce` L61 / L98 / L131–132)
**Severity:** Low (diagnostics only)

`writeLoop()` called `onReconnect?.invoke()` on **every** successful connect, including the
first, so `TransportManager.reconnectCount` (shown in the UI and the 1 Hz heartbeat) read `1`
immediately after a normal, clean connect.

**Fix:** `@Volatile private var hasConnectedOnce`; invoke `onReconnect` only when it's already
`true`. Reset to `false` in `disconnect()` so each transport lifecycle counts from zero (a
deliberate `reconnectTransport(host, port)` therefore does not self-inflate the counter).

**Risk:** none.

---

### 5. Framing errors were counted as CRC failures
**File:** `core/CommandDispatcher.kt` (`dispatch`, removed `notifyCrcFailure()` in the
`frame == null` branch)
**Severity:** Low (diagnostics only)

`AsrPacket.decode()` returns `null` for *too short* / *bad magic* — a framing problem, not a
checksum mismatch. The old code still bumped `totalCrcFailures`, conflating two very
different failure modes in the metric operators watch to spot line-quality problems.

**Fix:** the `frame == null` branch now just logs and returns. The genuine
`!frame.crcValid` branch still calls `notifyCrcFailure()`.

**Risk:** none. (If a dedicated "decode/framing error" counter is wanted, that's a small
follow-up.)

---

### 6. `ACTION_STOP` / `ACTION_RESTART` block the main thread (ANR risk)
**File:** `core/AsrService.kt` (`onStartCommand`, L357–358)
**Severity:** Medium (ANR under ADB/shortcut control)

`onStartCommand` runs on the main thread. `ACTION_STOP → handleStop()` and
`ACTION_RESTART → handleRestart()` were invoked inline, and both reach
`tcpTransport.disconnect()`, which does `writeThread.join(500)` + `readThread.join(500)` —
up to ~1 s of main-thread blocking. The file's own comment (in `handleTcpConnect`) already
warns: *"Do NOT call tcpTransport.disconnect() here directly — it calls Thread.join() which
would block the main/binder thread."* The public `stopRuntime()` / `restartRuntime()`
already hop to `Dispatchers.Default`; the intent path did not.

**Fix:**
```kotlin
ACTION_STOP    -> serviceScope.launch(Dispatchers.Default) { handleStop() }
ACTION_RESTART -> serviceScope.launch(Dispatchers.Default) { handleRestart() }
```
`ensureForeground()` still runs synchronously on the main thread first (required, and it's
fast), so the foreground-service deadline is still met.

**Risk:** `handleStop`/`handleRestart` now complete slightly asynchronously w.r.t. the
returning `onStartCommand`. This already matches the behaviour of the UI buttons (which call
the coroutine-wrapped public methods). All work they do (module start/stop, sensor register,
`Handler.post`) is safe off the main thread.

---

### 7. `START_STICKY` null-intent restart left the service dead
**File:** `core/AsrService.kt` (`onStartCommand`, L342–349)
**Severity:** Medium (defeats crash recovery + the Emergency Reset button)

`onStartCommand` returns `START_STICKY`, so after a process kill Android recreates the
service with **`intent == null`**. Every code path was gated on `intent?.action`, so on a
sticky restart: `ensureForeground()` never ran (no foreground notification — risk of
`RemoteServiceException` for a started FGS) and `handleStart()` never ran (no modules). This
also breaks `MainActivity`'s "Emergency Reset", which calls `Runtime.getRuntime().exit(0)`
and relies on the sticky restart to bring everything back.

**Fix:** a null intent is treated as "resume":
```kotlin
if (intent == null) {
    Log.i(TAG, "[ASR] Sticky restart (null intent) — restoring foreground runtime")
    ensureForeground()
    handleStart()
    return START_STICKY
}
```
`handleStart()` already no-ops if `isRunning`, and defers to `pendingStart` if the camera
provider isn't ready yet.

**Risk:** low. A sticky restart now always re-enters the foreground + running state, which is
the documented intent of `START_STICKY` here.

---

### 8. First `speaker.speak` after auto-start always fails
**File:** `core/AsrService.kt` (`speaker.speak` handler, L441–447)
**Severity:** Low–Medium (first TTS command lost)

The handler did:
```kotlin
if (speakerManager.state == ModuleState.STOPPED) { speakerManager.start() }
val success = speakerManager.speak(text)   // isTtsReady still false
```
`SpeakerManager.start()` only constructs `TextToSpeech`; readiness arrives later on the main
thread via `onInit`. So the immediately-following `speak()` sees `isTtsReady == false`,
increments `failedCount`, and returns `Result.failure("Speaker not ready")`. The "auto-start"
convenience never actually works on the first call — the caller must send the command twice.

**Fix:** after an auto-start, briefly wait (poll `isTtsReady`, 50 ms steps, ≤ 2 s) before
calling `speak()`. This runs on the single-threaded TCP dispatch thread; the bounded wait
only happens once (first speak after a cold auto-start).

**Risk:** up to 2 s of dispatch-thread stall in that one narrow window. Commands are
infrequent and this path is already the exception (during normal startup the speaker module
is started by `startInternal()` well before any command arrives). Acceptable trade for not
silently dropping the command.

---

### 9. Torch/camera fields read across threads without a memory barrier
**File:** `camera/CameraManager.kt` (`imageAnalysis` L62, `camera` L78)
**Severity:** Medium (torch command intermittently no-ops)

`camera` and `imageAnalysis` are assigned inside `mainExecutor.execute { … }` when the camera
binds. `setTorch()` reads `camera` from the **binder thread** (`ACTION_FLASHLIGHT` in
`onStartCommand`) and the **TCP dispatch thread** (`flashlight.set` command handler). With no
`@Volatile`/synchronisation, those threads can observe a stale `null` after the camera is
already bound → `setTorch()` returns `false` and the light never comes on (or, less often, a
stale non-null after `stopCamera()`).

**Fix:** `@Volatile` on both fields. Cheap, correct for single-writer / multi-reader
reference publication.

**Risk:** none.

---

### 10. `MainViewModel.setService()` leaks collector coroutines on every call
**File:** `ui/MainViewModel.kt` (`serviceJobs` L34, cancel L43–44, tracked launches L47–57)
**Severity:** Medium (accumulating infinite loops after a service crash)

Each `setService(nonNull)` launched **four** coroutines in `viewModelScope` — three
`StateFlow` collectors plus an infinite `while (true) { … delay(1000) }` module-status poll —
and never cancelled the ones from a previous call. `ServiceConnection.onServiceConnected` can
fire again after the service process dies and is recreated (`BIND_AUTO_CREATE`), so each
rebind stacks another full set. N infinite loops then race to write the same `StateFlow`s
until the Activity/ViewModel is destroyed.

**Fix:** collect the `Job`s into `serviceJobs`; at the top of `setService()` cancel and clear
them before (re)launching. The `service == null` branch now also cancels, so a disconnect
stops the poll loop immediately instead of leaving it running against a stale captured
reference.

**Risk:** none — same coroutines, now with a defined lifecycle.

---

### 11. Battery percentage unguarded against a missing `EXTRA_SCALE`
**File:** `battery/BatteryManager.kt` (L46)
**Severity:** Low

```kotlin
_level = level * 100 / scale.toFloat()   // BEFORE
```
`EXTRA_LEVEL` / `EXTRA_SCALE` default to `-1` when absent. `scale == -1` yields a **negative**
percentage; a hypothetical `scale == 0` yields `NaN` / `+Inf`. That value is broadcast in the
`BATTERY` packet and shown in `logStatus()`.

**Fix:**
```kotlin
_level = if (level >= 0 && scale > 0) level * 100f / scale else -1f
```
`-1f` is the existing "unknown" sentinel (matches the field's initial value).

**Risk:** none.

---

## What was NOT fixed (deliberately deferred)

### A. Raw PCM is streamed unconditionally, with no subscriber and no back-pressure interlock
**File:** `audio/AudioRuntime.kt` `readLoop()` (~L206–216)
**Severity:** Medium — throughput / design

Every buffer, the loop allocates a fresh `ByteArray` via `PacketSerializer.audio(buffer, read)`
and calls `tm.send()` on `RESERVED_10`, **on top of** the `audioLevel` packet, whether or not
any desktop/ROS client wants raw audio. That's a sustained ~256 kbit/s (16 kHz × 16-bit mono)
plus a doubled audio packet rate, sharing one `TransportManager` / TCP queue with camera
frames. If the transport queue backs up, `DROP_OLDEST` will start discarding **camera**
frames to make room for audio, and the extra allocation churn runs on the audio thread.

**Why deferred:** needs a product decision — a capability/subscription flag ("client wants
raw audio"), a separate transport/queue for bulk audio, or a config toggle. Not a
localised bug fix.

**Suggested direction:** gate the raw-PCM send on a runtime flag (default off) set by a
`COMMAND` (`audio.raw_stream` on/off), mirroring how `DevModeConfig` flags are toggled.

---

### B. `MainActivity` STOP / RESTART buttons are silent no-ops before the service binds
**File:** `MainActivity.kt` (`btnStopRuntime` / `btnRestartRuntime` `setOnClickListener`, ~L233–239)
**Severity:** Low — UX

These call only `viewModel.stopRuntime()` / `restartRuntime()`, which delegate to
`asrService?.…` and do nothing while `asrService` is null (before `onServiceConnected`, or
after a service-process death not yet rebound). RESTART additionally shows a
"Restarting ASR Runtime…" toast **unconditionally**, so the user gets positive feedback when
nothing happened. (START and the transport buttons already guard on `isServiceBound` or fall
back to `startServiceIntent(...)`.)

**Why deferred:** low impact (the service binds within a frame or two of `onCreate`), and the
clean fix is a small UX refactor — disable the buttons until `isServiceBound`, or fall back
to `startServiceIntent(ACTION_STOP / ACTION_RESTART)` like the START button does.

---

### C. Camera-pipeline latency mixes two clock domains
**Files:** `diagnostic/BenchmarkProcessor.kt` (`process()`, ~L65–67),
`camera/CameraPipeline.kt` (`process()`, ~L179–181)
**Severity:** Low — diagnostics only

`latencyMs` subtracts `image.imageInfo.timestamp` (CameraX frame clock) from
`SystemClock.elapsedRealtimeNanos()` / `System.nanoTime()`. On most devices CameraX uses the
same base so the number is roughly right, but it is not guaranteed and can read as a
nonsensical value on devices where the camera timestamp is `SENSOR` / `UPTIME`-based.

**Why deferred:** cosmetic (a logged/telemetry number), and doing it properly means pinning
the CameraX timestamp source (`Camera2Interop` `SENSOR_INFO_TIMESTAMP_SOURCE`) and
converting — more than a one-liner, with device-specific testing.

---

### D. `AudioRecord` error now stops capture with no automatic retry
Follow-up to fix #2. A transient `ERROR_DEAD_OBJECT` (common on audio-route changes) now
leaves the module in `ERROR` until something re-issues `module.start`. A bounded auto-retry
with back-off (e.g. 3 attempts, 250 ms → 1 s → 2 s) would make it self-healing. Left out to
keep this PR to minimal, obviously-correct changes.

---

### E. Foreground-service type vs. runtime permissions
**File:** `core/AsrService.kt` `ensureForeground()` (~L554–565)

`startForeground(…, TYPE_CAMERA or TYPE_MICROPHONE or TYPE_LOCATION)` is always requested
with all three types. On Android 14+ a started FGS with type `microphone`/`camera`/`location`
throws `SecurityException` if the matching runtime permission isn't granted. The UI path
gates on `allPermissionsGranted()` first, but the ADB-broadcast / shortcut `ACTION_START`
path does not. Not observed as a live crash in the current flows, so left as a hardening
follow-up: compute the type mask from actually-granted permissions.

---

## Commit breakdown

| Commit | Fixes | Files |
|---|---|---|
| `18254bd` `fix(audio): …` | 1, 2 | `AudioRuntime.kt` |
| `bd03a52` `fix(transport): …` | 3, 4, 5 | `TcpTransport.kt`, `CommandDispatcher.kt` |
| `97bb78a` `fix(service): …` | 6, 7, 8 | `AsrService.kt` |
| `430eb40` `fix(camera/ui/battery): …` | 9, 10, 11 | `CameraManager.kt`, `MainViewModel.kt`, `BatteryManager.kt` |

## Suggested review / test focus

- **Build the APK** (blocked locally — see caveats).
- Transport: point the read loop at a peer that sends a deliberately bad length; confirm no
  crash, clean re-sync.
- Audio: on a device, revoke the mic mid-stream (or trigger a route change) → module goes
  `ERROR`, CPU stays flat.
- Audio seq: confirm the ROS bridge / desktop monitor no longer reports gaps on `/audio`
  and the raw stream.
- Service: `adb shell am start-foreground-service -a org.asr.runtime.RESTART` → no ANR;
  kill the process → sticky restart comes back foregrounded and running.
- Torch: `flashlight.set {"on":true}` immediately after camera start.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
