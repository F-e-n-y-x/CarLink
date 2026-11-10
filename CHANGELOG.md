# Changelog

CarLink's releases, newest first. Versions follow `major.minor.patch`; each one is a release on
[GitHub](https://github.com/F-e-n-y-x/CarLink/releases) with its APK. The text under each version
is what the in-app update popup shows.

## [1.0.15] - 2026-11-10

- Fixed: on Android 17, Home kept saying "Permissions needed" for wireless CarPlay, because its
  Allow button never asked for the new Local network permission (#38). Wireless Android Auto now
  asks for it on Android 17 too.
- New: CarLink now runs on Intel (x86) head units such as FYT/SYU SC9853i stereos (thanks
  j0hann3sk).
- Fixed, from DiPlay: wired CarPlay could stall when the iPhone repeated its first link message;
  the call microphone could stay silent after it failed to restart; the CarPlay picture could be
  lost after leaving split screen or closing a video; music now plays at the car's own sample
  rate; Bluetooth wake retries; and safer handling of malformed data from the phone.

## [1.0.14] - 2026-11-10

- New: Settings › Connection › Show advanced › "Switch the car hotspot on". When wireless needs
  the stereo's hotspot and it is off, CarLink switches it on and connects (#36). It needs
  "Modify system settings" for CarLink; some stereos only allow it with debugging approved.
- Fixed: an iPhone could be taken for an Android phone and sent to Android Auto every time,
  for example after pairing with a custom name or on stereos with a Bluetooth module. Home now
  asks when it isn't sure (thanks pphan93).
- Fixed: wireless Android Auto said "Wireless isn't available on this stereo yet" on some
  stereos without a real Bluetooth module (thanks pphan93).
- Fixed: CarLink closed with an error when the phone disconnected on some Android 15 tablets
  (thanks MrKeltron).
- Fixed: a crash on Android 8 when a CarPlay connection ended right as it started (thanks
  j0hann3sk).

## [1.0.13] - 2026-11-07

- Fixed: Android Auto on a screen that can rotate still showed a portrait picture after turning
  the screen to landscape (1.0.11's fix never triggered). CarLink now reconnects in the new
  orientation.
- Fixed: wireless CarPlay through a stereo's Bluetooth module was blocked with "choose an iPhone",
  although the iPhone pairs with the module, not with Android.
- Fixed: on stereos that turn Wi-Fi off while their hotspot is on, the car hotspot connection
  was blocked with "Wi-Fi is off".
- Wired CarPlay no longer stays on "Getting CarPlay ready" on head units that miss the iPhone
  reconnecting in CarPlay mode (from DiPlay).
- Android Auto ends a corrupted connection at once instead of after a long freeze (from
  open-headunit).

## [1.0.12] - 2026-11-06

- New (experimental): wireless CarPlay on FYT stereos whose Bluetooth is a separate module. Turn
  on Settings › "Use the stereo's Bluetooth module" and disable the stereo's own "Car Link" app
  (com.syu.carlink) first. If it doesn't connect, please share the log from Diagnostics.

## [1.0.11] - 2026-11-06

- Fixed: Android Auto on a screen that can rotate (a tablet or phone used as the display) was
  stretched and could not be tapped correctly after turning the screen. Now CarLink reconnects
  in the new orientation a moment after the screen turns.
- Fixed: a crash in Android Auto on head units that report no Bluetooth service.

## [1.0.10] - 2026-11-05

- Fixed: CarLink could not be installed ("App not installed", "duplicate permissions") on a
  screen that also has the Open Headunit app.
- Stereos without Wi-Fi Direct: CarLink notices it, stops retrying Wi-Fi Direct and uses the
  screen's hotspot instead. If the hotspot is off, Home says so right away.
- Stereos with a Bluetooth module that has no service CarLink can reach: Home now says up front
  that wireless CarPlay isn't available, instead of retrying every 30 seconds.
- Diagnostics: the log now shows each start-up and phone Bluetooth signal the screen sends, to
  find out why CarLink doesn't open by itself on some head units.

## [1.0.9] - 2026-11-04

- Fixed: 1.0.8 closed right after opening on Android 7 head units. If CarLink 1.0.8 does not open
  on your stereo, download and install this version by hand; your settings are kept.
- Fixed: when CarLink was missing a permission, wireless CarPlay kept retrying several times a
  second instead of waiting for you to allow it.

## [1.0.8] - 2026-11-04

- Stereos with an external Bluetooth module: wireless Android Auto and CarPlay can now go through
  the stereo's module. It is detected and turned on automatically. Tap the Bluetooth chip on Home
  to choose between the module and the screen's own Bluetooth.
- Two phones at once: when an iPhone and an Android phone both try to connect, Home asks which one
  to use instead of guessing.
- Phone list: a delete icon on each phone (forgets and unpairs it), an "Add a new phone" row, and
  phones with the same name told apart.
- A failure on Home shows a QR code: scan it with your phone to open a ready-filled GitHub issue
  with the details and log.
- Faster starts: wireless Android Auto and CarPlay, re-plugged USB phones, and quicker recovery
  when a wired iPhone drops and comes back.
- Android Auto on this screen (experimental, Settings › Connection, Show advanced) where Android
  Auto is installed and allows it.
- iPhone charging over USB (Settings › Connection, Show advanced): offer less current if wired
  CarPlay drops after a few seconds.
- Diagnostics names more problems: another projection or mirroring app, the stereo's clock, low
  storage, another VPN, the stereo joined to the phone's own hotspot, and Unisoc UMS512 chips.
- A phone that is no longer paired no longer blocks every automatic start.
- Faster CarPlay encryption on Android 7.1-8.1, CarPlay and Android Auto engine fixes from upstream.

## [1.0.7] - 2026-10-27

- Wireless CarPlay: a stuck start or an iPhone that left is noticed in seconds, and a slow first
  join is no longer cut off early.
- Turning on Location, Bluetooth or Wi-Fi from quick settings retries a blocked start right away.
- Smoother CarPlay on slow head units (wired picture path runs ahead of other work).
- Stereos with an external Bluetooth/Wi-Fi module: Home says "plug in your phone" up front.
- iPads and iPods are no longer treated as iPhones (iPadOS has no CarPlay).
- Android Auto engine fixes from open-headunit: steadier USB and wireless handshakes, fewer
  broken frames after a stride change or GL context loss, and better 5 GHz fallback.
- CarPlay engine updated to the latest DiPlay.
- "Report a problem" now names the phone and session, shows a timeline and settings, hides
  serials and MAC addresses, and can copy the full log.


<!-- more -->

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
