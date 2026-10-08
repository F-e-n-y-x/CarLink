# CarLink UI screenshots

Rendered by `app/src/test/java/com/carlink/app/gallery/ScreenshotGallery.kt` (Robolectric,
native graphics) from the screens' stateless content with fake state. One folder per area;
files are named `<area>_<state>_<config>_<theme>.png`.

## Regenerate

```sh
./gradlew :app:recordScreenshots
```

This runs only the gallery test, deletes the old PNGs of every registered area and writes new
ones. Ordinary `testDebugUnitTest` runs skip the gallery (it needs `-Dcarlink.screenshots=true`,
which the task sets; `-Pcarlink.screenshots=true` works too).

## Configs

| Config | Profile | Panel | CarLink canvas | Themes |
|---|---|---|---|---|
| `car-landscape` | Car | 2160×1215px (1440×810dp at 240dpi) | 1440×810dp | dark, light |
| `car-1080p` | Car | 1920×1080px reporting 240dpi | 1440×810dp | dark |
| `car-split-third` | Car | 640×1080px window on a 1920×1080px panel (BYD ⅓ split) | 480×810dp | dark |
| `phone-portrait` | Phone/tablet | 400×860dp at 420dpi | 400×860dp | dark, light |
| `phone-landscape` | Phone/tablet | 860×400dp at 420dpi | 860×400dp | dark |
| `tablet-portrait` | Phone/tablet | 800×1280dp at 240dpi | 800×1280dp | dark |

## Add a screen

Append `GalleryEntry(area, name) { YourScreenContent(fakeState, ...) }` to
`app/src/test/java/com/carlink/app/gallery/GalleryEntries.kt`, using the same stateless
composable and fakes as the screen's `@Preview`s. Do not wrap it in `CarLinkTheme`; the harness
does that per config. Set `opensWindow = true` for states that show a dialog or bottom sheet.

## Caveats

- Robolectric always sizes the activity window to the panel, so `car-split-third` is emulated:
  the harness narrows `Configuration` and the layout to 640px and crops. Dialogs and bottom
  sheets are separate full-panel windows and cannot be placed in that strip, so entries with
  `opensWindow = true` are skipped there.
- Robolectric runs on SDK 35: its SDK 36+ sandbox does not start on the current JDK.
- Fonts are Robolectric's Roboto; a real head unit's system font may differ slightly.
