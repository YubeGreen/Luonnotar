# Luonnotar 2.4.1 r4

- versionCode 66
- privileged engine revision 244
- privileged status schema 10

## Why this patch exists

The Pad r243 capture showed a verified OEM-refreeze loop rather than a VPN or GMS transport outage:

- Tailscale remained connected and validated.
- the GMS MCS socket remained established on port 5228.
- WhatsApp successor processes repeatedly returned, then entered Xiaomi `UidFrozen` again.
- r243 reached reset 28, including repeated verified `force-stop -> package unstop` cycles, without restoring delivery.
- Termux stayed present but frozen; r243 only had an absence wake path, so present-but-frozen Termux never escalated beyond failed cgroup thaw attempts.

## Changes

### Xiaomi / vivo successor circuit breaker

- After five verified successor refreezes and five resets on Xiaomi/vivo, stop the destructive reset storm.
- Before opening the circuit, attempt one screen-off foreground rescue with an 8-second hold.
- If the package is still frozen, finish the guard and defer new package rebuild campaigns for 30 minutes.
- The circuit state is recorded in privileged status and event logs.

### HyperOS 3 low-latency whitelist

- On detected Xiaomi Android 16 / HyperOS 3 devices, merge each installed managed target into the system setting `cloud_lowlatency_whitelist`.
- Existing entries are preserved and duplicates are removed.
- The write is read back and exposed as the capability `xiaomi_cloud_lowlatency_whitelist`.

This is an experimental vendor-private capability. It is verified as a setting write, not claimed as proof that HyperOS will stop freezing the target.

### Present-but-frozen Termux recovery

- Track continuous frozen duration for `com.termux` even while its PID still exists.
- After 15 seconds of verified frozen state, reapply policy and attempt a screen-off foreground wake for 8 seconds.
- Limit this rescue to once per five minutes.
- Do not kill or force-stop Termux; preserve its session and record whether the PID actually thawed.

## Validation gates

The patch apply scripts require:

- baseline 2.4.1 r3 / code 65 / revision 243, or an already-applied r4 tree;
- source assertions for the circuit breaker, HyperOS whitelist, Termux frozen wake and revision 244;
- `clean testDebugUnitTest lintDebug assembleRelease` before producing the APK.
