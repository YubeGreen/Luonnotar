# Luonnotar 2.6.1 v136 / r299

## Delivery truth + NotificationListener hard self-heal

- `GmsTransportIncident.startedElapsed` is now the real per-generation open time.
- `outageSinceElapsed` preserves the older underlying transport outage origin.
- Controlled delivery can no longer be reused by a later incident.
- Incident duration is measured from the incident open, not the inherited outage.
- A real controlled delivery suppresses transport/freezer recovery reopen for 60 s
  without falsifying the raw socket-health observation.
- The shell engine independently guards NotificationListener health.
- Ordinary requestRebind is tried first; after 120 s of continued failure the
  shell engine performs `disallow_listener -> allow_listener`.
- Strong listener recovery verifies privacy acknowledgement and existing system
  authorization before mutation, retries allow up to three times, verifies the
  fresh listener heartbeat, and has a 15 minute cooldown.
- v135/r298 transactional self-update/handoff behavior is otherwise unchanged.
- Status schema: 61.
