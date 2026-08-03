# Luonnotar 1.6.8

- Fixed deep-sleep heartbeat checks being mistaken for service death.
- Fixed keeper clearing NotificationListener state owned by the main process.
- Fixed notification removal and destruction paths blocking system callback threads.
- Fixed the NotificationListener automatic rebind chain.
- Fixed stale cleanup broadcasts cancelling newer recovery work.
- Fixed underlay state not refreshing after a physical network is lost.
- Unified target UID routing verification.

This release does not claim to solve QuickFrozen or guarantee WhatsApp delivery.
