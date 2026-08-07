# Luonnotar 2.5.1 r260 — embedded-engine hot handoff

## Why this revision exists

A real r259 APK replacement left the already-running privileged engine on the
previous r257 code. The APK was 2.5.1 while the long-lived `app_process` still
reported engine revision 257 and continued the old multi-round MCS recovery
loop.

This is expected from the process ownership boundary: PackageManager replaces
and kills the package appId, while Luonnotar's privileged engine is intentionally
spawned by adbd as shell UID 2000. The shell process is not the package's app
process and can therefore outlive an APK replacement while keeping the old APK
mapped.

r260 makes that lifecycle explicit instead of assuming package replacement kills
the privileged engine.

## Hot handoff protocol

r260 adds authenticated `handoff` to the existing loopback engine protocol.
The currently running shell engine can now:

1. verify that the requested APK path is the currently installed Luonnotar APK;
2. capture its exact PID plus `/proc/<pid>/stat` start-time ticks;
3. spawn a detached successor shell that waits for that exact process instance
   to disappear;
4. stop and release the singleton engine lock;
5. let the successor load the new APK with the same loopback port and 256-bit
   token;
6. require the app side to observe the new PID/current revision and run normal
   configuration before declaring the handoff successful.

The successor never uses `pkill` and does not kill the old engine. Waiting on
PID + start-time prevents PID reuse from being mistaken for the old instance.
`SO_REUSEADDR` is enabled on the loopback listener so the verified successor can
reuse the same endpoint without rotating identity.

## First upgrade into r260

r259 and older engines do not understand `handoff`. r260 therefore treats them
as a one-time legacy case:

- if the old authenticated loopback engine is reachable, Luonnotar asks it to
  stop using the already-existing `destroy` protocol operation;
- the normal embedded ADB starter then reuses the persisted Kadb host identity
  and launches the new APK;
- persisted pairing state and ADB key material are not cleared merely because
  the engine revision is old;
- re-pairing is requested only if adbd itself reports an authorization failure.

This means an ordinary engine restart or APK replacement should not require a
new pairing code.

## Shell-only ADB control entry

A new exported receiver is guarded by `android.permission.DUMP`, matching the
project's existing ADB-only diagnostic surfaces. It is intended for `adb shell`,
not ordinary third-party apps.

Status:

```sh
adb shell am broadcast -W \
  -a com.yubegreen.luonnotar.action.ADB_ENGINE_STATUS \
  -n com.yubegreen.luonnotar/.receiver.AdbEmbeddedEngineControlReceiver
```

Controlled restart:

```sh
adb shell am broadcast -W \
  -a com.yubegreen.luonnotar.action.ADB_ENGINE_RESTART \
  -n com.yubegreen.luonnotar/.receiver.AdbEmbeddedEngineControlReceiver
```

Status performs a short direct loopback ping instead of trusting cached Store
state, so it can reveal an APK/engine revision mismatch.

## Package replacement

`ACTION_MY_PACKAGE_REPLACED` now requests an engine restart instead of first
marking the shell runtime dead. For r260+ this uses hot handoff. For a pre-r260
engine it falls back to the persisted local-ADB startup path described above.
Real device reboot handling is unchanged because a shell `app_process` cannot
survive a kernel reboot.

## Safety boundaries

- The handoff command is authenticated by the existing loopback token.
- The shell engine accepts only the currently installed Luonnotar package path.
- The singleton file lock still gates the privileged engine.
- A successor starts only after the exact old PID/start-time instance has gone.
- No ADB identity reset occurs on revision mismatch, handoff unavailability, or
  normal restart.
- Authorization reset remains confined to the existing explicit adbd
  authorization-failure path.
- The ADB lifecycle receiver requires `android.permission.DUMP`.

## Version

- App: 2.5.1
- versionCode: 82
- Embedded engine revision: 260
- Status schema: 20
- Bridge strategy: `atomic_group_defense_episode`
