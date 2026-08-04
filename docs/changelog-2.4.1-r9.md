# Luonnotar 2.4.1 r9

- Reworks the iQOO GMS fast-thaw path into a bounded 1.5 second burst with up to three verified passes instead of a single best-effort command.
- A verified thaw refreshes the existing stabilization lease; the connected Binder anchor immediately issues a read-only query and then resumes its normal 750 ms cadence.
- Records fast-thaw retries, final verification, post-thaw transport reconnection latency, transport collapse count, and longest continuous MCS health.
- Consumes stabilization grace only once per recovery phase. Repeated short 5228 windows can no longer restart the same grace indefinitely.
- Escalates a phase after three transport collapses in 20 seconds or after 30 seconds without reaching 15 seconds of continuous transport health.
- Keeps the existing bounded reset and force-stop budgets; a closed force-stop gate remains anchor-only rather than falling back to another stop-app.
