# Contributing to CarLink

How to build, test and submit a change to CarLink. Bug reports and ideas are welcome too: use the
[issue forms](https://github.com/F-e-n-y-x/CarLink/issues/new/choose).

## Before you start

- Read [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for how the app is put together and
  [docs/BUILDING.md](docs/BUILDING.md) for the toolchain. The rules the code follows (one engine
  at a time, adapters that only start the session they are given, settings written per session,
  Android Auto in its own `:aa` process, no BYD behaviour outside the BYD profile) are in
  [.claude/skills/carlink-dev/SKILL.md](.claude/skills/carlink-dev/SKILL.md).
- For a larger change, open an issue first so the approach can be agreed.
- CarLink ships in English only. UI text goes into string resources prefixed `carlink_`, never
  hard-coded.

## Build and test

The project uses **no GitHub Actions or other CI**. Run the tests locally before every pull
request:

```sh
S=.claude/skills/carlink-dev/scripts/dev.sh
$S test     # resource-collision check + all five unit-test suites (app, carlink-core, engines)
$S build    # debug APK (com.carlink.app.hudtest)
```

Without the helper script, the same commands are listed in
[docs/BUILDING.md](docs/BUILDING.md#build-and-test). `SmoothVideoHostLifecycleTest` (DiPlay) is a
known flaky timing test; rerun it when it is the only failure.

- **UI changes:** check the screen at car-display size and at phone size (the emulator, or the
  [screenshot gallery](docs/screenshots/README.md): `./gradlew --no-daemon :app:recordScreenshots`)
  and look for clipped text, wrong colours and empty areas. Update the gallery images you change.
- **Connection changes** (USB, Bluetooth, Wi-Fi Direct, Same Wi-Fi, the car hotspot): the
  emulator has no Bluetooth or real Wi-Fi, so these are tested on a real tablet or head unit
  ([docs/BUILDING.md › Testing on a device](docs/BUILDING.md#testing-on-a-device)). Say in the
  pull request what you tested, on which screen and phone, and what you could not test.
- **Release builds run R8.** Reflection, JNI or classes loaded by name need a narrow keep rule;
  check with `dev.sh build-release`.

## Changing the engines

`vendor/diplay` (CarPlay, DiPlay) and `vendor/diauto` (Android Auto, DiAuto) are **git subtrees**
of the upstream projects, kept with their history so upstream fixes can be pulled in.

- Prefer a host hook (`CarPlayHostIntegration`, DiAuto's `HostIntegration`) or a change in
  `carlink-core` over editing engine logic.
- When an engine edit is unavoidable, keep it small and mark it with a `// CarLink` comment (new
  files say so in their header).
- Add a row to [docs/VENDOR_CHANGES.md](docs/VENDOR_CHANGES.md) for every edit: every edit needs
  a row, every row an edit. The audit commands are at the top of that file.
- Do not run `git subtree pull` in a feature change; engine updates are their own pull request,
  followed by `python3 scripts/resource_collisions.py` and the full test run.

## Commits and pull requests

- Small commits, each building and passing the tests.
- An imperative subject line ("Show the update popup and About > Updates"), and a body that
  explains why the change is needed, not only what it does.
- Never commit signing keys or CarPlay authentication files (`*.jks`, `*.keystore`, `*.pk8`,
  `*.p7b`; `.gitignore` blocks them, but check `git status`).
- Update the docs that describe what you changed: the [user guide](docs/USER_GUIDE.md),
  [troubleshooting](docs/TROUBLESHOOTING.md), [CHANGELOG.md](CHANGELOG.md) for anything a driver
  notices.

## License

CarLink is licensed under the [GNU AGPL v3.0](LICENSE). By contributing you agree that your
contribution is distributed under the same license. The engines keep their own licenses (DiPlay
GPL-3.0, DiAuto AGPL-3.0).
