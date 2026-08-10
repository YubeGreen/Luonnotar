# Luonnotar 2.6.1 v137 / r300

## Listener self-heal cadence + exact fault injection + delivery-grace owner cleanup

r299 correctly implemented ordinary rebind -> strong shell re-registration, but
the reconciliation call was attached to `tunePackagesLocked()`. The live
configuration uses a 900000 ms tuning interval, so a nominal 120 s escalation
could actually wait roughly 15-30 minutes.

r300 moves listener reconciliation onto the normal engine cycle with an
independent 30000 ms probe interval. With the existing 120000 ms strong
threshold, the strong action should now happen around 120-150 s after a
persistent disconnect.

A new `android.permission.DUMP` protected explicit receiver calls
`NotificationListenerService.requestUnbind()` on the live listener without
revoking notification access. It therefore reproduces the target test state:
system authorization remains present while runtime connection disappears.

During the 60 s controlled-delivery grace, r300 also prevents
`verified_cgroup_frozen_mcs_missing` from creating a freezer recovery campaign.
Raw freezer/socket evidence continues to be observed.

Identity:
- versionCode 137
- versionName 2.6.1
- embedded engine r300
- status schema 62
