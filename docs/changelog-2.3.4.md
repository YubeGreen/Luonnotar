# Luonnotar 2.3.4

Version: `2.3.4`  
Version code: `56`

## Why this release exists

2.3.3 could observe standard Android freezer state and several HyperOS log
signals, but two real-device failures remained:

1. HyperOS delivered FCM to GMS and then repeatedly rejected the C2DM broadcast
   because WhatsApp was held by Greezer. Standard `am unfreeze` returned without
   changing the vendor-private state.
2. OriginOS kept the privileged engine alive while GMS lost its MCS transport
   and emitted `BAD_AUTHENTICATION`; no `am_app_frozen` event was required, so
   the old automatic recovery trigger remained silent.

## Changes

- Counts delivery-critical HyperOS signals as deduplicated delivery episodes.
- After a second separated episode within ten minutes, rebuilds only the
  WhatsApp/WhatsApp Business process with `am kill --user 0`.
- Never uses `force-stop` for WhatsApp and verifies that the old PID disappears.
- Limits WhatsApp process rebuilding to one per ten minutes and three per 24-hour window during the current privileged-engine run.
- Watches GMS `BAD_AUTHENTICATION` and MCS connection-attempt logs.
- Probes established TCP transport on dedicated ports 5228-5230 every 30 seconds; port 443 counts only when `ss` exposes a GMS owner.
- Allows automatic deep GMS recovery only after sustained transport loss plus
  `BAD_AUTHENTICATION` or an explicit stalled MCS reconnect attempt, while preserving the existing opt-in, cooldown, and daily
  limits.
- Separates PID replacement from actual recovery success: MCS transport must be
  observed after restart before the attempt is counted as verified.
- Adds token-free persistent diagnostics:
  - `/data/local/tmp/luonnotar-guardian-status.json`
  - `/data/local/tmp/luonnotar-guardian-events.log`
- Adds policy, parser, transport, and diagnostic-store unit tests.

## Safety boundaries

- No root-only commands were added.
- No WhatsApp/GMS private token or message content is read. `AuthPII` details are reduced to a redacted counter before persistent logging.
- WhatsApp is never force-stopped.
- Missing port 5228 alone is not enough to restart GMS.
- All destructive GMS recovery remains explicitly opt-in and rate-limited.
