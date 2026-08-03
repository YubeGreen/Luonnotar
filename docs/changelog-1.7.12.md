# Luonnotar 1.7.12

Version 1.7.12 adds a shell-only, unattended runtime-configuration bridge and
hardens configuration consistency around the OriginOS screen-off experiments.

## Locked-device ADB runtime configuration

- Adds `AdbRuntimeConfigReceiver`, exported only behind
  `android.permission.DUMP`, direct-boot aware, and hosted in `:keeper`.
- Supports ordered-broadcast status and mutation actions:
  - `ADB_RUNTIME_CONFIG_STATUS`
  - `ADB_SET_RUNTIME_CONFIG`
- Preserves unspecified fields, validates the complete target configuration,
  commits it atomically through `GuardianStatusProvider`, reads it back, and
  asks the live guardian service to reconcile immediately.
- Returns machine-readable ordered-broadcast data so automation can distinguish
  success, policy rejection, persistence failure, and live-service reload
  failure.
- Adds `tools/set-adb-runtime-config.ps1`, including an
  `-OriginOsPreventionPreset` for high-performance Wi-Fi Lock plus periodic VPN
  DNS and HTTPS 204 probes.

## Runtime configuration stability

- Centralizes lab-only and ADB-passive restrictions in
  `GuardianProfilePolicy`, removing duplicated policy lists from the provider
  and UI.
- Rejects internally inconsistent complete configurations instead of silently
  accepting impossible states, while profile-only ADB changes clear stale bits
  that are incompatible with the requested profile.
- Serializes provider calls so simultaneous UI, notification-listener, and ADB
  updates cannot interleave a configuration read-modify-write transaction.
- Fixes the profile dialog so preset changes also update the hidden scoped CPU
  Lock value; switching to ADB passive can no longer carry a stale active value
  into the provider transaction.
- Adds policy and manifest tests for the new bridge, the existing Binder-pulse
  bridge, and the shared restrictions.

## ADB diagnostics and watcher

- `AdbGmsBinderPulseReceiver` now returns an ordered-broadcast result instead of
  failing silently when the guardian is inactive or service dispatch fails.
- Integrates watcher revision `1.7.12-r3`:
  - keeps the 1.7.11-r2 CSV-race and recovery-limit fixes;
  - backfills up to five minutes of sender history by default;
  - can restore the armed state from a recent persisted live arrival;
  - labels active-scan evidence as non-arming without falsely printing that the
    whole watcher session is unarmed.
