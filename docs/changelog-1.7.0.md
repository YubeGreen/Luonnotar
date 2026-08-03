# Luonnotar 1.7.0

- Added a user-visible Screen-off CPU Guard switch.
- The switch works across manufacturers and in STANDARD mode.
- iQOO cooperative mode enables Screen-off CPU Guard by default.
- STANDARD mode keeps it disabled by default for clean A/B testing.
- ADB passive verification always ignores continuous CPU locks.
- Screen-off CPU Guard never enables the high-performance Wi-Fi lock.
- Added an independent persisted status for the continuous WakeLock.
- The dashboard now shows configuration, screen state, and actual continuous-lock state.
- Added STANDARD to the runtime-profile selector.
- Preserved the separate scoped 10-second CPU WakeLock.
- Preserved all 1.6.8 and 1.6.9 lifecycle, generation, cleanup, and rebind fixes.