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
The manager also tries the station WifiInfo when the capabilities snapshot is redacted.
Precise location permission and enabled system location can make SSID/BSSID readable;
this validates Wi-Fi configuration independently of optional GPS reporting. When
neither snapshot exposes the network name, diagnostics say `configCheck=manual_unverified`.
This mode does not require location permission just to start wireless CarPlay. Optional
location reporting still uses its existing permission flow.

## Implementation

`ExistingWifiManager` implements the existing wireless network backend contract. It
selects the single non-VPN Wi-Fi `Network` exposed by `ConnectivityManager`, obtains its
interface and addresses from `LinkProperties`, and observes its channel via `WifiInfo`.
It does not require Android’s INTERNET/VALIDATED capabilities or use the default network
as a proxy for Wi-Fi. Multiple Wi-Fi networks are rejected rather than guessed.

The Bluetooth/iAP2 start endpoint retains an explicitly scoped link-local IPv6 address,
falling back to the interface's IPv4 address. Existing Wi-Fi also retains that interface's
usable IPv4 address for discovery and TCP: separate JmDNS registries publish and browse
IPv4 and IPv6 multicast, and separate address-bound listeners share one AirPlay port.
This does not depend on the platform's IPv6 wildcard being dual-stack. Bonjour probes
use a source address of the peer's family on this same interface, applying the local
interface scope to link-local IPv6 peers. The scope suffix stays local; iAP2 sends the
unscoped literal as before. 0x5703 supplies Wi-Fi credentials; IP addresses are carried
by 0x4301, whose link-local IPv6 preference is unchanged. Authentication, media, touch
and the iAP2 handoff are reused unchanged. The
router’s BSSID is deliberately not used as the receiver’s AirPlay device identity.
When readable, the current AP BSSID is sent separately as 0x5703 parameter 0, so the
iPhone can identify the existing access point. Unknown/redacted, zero, multicast or
malformed AP addresses are omitted; no address is invented from the receiver identity.
On API 31+, observed open/secured mismatches and unsupported security types fail early;
an SAE station connection can still be a WPA2/WPA3 mixed AP and does not prove WPA3-only.
Unknown channel data stays zero (auto); it is never invented from user preferences.

JmDNS registry family selection uses `getInetAddress()`. Its deprecated `getInterface()`
can return another address of the same interface on Android, causing a false binding
mismatch and discarding valid IPv4 discovery results. TXT feature bits now share the
same source as `/info`, including the audio-disabled configuration.

Related work: [upstream PR #22](https://github.com/shihabal3amri/DiPlay/pull/22) also
proposes external Wi-Fi as part of an Android 7 port. This change targets the current
mainline network/discovery stack and does not import that port or its protocol changes.

The manager observes network loss and changes to either selected address or the interface. The
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

For a failed bootstrap, the report includes the actual sent 0x5703/0x4301 parameter
IDs and lengths, value-match booleans, and whether a scope suffix escaped into the
wire address. No network credentials, identifiers, raw messages or their hashes are
exported. P2P's optional AP parameter remains omitted; no alternate bootstrap sequence
is introduced for Same LAN.

During the first 90 seconds, a passive observer joins mDNS on each selected family.
`mdnsWire` distinguishes local and peer queries/responses and counts only the fixed
AirPlay/CarPlay service types. It sends nothing and closes with discovery. Peer counts
do not identify a particular iPhone; local packets do not prove reception by a peer.
Unavailable observers report an error class, so zero traffic is not confused with a
failed join. These diagnostics replace reliance on inaccessible Android kernel counters.

After recording a failed Same LAN attempt, an iPhone browser can open
`http://<Android Wi-Fi IPv4>:<AirPlay port>/diplay-network-check` (normally port 7000).
The response tests the actual AirPlay listener without pairing or activating a session.
The report marks this as `airplay network-check received`; the resulting TCP accept
must not be interpreted as an automatic CarPlay connection. IPv4 success alone does
not establish IPv6 link-local reachability.

When investigating a mode-switch regression, test P2P first in a fresh app process,
then Same LAN, then P2P again. Record the iPhone's actual Wi-Fi membership on a failed
P2P attempt: Android's legacy P2P client count may omit iPhones. If P2P already fails
before Same LAN, compare the previously successful APK without clearing app data or
pairing; otherwise there is no controlled evidence of a binary regression.

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
