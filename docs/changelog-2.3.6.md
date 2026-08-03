# Luonnotar 2.3.6

## Embedded ADB endpoint and retry lifecycle hotfix

- Treat every `_adb-tls-connect._tcp` advertisement as a candidate instead of allowing the first callback to permanently win.
- When one advertised localhost port refuses the TCP connection, cool that port down and immediately try the next candidate.
- Re-advertising a stale port no longer clears its cooldown; all-stale candidates wait 45 seconds rather than retrying every two seconds.
- Live refresh no longer recursively starts setup while setup is already discovering, starting, or failed.
- Only show “connected to local adbd” after `Kadb.create()` completes; discovery alone is not reported as a connection.
- Pairing failures remain recoverable without tearing down discovery.
- Bump embedded engine revision to 236 so an APK replacement cannot silently keep the 2.3.5 shell process.
