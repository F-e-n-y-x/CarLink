# CarLink UI screenshots

An index of every CarLink screen state, rendered by
`app/src/test/java/com/carlink/app/gallery/ScreenshotGallery.kt` (Robolectric, native graphics)
from each screen's stateless content (`HomeContent`, `SettingsContent`, `DiagnosticsContent`,
`AboutContent`) with fake state. Nothing here comes from a real phone or head unit. Files are named
`<area>/<area>_<state>_<config>_<theme>.png`; 216 images in total.

## Regenerate

```sh
echo "sdk.dir=$HOME/android-sdk" > local.properties   # once, if not already there
./gradlew --no-daemon :app:recordScreenshots
```

This runs only the gallery test (about 4 minutes), deletes the old PNGs of every registered area
and writes new ones. Ordinary `testDebugUnitTest` runs skip the gallery (it needs
`-Dcarlink.screenshots=true`, which the task sets; `-Pcarlink.screenshots=true` works too). No
device or emulator is involved. The gallery renders with system animations off, so CarLink's
"reduce motion" path is active: the hero phone is shown at rest (its float and pulse do not run).

## Configs

| Config | Profile | Panel | CarLink canvas | Themes |
|---|---|---|---|---|
| `car-landscape` | Car | 2160×1215px (1440×810dp at 240dpi) | 1440×810dp | dark, light |
| `car-1080p` | Car | 1920×1080px reporting 240dpi | 1440×810dp | dark |
| `car-split-third` | Car | 640×1080px window on a 1920×1080px panel (BYD ⅓ split) | 480×810dp | dark |
| `phone-portrait` | Phone/tablet | 400×860dp at 420dpi | 400×860dp | dark, light |
| `phone-landscape` | Phone/tablet | 860×400dp at 420dpi | 860×400dp | dark |
| `tablet-portrait` | Phone/tablet | 800×1280dp at 240dpi | 800×1280dp | dark |

Each image shows the first screenful only; content below the fold of a scrolling screen is not
captured. Sheets and dialogs (`sheet-*`) are not rendered in `car-split-third` (see Caveats).

## Screens

### Home — `home/` (87 images)

| State | car-landscape dark | car-landscape light | car-1080p dark | car-split-third dark | phone-portrait dark | phone-portrait light | phone-landscape dark | tablet-portrait dark |
|---|---|---|---|---|---|---|---|---|
| `permission` — First run: permissions needed | [png](home/home_permission_car-landscape_dark.png) | [png](home/home_permission_car-landscape_light.png) | [png](home/home_permission_car-1080p_dark.png) | [png](home/home_permission_car-split-third_dark.png) | [png](home/home_permission_phone-portrait_dark.png) | [png](home/home_permission_phone-portrait_light.png) | [png](home/home_permission_phone-landscape_dark.png) | [png](home/home_permission_tablet-portrait_dark.png) |
| `bluetooth-off` — Bluetooth is off | [png](home/home_bluetooth-off_car-landscape_dark.png) | [png](home/home_bluetooth-off_car-landscape_light.png) | [png](home/home_bluetooth-off_car-1080p_dark.png) | [png](home/home_bluetooth-off_car-split-third_dark.png) | [png](home/home_bluetooth-off_phone-portrait_dark.png) | [png](home/home_bluetooth-off_phone-portrait_light.png) | [png](home/home_bluetooth-off_phone-landscape_dark.png) | [png](home/home_bluetooth-off_tablet-portrait_dark.png) |
| `ready-no-phone` — Ready, no phone paired yet | [png](home/home_ready-no-phone_car-landscape_dark.png) | [png](home/home_ready-no-phone_car-landscape_light.png) | [png](home/home_ready-no-phone_car-1080p_dark.png) | [png](home/home_ready-no-phone_car-split-third_dark.png) | [png](home/home_ready-no-phone_phone-portrait_dark.png) | [png](home/home_ready-no-phone_phone-portrait_light.png) | [png](home/home_ready-no-phone_phone-landscape_dark.png) | [png](home/home_ready-no-phone_tablet-portrait_dark.png) |
| `ready-iphone-same-wifi-offer` — Ready with a known iPhone, plus the one-time Same Wi-Fi card (password field) | [png](home/home_ready-iphone-same-wifi-offer_car-landscape_dark.png) | [png](home/home_ready-iphone-same-wifi-offer_car-landscape_light.png) | [png](home/home_ready-iphone-same-wifi-offer_car-1080p_dark.png) | [png](home/home_ready-iphone-same-wifi-offer_car-split-third_dark.png) | [png](home/home_ready-iphone-same-wifi-offer_phone-portrait_dark.png) | [png](home/home_ready-iphone-same-wifi-offer_phone-portrait_light.png) | [png](home/home_ready-iphone-same-wifi-offer_phone-landscape_dark.png) | [png](home/home_ready-iphone-same-wifi-offer_tablet-portrait_dark.png) |
| `connecting-carplay-usb` — Connecting CarPlay over USB (step 2 of 6) | [png](home/home_connecting-carplay-usb_car-landscape_dark.png) | [png](home/home_connecting-carplay-usb_car-landscape_light.png) | [png](home/home_connecting-carplay-usb_car-1080p_dark.png) | [png](home/home_connecting-carplay-usb_car-split-third_dark.png) | [png](home/home_connecting-carplay-usb_phone-portrait_dark.png) | [png](home/home_connecting-carplay-usb_phone-portrait_light.png) | [png](home/home_connecting-carplay-usb_phone-landscape_dark.png) | [png](home/home_connecting-carplay-usb_tablet-portrait_dark.png) |
| `connecting-android-auto-wireless-waiting` — Wireless Android Auto waiting; the phone's Bluetooth is not connected, so Home offers Bluetooth settings | [png](home/home_connecting-android-auto-wireless-waiting_car-landscape_dark.png) | [png](home/home_connecting-android-auto-wireless-waiting_car-landscape_light.png) | [png](home/home_connecting-android-auto-wireless-waiting_car-1080p_dark.png) | [png](home/home_connecting-android-auto-wireless-waiting_car-split-third_dark.png) | [png](home/home_connecting-android-auto-wireless-waiting_phone-portrait_dark.png) | [png](home/home_connecting-android-auto-wireless-waiting_phone-portrait_light.png) | [png](home/home_connecting-android-auto-wireless-waiting_phone-landscape_dark.png) | [png](home/home_connecting-android-auto-wireless-waiting_tablet-portrait_dark.png) |
| `connected-carplay` — CarPlay connected over the same Wi-Fi, with the session timer | [png](home/home_connected-carplay_car-landscape_dark.png) | [png](home/home_connected-carplay_car-landscape_light.png) | [png](home/home_connected-carplay_car-1080p_dark.png) | [png](home/home_connected-carplay_car-split-third_dark.png) | [png](home/home_connected-carplay_phone-portrait_dark.png) | [png](home/home_connected-carplay_phone-portrait_light.png) | [png](home/home_connected-carplay_phone-landscape_dark.png) | [png](home/home_connected-carplay_tablet-portrait_dark.png) |
| `failed-carplay-auth-missing` — Failed: this build has no CarPlay authentication files | [png](home/home_failed-carplay-auth-missing_car-landscape_dark.png) | [png](home/home_failed-carplay-auth-missing_car-landscape_light.png) | [png](home/home_failed-carplay-auth-missing_car-1080p_dark.png) | [png](home/home_failed-carplay-auth-missing_car-split-third_dark.png) | [png](home/home_failed-carplay-auth-missing_phone-portrait_dark.png) | [png](home/home_failed-carplay-auth-missing_phone-portrait_light.png) | [png](home/home_failed-carplay-auth-missing_phone-landscape_dark.png) | [png](home/home_failed-carplay-auth-missing_tablet-portrait_dark.png) |
| `failed-car-hotspot-off` — Failed: the car hotspot is off | [png](home/home_failed-car-hotspot-off_car-landscape_dark.png) | [png](home/home_failed-car-hotspot-off_car-landscape_light.png) | [png](home/home_failed-car-hotspot-off_car-1080p_dark.png) | [png](home/home_failed-car-hotspot-off_car-split-third_dark.png) | [png](home/home_failed-car-hotspot-off_phone-portrait_dark.png) | [png](home/home_failed-car-hotspot-off_phone-portrait_light.png) | [png](home/home_failed-car-hotspot-off_phone-landscape_dark.png) | [png](home/home_failed-car-hotspot-off_tablet-portrait_dark.png) |
| `failed-engine` — Failed: the Android Auto engine stopped the connection | [png](home/home_failed-engine_car-landscape_dark.png) | [png](home/home_failed-engine_car-landscape_light.png) | [png](home/home_failed-engine_car-1080p_dark.png) | [png](home/home_failed-engine_car-split-third_dark.png) | [png](home/home_failed-engine_phone-portrait_dark.png) | [png](home/home_failed-engine_phone-portrait_light.png) | [png](home/home_failed-engine_phone-landscape_dark.png) | [png](home/home_failed-engine_tablet-portrait_dark.png) |
| `sheet-phones` — Phones sheet (iPhone, Android phone, unidentified device) | [png](home/home_sheet-phones_car-landscape_dark.png) | [png](home/home_sheet-phones_car-landscape_light.png) | [png](home/home_sheet-phones_car-1080p_dark.png) | — | [png](home/home_sheet-phones_phone-portrait_dark.png) | [png](home/home_sheet-phones_phone-portrait_light.png) | [png](home/home_sheet-phones_phone-landscape_dark.png) | [png](home/home_sheet-phones_tablet-portrait_dark.png) |

### Settings — `settings/` (98 images)

| State | car-landscape dark | car-landscape light | car-1080p dark | car-split-third dark | phone-portrait dark | phone-portrait light | phone-landscape dark | tablet-portrait dark |
|---|---|---|---|---|---|---|---|---|
| `root` — Root: category rail with Connection (wide) or the category grid (narrow) | [png](settings/settings_root_car-landscape_dark.png) | [png](settings/settings_root_car-landscape_light.png) | [png](settings/settings_root_car-1080p_dark.png) | [png](settings/settings_root_car-split-third_dark.png) | [png](settings/settings_root_phone-portrait_dark.png) | [png](settings/settings_root_phone-portrait_light.png) | [png](settings/settings_root_phone-landscape_dark.png) | [png](settings/settings_root_tablet-portrait_dark.png) |
| `connection-paused` — Connection, automatic connection paused | [png](settings/settings_connection-paused_car-landscape_dark.png) | [png](settings/settings_connection-paused_car-landscape_light.png) | [png](settings/settings_connection-paused_car-1080p_dark.png) | [png](settings/settings_connection-paused_car-split-third_dark.png) | [png](settings/settings_connection-paused_phone-portrait_dark.png) | [png](settings/settings_connection-paused_phone-portrait_light.png) | [png](settings/settings_connection-paused_phone-landscape_dark.png) | [png](settings/settings_connection-paused_tablet-portrait_dark.png) |
| `display-schedule` — Display with a day/night schedule | [png](settings/settings_display-schedule_car-landscape_dark.png) | [png](settings/settings_display-schedule_car-landscape_light.png) | [png](settings/settings_display-schedule_car-1080p_dark.png) | [png](settings/settings_display-schedule_car-split-third_dark.png) | [png](settings/settings_display-schedule_phone-portrait_dark.png) | [png](settings/settings_display-schedule_phone-portrait_light.png) | [png](settings/settings_display-schedule_phone-landscape_dark.png) | [png](settings/settings_display-schedule_tablet-portrait_dark.png) |
| `display-advanced` — Display with Show advanced on and a custom picture | [png](settings/settings_display-advanced_car-landscape_dark.png) | [png](settings/settings_display-advanced_car-landscape_light.png) | [png](settings/settings_display-advanced_car-1080p_dark.png) | [png](settings/settings_display-advanced_car-split-third_dark.png) | [png](settings/settings_display-advanced_phone-portrait_dark.png) | [png](settings/settings_display-advanced_phone-portrait_light.png) | [png](settings/settings_display-advanced_phone-landscape_dark.png) | [png](settings/settings_display-advanced_tablet-portrait_dark.png) |
| `sound-advanced-lab` — Sound & voice with Advanced and Lab rows | [png](settings/settings_sound-advanced-lab_car-landscape_dark.png) | [png](settings/settings_sound-advanced-lab_car-landscape_light.png) | [png](settings/settings_sound-advanced-lab_car-1080p_dark.png) | [png](settings/settings_sound-advanced-lab_car-split-third_dark.png) | [png](settings/settings_sound-advanced-lab_phone-portrait_dark.png) | [png](settings/settings_sound-advanced-lab_phone-portrait_light.png) | [png](settings/settings_sound-advanced-lab_phone-landscape_dark.png) | [png](settings/settings_sound-advanced-lab_tablet-portrait_dark.png) |
| `general-lab` — General with Lab unlocked | [png](settings/settings_general-lab_car-landscape_dark.png) | [png](settings/settings_general-lab_car-landscape_light.png) | [png](settings/settings_general-lab_car-1080p_dark.png) | [png](settings/settings_general-lab_car-split-third_dark.png) | [png](settings/settings_general-lab_phone-portrait_dark.png) | [png](settings/settings_general-lab_phone-portrait_light.png) | [png](settings/settings_general-lab_phone-landscape_dark.png) | [png](settings/settings_general-lab_tablet-portrait_dark.png) |
| `navigation` — Navigation | [png](settings/settings_navigation_car-landscape_dark.png) | [png](settings/settings_navigation_car-landscape_light.png) | [png](settings/settings_navigation_car-1080p_dark.png) | [png](settings/settings_navigation_car-split-third_dark.png) | [png](settings/settings_navigation_phone-portrait_dark.png) | [png](settings/settings_navigation_phone-portrait_light.png) | [png](settings/settings_navigation_phone-landscape_dark.png) | [png](settings/settings_navigation_tablet-portrait_dark.png) |
| `sheet-connection-method` — Sheet: connection method | [png](settings/settings_sheet-connection-method_car-landscape_dark.png) | [png](settings/settings_sheet-connection-method_car-landscape_light.png) | [png](settings/settings_sheet-connection-method_car-1080p_dark.png) | — | [png](settings/settings_sheet-connection-method_phone-portrait_dark.png) | [png](settings/settings_sheet-connection-method_phone-portrait_light.png) | [png](settings/settings_sheet-connection-method_phone-landscape_dark.png) | [png](settings/settings_sheet-connection-method_tablet-portrait_dark.png) |
| `sheet-hotspot` — Sheet: same Wi-Fi details | [png](settings/settings_sheet-hotspot_car-landscape_dark.png) | [png](settings/settings_sheet-hotspot_car-landscape_light.png) | [png](settings/settings_sheet-hotspot_car-1080p_dark.png) | — | [png](settings/settings_sheet-hotspot_phone-portrait_dark.png) | [png](settings/settings_sheet-hotspot_phone-portrait_light.png) | [png](settings/settings_sheet-hotspot_phone-landscape_dark.png) | [png](settings/settings_sheet-hotspot_tablet-portrait_dark.png) |
| `sheet-picture-custom` — Sheet: picture (custom) | [png](settings/settings_sheet-picture-custom_car-landscape_dark.png) | [png](settings/settings_sheet-picture-custom_car-landscape_light.png) | [png](settings/settings_sheet-picture-custom_car-1080p_dark.png) | — | [png](settings/settings_sheet-picture-custom_phone-portrait_dark.png) | [png](settings/settings_sheet-picture-custom_phone-portrait_light.png) | [png](settings/settings_sheet-picture-custom_phone-landscape_dark.png) | [png](settings/settings_sheet-picture-custom_tablet-portrait_dark.png) |
| `sheet-interface-size` — Sheet: interface size | [png](settings/settings_sheet-interface-size_car-landscape_dark.png) | [png](settings/settings_sheet-interface-size_car-landscape_light.png) | [png](settings/settings_sheet-interface-size_car-1080p_dark.png) | — | [png](settings/settings_sheet-interface-size_phone-portrait_dark.png) | [png](settings/settings_sheet-interface-size_phone-portrait_light.png) | [png](settings/settings_sheet-interface-size_phone-landscape_dark.png) | [png](settings/settings_sheet-interface-size_tablet-portrait_dark.png) |
| `sheet-driving-side` — Sheet: driving side | [png](settings/settings_sheet-driving-side_car-landscape_dark.png) | [png](settings/settings_sheet-driving-side_car-landscape_light.png) | [png](settings/settings_sheet-driving-side_car-1080p_dark.png) | — | [png](settings/settings_sheet-driving-side_phone-portrait_dark.png) | [png](settings/settings_sheet-driving-side_phone-portrait_light.png) | [png](settings/settings_sheet-driving-side_phone-landscape_dark.png) | [png](settings/settings_sheet-driving-side_tablet-portrait_dark.png) |
| `sheet-advanced-choice` — Sheet: an Advanced engine setting (Android Auto resolution) | [png](settings/settings_sheet-advanced-choice_car-landscape_dark.png) | [png](settings/settings_sheet-advanced-choice_car-landscape_light.png) | [png](settings/settings_sheet-advanced-choice_car-1080p_dark.png) | — | [png](settings/settings_sheet-advanced-choice_phone-portrait_dark.png) | [png](settings/settings_sheet-advanced-choice_phone-portrait_light.png) | [png](settings/settings_sheet-advanced-choice_phone-landscape_dark.png) | [png](settings/settings_sheet-advanced-choice_tablet-portrait_dark.png) |

### Diagnostics — `diagnostics/` (16 images)

| State | car-landscape dark | car-landscape light | car-1080p dark | car-split-third dark | phone-portrait dark | phone-portrait light | phone-landscape dark | tablet-portrait dark |
|---|---|---|---|---|---|---|---|---|
| `idle` — Checks and the connection log, no session | [png](diagnostics/diagnostics_idle_car-landscape_dark.png) | [png](diagnostics/diagnostics_idle_car-landscape_light.png) | [png](diagnostics/diagnostics_idle_car-1080p_dark.png) | [png](diagnostics/diagnostics_idle_car-split-third_dark.png) | [png](diagnostics/diagnostics_idle_phone-portrait_dark.png) | [png](diagnostics/diagnostics_idle_phone-portrait_light.png) | [png](diagnostics/diagnostics_idle_phone-landscape_dark.png) | [png](diagnostics/diagnostics_idle_tablet-portrait_dark.png) |
| `connecting` — Live CarPlay session card while connecting, checks and log | [png](diagnostics/diagnostics_connecting_car-landscape_dark.png) | [png](diagnostics/diagnostics_connecting_car-landscape_light.png) | [png](diagnostics/diagnostics_connecting_car-1080p_dark.png) | [png](diagnostics/diagnostics_connecting_car-split-third_dark.png) | [png](diagnostics/diagnostics_connecting_phone-portrait_dark.png) | [png](diagnostics/diagnostics_connecting_phone-portrait_light.png) | [png](diagnostics/diagnostics_connecting_phone-landscape_dark.png) | [png](diagnostics/diagnostics_connecting_tablet-portrait_dark.png) |

### About — `about/` (15 images)

| State | car-landscape dark | car-landscape light | car-1080p dark | car-split-third dark | phone-portrait dark | phone-portrait light | phone-landscape dark | tablet-portrait dark |
|---|---|---|---|---|---|---|---|---|
| `main` — Engines, device, licences, source QR codes, trademarks | [png](about/about_main_car-landscape_dark.png) | [png](about/about_main_car-landscape_light.png) | [png](about/about_main_car-1080p_dark.png) | [png](about/about_main_car-split-third_dark.png) | [png](about/about_main_phone-portrait_dark.png) | [png](about/about_main_phone-portrait_light.png) | [png](about/about_main_phone-landscape_dark.png) | [png](about/about_main_tablet-portrait_dark.png) |
| `sheet-licenses` — Open-source licences sheet | [png](about/about_sheet-licenses_car-landscape_dark.png) | [png](about/about_sheet-licenses_car-landscape_light.png) | [png](about/about_sheet-licenses_car-1080p_dark.png) | — | [png](about/about_sheet-licenses_phone-portrait_dark.png) | [png](about/about_sheet-licenses_phone-portrait_light.png) | [png](about/about_sheet-licenses_phone-landscape_dark.png) | [png](about/about_sheet-licenses_tablet-portrait_dark.png) |

## Add a screen or state

Append `GalleryEntry(area, name) { YourScreenContent(fakeState, ...) }` to
`app/src/test/java/com/carlink/app/gallery/GalleryEntries.kt`, using the same stateless
composable and fakes as the screen's `@Preview`s (Home's fakes live in `HomeFakes.kt`). Do not
wrap it in `CarLinkTheme`; the harness does that per config. Set `opensWindow = true` for states
that show a dialog or bottom sheet. Then regenerate and add the new row to this index.

## Caveats

- Robolectric always sizes the activity window to the panel, so `car-split-third` is emulated:
  the harness narrows `Configuration` and the layout to 640px and crops. Dialogs and bottom
  sheets are separate full-panel windows and cannot be placed in that strip, so entries with
  `opensWindow = true` are skipped there.
- Robolectric runs on SDK 35: its SDK 36+ sandbox does not start on the current JDK.
- Fonts are Robolectric's Roboto; a real head unit's system font may differ slightly.
- Times in the Diagnostics log and timers come from the test clock, not real sessions.
