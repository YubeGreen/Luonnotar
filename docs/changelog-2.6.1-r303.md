# Luonnotar 2.6.1 v140 / r303 — shell child lifecycle hardening

## Incident

On the OriginOS iQOO target, an overnight battery incident left four UID 2000
`tr \000 \n` processes consuming roughly two CPU cores in aggregate after the embedded
engine had already been stopped. A legacy Luonnotar freezer `logcat` watcher was
also found reparented to PID 1. Killing the four `tr` processes immediately
returned the device to normal CPU idle and none respawned.

## Root cause

The vendor-freeze bridge validated `/proc/<pid>/cmdline` with an external
`tr '\000' '\n' | head -n 1` pipeline inside its hot identity path. A wedged
command-substitution pipeline can outlive the engine generation because killing
the top-level shell does not guarantee termination of nested pipeline children.
The legacy event watcher also used a direct `logcat` Process but its stop path
only called `Process.destroy()` without a bounded wait / forced-stop fallback.

## r303 changes

- Replaces the hot `/proc/<pid>/cmdline` `tr | head` pipeline with Android
  mksh's builtin NUL-delimited `read`, bounded by a 250 ms timeout. No external
  process is created for PID identity verification.
- Adds a shared managed-process shutdown path: graceful destroy, bounded wait,
  forced destroy, bounded verification. Both the vendor bridge and event watcher
  use it.
- Emits `managed_process_force_stop` only when escalation is required and
  `managed_process_stop_failed` only if a process still fails to terminate.
- Adds a regression assertion that the vendor bridge no longer generates the
  `tr \000 \n` cmdline pipeline.
- No GMS recovery thresholds, destructive budgets, freezer policy, Listener
  policy, SSH policy, or status schema are changed.

Identity: versionCode **140**, engine revision **303**, status schema **63**.
