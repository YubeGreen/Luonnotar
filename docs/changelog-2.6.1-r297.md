# Luonnotar 2.6.1 v134 / r297

## Why r297 exists

The 2026-08-10 iQOO unattended soak (16:10–20:00) showed that r296's individual recovery actions were effective but the outage lifetime was not. A bootstrap could declare success after eight healthy seconds, then a later freezer collapse in the same real outage opened a fresh bootstrap with a fresh soft/hard allowance. Over the soak this multiplied bounded actions into 26 additional soft resets and five additional hard resets. The same run showed a vendor-bridge split brain: the shell heartbeat file stayed fresh while protocol heartbeat telemetry stopped for roughly two hours, yet guardian fallback remained suppressed.

## r297 invariants

1. **One transport incident owns destructive accounting.** Bootstrap generations and the older freezer recovery campaign share it. The incident permits at most one soft reset and one hard reset. Consuming hard escalation closes the lower soft tier too.
2. **Eight healthy seconds means socket recovered, not outage recovered.** Socket recovery starts a 120-second probation. Collapses shorter than 30 seconds are tolerated; a collapse lasting 30 seconds or more resets probation without resetting the destructive ledger. Controlled delivery remains immediate strongest recovery evidence.
3. **Hard-reset efficacy is remembered.** No socket recovery within the post-hard grace, or a long collapse after recovery, marks the hard reset ineffective. A replacement bootstrap cannot spend another hard reset in that incident.
4. **The freezer campaign cannot bypass the incident.** Before any GMS reset while transport is observably unhealthy, it creates/adopts the active transport incident and consumes the same soft/hard tiers.
5. **Protocol heartbeat outranks file heartbeat.** If a READY vendor bridge stops producing protocol heartbeat for the stale interval, a still-fresh heartbeat file no longer masks the failure. Command ownership is revoked, the bridge is restarted, guardian fallback is unsuppressed, and ownership returns only after a new identity-validated READY record.

## Telemetry

Status schema 60 adds `gmsTransportIncident` plus vendor-bridge protocol stall/restart/ownership fields. Bootstrap telemetry retains the 8-second value under `socketRecoveredStableMs` and exposes the 120-second incident probation separately.

VersionCode: 134

Embedded engine: r297

Status schema: 60
