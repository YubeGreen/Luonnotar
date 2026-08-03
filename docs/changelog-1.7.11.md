# Luonnotar 1.7.11

Version 1.7.11 rebuilds the WhatsApp controlled-delivery evidence chain before
ADB-assisted GMS recovery is allowed to act.

## Notification evidence

- Logs notification-listener connection, disconnection, every WhatsApp callback,
  active-notification scans, parser outcomes, and suppression reasons.
- Treats only message bodies, text lines, current MessagingStyle messages, and
  historical MessagingStyle messages as delivery evidence. Titles and subtitles
  are diagnostic-only and cannot prove delivery.
- Normalizes Unicode separator spaces and invisible format/control characters in
  the strict `PUSH_TEST_<n> yyyy-MM-dd HH:mm:ss[.SSS]` format.
- Supports the platform MessagingStyle parser plus privacy-safe vendor Bundle
  fallbacks without logging ordinary message text, contact names, or group names.
- Scans `activeNotifications` after listener connection and on an explicit ADB
  request. Scan observations are stored as a separate upper-bound watermark and
  never masquerade as a live callback.

## ADB diagnostics

Three `android.permission.DUMP`-protected receiver actions expose only
privacy-safe state:

- `ADB_NOTIFICATION_STATUS`
- `ADB_NOTIFICATION_PRIVACY`
- `ADB_NOTIFICATION_SCAN_ACTIVE`

They report listener persisted/runtime connection, heartbeat age, privacy
acknowledgement, and separate live/scan controlled-test watermarks.

## Windows watcher

`tools/watch-push-test-recovery.ps1` now:

- reopens the sender CSV on every poll with `ReadWrite | Delete` sharing;
- tolerates UTF-8 BOMs, partial final lines, and atomic file replacement;
- ignores historical sender rows from before the watcher session;
- synchronizes persisted live and active-scan watermarks;
- defaults to observation-only and requires `-EnableRecovery` for force-stop;
- suppresses recovery unless privacy, runtime listener state, persisted listener
  state, and heartbeat are healthy;
- performs an active-notification scan twice, including immediately before
  `force-stop com.google.android.gms`;
- captures only `main`, `system`, `crash`, and `radio` logcat buffers.
