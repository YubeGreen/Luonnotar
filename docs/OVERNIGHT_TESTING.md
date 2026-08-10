# Low-interference overnight push testing

`tools/luonnotar-overnight.sh` is the macOS unattended observer used by the
`luoovn` shortcut. It is intentionally observation-first: the phone is touched
at the start and end of the window, not every few minutes.

## Quick start

```bash
luoovn --iq -1600
luoovn --pad -1600
luoovn --iq -1600 --no-screen-check
luoovn --iq --force-screen-off -1600
```

`-1600` means the **next local 16:00 on the Mac**. If the time suffix is omitted,
16:00 is the default.

Built-in device selectors:

```text
--iq    100.111.89.64:5555
--pad   100.117.209.84:5555
```

A manual target is also supported:

```bash
luoovn --serial 100.x.y.z:5555 --name test-device -1600
```

### Startup screen-off gate

By default, `luoovn` requires the target to already be non-interactive at
startup. If `dumpsys power` reports an awake/interactive device, the run stops
without sending a screen keyevent. This keeps the default experiment condition
strict and avoids the observer itself turning the display off.

When you explicitly want the observer to put the device to sleep during preflight, use:

```bash
luoovn --iq --force-screen-off -2000
```

`--force-screen-off` checks `dumpsys power` first. If the device is interactive, it
sends Android `KEYCODE_SLEEP` (`223`) once through ADB and verifies that the device
becomes non-interactive before continuing. If the device is already non-interactive,
no keyevent is sent. The pre-action power dump is preserved separately, and no screen
keyevents are sent after the unattended observation window starts.

When that preflight confirmation is not wanted, append:

```bash
luoovn --iq -1600 --no-screen-check
luoovn --pad -1600 --no-screen-check
```

This disables **only** the startup gate. The initial power state is still
captured in the evidence, `luoovn` still sends no screen keyevent, and later
screen/wake evidence is still retained as a possible confounder.
`--skip-screen-check` is accepted as an alias; `--screen-check` /
`--require-screen-off` explicitly restore the default. The environment override
`LUOOVN_REQUIRE_SCREEN_OFF=0` is also supported.

## Interference policy

During the observation window the runner intentionally does **not**:

- poll the device periodically;
- stream Logcat over ADB;
- send screen keyevents after the observation window starts;
- force-stop GMS or WhatsApp;
- call Luonnotar `rescue_*` methods;
- change VPN, freezer, guardian, or experiment configuration;
- keep `/Volumes/SMBProbe/send-events.csv` open.

The sender CSV is copied once at the start and once at the end. By default the
host ADB transport is disconnected after the start snapshot and reconnected for
the end snapshot. If another background job immediately reconnects ADB, the
runner aborts before the unattended window so that the test is not silently
contaminated. Pause that job first, or use `--keep-adb` only when an attached
ADB transport is an intentional test condition. The Mac itself is prevented
from idle system sleep with `caffeinate`; its display is still allowed to sleep.

The only diagnostic preparation is a one-time request for a 64 MiB Android
Logcat ring. Logcat is **not cleared**. Use `--no-ring-resize` if even that
change is undesirable.

## Evidence layout

The output folder follows the older numbered-text capture style:

```text
00-test-info.txt
00-orchestrator.log
01-send-events-start.csv
...
21-logcat-ring-final.txt
22-guardian-events-final.log
23-guardian-status-end.json
...
31-send-events-window.csv
32-analysis-console.txt
40-screen-wake-logcat.txt
41-push-gms-relevant-logcat.txt
42-control-planes-end.txt
43-screen-wake-batterystats.txt
90-delay-summary.txt
99-completed.txt
analysis/
```

The folder is zipped at the end.

`90-delay-summary.txt` is a plain-text automatic delay report. It correlates the
sender window with `push_test_arrival_observed` evidence and reports count,
median, P95, maximum delay, missing sequences, and the longest deliveries. The
existing `tools/analyze-luonnotar-push-session.py` is also run when available,
producing the richer `analysis/` directory.

## Screen wake as a confounder

The final capture includes screen/wake-related Logcat lines and battery history.
These are evidence for checking whether a manual wake coincided with a backlog
release. Their absence is not treated as proof that the display never woke.

## Sender truth

Default sender path:

```text
/Volumes/SMBProbe/send-events.csv
```

Override it with:

```bash
luoovn --iq -1600 --send-events /path/to/send-events.csv
```

The source CSV is never tailed or held open by `luoovn`; this avoids the file
locking/synchronization problems seen in older long-lived watcher workflows.


## Final Logcat fallback

The final capture treats a zero-byte Logcat dump as **unavailable evidence**, not as proof that every send was missing. If timestamped `adb logcat -T` returns an empty file, `luoovn` retries the complete ADB ring and then device-side `shell logcat`. The selected capture path and byte count are written to `21-logcat-ring-final.meta`. If all paths remain empty, `90-delay-summary.txt` reports arrival/missing evidence as unavailable instead of turning every `SEND_RESULT` into a false missing record.
