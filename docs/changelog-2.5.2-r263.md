# Luonnotar 2.5.2 r263 — edge-triggered vendor defense

## OriginOS freezer corrections

- Vendor-defense shell actions are now triggered by physical freeze edges, not a periodic retry timer.
- One freeze edge can spend at most 4 framework commands; one GMS PID generation can spend at most 12. The counter is charged when a command is reserved, so nested/repeated release phases cannot overrun the episode budget.
- A failed physical thaw no longer retries every few seconds. It waits for a real thaw/refreeze edge or escalates after the bounded no-thaw window.
- `stable_hold` remains one continuous episode and keeps the one-round, non-emergency MCS pulse policy.

## Recovery-owner lifecycle

- The race-closing owner lease now starts only from the bridge's own `FROZEN` record (`phase=pending`, 2 s TTL), not from broad `am_app_frozen` history. This removes `seq=0 phase=never` suppression.
- `stable` and `expired` release recovery ownership immediately.
- `escalating` keeps only a 500 ms handoff lease; the existing 750 ms VendorLock callback can then request the bounded recovery campaign; the existing global campaign cooldown/budget policy still applies.
- Stale VendorLock sequences are rejected.

## Version

- versionName 2.5.2
- versionCode 85
- embedded engine revision 263
- guardian status schema 22
