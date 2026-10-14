# Security policy

How to report a security problem in CarLink, and what is known and intended about the app's
security.

## Supported versions

Only the latest release on [GitHub Releases](https://github.com/F-e-n-y-x/CarLink/releases/latest)
gets fixes. The in-app updater offers new releases to installed copies.

## Reporting a vulnerability

Please do not open a public issue for a security problem. Report it privately through GitHub:
**Security › Report a vulnerability** on this repository
([direct link](https://github.com/F-e-n-y-x/CarLink/security/advisories/new)). If that is not
available, open an issue that asks for a private contact, without any details.

Include the CarLink version, the device and Android version, what an attacker can do and the
steps to reproduce. Do not include Wi-Fi or hotspot passwords or other personal data.

This is a volunteer project: reports are answered as time allows, and fixes ship as a new
release.

## Known and intended

- **The CarPlay accessory identity is extractable by design.** As in DiPlay's releases, the
  release APK bundles an experimental accessory identity recovered from public Carlinkit
  firmware, including its private key. Anyone with the APK can extract it. It is not a secret,
  and a report that it can be extracted is not a vulnerability. Builds from source contain no
  identity ([docs/BUILDING.md](docs/BUILDING.md#carplay-authentication-files-not-in-git)).
- **Updates are verified before they are installed.** The updater only runs in the release
  package `com.carlink.app` signed with CarLink's key, and installs a download only when its
  SHA-256 matches the release's `.sha256` file, it is `com.carlink.app` with a higher version,
  and its only signer is the pinned certificate
  ([docs/RELEASING.md › What the app checks](docs/RELEASING.md#what-the-app-checks)). A way
  around any of these checks is a vulnerability.
- **The release signing key** exists only on the maintainer's computer. Its certificate SHA-256
  is `03:74:68:CA:5B:EB:70:EA:5F:68:54:33:29:B0:33:68:24:7D:FB:39:3D:3F:62:6A:6B:47:2D:10:E5:65:7D:21`.
  An APK signed with another key is not an official build.
- **The connection log** hides phone addresses, but it describes your devices and networks. Look
  through it before you post it publicly.
- **Report on GitHub posts in public.** The button opens a new issue on this public repository
  with a log excerpt, the device and the phone's name filled in. Anyone can read a submitted
  issue. CarLink removes hardware addresses and Wi-Fi or hotspot passwords before the browser
  opens, and nothing is sent until you tap Submit; edit out anything else you do not want to
  publish.
