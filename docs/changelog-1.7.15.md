# Luonnotar 1.7.15

## Fixed persistent VPN NetworkRequest matching

`NetworkRequest.Builder()` includes
`NET_CAPABILITY_NOT_VPN` by default.

Version 1.7.14 requested `TRANSPORT_VPN` without removing that default
capability, creating a contradictory request that required the selected
network to be both a VPN and not a VPN. The callback therefore remained in
`REQUESTED` with handle `-1`, even while the active Tailscale VPN was valid.

Version 1.7.15 explicitly removes `NET_CAPABILITY_NOT_VPN` before submitting
the persistent VPN request.

Expected settled state:

```text
persistent_network_lease_state=AVAILABLE
persistent_network_lease_handle=<current VPN network handle>
```

Version:

```text
versionName: 1.7.14 -> 1.7.15
versionCode: 40 -> 41
```