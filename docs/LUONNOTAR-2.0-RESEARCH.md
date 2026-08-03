# Luonnotar 2.0.0 research register

Research snapshot: 2026-08-01. Sources are ranked by authority. Community reports are leads, not implementation proof.

## Official Android / AOSP

1. **Cached apps freezer**  
   https://source.android.com/docs/core/perf/cached-apps-freezer  
   Confirms frozen cgroups, zero CPU execution, TCP socket termination when all processes of an app are frozen, manifest-receiver unfreeze behavior, diagnostics, and the absence of a public client API.

2. **Binder freezer guidance**  
   https://source.android.com/docs/core/architecture/ipc/binder-freezer  
   Confirms synchronous Binder calls into a frozen server can kill it and asynchronous transactions can be buffered/overflow. This is why 2.0 does not blindly hammer frozen GMS with Binder calls.

3. **ActivityManager shell implementation**  
   https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/am/ActivityManagerShellCommand.java  
   Source basis for `am freeze`, `am unfreeze`, process/user parsing, and the `--sticky` path.

4. **Sticky freezer state record**  
   https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/am/ProcessCachedOptimizerRecord.java  
   Documents that a sticky freeze/unfreeze decision remains attached to the process lifetime. This is an AOSP mechanism, not evidence that vivo's independent `fast_freezer` respects it.

5. **Doze / app standby commands and restrictions**  
   https://developer.android.com/training/monitoring-device-state/doze-standby  
   Used only for policy calibration. Doze exemption is not represented as an OEM-freezer exemption.

## Shizuku / Sui

6. **Shizuku API official repository and UserService guide**  
   https://github.com/RikkaApps/Shizuku-API  
   Confirms UserService code runs in another process as shell UID 2000 or root UID 0, supports daemon mode, uses an IBinder/AIDL interface, and requires a special destroy transaction.

7. **Shizuku main repository**  
   https://github.com/RikkaApps/Shizuku  
   Confirms ADB/root privilege differences and that shell capabilities vary by Android version and OEM.

8. **Published API/provider artifacts**  
   https://central.sonatype.com/artifact/dev.rikka.shizuku/api/13.1.5  
   https://central.sonatype.com/artifact/dev.rikka.shizuku/provider/13.1.5  
   Version used by this source tree.

## vivo / OriginOS

9. **OriginOS official feature page**  
   https://www.vivo.com/au/originos  
   vivo publicly describes intelligent scene-based standby and ultra-fast sleep mode. It does not publish an API contract for third-party exclusion from `fast_freezer`.

10. **vivo manuals / support pages**  
    https://www.vivo.com.cn/service/  
    Useful for exposed background-power/autostart settings, but no authoritative public implementation of `fast_freezer`, PEM or QuickFrozen was found.

### Vendor evidence gap

No authoritative public vivo source located during this review specifies:

- the exact `fast_freezer` decision algorithm;
- whether `am unfreeze --sticky` is honored;
- a supported third-party exemption API;
- a stable shell command to permanently exempt arbitrary packages;
- the relationship among PEM, AI Sleep, QuickFrozen and AOSP `CachedAppOptimizer`.

Accordingly, the implementation detects capabilities and requires real-device A/B evidence instead of hard-coding an imagined vivo contract.

## Community and issue evidence

11. DontKillMyApp project and app  
    https://dontkillmyapp.com/  
    https://f-droid.org/packages/com.urbandroid.dontkillmyapp/  
    Supports the broad conclusion that exposed battery/autostart settings differ by OEM. It does not prove a privileged anti-freezer mechanism.

12. XDA vivo/OriginOS reports  
    https://xdaforums.com/search/  
    Reports delayed notifications and the need for high-background-power/autostart settings. Undocumented package deletion/service-call suggestions were rejected as unsafe and version-fragile.

13. r/Vivo OriginOS background reports  
    https://www.reddit.com/r/Vivo/  
    Multiple reports describe apps appearing “running” while ceasing background activity, delayed notifications until unlock, and failures even after normal settings are enabled. These match the project's device evidence, but remain anecdotal.

14. Shizuku issues/discussions  
    https://github.com/RikkaApps/Shizuku/issues  
    Used to account for server death, boot restart and UserService binding failures. Shizuku is a privilege transport, not an infallible watchdog.

15. Tailscale Android issues/discussions  
    https://github.com/tailscale/tailscale/issues  
    Used as background context for VPN process/service liveness. No Tailscale-supported API was found that lets another ordinary app make its process immune to an OEM freezer.

## Conclusions that entered code

- Execution must leave Luonnotar's normal UID.
- Freezer-event response must be event driven with a polling fallback.
- AOSP sticky unfreeze is attempted only after capability detection and is never assumed to defeat vivo.
- Root-only direct cgroup thaw is separated and disabled by default.
- Policy repair is idempotent and periodically rechecked because OEM updates/settings can revert it.
- No automatic GMS force-stop.
- End-to-end controlled push latency, not service PID survival, is the release verdict.
