# Luonnotar 2.6.1 r305 / vCode 142

## Vendor single-target recovery backoff

Overnight r304 evidence showed 1,722 vendor-bridge recovery attempts with only 6 verified recoveries while the extreme keepalive profile was enabled. r305 intentionally leaves the continuous CPU lock, GMS group defense, destructive recovery budgets, and r303/r304 lifecycle/handoff behavior unchanged.

For WhatsApp and Signal single-process freeze handling only:

- A newly observed physical freeze edge still receives an immediate recovery attempt.
- Two fast failures are allowed per burst.
- A continuously frozen episode then backs off for 30s, 60s, 120s, and finally 300s.
- Any observed physical thaw resets the backoff ladder immediately.
- Backoff transitions emit `vendor_refreeze_lock` plus `single_recovery_backoff` diagnostic evidence.

This is a command-storm reduction change, not a reduction of the user-selected extreme keepalive power policy.
