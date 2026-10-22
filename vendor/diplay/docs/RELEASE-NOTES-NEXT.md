# DiPlay next release notes

Changes through DiPlay 0.2.21 are documented in [0.2.21 release notes](RELEASE-NOTES-0.2.21.md). Measured checks are in [VALIDATION.md](VALIDATION.md). Device acceptance and remaining failure families are tracked in [connection reliability validation](CONNECTION_RELIABILITY.md).

## Fixes for 0.2.21

- Wireless CarPlay over the built-in car hotspot connects again on head units where Android cannot say which interface carries the hotspot. On BYD DiLink 3.0 and 4.0 units the hotspot runs on `wlan0` while the firmware's Wi-Fi patterns name only `softap0` and `wigig0`, and on an MG7 it runs on `ath0`. 0.2.21 then took a mobile-data interface (`rmnet_data…`) or an internal bridge (`br0`) for the hotspot, so the iPhone joined the hotspot but never reached DiPlay and CarPlay stayed on "Opening CarPlay" ([#921](https://github.com/shihabal3amri/DiPlay/issues/921), [#931](https://github.com/shihabal3amri/DiPlay/issues/931), [#944](https://github.com/shihabal3amri/DiPlay/issues/944), [#951](https://github.com/shihabal3amri/DiPlay/issues/951), [#955](https://github.com/shihabal3amri/DiPlay/issues/955), [#958](https://github.com/shihabal3amri/DiPlay/issues/958), [#991](https://github.com/shihabal3amri/DiPlay/issues/991), [#1019](https://github.com/shihabal3amri/DiPlay/issues/1019)). DiPlay again uses the interface 0.2.20 used there. The search for a hotspot on a wired interface such as `eth0.3` ([#573](https://github.com/shihabal3amri/DiPlay/issues/573)) now runs only when Android reports no hotspot interface at all, and never takes a mobile-data interface. The **Hotspot interface (optional)** workaround is no longer needed and can be cleared. Thanks to @Boring206. [#936](https://github.com/shihabal3amri/DiPlay/pull/936), [#950](https://github.com/shihabal3amri/DiPlay/pull/950)
- Wireless music no longer plays 1.5 % fast and then stutters. Over Wi-Fi the iPhone sends music in bursts about 0.4 s apart, and 0.2.21's music clock adjustment measured the buffer only right after a burst, at its fullest, so with the default 300 ms music buffer it sped playback up to its limit until the buffer ran dry every 20–40 s ([#807](https://github.com/shihabal3amri/DiPlay/issues/807), [#867](https://github.com/shihabal3amri/DiPlay/issues/867), [#973](https://github.com/shihabal3amri/DiPlay/issues/973), [#976](https://github.com/shihabal3amri/DiPlay/issues/976)). It now uses the average level over time. On a test head unit the speed-up and the underruns were gone at the default buffer. Thanks to @Boring206. [#993](https://github.com/shihabal3amri/DiPlay/pull/993)

## Connection setup and recovery

- Wireless CarPlay starts up to 20 s sooner on head units whose network resolver is slow. Setting up the CarPlay announcement on the hotspot looked up the head unit's own address name first, which took 20 s on a BYD head unit (Android 10) before every connection; it now takes about 50 ms. Thanks to @lpcheng1208. [#972](https://github.com/shihabal3amri/DiPlay/pull/972)
- Wired CarPlay connects on the first attempt after DiPlay was left or reconnected with the cable still plugged in. The iPhone tries DiPlay for only about 7 s after the start request, and after an unclosed session it kept its USB network closed for 15–26 s, so every attempt timed out ([#889](https://github.com/shihabal3amri/DiPlay/issues/889), [#610](https://github.com/shihabal3amri/DiPlay/issues/610), [#335](https://github.com/shihabal3amri/DiPlay/issues/335)). DiPlay now sends the start request once more when the USB network opens and the iPhone stays silent for 2 s. Fresh connections are unchanged. [#962](https://github.com/shihabal3amri/DiPlay/pull/962)
- On Android 8 and 9 head units, DiPlay notices about 20 s after the iPhone drops off Wi-Fi and reconnects. Before, the CarPlay picture froze until the driver touched the screen ([#1005](https://github.com/shihabal3amri/DiPlay/issues/1005)). Android 10 and later already did this. [#1042](https://github.com/shihabal3amri/DiPlay/pull/1042)
- When a wireless start failed because Bluetooth was off, turning Bluetooth on now retries at once instead of after a back-off of up to 30 s. [#914](https://github.com/shihabal3amri/DiPlay/pull/914)
- After DiPlay was stopped during a Wi-Fi Direct session (an update installed over it, for example), the next start waits for Android to remove the old group instead of failing after 0.5 s with "Wi-Fi Direct did not respond" and losing a retry delay. [#915](https://github.com/shihabal3amri/DiPlay/pull/915)
- **Import the car hotspot details (experimental)** asks the car to approve DiPlay's ADB access when the driver turns it on, instead of failing on a car that has not approved it yet, and saving the imported details no longer warns that the car's hotspot settings will be overwritten. [#919](https://github.com/shihabal3amri/DiPlay/pull/919)
- On head units with Android 9 and earlier, the CarPlay quick menu offers Wi-Fi Direct again, as Settings does, and the Android 9 note no longer says Wi-Fi Direct is replaced. [#937](https://github.com/shihabal3amri/DiPlay/pull/937)
- The CarPlay quick menu keeps showing the hotspot's name, band and channel after it is closed without changes, and after the CarPlay screen is recreated. [#924](https://github.com/shihabal3amri/DiPlay/pull/924)
- When DiPlay cannot reach the head unit's network ADB while USB debugging is on, it says that network ADB does not answer and to turn it off and on again, instead of saying it is off. Some DiLink UI 7.0 units leave the switch on with nothing listening after a restart ([#1002](https://github.com/shihabal3amri/DiPlay/issues/1002)). [#1045](https://github.com/shihabal3amri/DiPlay/pull/1045)

## Display and touch

- Two-finger gestures such as pinching in Apple Maps no longer jump. When the first finger lifted, the second one was reported as a new press and the first was released at the screen's corner ([#444](https://github.com/shihabal3amri/DiPlay/issues/444)). Each finger now keeps its place from press to release. [#912](https://github.com/shihabal3amri/DiPlay/pull/912)
- Opening the quick menu with the settings swipe no longer taps whatever lies under the first finger. With the swipe on, a new touch reaches the iPhone after 80 ms, on lift or on a move, so the swipe's fingers land first. Taps and drags are not delayed. [#922](https://github.com/shihabal3amri/DiPlay/pull/922)
- **Settings → Display → CarPlay size** shows the length actually saved, with each preset's millimetres (Large 250 mm, Medium 300 mm, Small 350 mm) and a Custom entry, and the quick menu's physical length moves in 5 mm steps instead of 25 mm ([#913](https://github.com/shihabal3amri/DiPlay/issues/913)). [#1032](https://github.com/shihabal3amri/DiPlay/pull/1032)
- **Settings → Display → Frame rate** shows a rate set in the quick menu, such as 45 fps, instead of "30 fps", and 30 fps can be chosen from it again. [#934](https://github.com/shihabal3amri/DiPlay/pull/934)
- The quick menu's display summary names the size DiPlay actually requests, and its video and full-screen lines are in the menu's language. [#923](https://github.com/shihabal3amri/DiPlay/pull/923)
- After the CarPlay screen is recreated during a session, for example by a language change, day/night changes and the Siri and wheel keys reach the iPhone again. [#933](https://github.com/shihabal3amri/DiPlay/pull/933)

## Audio, calls and Siri

- Music and navigation are heard again after a call on head units that were already in their call state before it, for example because of the car's own phone. DiPlay set that state back when the call ended and so kept the head unit in it ([#987](https://github.com/shihabal3amri/DiPlay/issues/987)); it now only withdraws its own request. [#994](https://github.com/shihabal3amri/DiPlay/pull/994)
- **Key for Siri** and **Key for Play/pause** can learn a key that reaches DiPlay's window but not the wheel key service, such as the knob on a Harman head unit ([#1021](https://github.com/shihabal3amri/DiPlay/issues/1021)). [#1025](https://github.com/shihabal3amri/DiPlay/pull/1025)

## Dashboard

- **Dashboard map shape** on BYD DiLink 3.0: half screen (as before) or full screen, so the CarPlay map can fill the cluster ([#870](https://github.com/shihabal3amri/DiPlay/issues/870)). Tested on a DiLink 3.0 car. Thanks to @Proselyte-M. [#956](https://github.com/shihabal3amri/DiPlay/pull/956)
- **CarPlay map on instrument cluster** works on the Lynk & Co 03 (2025) head unit (`geely_fx11`, Android 11). DiPlay asks the head unit for the cluster projection, gives it back when the session ends, and takes it again when the car's own map lets go. Only this head unit is verified. Thanks to @macheteHot. [#843](https://github.com/shihabal3amri/DiPlay/pull/843)
- The cluster's album cover no longer disappears during a song or after a skip. Empty or unreadable artwork transfers kept replacing a valid cover ([#660](https://github.com/shihabal3amri/DiPlay/pull/660)). Covers and song text now cross-fade, the music card can be sized from 50 to 300 % in 1 % steps, its frame takes the cover's colour, and the speedometer card is square. **Use album color for speedometer background**, off by default, matches the speedometer to the cover. Tested in a BYD Song Pro. Thanks to @LuisCav29. [#1033](https://github.com/shihabal3amri/DiPlay/pull/1033)
- **Auto-activate music card**, off by default, opens BYD's media screen once so the cluster shows the song card, which stays hidden until the car's media system has been opened once, then returns to CarPlay. Thanks to @wdubaiyu. [#959](https://github.com/shihabal3amri/DiPlay/pull/959)

## Home screen and ambient lighting

- The home-screen bubble can no longer be dragged off the screen or under a system bar, where it could not be tapped again. [#930](https://github.com/shihabal3amri/DiPlay/pull/930)
- **Car's own music rhythm** can be turned on on a 2023 Han, whose audio service stops the rhythm with a different call. Thanks to @sa3eedo12. [#957](https://github.com/shihabal3amri/DiPlay/pull/957)
- With **Send music data to the car's rhythm** on, short sounds such as a kick drum between two sends now reach the car. [#938](https://github.com/shihabal3amri/DiPlay/pull/938)
- Turning **Car's own music rhythm** off while its check is still running leaves it off. [#939](https://github.com/shihabal3amri/DiPlay/pull/939)
- The launcher bubble's settings card shows its own icon instead of a solid block, and the note in the ambient light dialog lines up with its control. Thanks to @macheteHot. [#1034](https://github.com/shihabal3amri/DiPlay/pull/1034), [#1035](https://github.com/shihabal3amri/DiPlay/pull/1035)

## Settings and updates

- Home shows **Update available** when DiPlay comes back after the background check found a release, not only after DiPlay was closed and opened again. [#947](https://github.com/shihabal3amri/DiPlay/pull/947)
- A settings backup now includes the resolution percentage, the ambient light delay, the third dashboard route, calls on speakerphone, the navigation voice level and the launcher bubble. [#928](https://github.com/shihabal3amri/DiPlay/pull/928)
- Settings search finds sliders, such as **Navigation guidance volume**, and finds the HEVC switch, now titled **Efficient video (HEVC)** ([#965](https://github.com/shihabal3amri/DiPlay/issues/965)). [#935](https://github.com/shihabal3amri/DiPlay/pull/935), [#969](https://github.com/shihabal3amri/DiPlay/pull/969)
- The setup guide no longer says "Suggested for Not sure" when the DiLink version is unknown. [#929](https://github.com/shihabal3amri/DiPlay/pull/929)
- The settings backup and report pages a phone opens are available in Italian. [#925](https://github.com/shihabal3amri/DiPlay/pull/925)
- The phone page's address no longer changes when it is reopened right after leaving it. [#932](https://github.com/shihabal3amri/DiPlay/pull/932)

## Diagnostics

- When a microphone window records only silence, the report adds the audio mode and the sources of other recordings, and the `Microphone:` lines name the source DiPlay actually records from ([#987](https://github.com/shihabal3amri/DiPlay/issues/987)). [#995](https://github.com/shihabal3amri/DiPlay/pull/995)
- A minute that sent the iPhone no wheel speed says what the speed and gear reads returned ([#807](https://github.com/shihabal3amri/DiPlay/issues/807)). [#996](https://github.com/shihabal3amri/DiPlay/pull/996)
- A media button that CarPlay does not use is listed under Wheel keys, so a car that sends its own code for a key shows it ([#610](https://github.com/shihabal3amri/DiPlay/issues/610)). [#970](https://github.com/shihabal3amri/DiPlay/pull/970)
- The `Audio: rtp clock` line names the microphone clock for PCM correctly. [#963](https://github.com/shihabal3amri/DiPlay/pull/963)
