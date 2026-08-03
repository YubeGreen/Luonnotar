# Luonnotar 2.1.0 integrated privileged engine

## Purpose

Luonnotar 2.1.0 can start its own shell-UID guardian without requiring the
separate Shizuku manager application. Shizuku/Sui support remains available as
an optional fallback.

## User flow

1. Enable Android **Wireless debugging**.
2. On first use, open **Pair device with pairing code**.
3. Luonnotar discovers `_adb-tls-pairing._tcp.` and
   `_adb-tls-connect._tcp.` with Android `NsdManager`.
4. Enter the six-digit code directly through the Luonnotar notification.
5. Kadb pairs to the device-local `adbd` and executes a fixed starter command.
6. `app_process` loads Luonnotar's installed APK and starts
   `EmbeddedGuardianServerMain` as shell UID 2000.
7. The app configures the existing freeze/unfreeze and controlled GMS restart
   engine through an authenticated loopback protocol.

After a reboot, Android removes the shell process. If the integrated engine was
enabled previously, Luonnotar posts a persistent reminder that guides the user
back to Wireless debugging and the one-tap startup flow. Pairing normally does
not need to be repeated while Android retains the host key.

## Security boundaries

- The privileged server binds only to `127.0.0.1`.
- A random 256-bit token authenticates every request.
- The protocol exposes fixed operations only; it does **not** expose arbitrary
  shell execution to the ordinary app process.
- mDNS host fields are ignored. Only locally discovered ports are used against
  `127.0.0.1`.
- The starter command is generated internally; no user-controlled command text
  is interpolated into it.
- GMS restart retains the 2.0.1 threshold, cooldown, daily cap and old/new PID
  verification.
- The server process is independent of Luonnotar's ordinary UID and `:keeper`.

## Third-party components

- `com.flyfishxu:kadb-android:1.3.0` supplies the direct ADB pairing and transport
  implementation under Apache License 2.0.
- Shizuku was used as an architectural and UX reference. Luonnotar does not
  reuse the Shizuku name, icon, package ID or reserved permission names.
- Notices and the Apache License 2.0 text are included in the repository and
  packaged under `assets/third_party/`.

## Known platform boundary

A non-root Android application cannot create a shell-UID process by itself.
Wireless debugging must be enabled after each device reboot and the user must
explicitly start the engine. Root builds may later provide true boot-time
startup, but 2.1.0 does not pretend to bypass this Android boundary.


## Build compatibility

The Android client dependency is pinned to **Kadb Android 1.3.0**, the last pre-2.1 Android artifact line. Kadb 2.1.1, 2.1.2 and 2.1.3 publish Android AAR metadata requiring compileSdk 37, while Luonnotar deliberately remains on compileSdk 36.1 / AGP 8.13.0. Luonnotar therefore uses the 1.3.x blocking compatibility adapter: the older suspend pairing API is bridged on the service worker thread, and the older in-memory `KadbCert` identity is persisted by Luonnotar in device-protected private storage.
