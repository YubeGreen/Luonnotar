# Luonnotar 2.6.1 v139 / engine r302

## NotificationListener strong recovery: post-allow rebind

r301 deterministic sticky-unbind acceptance reached STRONG_REREGISTER at ~136 s,
successfully executed `disallow_listener` and `allow_listener`, and released the
test fixture, but the listener remained unbound on OriginOS.

r302 keeps the strong authorization cycle unchanged and, after a successful
`allow_listener`, explicitly invokes the existing ordinary ACTIVE_SCAN/requestRebind
path before verification. If the first 5 s verification is still unhealthy, one
additional bounded requestRebind + 5 s verification is performed.

No GMS transport/freezer policy is changed.
Status schema remains 63.
