# Luonnotar 2.5.1 r262 — vendor-defense recovery owner + self-update PoC

## Why

Real OriginOS evidence showed r261's durable defense is directionally correct: after each VIVO `from fast_freezer` event it can restore a physical thaw, wait 12 seconds for stability and limit the MCS reconnect pulse to one non-escalating round. The same trace also exposed three remaining defects:

1. the old GMS recovery campaign could still reset/force-stop GMS while a vendor-defense episode was active;
2. a 12-second stable result ended the episode immediately, so a refreeze milliseconds later created another episode;
3. a fallback attempt could amplify into a large command burst, while this device's AOSP `am freeze` path consistently crashes inside `CachedAppOptimizer` because the freeze handler is unavailable.

## r262 defense changes

- Active vendor-defense phases own destructive GMS recovery. Automatic campaign start, reset/force-stop, and vendor-lock escalation are suppressed while the owner lease is active.
- 12 s physical-thaw stability now enters `stable_hold`; the episode remains alive for another 120 s. Refreeze resets only the stable timer, not the episode generation.
- Defense action interval is 2.5 s instead of 250 ms.
- VIVO defense fallback is release-only and physically verified. It never calls AOSP freeze adoption.
- The bridge learns the `freezeAppAsyncInternalLSP` / `Handler.obtainMessage` / `NullPointerException` failure signature and disables subsequent framework-freeze attempts for that bridge process.
- `Long.MAX_VALUE` wait telemetry is encoded as `-1`.
- Status schema 21 exposes defense owner state and suppression counts.

## Self-update PoC

The first PoC from the OriginOS shell-update requirements is included without a production UI.

- RPC operations: `install_self_update`, `self_update_status`.
- ADB provider methods: `self_update`, `self_update_status`.
- Source APK must be under `/data/local/tmp/luonnotar-self-update/`.
- The shell engine snapshots the source before validation to avoid validate-then-swap races.
- Candidate package must be exactly `com.yubegreen.luonnotar`.
- Candidate signer set must exactly match the installed Luonnotar signer set.
- Candidate versionCode must be strictly newer.
- Maximum APK size is 256 MiB.
- Uses framework `PackageInstaller` session APIs, `fsync()`, final status callback and bounded `setPermissionsResult(sessionId, true)` retries.
- Any live session is abandoned on pre-final failure or engine shutdown.
- No global verifier/security setting is modified and no arbitrary-package RPC exists.

## Identity

- versionName: 2.5.1
- versionCode: 84
- embedded engine revision: 262
- status schema: 21
