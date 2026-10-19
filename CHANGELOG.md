# Changelog

CarLink's releases, newest first. Versions follow `major.minor.patch`; each one is a release on
[GitHub](https://github.com/F-e-n-y-x/CarLink/releases) with its APK. The text under each version
is what the in-app update popup shows.

## [1.0.6] - 2026-10-20

- Faster connections: USB Android Auto in about 1.3 s (was 3.2 s), wireless Android Auto in about 3 s.
- Fixed an Android Auto video glitch where the map and app bar broke up for a moment.
- A phone that was already plugged in, or still in an old Android Auto session, now connects
  without unplugging it.
- The USB permission prompt no longer comes back on every plug once "Always" was ticked.
- Setup: "Allow all" goes through every permission, asks for everything up front, shows the real
  problem with a one-tap fix, and has "Use another phone".
- Home asks for a missing Wi-Fi password right there, and offers hotspot settings or Wi-Fi Direct
  when the car hotspot is off.
- The car hotspot name and password are filled in from the stereo and can be changed in Settings.
- Tapping CarLink's tile in Android Auto or CarPlay opens CarLink's Settings; the phone stays
  connected and Return goes back.
- A plugged-in phone stays on the cable; CarLink no longer starts wireless for it.
- Slow head units get Direct video output and a lighter CarPlay picture automatically.
- CarPlay shows CarLink's icon instead of BYD's on other car brands.
- Root mode for rooted stereos (Settings › Connection, Show advanced): no permission popups, and
  Wi-Fi low-latency mode while a phone is connected.

<!-- more -->

## [1.0.5] - 2026-10-20

- USB Android Auto opens its screen again without "display over other apps" (broken since 1.0.3).
- A phone that was already plugged in when CarLink opened now connects, for CarPlay and Android Auto.
- Unplugging a phone clears its "Allow" or error screen, and plugging it in again after Disconnect
  connects it.
- The VPN "Allow" button for wired CarPlay works.
- Wireless Android Auto 17.8: CarLink completes the hands-free Bluetooth link the phone now
  requires before it starts.
- Steering-wheel buttons: Settings › Car › Steering-wheel buttons lets you press a button and use
  it for the voice assistant, answering or ending a call, play/pause, next or previous, in both
  CarPlay and Android Auto.
- Android Auto keeps its full layout with media controls on short screens when the screen density
  is automatic, and offers a widescreen layout option for ultrawide screens.
- After a CarPlay call ends, music and navigation sound come back reliably.
- If Android loses a phone's pairing, Home says so and opens Bluetooth settings to pair again.
- A warning when the stereo's own phone link may take the microphone during a call.

## [1.0.4] - 2026-10-19

- CarPlay on iOS 27 with sound but a black screen: CarLink now offers "Direct video output" (also in
  Settings › Display › Advanced) and can turn it on and reconnect in one tap.
- Wireless Android Auto on stereos whose Bluetooth is a separate module (FYT, ZLink and similar),
  as an experimental option under Settings › Connection › Show advanced.
- CarLink opens when the car's ignition wakes the stereo, not only after a full reboot.
- Android 17: Android Auto keeps its sound and microphone when it starts while another app is open.
- Both engines updated (CarPlay engine DiPlay 0.2.21, Android Auto engine DiAuto 0.3.12): wired
  CarPlay retries by itself, music controls respond on time again, steadier Wi-Fi Direct, and
  exiting Android Auto on the phone no longer reconnects it.

## [1.0.3] - 2026-10-19

- CarLink no longer refuses to connect when "Display over other apps" isn't allowed. That
  permission is only needed for the floating switcher, which falls back to an in-app prompt.
- Motorcycles: Indian two-wheeler makes (Hero, Honda, TVS, Bajaj, Royal Enfield and more) in the
  brand picker.
- Shared logs and GitHub reports leave out routine screen switches, so the useful lines fit.

## [1.0.2] - 2026-10-15

- Faster and smoother on low-end stereos: CarLink now also checks the graphics chip and CPU speed,
  so stereos with a weak GPU get the lighter look automatically (cold start about 35% faster on a
  test setup like a Unisoc UIS8581A stereo).
- Pre-compiled start-up code for a quicker first launch.
- The connecting animations redraw without rebuilding the screen.
- On stereos with little memory and no Android phone set up, the Android Auto engine is no longer
  started in advance, saving memory.

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
