# Luonnotar

## 2.3.7 embedded engine post-connect crash hotfix

- Prevents a timed-out status socket from killing the UID 2000 `app_process` engine with `Broken pipe`.
- Defers live polling during setup and moves the first heavy guardian cycle behind the successful handshake.
- Embedded engine revision is now 237.

## 2.3.6 embedded ADB endpoint hotfix

- Every `_adb-tls-connect._tcp` advertisement is retained as a candidate; a refused stale port no longer prevents trying the next live port.
- Refused endpoints enter a 45-second cooldown, so repeated mDNS advertisements cannot create a two-second retry storm.
- Live refresh will not recursively create new setup generations while discovery, startup, or a terminal failure is already visible.
- “Connected to local adbd” is shown only after the Kadb connection succeeds.
- Embedded engine revision is now 236, forcing replacement of a 2.3.5 shell process after an APK update.


> Android VPN-path guardian and diagnostics tool. Current release identity: **YubeGreen**.  
> Current local version: **2.3.7 (versionCode 59)** — package: `com.yubegreen.luonnotar`.

Luonnotar observes and guards the VPN network path, foreground guardian service,
system recovery chain, and local notification-arrival evidence on Android. It is
intended to help diagnose screen-off network or message-delivery delays. It
supports Proton VPN and Tailscale when Tailscale is using an **Exit Node**.

## What it does

- Runs a dedicated `:keeper` foreground service and exposes liveness, lock,
  network, and recovery evidence in the UI.
- Performs DNS, fresh HTTPS 204, and diagnostic mtalk DNS/TCP checks only
  through the Android `Network` currently confirmed to have `TRANSPORT_VPN`.
  If VPN is absent, probes stop; Luonnotar never falls back to direct traffic.
- Defaults vivo/iQOO devices to `IQOO_COOPERATIVE`: screen-off begins with a
  120-second quiet window, network tasks use at most a scoped 10-second CPU
  lock, and permanent CPU/high-performance Wi-Fi locks are disabled.
- Offers an explicit `LAB_EXTREME` A/B profile with levels L0–L4. The levels
  progress from observation only, through scoped CPU and event/periodic probes,
  to permanent locks at L4. High-risk switches remain independently reversible.
  Automatic mtalk diagnosis stays off in every preset.
- Offers `ADB_PASSIVE` as a one-shot ADB invisibility verification: the guardian runs a 60-second
  verification window, then disables its foreground service, locks, callbacks,
  ticks, probes, and recovery alarms while leaving user-applied ADB settings
  and the independently hosted NotificationListener available.
- Uses a 30-second scheduler tick in `IQOO_COOPERATIVE`; structural VPN
  recovery can break the screen-off quiet window, while periodic, startup, and
  screen-event noise cannot.
- Monitors VPN/default-network changes, validation capability, service
  heartbeat, probe outcomes, recovery alarms, and notification-channel state.
- Builds a privacy-safe structural VPN session fingerprint from the provider,
  handle, interface, addresses, DNS, route set, MTU, underlying handles, and
  bypassability. Transient blocked/suspended health changes are not mislabeled
  as session rebuilds, and raw transport information is not stored.
- Treats vivo `EXTWIFI` and Wi-Fi interface evidence as Wi-Fi underlay
  fallbacks, avoiding a false Wi-Fi-lock release after history expires.
- Identifies Proton/Tailscale from the Android VPN owner when available and
  distinguishes Tailscale tailnet-only routes from an Exit Node by inspecting
  VPN IPv4/IPv6 default routes.
- Uses an Android-constrained recovery chain: exact alarm, inexact insurance
  alarm, WorkManager, and a user-actionable recovery notification.
- Optionally observes notification events from WhatsApp, WhatsApp Business,
  and GMS as local notification-arrival evidence.
- Persists strict `PUSH_TEST_<n>` delivery evidence (sequence, sender time,
  listener time, and approximate end-to-end delay). MessagingStyle backlogs
  select the newest controlled timestamp, while duplicate notification updates
  cannot overwrite the first observed arrival.
- Emits privacy-safe callback, parser, active-scan, and controlled-arrival events.
  Live callbacks and active-notification upper bounds are persisted separately.
- Ships `tools/watch-push-test-recovery.ps1`, which reopens sender CSV snapshots
  without holding iCloud files, tolerates partial/atomic replacement, and checks
  privacy, listener runtime/persisted state, heartbeat, and both watermarks.
  It is observation-only by default; `-EnableRecovery` is required before the
  bounded ADB-assisted GMS rebuild path can run.
- Adds explicit experiment sessions with start/mark/stop events stored in the
  `:keeper` process. Every diagnostic record carries the active session ID/name,
  while the shell-only ContentProvider bridge remains protected by
  `android.permission.DUMP`.
- Ships `tools/analyze-luonnotar-push-session.py` plus a PowerShell launcher.
  The analyzer pairs WhatsApp MNS EOF events with reconnect attempts, extracts
  persisted PUSH_TEST delays, groups backlog releases, and compares the same
  sequence across multiple device captures.
- Retires the automatic periodic GMS Binder pulse: repeated successful 15-second
  Binder/query cycles did not reliably restore the private MCS session. The
  manual 15-second pulse remains available only as a laboratory action.
- The shell/root guardian watches both AOSP `am_app_frozen` events and vendor
  delivery failures observed on HyperOS (`Greezer Denial`, `UidFrozen`, and
  cancelled C2DM callbacks). A matching target first receives a bounded sticky
  unfreeze burst. If WhatsApp delivery is cancelled again as a separate episode,
  Luonnotar escalates to rate-limited `am kill` process rebuilding without ever
  force-stopping WhatsApp.
- GMS recovery now has an active transport-health tier. The engine samples
  established FCM/MCS ports, correlates sustained loss with GMS
  `BAD_AUTHENTICATION` or an explicit stalled MCS reconnect attempt, and only then enters
  the existing cooldown-limited deep recovery. A replaced PID is no longer
  counted as success unless an MCS transport socket is observed afterward.
- The privileged engine continuously exposes redacted, token-free diagnostics
  at `/data/local/tmp/luonnotar-guardian-status.json` and rotating JSONL events
  at `/data/local/tmp/luonnotar-guardian-events.log` so a failure can be captured
  even when the authenticated embedded-engine endpoint is unavailable.
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

`VPN_PATH_HEALTHY` means that the current guardian and VPN session have recent
DNS/HTTPS path evidence and that the applicable VPN conditions are met. It
does **not** mean `FCM_DELIVERY_VERIFIED`. A successful mtalk TCP connect means
only that the selected host/port was reachable through this VPN; the GMS MCS
socket remains unknown.

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
| `VPN_BLOCKED` / `VPN_SUSPENDED` | Android reports the current VPN blocked or missing `NOT_SUSPENDED`. |
| `VPN_SESSION_INCOMPLETE` | Capabilities, LinkProperties, or a routed session fingerprint are incomplete. |
| `VPN_DNS_STALLED` | Network-scoped, no-cache DNS checks are failing for the current session. |
| `VPN_HTTPS_STALLED` | Fresh HTTPS checks are failing for the current session. |
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
the current boot ID, provider, VPN network handle, and VPN session fingerprint.
They are invalidated by a boot, provider/session change, explicit VPN loss, or
new contrary evidence. After 60 minutes unchanged evidence is displayed as
`STALE`, not reclassified as `NOT_ROUTED`. Luonnotar recomputes an import
fingerprint, but it cannot independently prove the computer's `dumpsys`
conclusion; the UI therefore calls this **ADB imported evidence**, not
app-owned verification.

`tools/test-iqoo-freezer.ps1` captures standard per-target UID state for
Luonnotar, Proton, Tailscale, GMS, WhatsApp, and WhatsApp Business. Its
DUMP-protected import contains only structured process/freeze/background/
standby/netpolicy/stopped/notification facts and sanitized event categories;
it does not import ordinary notification or chat text.

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

On vivo/iQOO, the default `IQOO_COOPERATIVE` profile is intentionally quiet:

- screen-off, screen-on, and user-present are timeline events only;
- the first 120 seconds after screen-off suppress automatic DNS, HTTPS, mtalk,
  routine notification refresh, and unchanged network-state persistence;
- startup VPN/default-network/Tailscale callbacks are aggregated for two
  seconds into at most one lightweight startup DNS check;
- a scoped CPU lock is held only around a necessary network task and is
  released in `finally`; permanent CPU and high-performance Wi-Fi locks stay
  off;
- explicit VPN loss/blocking and a user-requested manual diagnostic may break
  the quiet window.

The Liquid Glass **Guardian strategy and A/B experiments** sheet also exposes
`LAB_EXTREME` L0–L4. Increase one level at a time and compare real delivery,
GMS/WhatsApp freeze counts, heat, and battery use. If stronger settings make
delivery worse, return to the previous level. The app never disables vivo
QuickFrozen, PEM, or cleaner components by itself.

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
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\test-iqoo-sticky-unfreeze.ps1 -Mode Baseline
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\test-iqoo-sticky-unfreeze.ps1 -Mode StickyUnfreeze
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\tools\create-clean-source-archive.ps1
```

The iQOO script requires exactly one online ADB device and reports
`NO_DEVICE` or `MULTIPLE_DEVICES` instead of claiming an unexecuted test. It
installs the release APK, verifies the cooperative screen-event quiet period,
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

The current 1.6.8 release files are:

- `Luonnotar-1.6.8-debug.apk`
- `Luonnotar-1.6.8-YubeGreen-release.apk`
- `Luonnotar-1.6.8-source.zip`

The 1.6.8 build completed 130 unit tests with zero failures, Lint with zero
errors and 119 warnings, and Debug and signed Release APK assembly. The clean
source archive passed its sensitive path/content scan.

SHA-256:

- Debug APK: `59417AEF5026A6A4FCF2BF96D03EA37105248C96B46CA62999F0F63DB824EE30`
- Release APK: `C0CEC98D5FA3B00E4EAF22DCEF1B58880821AE4B479BB48FE27C97064BD503B4`

The APK verifies under Android APK Signature Schemes v2 and v3 with the
YubeGreen certificate. A successful build or VPN path probe must not be
inferred as successful FCM/WhatsApp delivery.

No connected-device test is claimed for this release. Earlier V2352A
observations remain historical diagnostics, not validation for 1.6.8.
