# Luonnotar 2.4.1 r8

- Version code 70; privileged engine revision 248; status schema 14.
- Replaces the recovery lease pulse train with one continuously connected GoogleApiClient Binder anchor.
- Reuses the same Binder client for read-only location-settings queries every 750 ms and reconnects after suspension/failure in 250 ms.
- Lease refreshes extend the existing deadline without replacing or disconnecting a healthy client.
- Holds a bounded partial wakelock only for the active 120-second stabilization lease.
- Adds a dedicated GMS fast-thaw executor during recovery campaigns; AOSP fast_freezer signals bypass policy tuning and immediately issue sticky unfreeze before asynchronous verification.
- Exposes fast-thaw attempt, success, coalescing, and freeze-to-unfreeze latency metrics in status schema 14.
- On vivo, when the second-stage force-stop is blocked by the 10-minute/daily gate, keeps the continuous Binder anchor instead of wasting the stage on another stop-app.
