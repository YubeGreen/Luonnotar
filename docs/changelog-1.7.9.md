# Luonnotar 1.7.9

## Evidence-driven strategy change

OriginOS testing showed that automatic 15-second GMS Binder pulses could complete
all eight binds and read-only queries, and could even provoke a TCP connection to
port 5228, while the GMS MCS session and WhatsApp delivery remained stalled across
multiple four-minute cycles. Version 1.7.9 therefore removes the automatic pulse
from the guardian tick. The existing manual 15-second pulse remains available as
a controlled laboratory action, not a claimed recovery mechanism.

## Controlled PUSH_TEST evidence

- Reads the normal notification text fields plus MessagingStyle current/historic
  message arrays and text lines.
- Persists only strict `PUSH_TEST_<n> yyyy-MM-dd HH:mm:ss[.SSS]` metadata.
- Selects the newest strict message from a MessagingStyle backlog.
- Uses sender timestamp as the primary session order and rejects duplicate
  notification updates so the first observed arrival time is preserved.
- Displays the latest controlled sequence, approximate end-to-end delay, and
  freshness in the main status panel.
- Emits `push_test_arrival_observed` for host-side correlation.

Ordinary chat text is not persisted.

## ADB-assisted recovery companion

`tools/watch-push-test-recovery.ps1` tails the sender's `send-events.csv` and a
Luonnotar logcat stream gated by a unique per-run marker. It correlates sends and
arrivals by sequence plus sender timestamp, so sender sequence resets and stale
logcat history cannot create a false match. After at least one known-good arrival,
a message that exceeds the configured timeout causes the script to:

1. save the complete pre-recovery logcat;
2. record an explicit test marker;
3. run `am force-stop --user 0 com.google.android.gms` as the ADB shell user;
4. wait for a replacement `com.google.android.gms.persistent` PID;
5. keep watching for the queued PUSH_TEST arrival.

The script defaults to a 25-second timeout and a three-minute recovery cooldown.
Use `-ObserveOnly` for a non-mutating dry run. This is intentionally an ADB test
and recovery tool; the APK itself still cannot force-stop GMS.

The watchdog suppresses recovery while the notification listener is explicitly
disconnected. `-ObserveOnly` reports at most once per cooldown window instead of
spamming the same timeout. Automatic recovery is also capped at two attempts per pending incident by default.
