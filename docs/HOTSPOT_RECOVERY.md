# Built-in car hotspot startup recovery — 0.3.11

A September 30 diagnostic report showed DiAuto starting before the built-in car
hotspot. As the hotspot came on, automatic selection published eth0 connection
details while the working hotspot interface was ap0. The phone repeatedly reported
WifiConnectStatus -11, and reopening the activity retained the cached details in
the background service. The owner recovered by removing the app from recents.

## Changes

- Automatic selection excludes numbered Ethernet interfaces, ccmni/rmnet modem
  interfaces and BYD's briotgw bridge, while retaining vendor AP names and explicit
  overrides.
- A manually named interface must be up with a private IPv4 address. If it is not
  ready, resolution waits instead of substituting an automatic candidate.
- Hotspot ENABLED and DISABLED broadcasts invalidate old details and start a fresh
  resolution, including when the initial resolution wait has expired.
- A phone-reported join failure re-resolves car-hotspot credentials before the wake
  retry. It does not restart the radio, refresh Wi-Fi Direct, or disturb a live or
  connecting session. Superseded Bluetooth handshakes cannot initiate recovery.
- Resolver generation and cancellation checks prevent old blocking interface/MAC
  reads from publishing over, or invalidating, newer results.

## Validation

On October 1 the owner confirmed that the 0.3.9-hud-hotspot-fix test build worked
and authorized merging and releasing it. All seven changed source/test files were
carried unchanged onto the current main branch, preserving 0.3.10 localization.
This is owner confirmation for the test build; it is not a separate physical-car
test of the optimized production APK or a claim about other firmware.

650 unit tests passed on the combined release source, with no failures, errors or
skips. Regression tests cover upstream-only startup, ap0 down/address-less/ready,
manual-interface waiting and overrides, and the -11 phone join report. Core
translation coverage and argument checks passed for all four translated editions.

For further device checks, open DiAuto with hotspot auto-enable off and the car
hotspot off, then enable the hotspot without exiting DiAuto. Repeat after more
than 60 seconds, with an explicit ap0 override, and with the hotspot already on at
launch. Automatic BYD detection should resolve ap0 rather than eth0; a rejected
join should log that credentials are discarded and resolved again.
