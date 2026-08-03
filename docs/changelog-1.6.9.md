# Luonnotar 1.6.9

- iQOO cooperative profile now holds a continuous PARTIAL_WAKE_LOCK only while the screen is off and guardian is active.
- The continuous screen-off lock is released immediately on screen-on, pause, stop, profile change, or service destruction.
- Continuous and scoped CPU locks are separate WakeLock instances so screen transitions cannot release an in-flight scoped task lock, and scoped completion cannot drop the screen-off guard.
- iQOO tick remains 30 seconds.
- No permanent high-performance Wi-Fi lock was added in this build.
- The 120-second screen-off network quiet window remains.
- Periodic DNS, HTTPS, automatic mtalk, and screen-event probes remain disabled by default for iQOO.
- Added pure GuardianPowerPolicy unit tests and explicit iQOO CPU guard timeline events.