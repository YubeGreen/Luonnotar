# Luonnotar 1.7.14

## Evidence-driven scope

The OriginOS failure was reproduced with the guardian awake, the high-performance
Wi-Fi lock held, the VPN and Wi-Fi validated, DNS successful, HTTPS 204 probes
successful, and IPv4 mtalk TCP 5228/443 reachable. WhatsApp still stopped
reconnecting after a socket EOF, and queued pushes were released only after the
GMS process was replaced during the diagnostic force-stop test.

This release therefore does not add more one-shot reachability probes as a
claimed recovery mechanism. It fixes confirmed instrumentation and scheduling
faults and adds two disabled-by-default, pure-phone prevention experiments.

## Periodic probe scheduler

- Fixed the Tailscale DNS timestamp mismatch: Tailscale scheduling now reads the
  timestamp that the Tailscale DNS probe actually updates.
- Replaced fixed DNS-first selection with a most-overdue scheduler.
- Zero-timestamp ties intentionally run HTTPS, then mtalk, then DNS, preventing
  one enabled transport from starving the others.
- Added policy tests covering initial tie-breaking, rotation, overdue ordering,
  and the no-work-due case.

## Locked-screen configuration reload

- Replaced the provider-to-service `startForegroundService()` reload with a
  process-local reload in the shared `:keeper` process.
- This avoids `ForegroundServiceStartNotAllowedException` when HyperOS accepts
  the synchronous provider call but forbids a new background FGS start.
- If the guardian service is not currently running, the configuration remains
  successfully persisted and the reload is reported as deferred instead of
  incorrectly failing the whole transaction.

## Notification listener recovery

- Reduced the persisted listener heartbeat interval to 60 seconds.
- Added guardian-side stale-heartbeat detection and a throttled framework
  `requestRebind()` recovery path.
- Added the same throttled recovery request to ADB notification diagnostics.
- Added status fields for listener heartbeat age and rebind count.

## Immediate ADB probes

- Added synchronous `ProbeNow` support for DNS, HTTPS, mtalk, or the complete
  manual diagnostic suite.
- ADB-triggered probes bypass the cooperative quiet window while preserving the
  existing single-probe serialization rules.

## Persistent OriginOS prevention experiments

Two experiments are disabled by default:

- `persistent_network_lease`: holds a process-lifetime VPN `NetworkRequest`.
- `persistent_mtalk_socket`: maintains an app-owned TCP socket through the
  current VPN network and reconnects after EOF or failure.

The socket does not impersonate GMS and does not speak the MCS protocol. It is
only an A/B experiment to determine whether continuously held network state
changes OriginOS background scheduling behaviour.

The ADB tool adds `-OriginOsPersistentLeasePreset`, which enables the screen-off
CPU guard, high-performance Wi-Fi lock, VPN network lease, and persistent socket
while disabling the earlier one-shot DNS/HTTPS/mtalk loop for causal isolation.

## Diagnostics

The runtime status output now includes:

- service generation and keeper PID;
- VPN network handle and session generation;
- GMS Binder anchor state, PID, and session generation;
- notification listener heartbeat age and rebind count;
- persistent network lease state, handle, and event age;
- persistent socket state, handle, event age, last reason, and connect count;
- whether an in-process reload was applied or deferred.

## Version

- `versionName`: `1.7.14`
- `versionCode`: `40`
