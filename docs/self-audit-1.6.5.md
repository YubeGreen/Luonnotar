# Luonnotar 1.6.5 — Three-Pass Release Audit

Date: 2026-07-26  
Package: `com.yubegreen.luonnotar`  
Version: `1.6.5` / `versionCode 22`

## Pass 1 — Concurrency, executor, watchdog, and stale callbacks

### Findings and changes

1. A process-wide probe lease could outlive one Service instance, while a new
   instance could not tell whether the old lease was blocked in HTTPS, DNS, or
   mtalk.
   - `ProbeRequestGate.kt`: `ActualProbePermit` now stores an owner-token-bound
     stage and permits stage changes only from the same token.
   - `FcmGuardianService.kt`: HTTPS marks the process lease as `HTTPS` before
     connection work. A new Service can therefore identify a stale HTTPS lease
     without treating ordinary DNS/mtalk timeouts as a reason to kill Keeper.
   - `ProbeWatchdogPolicy.kt`: hard restart revalidation now requires the same
     owner identity, generation, acquisition time, VPN handle, and `HTTPS`
     stage.

2. Old executor completion, submission-failure, and timeout callbacks could
   otherwise disturb a newer probe generation.
   - `ProbeRequestGate` keeps token/generation ownership for `finish`, `reset`,
     and actual-permit release.
   - `FcmGuardianService` checks service epoch, token ownership, VPN handle,
     session fingerprint, enabled state, and stopping/destroyed state before
     writing results.
   - Forced requests collapse to one pending follow-up.

3. The old aggressive 25/30/60-second cadence policy remained as dead code and
   tests after the cooperative redesign.
   - Removed those cadence branches. Periodic DNS, HTTPS, and mtalk are now
     separate explicit experiments; one tick chooses at most one plan.

4. A whole-suite timeout could previously be interpreted as proof that every
   network stage should restart Keeper.
   - The whole pipeline has a 30-second deadline and executor recovery.
   - Only a revalidated, process-wide stale HTTPS lease is eligible for the
     near-term Keeper restart path.

5. A normal recovery reschedule could cancel the two-second hard-restart
   alarm.
   - `LabAlarmScheduler` now separates `cancelRegularAlarms`,
     `cancelHardRestart`, and `cancelAll`.
   - `scheduleNext` touches regular exact/inexact alarms only.

6. A hard-restart alarm could fire inside the old Keeper before that process
   killed itself.
   - The alarm is bound to a nonce, expected old PID, service generation, and
     actual-permit owner.
   - Matching alarms received by the old PID are rescheduled, not counted as
     recovery. Mismatched metadata is rejected.
   - `FcmRecoveryWorker` one-time unique work uses `KEEP`, preventing recovery
     request storms from repeatedly cancelling active checks.

### Verification

- `ProbeRequestGateTest`: concurrent stress, stale callback rejection,
  single-process actual-probe permit, and old-owner release rejection.
- `ProbeWatchdogPolicyTest`: owner/VPN revalidation and DNS/mtalk hard-restart
  rejection.
- Final unit tests: **108 passed, 0 failed, 0 skipped**.

## Pass 2 — Android lifecycle, callbacks, locks, and process recovery

### Findings and changes

1. vivo/iQOO aggressive locks and screen-driven probes could increase the
   likelihood of OEM high-power countermeasures.
   - Added `IQOO_COOPERATIVE`, selected by default on vivo/iQOO.
   - Cooperative mode masks permanent CPU Lock, high-performance Wi-Fi Lock,
     and screen-event probes at the runtime policy layer as well as the UI and
     Provider validation layers.
   - Necessary network tasks may use a 10-second scoped CPU Lock; release is in
     `finally`.
   - `LAB_EXTREME` L0–L4 is explicit and reversible. Only L4 presets permanent
     CPU and high-performance Wi-Fi locks. Automatic mtalk remains off.

2. Screen broadcasts could trigger several immediate connections.
   - Screen-off, screen-on, and user-present are timeline-only in cooperative
     mode.
   - Cooperative screen-off starts a 120-second quiet window.
   - VPN loss/blocking and a manual user diagnostic are the only intended
     quiet-window exceptions.

3. Startup VPN, default-network, and Tailscale callbacks could each request
   work.
   - They are aggregated for two seconds and submit at most one lightweight
     startup DNS request.
   - The startup future is cancelled during pause, stop, recovery, and
     `onDestroy`.

4. Heartbeat and notification work was unnecessarily frequent.
   - Heartbeat/tick evidence stays in memory and is persisted every 60 seconds
     or on semantic state change.
   - Notification fingerprints omit RTT, drift, age, timestamps, and counters.
     Stable notifications refresh no more often than ten minutes and do not
     refresh during the cooperative quiet window.

5. Listener survivability was coupled to Keeper.
   - The merged release Manifest confirms
     `ArrivalNotificationListener` has no `:keeper` process attribute.
   - `FcmGuardianService`, its Provider, and recovery receivers remain in
     `:keeper` where intended.

6. Resource cleanup was rechecked.
   - Dynamic screen receiver unregisters in monitor-start failure and
     `onDestroy`.
   - VPN/default-network/Tailscale monitors unregister callbacks in `stop`.
   - scheduler, startup future, probe executor, active DNS cancellation,
     sockets, HTTPS connection, CPU Lock, and Wi-Fi Lock are cancelled,
     disconnected, shut down, or released on stop/pause/recovery/destruction.
   - Epoch/token checks prevent old tasks from writing current-generation
     state after shutdown.

7. Main-process cleanup broadcasts could recreate periodic work after a user
   disabled or paused the guardian.
   - Ensure/enqueue broadcasts carry an expected service generation.
   - `GuardianCleanupReceiver` rereads Provider state immediately before work
     and rejects disabled, paused, unavailable, or generation-mismatched
     requests.

8. Notification-listener disconnects could request immediate rebind forever.
   - Rebind uses 30 seconds, 60 seconds, then five-minute backoff.
   - It checks notification-listener access first and stops after five
     consecutive disconnect attempts until a later successful connection or
     user action.

### Verification

- Clean build assembled the release APK, AAB, and instrumentation-test APK.
- Release Manifest contains no `android:debuggable="true"`.
- Instrumentation APK was assembled but **not run**: no ADB device was
  connected at final validation time.

## Pass 3 — Privacy, cross-process state, diagnostics, and release safety

### Findings and changes

1. Moving `ArrivalNotificationListener` to the main process exposed direct
   cross-process SharedPreferences access.
   - The Listener no longer reads or writes Keeper preferences.
   - Privacy authorization, listener connection state, notification dedupe,
     counters, removal, current VPN handle, and last-success timing are owned
     atomically by `GuardianStatusProvider` in `:keeper`.
   - Listener traffic contains package identifiers, truncated hashes, post
     time, group-summary state, and counters; ordinary notification text is not
     persisted. Only the strict local `PUSH_TEST_...` pattern is parsed for a
     user-controlled test timeline.

2. Transient health fields could make structural VPN fingerprints churn and
   raw `transportInfo.toString()` could leak unstable implementation detail.
   - The structural fingerprint excludes blocked, suspended/NOT_SUSPENDED, and
     raw transport strings.
   - These values remain health dimensions, not session identity.

3. Documentation could misrepresent a successful Luonnotar HTTPS request as
   FCM health.
   - README and UI explicitly separate VPN DNS/HTTPS health, mtalk
     reachability, FCM health, and WhatsApp delivery health.
   - No FirebaseMessagingService/token/canary is claimed in 1.6.5.

4. Release archive safety was rechecked.
   - Archive excludes `.git`, signing directories, keystores,
     `keystore.properties`, `local.properties`, build output, logs, and device
     test data.
   - Content/path scan passed for credential and private-key indicators.

5. ADB diagnostics could turn missing or unsupported output into healthy
   `false` values and keep an older snapshot after a failed import.
   - Target fields use `TRUE`, `FALSE`, or `UNKNOWN`; command support,
     exit status, parse status, and capture error are included.
   - Empty/failed imports clear the current snapshot and store
     `SNAPSHOT_IMPORT_FAILED`.
   - Capture-start, capture-finish, and import times are separate.

6. System-event ordering and Tailscale diagnostics were tightened.
   - The iQOO script uses epoch logcat, a unique sequence, and a line hash,
     globally sorts events, and only then keeps the newest records.
   - Quad100 IPv4 and IPv6 servers are each attempted; one working family is
     sufficient for overall reachability and both family results are stored.
   - LinkProperties callbacks republish aggregated Wi-Fi/EXTWIFI underlay
     evidence.
   - Route reflection failure is conservative/unknown, not confirmed unusable.
   - `TARGET_ROUTING_UNVERIFIED` keeps priority over the softer Tailscale
     GMS-unknown overlay.
   - Target routing requirements now include only user-0 apps that are
     enabled, not suspended, not stopped, and explicitly selected in the
     strategy sheet. WhatsApp Business is opt-in by default.

### Final build evidence

- Command:
  `gradlew clean testDebugUnitTest lintDebug assembleRelease bundleRelease assembleDebugAndroidTest`
- Unit tests: **108 / 108 passed**
- Lint: **0 errors, 112 warnings**
- APK signature: YubeGreen certificate; APK Signature Scheme v2 and v3 verified
- APK SHA-256:
  `D87774E8AC3F9686FC3AC30B7CB5D2DEF31D022B9AF0A54B44886EAF019F3E6B`
- AAB SHA-256:
  `CC953EF0F272E6E3E136ECF878741D4E0CF63EB10A2A9FD9BEC90501C5A2481C`
- Clean source archive: 120 files; sensitive-content scan passed

## Device validation status

Final device validation result: **NO_DEVICE / not executed**.

The connected-device test was not run because the user confirmed that no ADB
device was connected. Historical 1.6.4 iQOO observations are not presented as
1.6.5 evidence.
