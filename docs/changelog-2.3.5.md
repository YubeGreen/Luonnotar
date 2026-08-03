# Luonnotar 2.3.5

## Embedded engine lifecycle

- `recover_gms` is a short request/ack operation; the long PID and MCS verification runs on a dedicated engine worker.
- Status or operation timeouts preserve the green UID 2000 state when an independent 2-second authenticated ping succeeds.
- Ping now carries engine revision `235`; stale engines loaded from an older APK are rejected and replaced through the existing local ADB authorization.
- `MY_PACKAGE_REPLACED` starts an automatic repair attempt.
- Setup generations are coalesced while discovery/pairing/startup is already active.
- Explicit disable clears the app-side paired flag. Strong ADB authentication evidence also resets the persisted host identity and returns to pairing instead of looping forever.

## UI

- The GMS button explicitly reports that recovery is queued and verified asynchronously.
- The header uses the actual APK version name.

## Unchanged

- Liquid-glass renderer, visual/motion files, vendor-freezer recovery, background policy, notification delivery and guardian target configuration are unchanged.
