# Luonnotar 2.6.0 — engine r294

## Transactional shell-engine handoff

- Replaces the r260–r293 predecessor-first handoff with a two-engine transaction for r294 and later engines.
- The old engine stays operational while a candidate from the installed APK starts on a temporary authenticated loopback endpoint.
- The candidate must prove the exact expected engine revision and complete preflight while the predecessor continues serving its watchdogs and primary control endpoint.
- A handoff-exclusion lock prevents unrelated starters from racing the primary lock transfer.
- The predecessor transfers only the singleton engine lock first; the candidate then starts its watchdogs, verifies the provisioned SSH rescue layer, and records durable `candidate_ready` state on its temporary endpoint.
- Only after the candidate is READY is the persisted primary control endpoint transferred and exact-revision takeover verified; the predecessor stops only after `TAKEOVER_CONFIRMED`.
- Failure paths destroy the candidate and restore the predecessor endpoint/lock instead of intentionally leaving the device without a READY engine.
- r293 -> r294 is necessarily the one migration that still begins in the legacy r293 process; transactional guarantees apply once r294 is the running predecessor.

## Shell-owned SSH rescue daemon

- Adds a dedicated UID 2000 SSH server process (`luonnotar_shell_sshd`) on port `8025` by default.
- The SSH daemon is independent of the Luonnotar app process, Termux, and the guardian engine process.
- Public-key authentication only; login user is `shell`.
- Persistent host key: `/data/local/tmp/luonnotar-ssh/ssh_host_key`.
- Persistent authorized keys: `/data/local/tmp/luonnotar-ssh/authorized_keys`.
- Existing SSH sessions are not intentionally terminated during an r294+ engine handoff because the daemon process is adopted rather than restarted.

## SSH Guardian

- Dedicated 5-second watchdog independent from the GMS guardian cycle.
- Health requires all three: exactly one expected daemon PID, a LISTEN socket on the configured port, and a valid localhost SSH identification banner.
- A failed health check stops stale daemon instances, launches the daemon from the current APK, and verifies health again.
- Recovery backoff: 5 s -> 15 s -> 30 s -> 60 s -> 5 min.
- Host and authorization state survive APK/engine updates.
- Missing `authorized_keys` is reported as `unprovisioned` and does not cause a restart storm.

## Recovery-plane observability

Engine status schema 53 adds:

- `sshGuardian`
- `networkHealth.LOCAL_SSH_OK`
- `networkHealth.TAILSCALE_CONTROL_OK`
- `networkHealth.TAILSCALE_INGRESS_OK` (currently unknown until the external-probe phase)
- `networkHealth.ADB_5555_OK`

This intentionally distinguishes a healthy local SSH service from Tailscale ingress health. r294 does not yet perform external Tailscale ingress probes or the recovery ladder; those remain later phases.
