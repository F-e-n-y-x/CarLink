# Changelog

CarLink's releases, newest first. Versions follow `major.minor.patch`; each one is a release on
[GitHub](https://github.com/F-e-n-y-x/CarLink/releases) with its APK. The text under each version
is what the in-app update popup shows.

## [1.0.1] - 2026-10-15

- Stereos whose Bluetooth is a separate module (common on FYT and similar units) are now
  recognised. CarLink says plainly that wireless isn't available there yet and to use a USB
  cable, instead of asking you to re-pair, and Diagnostics shows what it found.
- Report on GitHub: one tap in Diagnostics, or on a failed connection, opens a new issue with
  the error, device details and the relevant log lines already filled in.
- CarPlay engine updated (DiPlay 0.2.19): H.264 video on stereos without a hardware HEVC
  decoder, steadier music, clearer Bluetooth errors.
- The CarPlay menu matches CarLink's light or dark theme, has larger buttons and no longer shows
  settings CarLink manages for you.
- Passwords are now removed from shared logs as well as addresses.

## [1.0.0] - 2026-10-10

The first public release of CarLink: CarPlay and Android Auto in one app for Android car
stereos, tablets and phones used as the car's display.

- Plug in or connect your phone: an iPhone gets CarPlay, an Android phone gets Android Auto.
  CarLink tells them apart by itself.
- Works over USB and wirelessly. Wireless connections use Wi-Fi Direct by default, with no router
  or hotspot needed; the car's own hotspot and the same Wi-Fi network are options.
- One Home screen with one status and one action, a step-by-step view while connecting, and a
  plain-language explanation with a one-tap fix when something is wrong.
- Settings shared by both engines (picture, interface size, day and night, driving side, sound,
  connection method), plus every engine option under Show advanced.
- A floating switcher over CarPlay and Android Auto to change phones or return to CarLink.
- First-time setup that walks through permissions, your car's brand (popular makes sold in India,
  and more; BYD adds its DiLink extras) and pairing your phone.
- Diagnostics with device checks and a shareable connection log.
- Large, glanceable controls on car screens; phone and tablet sizes elsewhere; dark and light.
  A lighter look for slow head units.
- A 16 MB download that starts quickly on low-end stereos.
- Updates itself from GitHub Releases: it asks before downloading and checks every download
  before installing it.

CarPlay is experimental and not Apple-certified: it may stop working after an iOS update.
