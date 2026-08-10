# Luonnotar 2.6.1 r298 / v135

Transactional handoff concurrency and durability hotfix.

- Prevents `ACTION_MY_PACKAGE_REPLACED` from starting a competing restart when the shell-owned self-update for the just-installed version is already in its handoff phase.
- `EmbeddedAdbService` now honors `requiresAdbRestartFallback()`; ordinary transactional rejection/timeouts no longer fall through to destructive `pidof -> kill` startup.
- Rejects a second handoff transaction in the same primary process instead of treating the existing handoff file lock as re-entrant ownership.
- Persists self-update handoff success immediately after the promoted candidate is verified as the primary, before predecessor cleanup.
- A current primary can reconcile an orphaned `handoffState=running` journal left by an unclean predecessor death.
- `adb-self-update-poc.sh` prints self-update status only when meaningful fields change instead of dumping the same Bundle every poll.
