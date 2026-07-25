# Luonnotar

> Android VPN-path guardian and diagnostics tool. Current release identity: **YubeGreen**.  
> Current local version: **1.6.3 (versionCode 20)** — package: `com.yubegreen.luonnotar`.

Luonnotar observes and guards the VPN network path, foreground guardian service,
system recovery chain, and local notification-arrival evidence on Android. It is
intended to help diagnose screen-off network or message-delivery delays. It
supports Proton VPN and Tailscale when Tailscale is using an **Exit Node**.

## What it does

- Runs a dedicated `:keeper` foreground service and exposes liveness, lock,
  network, and recovery evidence in the UI.
- Performs low-frequency HTTPS 204 probes only through the Android `Network`
  currently confirmed to have `TRANSPORT_VPN`. If VPN is absent, probes stop;
  Luonnotar never falls back to direct traffic.
- Uses a Partial WakeLock and acquires a Wi-Fi lock only when a Wi-Fi underlay
  is detected. Transient unknown-underlay callbacks do not immediately release
  an already-held lock.
- Offers an opt-in vivo/iQOO aggressive mode: the normal five-minute VPN-path
  cadence becomes 30 seconds while the screen is off, with immediate probes on
  screen and relevant VPN transitions.
- Monitors VPN/default-network changes, validation capability, service
  heartbeat, probe outcomes, recovery alarms, and notification-channel state.
- Identifies Proton/Tailscale from the Android VPN owner when available and
  distinguishes Tailscale tailnet-only routes from an Exit Node by inspecting
  VPN IPv4/IPv6 default routes.
- Uses an Android-constrained recovery chain: exact alarm, inexact insurance
  alarm, WorkManager, and a user-actionable recovery notification.
- Optionally observes notification events from WhatsApp, WhatsApp Business,
  and GMS as local notification-arrival evidence.
- Provides Liquid Glass guides for device-specific ADB stability and routing
  checks.

The primary guardian control is the **Core action** card directly below the
app header:

- Disabled: **Enable Extreme Guardian**
- Paused: **Resume Extreme Guardian**
- Running: **Stop Extreme Guardian**

## What it does not do

Luonnotar is not a VPN, an FCM client, or a WhatsApp modification tool.

- It does not implement `VpnService` and cannot create or control a
  Proton/Tailscale tunnel.
- It does not root, hook, or use Accessibility to automate a VPN app.
- It does not read, forge, or directly connect to private WhatsApp/GMS FCM
  tokens, sockets, or heartbeats.
- It cannot bypass every Android Doze, OEM background-freezing, or Android 12+
  background foreground-service restriction.
- It does not call one `connectivitycheck.gstatic.com` HTTPS 204 result an FCM
  or WhatsApp delivery verification.

`VPN_PATH_HEALTHY` means that the current guardian generation has recent HTTPS
204 evidence on the current VPN network handle and that the applicable VPN
conditions are met. It does **not** mean `FCM_DELIVERY_VERIFIED`.

## Quick start

1. Install the APK and read the first-run policy.
2. Establish a tunnel in Proton VPN or Tailscale.
3. For Tailscale, enable an **Exit Node** that carries public Internet traffic.
   Joining only a tailnet does not prove that the FCM public path uses the
   tunnel.
4. Tap **Enable Extreme Guardian** on the Luonnotar home screen.
5. Allow notifications, background activity/high-power use, unrestricted
   battery mode, and recents-task locking for both Luonnotar and the active
   VPN app.
6. To observe notification arrival, enable **Notification arrival verification
   mode** and grant notification-listener access when Android asks.

Split-tunnel setups may keep Android's *Block connections without VPN*
(Lockdown) disabled. The user must ensure that GMS, WhatsApp, and WhatsApp
Business are not excluded by Proton/Tailscale routing rules. A normal APK
cannot supply system-wide traffic blocking when the VPN itself disconnects.

## State reference

| State | Meaning |
| --- | --- |
| `WAITING_FOR_VPN` | Guardian is enabled, but no VPN has been observed in this boot session. |
| `VPN_LOST` | VPN was observed previously and is now absent; HTTPS probing has stopped. |
| `VPN_UNVALIDATED` | VPN exists, but Internet validation is currently insufficient. |
| `NO_SUCCESS_EVIDENCE` | The current service generation has no successful VPN-only 204 evidence yet. |
| `KEEPALIVE_DEGRADED` | Probes are failing, or the successful evidence is stale. |
| `VPN_PATH_HEALTHY` | Fresh HTTPS 204 evidence exists for the current VPN path; this is not verified FCM delivery. |
| `PAUSED` | The guardian was paused by the user. |

Green means only that the corresponding prerequisite has been established.
Yellow or unverified does not automatically mean Android is broken; it often
means that a normal APK does not have enough evidence to make a stronger claim.

## Optional ADB evidence

ADB is not required after installation. It is useful only when additional
diagnosis is needed for Always-on VPN, Lockdown, target UID coverage, or
Tailscale Exit Node Internet routing.

Luonnotar accepts a declaration imported through an
`android.permission.DUMP`-protected ADB receiver. Imported fields are bound to
the current boot ID, VPN network handle, and a five-minute validity window.
They are cleared immediately when the VPN changes or disappears. Luonnotar
recomputes an import fingerprint, but it cannot independently prove the
computer's `dumpsys` conclusion; the UI therefore calls this **ADB imported
evidence**, not app-owned verification.

Check system connectivity, VPN management state, and target-UID coverage on
the computer first, then follow the device-specific instructions in the app's
**ADB stability and routing evidence** card.

## Recovery and power limits

While the service stays alive, foreground priority, a CPU lock, and low-rate
VPN-only probes can reduce some idle or path-failure risks. They cannot defeat
all platform restrictions:

- Android Doze restricts ordinary app networking and alarms; allow-while-idle
  cannot guarantee minute-by-minute execution in deep idle.
- Android 12+ normally blocks background foreground-service starts. Inexact
  alarm recovery therefore degrades to a recovery notification that requires
  a user tap.
- On Android 14+, a Wi-Fi high-performance lock must not be interpreted as a
  guarantee of screen-off high-performance Wi-Fi.
- vivo/iQOO, HyperOS, and other OEM policies may still freeze the guardian or
  VPN. The in-app guide offers settings guidance; it cannot silently change
  OEM allowlists.

Luonnotar does not promise a one-minute recovery ceiling and cannot guarantee
that a clean device will match a device that has already been manually tuned
with OEM allowlists.

## vivo/iQOO screen-off diagnostics

On vivo/iQOO devices, enable **vivo/iQOO aggressive keepalive** from the home
screen only after the normal guardian is running. This mode:

- performs an immediate VPN-only probe for screen-off, screen-on, user-present,
  VPN-handle change, VPN recovery, and regained validation events;
- uses a 30-second cadence while the screen is off and returns to five minutes
  while the screen is on;
- keeps only one probe in flight, queues at most one forced follow-up, rejects
  stale results from an old VPN handle, and watches for a stuck probe;
- records a structured timeline for screen, VPN, underlay, lock, timer-drift,
  probe, and target-notification events.

These probes keep Luonnotar's current VPN path active. They do not inspect,
reconnect, or certify the private GMS/WhatsApp FCM socket. A controlled message
body may use `PUSH_TEST_<n>  yyyy-MM-dd HH:mm:ss`; only that strict test pattern
is parsed for sender-to-listener timing. Ordinary message text is never stored.

The in-app Liquid Glass ADB guide generates a PowerShell script that only
touches installed target packages and prints verification output. The stronger
`adb shell dumpsys deviceidle disable` command is deliberately separated as an
A/B diagnostic and is never executed by the app.

## Build

JDK 17 and the Android SDK are required.

```powershell
$env:JAVA_HOME='H:\Android\Unraveller of All Reason\.tools\jdk-17.0.19+10'
.\gradlew.bat testDebugUnitTest lintDebug assembleRelease bundleRelease
```

Outputs:

- APK: `app/build/outputs/apk/release/Luonnotar-release.apk`
- AAB: `app/build/outputs/bundle/release/app-release.aab`

Release validation and source packaging:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\test-iqoo-aggressive.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\create-clean-source-archive.ps1
```

The iQOO script requires exactly one online ADB device and reports
`NO_DEVICE` or `MULTIPLE_DEVICES` instead of claiming an unexecuted test. It
installs the release APK, performs an 80-second screen-off cadence check,
returns the device to an awake state, and exports the timeline, logcat, and
relevant `dumpsys` snapshots. The archive script excludes repository metadata,
signing material, local configuration, builds, logs, and device-test data, then
scans the resulting ZIP for sensitive path and content markers.

The release build reads `keystore.properties` from the project root. Never
commit or disclose that file or the signing key. If the YubeGreen release key
is lost, existing installations cannot receive a same-package signed update.

## Privacy

- Logs, state snapshots, and diagnostic ZIP files remain in app-private local
  storage until the user explicitly exports them.
- Notification-arrival verification observes target-package notification
  events; it is not a server-side WhatsApp or FCM message audit.
- The project has no analytics upload and no backend push-test service.

## Current deliverables and validation

The current local release files are:

- `Luonnotar-1.6.3-YubeGreen-release.apk`
- `Luonnotar-1.6.3-YubeGreen-release.aab`

This version has completed 70 Debug unit tests, Debug lint with zero errors,
Release APK/AAB assembly, and instrumentation-test APK compilation. A real
screen-off result is reported separately and must not be inferred from a
successful build. Long-running conclusions should combine a controlled send
time, target-app notification-arrival time, VPN-routing evidence, and a real
screen-off observation window.

The V2352A observation confirmed Tailscale dual-stack Exit Node routing and
successful screen-off VPN-only probes near 0, 33, and 64 seconds, but two
controlled WhatsApp sends did not arrive. That run is intentionally recorded
as failed FCM delivery despite a healthy VPN path.

The detailed concurrency, Android lifecycle, privacy, and release-archive
review is recorded in `docs/self-audit-1.6.3.md`.
