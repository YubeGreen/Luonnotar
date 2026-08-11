# Luonnotar 2.6.1 r304 / vCode141

## Hot-handoff configure timeout reconciliation

During r303 lifecycle torture testing on OriginOS, a promoted successor could be
healthy and authoritative while the first post-handoff `configure` RPC exceeded
the app-side 30 second read timeout. The server continued executing
`configureAndStart()` and later became fully configured, but the provider had
already classified the handoff as `handoff_configure_failed` and immediately
issued a second configure against the same successor.

r304:

- raises the configure read budget from 30s to 45s;
- treats `SocketTimeoutException` after a verified successor takeover as an
  ambiguous result, not proof that the takeover failed;
- preserves a verified live successor ping while the result is unknown;
- performs a 15s read-only late reconciliation using ping plus cached status;
- rejects the lightweight `handoffActivation=true` snapshot as evidence of full
  configuration;
- records `embedded_engine_handoff_configure_reconciled` when the original
  in-flight configure completes;
- returns `hot_handoff_late_configure` as success after reconciliation so the
  restart path does not enqueue an immediate duplicate configure;
- leaves non-timeout configure failures on the existing failure path.

No guardian power/freezer/listener/SSH policy changes. Status schema remains 63.
