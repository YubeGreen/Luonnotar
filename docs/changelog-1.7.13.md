# Luonnotar 1.7.13

## ADB runtime configuration transport

- Replaced the primary ADB runtime-configuration transport with a synchronous,
  `android.permission.DUMP`-protected `ContentProvider` call.
- This avoids Xiaomi/HyperOS `Greezer Denial` dropping explicit broadcasts when
  the `:keeper` process is cached while the device is locked.
- The provider preserves unspecified settings, commits the complete runtime
  configuration atomically through `GuardianStatusProvider`, verifies the
  persisted readback, and requests an immediate guardian reload when needed.
- Added caller-UID defense in depth: only the app UID, root, system, or adb shell
  is accepted.
- Kept the 1.7.12 broadcast receiver for compatibility, but the shipped tool no
  longer relies on it.

## PowerShell tool

- `tools/set-adb-runtime-config.ps1` now uses `adb shell content call`.
- Fixed Windows PowerShell nullable Boolean and integer unboxing failures.
- Improved native-command error reporting and provider-result parsing.
