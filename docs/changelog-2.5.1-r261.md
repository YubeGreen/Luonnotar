# Luonnotar 2.5.1 r261 — provider-first engine lifecycle control

## Why r261 exists

Real-device OriginOS testing of r260 showed that explicit `am broadcast -W`
commands targeting multiple `android.permission.DUMP`-protected manifest
receivers could return only `result=0` without executing the receiver. The
shell itself still held `android.permission.DUMP` (UID 2000, permission check
result 0), while the existing synchronous `adb_runtime_config` ContentProvider
worked immediately and started the `:keeper` process.

The problem is therefore treated as a transport/reliability issue rather than
an authorization issue.

## Provider-first control plane

The existing exported provider remains protected by
`android.permission.DUMP`, and `Binder.getCallingUid()` still restricts calls
to this app, root, system or shell. r261 adds:

- `engine_status`
- `engine_restart`

`engine_status` authenticates the loopback engine using the stored random token
and accepts the runtime as reachable only when the reply identifies the
Luonnotar engine running as UID 2000. It reports the live PID, actual revision,
expected revision, handoff support, pairing state and whether the revision has
converged.

`engine_restart` dispatches the existing controlled restart path without
resetting the persisted Kadb identity. r260+ engines use authenticated hot
handoff. Older or unreachable engines fall back to the existing local-ADB
startup path. Pairing state is reset only by the pre-existing code path for a
real adbd authorization failure.

The old broadcast receiver remains in the manifest as a compatibility path,
but host tooling no longer depends on it.

## Host commands

```bash
adb -s SERIAL shell content call \
  --uri content://com.yubegreen.luonnotar.adb_runtime_config \
  --method engine_status
```

```bash
adb -s SERIAL shell content call \
  --uri content://com.yubegreen.luonnotar.adb_runtime_config \
  --method engine_restart
```

The repository helper `tools/adb-embedded-engine-control.sh` uses these provider
methods and polls `engine_status` after restart until the live engine reports the
current revision.

## Identity

- App: 2.5.1
- versionCode: 83
- embedded engine revision: 261
- minimum hot-handoff engine revision: 260
- status schema: 20
