## Screenshots

| Ready | Connecting | Car brand |
|---|---|---|
| ![Home, ready to connect](https://raw.githubusercontent.com/F-e-n-y-x/CarLink/{{TAG}}/docs/screenshots/home/home_ready-iphone-usual_tablet_dark.webp) | ![Connecting by cable](https://raw.githubusercontent.com/F-e-n-y-x/CarLink/{{TAG}}/docs/screenshots/home/home_connecting-carplay-usb_tablet_dark.webp) | ![Car brand picker](https://raw.githubusercontent.com/F-e-n-y-x/CarLink/{{TAG}}/docs/screenshots/setup/setup_car_tablet_dark.webp) |

More in the [screenshot gallery](https://github.com/F-e-n-y-x/CarLink/tree/{{TAG}}/docs/screenshots).

## Download and install

1. On the car stereo or tablet, download **{{APK}}** ({{SIZE}}) from the assets below.
2. Open it and allow installing apps from your browser or file manager when Android asks.
3. Open CarLink and follow the first-time setup. Then plug in your phone, or pair it once over
   Bluetooth for wireless.

After that, CarLink finds new releases itself and asks before installing them (Settings ›
About › Check for updates).

**Needs:** Android 7.1 or newer (ARM) on the stereo or tablet. An iPhone gets CarPlay, an Android
phone gets Android Auto. Wireless needs Bluetooth and Wi-Fi on both sides; USB needs only the cable.

## Verify the download

- SHA-256 of {{APK}}: `{{SHA256}}` (also in `{{APK}}.sha256`)
- Signing certificate SHA-256: `{{CERT}}`

Every CarLink release is signed with this certificate, and the in-app updater installs only APKs
signed with it. Check with `sha256sum {{APK}}` and `apksigner verify --print-certs {{APK}}`.

## CarPlay notice

CarLink is **not an Apple-certified product**. As in DiPlay's releases, the release APK bundles an
experimental accessory identity recovered from public Carlinkit firmware, not a newly provisioned
MFi identity for CarLink. A bundled private key is extractable. Acceptance after future iOS
updates, reliability across head units and suitability of that identity for general distribution
are unresolved: **there is no guarantee that CarPlay keeps working after an iOS update.** Android
Auto does not depend on it.

## Source and license

Source for this release: [{{TAG}}](https://github.com/F-e-n-y-x/CarLink/tree/{{TAG}}).
CarLink is AGPL-3.0. It is built on [DiPlay](https://github.com/shihabal3amri/DiPlay) (CarPlay,
GPL-3.0) and [DiAuto](https://github.com/shihabal3amri/DiAuto) (Android Auto, AGPL-3.0).
CarLink is not affiliated with Apple or Google; CarPlay is a trademark of Apple Inc. and Android
Auto a trademark of Google LLC.

Found a problem? See [troubleshooting](https://github.com/F-e-n-y-x/CarLink/blob/main/docs/TROUBLESHOOTING.md),
or [open an issue](https://github.com/F-e-n-y-x/CarLink/issues/new/choose) and attach the log from
Settings › General › Diagnostics › Share log.
