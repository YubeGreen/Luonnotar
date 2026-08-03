# Luonnotar 2.0.0 architecture

## Goal

Luonnotar 2.0.0 is not another app-UID keepalive layer. Its job is to produce a measurable improvement in the availability of Google Play services, WhatsApp and the selected VPN when an OEM freezer suspends normal application UIDs.

The old `:keeper` service, alarms, WorkManager, wake locks, Wi-Fi locks, network probes, notification listener and GMS Binder experiments remain useful evidence sources. They are not counted as anti-freezer execution, because an OEM can suspend all processes belonging to the application UID at once.

## Two execution planes

### 1. Privileged Guardian UserService

`PrivilegedGuardianUserService` is loaded by Shizuku or Sui and runs as shell UID 2000 or root UID 0. It is configured as a daemon UserService, outside both the main APK process and `:keeper`.

It performs:

- an event-driven `logcat -b events` watch for `am_app_frozen` affecting configured targets;
- a 15-second polling fallback in case vendor events or cgroup state are hidden;
- capability detection for `am unfreeze --sticky` and app hibernation commands;
- sticky unfreeze on target process creation, freezer evidence, and periodic reassertion;
- cgroup v1/v2 freezer-state inspection where SELinux permits it;
- optional root-only direct cgroup thaw, disabled by default;
- periodic repair of standby, inactivity, device-idle whitelist, selected AppOps, app hibernation, and restricted-background whitelist state;
- a bounded event/status ledger returned through AIDL.

It explicitly does **not** force-stop Google Play services, clear application data, fake FCM delivery, or claim that a successful command equals restored push delivery.

### 2. Standalone ADB shell guardian

`tools/device/luonnotar-guardian-v2.sh` is an independent fallback for testing before, without, or alongside the APK UserService. It runs from `/data/local/tmp/luonnotar2`, survives the Luonnotar APK UID being frozen, watches freezer events, performs the same target unfreeze/reassert policy, and writes machine-readable status plus a rotated log.

It is installed and controlled by `tools/install-luonnotar-2.0-shell-engine.ps1`.

The shell engine does not automatically survive a device reboot. Shizuku/Sui startup and the APK boot reconnect path are the intended integrated route; reboot persistence of an ad-hoc shell process is not represented as solved.

## Control plane

The normal APK stores only user intent and the last returned status. `PrivilegedGuardianController` binds to the daemon through AIDL, requests permission, starts or stops the engine, and periodically refreshes status. The daemon keeps executing after the UI and normal app process disappear.

On unlocked boot/package replacement, the existing main-process maintenance receiver initializes the control plane and reconnects an enabled daemon after Shizuku/Sui becomes available.

## Target policy

Default process targets:

- `com.google.android.gms`
- `com.google.android.gms.persistent`
- `com.whatsapp`
- `com.whatsapp:account_switching`
- `com.whatsapp.w4b`
- `com.tailscale.ipn`
- `ch.protonvpn.android`

All process/package identifiers received from JSON are syntactically validated before they can become command arguments. Commands are passed as `ProcessBuilder` argument arrays; no external identifier is interpolated through a shell.

## Recovery boundary

AOSP unfreezing restores CPU execution, but it does not itself prove that an OEM preserved or rebuilt GMS MCS and WhatsApp network state. AOSP can terminate active TCP sockets when all processes of an app are frozen. Therefore 2.0.0 treats these as separate facts:

1. the target was observed;
2. freezer evidence was visible or an OEM event fired;
3. an unfreeze/immunity command succeeded;
4. the process remained runnable;
5. MCS/WhatsApp reconnected;
6. the controlled push arrived.

Only step 6 is end-to-end success. The engine records steps 1–4 and the existing evidence chain measures steps 5–6.

## Deliberately rejected designs

- More wake locks, timers, alarms, Binder anchors, or foreground-service restarts inside the same UID.
- Automatic GMS `force-stop`; previous evidence did not establish it as causal and it can destroy authentication/network state.
- Repeated GMS Binder pulses as production recovery; earlier device tests showed successful Binder/query cycles without reliable MCS recovery.
- Disabling broad vivo system packages or changing undocumented service-call transaction numbers.
- Claiming that manual OEM settings are equivalent to defeating `fast_freezer`.
