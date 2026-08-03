# Luonnotar 2.0.0 acceptance protocol

2.0.0 is a candidate until the following device tests pass. A successful build or `am unfreeze` output is not acceptance.

## Build and static gates

- Version must be `2.0.0` / versionCode 44.
- Debug unit tests, lint, debug APK and signed release APK must complete on the maintainer machine.
- Source and release archives must contain no `keystore.properties`, JKS/keystore, signing password, API key, `local.properties`, APK or AAB from another build.
- The privileged source must contain no GMS `force-stop` production path.
- Shell script passes `bash -n`; PowerShell scripts parse on Windows PowerShell 5.1 and PowerShell 7.

## Engine identity gate

With the integrated engine enabled:

1. UI status reports UID 2000 (Shizuku/ADB) or UID 0 (Sui/root).
2. `adb shell ps -A | grep luonnotar` shows the UserService outside the APK app UID.
3. Killing/freezing `com.yubegreen.luonnotar` and `com.yubegreen.luonnotar:keeper` does not stop the UserService loop.
4. Shizuku server death is reported as loss of execution, not hidden as success.

The standalone shell engine must similarly survive the APK UID being frozen/killed.

## Freezer response gate

Capture `events`, system logcat, engine status and engine log.

- A target `am_app_frozen` event must produce an engine `freezer_event_observed` and unfreeze cycle.
- Event-path response target: <=5 seconds from event to action.
- If events are unavailable, polling fallback target: <=20 seconds with the default 15-second poll.
- New target PIDs receive immediate sticky/unfreeze action.
- No Luonnotar-generated `force-stop`, package-data clearing or package disabling occurs.
- If vivo immediately refreezes the process despite sticky unfreeze, record this as a failed mechanism, not a pass.

## End-to-end iQOO matrix

Run each cell for at least eight hours, unplugged, screen off. Randomize/cross over order on the same device.

| Cell | OriginOS Sleep/standby | 1.7 app-UID guardian | 2.0 privileged engine |
|---|---|---|---|
| A | On | On | Off |
| B | On | On | Shizuku UserService |
| C | On | On | standalone shell engine |
| D | Off | On | Off |
| E | Off | On | Shizuku UserService |

During every cell:

- continuously generate numbered `PUSH_TEST` messages;
- preserve sender CSV, notification listener evidence, events/system logcat and engine status;
- record WhatsApp EOF/reconnect evidence where visible;
- probe Tailscale ADB reachability without waking the screen;
- do not touch the UI or manually reconnect during the measurement window.

## Release verdict

The privileged engine passes only if improvement follows its ON/OFF state across repeated crossovers:

- no long PUSH_TEST backlog attributable to frozen GMS/WhatsApp;
- materially lower p95 and worst-case delivery latency than cell A;
- materially shorter EOF-to-reconnect windows;
- target freezer events are followed by prompt action;
- Tailscale ADB remains reachable materially more often overnight;
- no serious authentication breakage, reboot loop, system_server instability or unacceptable drain.

Suggested first quantitative target, subject to the baseline distribution:

- no controlled push delayed more than 60 seconds;
- p95 delivery under 15 seconds;
- no freezer event left without an engine action for more than 20 seconds;
- no Luonnotar-caused GMS process replacement.

If those numbers fail, keep the build marked experimental and use the evidence to choose the next mechanism. Do not relabel diagnostic value as anti-freezer success.

## Evidence command

```powershell
.\tools\test-luonnotar-2.0-engine.ps1 -Serial 127.0.0.1:5132
```

For the standalone engine:

```powershell
.\tools\install-luonnotar-2.0-shell-engine.ps1 -Action Install -Serial 127.0.0.1:5132
```
