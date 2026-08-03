# Luonnotar 1.6.6 — Release Audit

Date: 2026-07-26  
Package: `com.yubegreen.luonnotar`  
Version: `1.6.6` / `versionCode 23`

## Pass 1 — Concurrency and probe ownership

- Removed the stale-Tailscale veto from generic VPN probing. A non-current
  Tailscale record is diagnostic evidence only and cannot block Proton.
- Tailscale records now prefer the expected current handle, then an identified
  Tailscale owner, then Quad100 inference, with callback time only as a
  same-priority tie-breaker.
- Probe requests carry their plan and trigger class through process-permit
  retries. Forced requests merge by priority:
  `MANUAL_DIAGNOSTIC > HTTPS > MTALK > DNS`.
- Structural recovery is the only automatic trigger allowed to break the
  iQOO quiet window. Screen, startup, and periodic events remain suppressed.
- Hard-restart alarms retain nonce/PID/generation/permit ownership and are
  capped at three attempts and 15 seconds.

Verification: probe-gate stress tests, policy tests, Kotlin compilation, unit
tests, and release assembly.

## Pass 2 — Android lifecycle and recovery

- Screen-on and user-present clear the quiet window and flush deferred VPN and
  Tailscale evidence. Quiet-window expiry also flushes on the next cooperative
  tick.
- Semantic loss/blocked state remains immediately persistent; structural
  recovery flushes the recovered state before probing.
- Boot `ENSURE_ENABLED` maintenance is boot-scoped and idempotent instead of
  being rejected when the asynchronously started Service increments its
  generation. Destructive one-time recovery retains generation validation.
- NotificationListener callbacks enqueue immutable, privacy-minimized events
  to a local single-thread batch queue and return immediately. Provider writes
  use `apply()`, listener destruction clears state, and a freshness heartbeat
  prevents a permanent stale boolean.
- Android 26/27 no longer infer Tailscale suspension from a capability that is
  only meaningful on Android 9+.
- `IQOO_COOPERATIVE` uses a 30-second tick. `ADB_PASSIVE` stops all persistent
  guardian resources after its 60-second verification window.

Verification: repeated compile/test runs and inspection of Receiver, callback,
executor, lock, and Service stop paths. No ADB device was connected for this
release audit.

## Pass 3 — Privacy, evidence semantics, and release safety

- Notification queue records only package identifiers, hashes, timing,
  grouping flags, and explicit test markers; it never records message bodies.
- Failed ADB health imports clear the old capture wall. Successful imports
  accept capture times only when start/finish ordering is valid and within a
  bounded clock tolerance.
- A global scan of unrelated validated physical networks is now labelled
  `POSSIBLE_UNDERLAY_*`; it cannot authorize a high-performance Wi-Fi lock.
- Route reflection failure produces `UNKNOWN`, not a false assertion that a
  Tailscale route is unusable.
- HTTPS remains labelled VPN path evidence, never FCM/WhatsApp delivery proof.
- Clean-source packaging excludes signing material, local properties, build
  products, logs, device data, and repository metadata, then scans the archive
  staging area for secret indicators.

Verification: lint, signed artifact inspection, clean-source scan, checksums,
and archive content review.
