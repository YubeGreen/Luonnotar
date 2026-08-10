# Luonnotar 2.6.1 v138 / r301

## Deterministic NotificationListener strong-recovery acceptance fixture

r300 proved:
- DUMP-only requestUnbind fault injection preserves NotificationListener access.
- the listener's own soft requestRebind can recover in under 15 seconds.
- the shell listener guardian now probes on an independent ~30 second cadence.

That success made the strong shell path impossible to trigger reliably on demand.

r301 adds a time-bounded in-memory sticky-unbind fixture:
- default 5 minutes, minimum 150 seconds, maximum 10 minutes
- never changes the user's notification-access authorization
- suppresses the listener's own local rebind loop while active
- immediately re-unbinds if Android reconnects the listener by another path
- lets the shell guardian record its ordinary attempt but suppresses the actual
  ordinary ADB rebind only while the fixture is active
- immediately before STRONG_REREGISTER, the shell engine releases the fixture
  and then runs the real disallow_listener -> allow_listener recovery
- process death or timeout clears the fixture even if the test is abandoned

ADB test actions:
- ADB_NOTIFICATION_TEST_UNBIND
- ADB_NOTIFICATION_TEST_STICKY_UNBIND
- ADB_NOTIFICATION_TEST_STATUS
- ADB_NOTIFICATION_TEST_RELEASE

Identity:
- versionCode 138
- versionName 2.6.1
- embedded engine r301
- status schema 63
