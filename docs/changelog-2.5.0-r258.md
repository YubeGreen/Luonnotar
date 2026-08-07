# Luonnotar 2.5.0 r258 — atomic freezer-command ownership

## Why r257 needed a corrective revision

The first r257 iQOO run proved that direct cgroup observation was the correct
trigger: verified bridge thaws were repeatedly followed by successful MCS 5228
reconnects. It also exposed several correctness gaps that could make the result
look better or worse than the actual freezer state:

1. GMS main and `com.google.android.gms.persistent` were recovered one after the
   other. OriginOS could refreeze one process while the other was still being
   handled.
2. `freezeRc=255` was common, but command output was discarded. Exit 255 is only
   the shell representation of a negative command result; it does not establish
   whether the framework changed any internal state.
3. A recovery was labelled adopt-release merely because the freeze phase ran.
   It did not prove that the PID appeared in ActivityManager's freezer ledger.
4. The bridge protocol heartbeat could be delayed while Kotlin held its main
   service lock during a long tuning pass. The supervisor then restarted a live
   shell after 23 seconds.
5. The vendor-lock fallback could reactivate the older Kotlin fast-thaw loop,
   creating two unfreeze command owners.
6. The retired `tools/device/luonnotar-guardian-v2.sh daemon` has its own
   `am unfreeze` loop and could survive from an older installation.
7. A cached PID/cgroup path was not sufficient identity proof; Android can reuse
   PIDs after a process exits.

## r258 design

### One configured owner, even during transitions

For Vivo GMS and the configured WhatsApp/Signal bridge targets, command
ownership follows policy rather than subprocess readiness. Kotlin pollers,
fast-thaw, MCS pre-thaw, recovery campaigns, and the r256 fast lane remain
observers while the bridge is starting, running, restarting, or cooling down.
They cannot reclaim `freeze`/`unfreeze` commands during a temporary heartbeat
or process transition.

The embedded app_process engine already has a kernel file lock. r258 adds a
second, narrower owner lease for freezer commands:

- `/data/local/tmp/luonnotar-freezer-command-owner`
- atomic lock directory beside it
- a unique heartbeat file containing parent/shell PIDs and both `/proc` start times
- owner validation before every write-capable bridge phase

A stale bridge that later resumes notices that it lost the lease and exits. A
new bridge refuses to start while a live, fresh owner exists. PID liveness alone
is insufficient: every owner and heartbeat comparison also requires the original
`/proc/<pid>/stat` start time, so a reused PID cannot inherit command ownership.

### GMS is a strict group

GMS main and persistent are now handled as one group:

1. Resolve both exact PIDs and verify `/proc/<pid>/cmdline`.
2. Issue the first release attempt to both PIDs concurrently.
3. Verify the physical cgroup state of both processes.
4. For only the still-frozen members, attempt framework adoption concurrently.
5. Before forcing an adoption, capture ActivityManager's freezer ledger. If a
   still-frozen PID is already listed, skip the extra freeze and retry only the
   framework release; this avoids gratuitous Binder-freezer transitions.
6. For only the still-frozen, ledger-missing members, attempt framework
   adoption concurrently and verify whether every required PID actually appears.
7. Release both members concurrently again.
8. Count success only when both exact processes are present and physically
   thawed.
9. Optionally apply sticky-unfrozen state, then recheck both cgroups after a
   stability delay.
10. Start one MCS reconnect window only after group verification.

A missing, identity-mismatched, unknown, or refrozen peer is a failed group
recovery. It cannot be hidden by success on the other process.

### Honest adoption evidence

`adoptObserved=true` now means ActivityManager's `Apps frozen` section actually
listed every PID that required adoption. A zero command exit code alone is not
accepted as evidence. Every failed PID/name/fallback command records bounded,
sanitized stdout/stderr in `vendor_bridge_diagnostic`, and the recovery event
retains a compact phase-by-phase detail string.

### Independent liveness

The file heartbeat runs in a separate shell child and uses a path unique to the
bridge shell PID. Kotlin restarts the bridge only when both the protocol
heartbeat and the file heartbeat are stale. A delayed protocol reader is logged
as delay, not treated as process death.

### Legacy daemon quarantine without broad process killing

The engine audits the known legacy PID file and the process table. It sends a
signal only when all of these remain true immediately before the signal:

- one command-line token is exactly `luonnotar-guardian-v2.sh`;
- its next token is exactly `daemon`;
- `/proc/<pid>/stat` start time is unchanged;
- `/proc/<pid>/cmdline` is unchanged.

The same identity is rechecked before a possible SIGKILL. Generic `sh`,
`app_process`, unrelated scripts, and reused PIDs are never selected. The
retired script itself also validates the modern owner lease before daemon
startup, before every unfreeze, and before each cycle; it therefore fails
closed even if somebody launches it between the engine's periodic audits.

## Safety boundaries

- No direct cgroup write was added.
- WhatsApp and Signal use bounded, non-destructive bridge retries; they do not
  trigger GMS force-stop or transport rebuild campaigns.
- GMS destructive escalation starts only after a verified vendor lock and an
  unhealthy observable MCS state.
- Sticky commands are skipped when sticky mode is disabled.
- A recovery command owner is validated before each write-capable phase.
- The bridge's ready record is rejected unless shell PID, unique heartbeat path,
  and fixed owner path match the process that Kotlin started.

## Runtime audit

Read-only:

```bash
tools/audit-unfreeze-owners.sh --device 100.111.89.64:5555
```

Only to remove the exact retired daemon found by the audit:

```bash
tools/audit-unfreeze-owners.sh \
  --device 100.111.89.64:5555 \
  --quarantine-legacy
```

## Version

- App: 2.5.0
- versionCode: 80
- Embedded engine revision: 258
- Status schema: 19
