# Luonnotar 2.5.1 r259 — sustained vendor-refreeze defense

## Evidence behind this revision

The overnight r257 iQOO trace was not a single missed thaw. OriginOS repeatedly
reported `am_app_frozen ... from fast_freezer` for GMS main and persistent,
usually only a few seconds after Luonnotar had observed a transient thaw. During
that interval the transport probe remained empty and repeated
`GCM_RECONNECT` broadcasts exhausted without rebuilding delivery.

The same PIDs later recovered naturally and messages became immediate. That
proved three things:

1. a momentary `cgroup.freeze=0` is not durable recovery;
2. restarting GMS is not always required;
3. a reconnect broadcast is useful only after the vendor has stopped forcing
   the process group back into the freezer long enough for transport to rebuild.

AOSP source confirms that shell `unfreeze --sticky` changes
`CachedAppOptimizer`'s sticky state and blocks later *AOSP* non-forced freezes.
It cannot govern a separate OEM component that directly returns the process to
a frozen cgroup. AOSP also documents that freezing every process of an app can
terminate the app's active TCP sockets, so CPU thaw and transport recovery are
distinct milestones.

## r259 design

### One defense episode per GMS process generation

r259 retains r258's validated single freezer-command owner and PID-verified
atomic GMS group. The first physical freeze starts one defense episode. Further
freeze observations extend that episode instead of launching independent shell
recoveries or Kotlin campaigns.

A process-generation change is explicitly recorded and assigned a new sequence.
This prevents a reconnect pulse for an old PID pair from suppressing the pulse
for newly restarted GMS processes.

### Durable success gate

The bridge checks both `com.google.android.gms` and
`com.google.android.gms.persistent` as one required group.

- A cheap parallel sticky release is attempted first.
- If either peer remains physically frozen, the audited r258 adoption/release
  path runs silently inside the same episode.
- A refreeze resets the stability clock but does not create another recovery
  record.
- After twelve seconds of continuous physical thaw, Kotlin may issue exactly one
  bounded reconnect pulse for that process generation.
- Success is emitted only after both exact PIDs remain physically thawed for
  twelve continuous seconds.

The twelve-second gate deliberately exceeds Android's normal ten-second cached
freezer debounce and the approximately four-to-six-second refreeze rhythm seen
in the overnight trace.

### No reconnect or emergency storm

The defense pulse uses one reconnect round and cannot increment the global MCS
exhaustion streak or recursively start a destructive recovery campaign. The
final stable record does not schedule the legacy multi-round post-thaw window
when a pulse was already attempted.

If no physical thaw is observed for thirty seconds, or sustained refreezing
continues for two minutes, the bridge emits one vendor-lock escalation and keeps
defending. It does not repeatedly open a new emergency campaign. A process PID
change starts a fresh generation with a fresh one-shot pulse allowance.

The episode has a ten-minute safety ceiling. On expiry it fails closed, emits a
single escalation if one was not already emitted, then waits thirty seconds
before another episode. This bounds a pathological OEM command loop without
reintroducing r257's thirty-minute recovery blind spot.

## Diagnostics

New protocol phases:

- `vendor_bridge_defense_started`
- `vendor_bridge_defense_refrozen`
- `vendor_bridge_defense_pulse_ready`
- `vendor_bridge_defense_pid_changed`
- `vendor_bridge_defense_escalating`
- `vendor_bridge_defense_stable`
- `vendor_bridge_defense_expired`

Status schema 20 exposes episode, pulse, refreeze, PID-change, escalation,
stability, expiry, elapsed-time, and attempt counters.

## Safety boundaries

- No direct `cgroup.freeze` write is used; ActivityManager remains responsible
  for Binder and framework freezer bookkeeping.
- GMS main and persistent remain a strict atomic group.
- Exactly one validated Luonnotar owner can issue freezer commands. Runtime authorization requires live PID/start-time identity; exit cleanup uses the same exact lease fields without incorrectly requiring an already-dead parent to remain alive.
- WhatsApp and Signal keep the bounded r258 single-target path and cannot start
  the GMS transport/destructive escalation flow.
- A bridge restart clears the Kotlin reconnect sequence namespace so a new
  shell cannot inherit stale deduplication state.
- An old r258 bridge is rejected at READY because its strategy identifier does
  not match r259.

## Version

- App: 2.5.1
- versionCode: 81
- Embedded engine revision: 259
- Status schema: 20
- Bridge strategy: `atomic_group_defense_episode`
