# Luonnotar 1.7.17

## Scope

This release adds diagnostic experiment infrastructure only. It does not add a
new keepalive, network request, wake lock, screen guard, GMS action, or WhatsApp
automation path.

## Changes

- Updated version to `1.7.17` / `versionCode 43`.
- Added persistent experiment sessions in the device-protected `:keeper`
  preference store:
  - start with a normalized session name;
  - add normalized event marks;
  - stop with a measured elapsed duration;
  - reject accidental overwrite of an already-active session.
- Added privacy-safe structured timeline events:
  - `experiment_session_started`;
  - `experiment_session_marked`;
  - `experiment_session_stopped`.
- Session start records the guardian profile, lab level, enabled experiment
  switches, screen/idle state, CPU/Wi-Fi lock state, and VPN evidence already
  available to Luonnotar.
- Every normal Luonnotar JSONL record now includes the active experiment session
  ID, name, active state, and age.
- Extended the shell-only `android.permission.DUMP` ContentProvider bridge with
  synchronous experiment start/mark/stop methods and session status fields.
- Added `tools/set-adb-experiment-session.ps1` for unattended remote tests.
- Added `tools/analyze-luonnotar-push-session.py` and a PowerShell launcher. The
  analyzer:
  - pairs WhatsApp MNS TCP EOF with the next reconnect attempt and connection;
  - reads persisted PUSH_TEST sender/arrival epochs and delays;
  - groups backlog releases;
  - correlates send-events.csv;
  - reports missing arrival evidence within the observed window;
  - compares the same sequence across multiple device captures;
  - extracts experiment session events.
- Fixed Windows PowerShell generic-list splatting in
  `set-adb-runtime-config.ps1` by converting arguments to `string[]` and calling
  `adb.exe` explicitly.
- Updated diagnostic manifest format to version 3 with
  `experimentSessionIncluded=true`.

## Deliberately unchanged

- Persistent HTTPS heartbeat remains available only for compatibility and is
  still default-off.
- Persistent network lease, periodic DNS/HTTPS, automatic mtalk, and frequent
  notification refresh behavior are unchanged.
- No Display Guard or active WhatsApp recovery mechanism is included in this
  release.
