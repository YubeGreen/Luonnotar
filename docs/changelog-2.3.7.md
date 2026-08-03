# Luonnotar 2.3.7

## Embedded engine post-connect crash hotfix

- Catch client disconnect/write failures inside every embedded server worker so a timed-out status caller cannot terminate the shell `app_process` engine with `SocketException: Broken pipe`.
- Defer ordinary live-status polling while the setup service is discovering, waiting for a pairing code, or starting/configuring the engine.
- Return from `configureAndStart` before the first full guardian cycle; schedule that cycle shortly afterward so the initial handshake is not blocked by process scans, unfreeze commands, background-policy commands, and transport probes.
- Cancel the deferred initial cycle when the engine is stopped or destroyed.
- Bump app version to 2.3.7 (59) and embedded engine revision to 237.
