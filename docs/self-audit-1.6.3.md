# Luonnotar 1.6.3 — Three-Pass P0 Self-Audit

This report records concrete findings, changes, and verification. A successful
build is not treated as proof of real FCM delivery.

## Pass 1 — Concurrency, executors, watchdog, and generations

### Findings and changes

1. A rejected submission or late `finally` from an old executor could clear a
   newer probe gate.
   - Changed `ProbeRequestGate` to issue an unforgeable owner token containing
     its generation.
   - `finish(token)` and `reset(token)` are no-ops unless the token is still the
     current owner.
   - Probe gate transitions and diagnostic preference writes are serialized by
     `probeLifecycleLock`.

2. Advancing an executor generation could allow a new HTTPS call while the old
   call was still draining.
   - The gate now distinguishes logical ownership from actual-network
     ownership.
   - Actual ownership survives a logical generation advance until the old call
     really exits.
   - A keeper-process-wide, token-owned `ActualProbePermit` prevents two
     service instances from opening HTTPS at the same time; a stale release
     cannot clear a newer owner's permit.
   - At most one forced follow-up is retained.

3. A successful or failed old request could pass its epoch check and then write
   evidence after watchdog recovery.
   - The owner token is passed through `executeKeepalive()` and failure
     recording.
   - Token, epoch, service generation, active VPN handle, and authoritative
     enabled state are rechecked while holding the same lifecycle lock used by
     recovery.
   - Evidence uses synchronous commit inside that atomic acceptance boundary.

4. An old scheduled tick or manual-check task could act on a new generation.
   - Ticks now carry their scheduler epoch.
   - Heartbeat writes, watchdog actions, probe requests, error writes, and
     expected-time updates reject stale epochs.
   - Manual checks capture an executor and epoch and handle rejected submission.

5. Repeated watchdog recovery could hide a still-draining old actual request.
   - Gate snapshots retain the actual owner and its start time.
   - The old request remains visible as in flight, is disconnected, and is
     logged as draining without allowing a second HTTPS request.
   - A 45-second hard lease now schedules recovery and terminates only the
     dedicated keeper process when DNS, TLS, or native socket code never
     returns; the process-wide permit is never unsafely stolen.

6. `HttpsURLConnection.connect()` held the lifecycle monitor during network
   I/O.
   - Token, epoch, VPN handle, and enabled state are checked while locked.
   - The connection is published while locked, but `connect()` and response
     reads execute outside the lock.
   - Stop, pause, route change, and recovery detach the active connection
     while locked and disconnect it after releasing the monitor.

### Verification

- `ProbeRequestGateTest` simulates late finish/reset, rejected old ownership,
  generation advance, forced-event collapse, and 500 rounds of concurrent stale
  callbacks.
- `ActualProbePermit` is stressed from 12 threads and asserts maximum actual
  concurrency is one.
- Debug unit tests and instrumentation-test compilation passed before the final
  clean build.

## Pass 2 — Android lifecycle, boot, callbacks, locks, and process recovery

### Findings and changes

1. `LOCKED_BOOT_COMPLETED`, `BOOT_COMPLETED`, and `USER_UNLOCKED` could trigger
   repeated foreground-service starts within seconds.
   - Added a 25-second same-boot idempotency claim.
   - Failed foreground-service dispatch rolls the claim back.
   - `MY_PACKAGE_REPLACED` remains independent.
   - Boot logs include the action, anonymized boot ID, decision, reason, and
     elapsed delta.
   - Boot uses `ACTION_START`; it no longer rebuilds a fresh service's
     executors unnecessarily.
   - Deduplication applies only to foreground-service dispatch. An unlocked
     `BOOT_COMPLETED`, `USER_UNLOCKED`, or package-replacement event still
     ensures WorkManager periodic recovery.
   - Logging, alarms, fallback work, and periodic-work initialization now run
     under `goAsync()` on a bounded receiver path; only the authoritative
     state claim and immediate foreground-service request remain synchronous.

2. Wi-Fi underlay history was written but erased during keeper-process rebuild.
   - History now includes a boot ID.
   - Keeper-process initialization preserves history; new-boot initialization
     removes it.
   - Restore requires current, runtime, and stored boot IDs to match and rejects
     invalid/future `elapsedRealtime` values.
   - Explicit cellular or Ethernet evidence immediately clears Wi-Fi recency.

3. VPN route and underlay classification had order and coverage gaps.
   - Default-route evidence now accepts `/0` and paired IPv4/IPv6 split-default
     `/1` routes while rejecting unreachable/throw route types.
   - Multiple VPN underlays are aggregated deterministically; Wi-Fi wins for
     Wi-Fi-lock purposes regardless of underlying-network list order.

4. Connectivity callbacks could arrive after unregister and mutate old service
   state.
   - Both monitors have an atomic accepting-callback state.
   - Stop disables callbacks before unregister.
   - The network handler queue is cleared and its thread is shut down and
     joined.
   - Service callbacks verify the persisted service generation before writing
     evidence or reconciling locks.

5. Stop, pause, destroy, and recovery did not share one quiescence boundary.
   - These paths now invalidate the logical generation, cancel the tick future,
     disconnect the active request, cancel pending retries, and release locks.
   - `onDestroy()` shuts down and waits briefly for both executors and logs
     whether they terminated.
   - Monitor-start failure unregisters partial resources, removes foreground
     state, releases locks, and closes executors.

6. A rapid pause/resume could reuse an instance whose foreground notification
   had already been removed.
   - Resume explicitly restores foreground state before reacquiring locks and
     scheduling work.

7. Tailscale support could not distinguish a tailnet-only VPN from an Exit
   Node carrying public Internet traffic.
   - VPN evidence now records the Android VPN owner package when the platform
     exposes it.
   - IPv4 and IPv6 default routes are derived from the active VPN
     `LinkProperties`.
   - Provider, default-route, and network-handle changes invalidate prior HTTP
     evidence and request a fresh VPN-bound probe.
   - The UI and diagnostic package report the provider and both default-route
     families without claiming third-party UID delivery verification.

### Verification

- Boot policy tests cover same-boot deduplication, window expiry, different
  boot IDs, failed dispatch retry, and package replacement.
- Wi-Fi policy tests cover same-boot process rebuild, device reboot rejection,
  future elapsed values, UNKNOWN hysteresis, and explicit cellular release.
- `GuardianSystemIntegrationTest` includes a 100-cycle start/stop resource
  stress test and final PID/WakeLock/Wi-Fi Lock/probe assertions.
- Route-policy tests distinguish `/0`, paired split-default `/1`, tailnet-only,
  incomplete, and unusable routes. Underlay tests prove list-order independence.
- Reducer tests prove current target-UID evidence is required before
  `VPN_PATH_HEALTHY`.
- The instrumentation test APK compiles; execution status is reported
  separately from compilation.

## Pass 3 — Privacy, diagnostics, signing material, and wording

### Findings and changes

1. Earlier log records contained the raw boot ID.
   - New records contain only a truncated SHA-256 identifier.
   - Export rewrites older JSONL records, removes raw boot IDs, strips
     token/credential-like fields, and omits malformed lines.
   - Original internal log filenames are not copied into the ZIP.

2. Diagnostic export lacked several required state fields.
   - The one-click diagnostic package now includes structured timeline logs,
     app/device/version state, VPN/validation/underlay state, locks, probe
     results, notification-listener authorization and connection state, battery
     optimization state, safe ADB advice, and an explicit privacy manifest.

3. Notification text risked being mistaken for general message logging.
   - Only the full, strict `PUSH_TEST_<n>  yyyy-MM-dd HH:mm:ss` pattern is
     parsed.
   - Ordinary chat text, phone numbers, contact names, FCM tokens, VPN
     account material, signing stores, and secret values are excluded from
     diagnostics.

4. Source archives could include repository metadata, signing files, builds,
   screenshots, or device-test output.
   - `tools/create-clean-source-archive.ps1` applies path and extension
     exclusions, creates a source-only ZIP, and then scans the ZIP.
   - Any sensitive path or content marker fails the task and removes the
     archive.
   - The task never modifies real signing files.

5. VPN path evidence could be described too broadly.
   - Documentation says validation **regained** triggers a probe.
   - `VPN_PATH_HEALTHY` is explicitly not FCM or WhatsApp delivery
     verification.

### Verification

- The clean source archive task completed with 101 files and passed its
  post-archive scan.
- PowerShell AST parsing passed for both release-archive and iQOO test scripts.
- Instrumentation coverage checks required diagnostic entries and rejects the
  raw boot ID in every exported text entry.
- The final clean build ran 78 unit tests with zero failures, Lint with zero
  errors and 88 warnings, release APK/AAB assembly, and instrumentation-APK
  assembly.
- The release APK reports package `com.yubegreen.luonnotar`, version 1.6.3
  (code 20), and verifies with the YubeGreen certificate under APK Signature
  Schemes v2 and v3.

## iQOO real-device observation

The following delivery observation was captured with the immediately preceding
1.6.2 build. The shared ADB server was unavailable after the 1.6.3 build, so it
is not mislabeled as a completed 1.6.3 device run.

On a vivo V2352A running Android 16 with Tailscale active, ADB showed:

- `com.tailscale.ipn` owned the validated, non-bypassable VPN network;
- `0.0.0.0/0` and `::/0` both routed through `tun0`;
- GMS UID 10171 and WhatsApp UID 10172 were both included in the VPN UID
  ranges;
- GMS and WhatsApp were in the exempted standby bucket, background AppOps were
  allowed, both were in the user Doze whitelist, and both processes were
  alive;
- screen-off VPN-only probes succeeded near 0, 33, and 64 seconds with one
  request at a time and the same VPN network handle.

Two controlled WhatsApp sends did not arrive during the observation. This is
recorded as a delivery failure despite healthy VPN-path evidence. It confirms
that Luonnotar's 204 probe and locks cannot substitute for, restart, or certify
the private GMS/WhatsApp FCM connection.
