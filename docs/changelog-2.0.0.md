# Luonnotar 2.0.0

## Added

- Privileged Guardian daemon UserService using Shizuku/Sui shell or root identity.
- AIDL control/status interface and persistent app-side control plane.
- Event-driven `am_app_frozen` watcher plus fixed-delay polling fallback.
- Capability-gated `am unfreeze --sticky` with PID fallback.
- cgroup v1/v2 freezer evidence inspection and opt-in root direct thaw.
- Periodic repair of inactivity/standby, Doze whitelist, AppOps, app hibernation and restricted-background policy.
- Dedicated UI section showing real UID, event watcher, sticky support, cycles, actions and errors.
- Standalone `/data/local/tmp` ADB shell guardian, installer and evidence collector.
- Parser/policy unit tests and a strict overnight A/B acceptance protocol.

## Changed

- Version jumps from 1.7.17 directly to 2.0.0; 1.7.18 does not exist.
- Existing normal-UID features are classified as diagnostics/reliability controls, not anti-freezer proof.

## Not claimed yet

- That vivo `fast_freezer` honors AOSP sticky unfreeze.
- That unfreezing alone always rebuilds GMS MCS/WhatsApp sockets.
- That Shizuku itself survives every OriginOS overnight condition.
- That the current candidate has passed the eight-hour cross-over matrix.
