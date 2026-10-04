# Existing Wi-Fi / Same LAN (experimental)

Connect both the Android head unit and iPhone to the same third-party router or portable
Wi-Fi using their system settings. In DiPlay → Connection setup → Wireless, select
**Existing Wi-Fi / Same LAN**, enter that network’s exact SSID and WPA2 password, and save.
For an open network, leave the password empty. Keep Bluetooth enabled and the iPhone
paired with the car, then start wireless CarPlay as usual.

Use WPA2-Personal or WPA2/WPA3 mixed mode. WPA3-only, enterprise authentication and
captive portals are outside this mode’s current scope. Disable AP/client isolation on
the router; both clients must be able to communicate and exchange multicast DNS.
5 GHz is preferable when the router and both devices support it.

DiPlay never creates Wi-Fi Direct or a hotspot in this mode, never joins a different
Wi-Fi network, and never changes the default route. The router remains responsible for
Internet access. SSID and password are stored separately from the car hotspot settings.
Android does not expose saved Wi-Fi passwords to ordinary apps; manual entry supplies
the iAP2 bootstrap credentials. A readable live SSID must match the entered name. If
Android redacts the SSID, the entered name is authoritative; verify it in system settings.
This mode does not require location permission just to start wireless CarPlay. Optional
location reporting still uses its existing permission flow.

## Implementation

`ExistingWifiManager` implements the existing wireless network backend contract. It
selects the single non-VPN Wi-Fi `Network` exposed by `ConnectivityManager`, obtains its
interface and addresses from `LinkProperties`, and observes its channel via `WifiInfo`.
It does not require Android’s INTERNET/VALIDATED capabilities or use the default network
as a proxy for Wi-Fi. Multiple Wi-Fi networks are rejected rather than guessed.

The existing address policy prefers an explicitly scoped link-local IPv6 address,
falling back to the interface’s IPv4 address. AirPlay listening, interface mDNS, the
Bonjour control probe, and the Bluetooth/iAP2 advertised endpoint all use this same
address. Authentication, media, touch and the iAP2 handoff are reused unchanged. The
router’s BSSID is deliberately not used as the receiver’s AirPlay device identity.
Unknown channel data stays zero (auto); it is never invented from user preferences.

Related work: [upstream PR #22](https://github.com/shihabal3amri/DiPlay/pull/22) also
proposes external Wi-Fi as part of an Android 7 port. This change targets the current
mainline network/discovery stack and does not import that port or its protocol changes.

The manager observes network loss and removal of the selected interface/address. The
controller tears down the old wireless stack and starts a new attempt; the manager
unregisters its callback on close without disconnecting Wi-Fi. If the router is absent
past the normal startup timeout, reconnect after restoring the network. DNS changes
alone do not restart CarPlay. Saved automatic car-hotspot startup only applies to car
hotspot mode, including when the old preference remains enabled.

## Validation and device test

Local tests cover station selection with a cellular/VPN network also present, exact
interface/address use, IPv6 scoping and IPv4 fallback, redacted SSID/manual credentials,
SSID mismatch, missing/ambiguous Wi-Fi, network loss/address change, callback cleanup,
credential persistence and setup cancellation. The controller test verifies this mode
bypasses hotspot control even with automatic hotspot startup saved and the AP off.
Existing hotspot and Wi-Fi Direct tests are run alongside these.

Local tests cannot establish the iPhone’s final behavior on this topology. With an
appropriately provisioned test build, check that both devices remain on the portable
Wi-Fi, no `DIRECT-*` network or car hotspot appears, CarPlay starts, and online maps work
on the iPhone without cellular data. Then check touch and music briefly. Separately
check that the two original wireless modes still connect after switching back.

For a failed attempt, record only the visible stage/error, whether the iPhone stayed on
the portable Wi-Fi, and the approximate failure time. Save one DiPlay diagnostic report
right after that attempt (Settings → Diagnostics → Save diagnostic report). Useful
boundaries are existing Wi-Fi attachment → AirPlay listener/Bonjour → Bluetooth iAP2
bootstrap → Bonjour control probe → AirPlay SETUP/event channel → first rendered frame.
A report with attachment but no discovered control service points toward router client
isolation/multicast or phone bootstrap; resolved services and a failed probe distinguish
TCP reachability. Successful SETUP without a rendered frame is a later media boundary.
No packet capture or broad log collection is required for the first test.

## Authentication and APKs

Ordinary public-source builds omit the accessory identity. A debug APK can be installed
for UI checks, but cannot independently authenticate CarPlay. The existing
`:mobile:assembleStandaloneDebug` task requires legitimate externally supplied
`DIPLAY_AUTH_ASSETS_DIR` assets; see [BUILD.md](BUILD.md). An Android debug signing key
only signs the app and is not a CarPlay identity. Do not extract private keys from a
release APK or substitute synthetic test identities for runtime authentication.
