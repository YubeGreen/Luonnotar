# Luonnotar 1.7.16

## Changes

- Replaced the raw mtalk TCP experiment with an app-owned persistent TLS/HTTP heartbeat channel.
- The new channel sends an HTTPS heartbeat every 25 seconds over the active VPN network.
- Added TLS hostname verification, HTTP response validation, connection lifetime tracking, heartbeat counters, and per-session diagnostics.
- Added exponential reconnect backoff and reconnect-storm cooldown protection.
- Migrates the old `persistent_mtalk_socket` toggle to `persistent_heartbeat_socket`.
- Preserves `-PersistentMtalkSocket` as a PowerShell alias for compatibility.
- Updated version to `1.7.16` / `versionCode 42`.

## Important Boundary

The heartbeat channel does not connect to mtalk, does not implement MCS, and does not impersonate Google Play services. It is an application-owned long-lived traffic experiment only.
