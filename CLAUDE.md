# CarLink

One Android app for car head units and tablets: an iPhone gets CarPlay (DiPlay engine,
`vendor/diplay`), an Android phone gets Android Auto (DiAuto engine, `vendor/diauto`, `:aa`
process), over USB or wirelessly.

Before any work here, load the `carlink-dev` skill (`.claude/skills/carlink-dev/SKILL.md`): it has
the architecture, the build and test loop (`.claude/skills/carlink-dev/scripts/dev.sh`), the vendor
rules and the commit rules. Use `carlink-device` for anything tested on the user's tablet.

## The engines' own agent files do not apply

`vendor/diplay/CLAUDE.md` and `vendor/diplay/AGENTS.md` are upstream DiPlay's instructions for the
standalone DiPlay app. In CarLink they are reference only. Where they differ, CarLink's rules win:

- Do not add settings to `DiPlayActivity` or any engine settings screen; the driver never sees
  them. Settings live in CarLink's UI (`app/.../ui/settings`) and reach the engines through
  `UnifiedSettings` and `CarPlaySettings`.
- CarLink ships English only. New CarLink strings go in `values/` with the `carlink_` prefix; do
  not add translations.
- Change engine code only through small `// CarLink` hooks, each with a row in
  `docs/VENDOR_CHANGES.md`. Update an engine with the upstream-diff procedure in that file, never
  with `git subtree pull`.
- Build and test with `dev.sh build` and `dev.sh test`, not DiPlay's CI command.

## Facts that are easy to get wrong

- Connection changes (USB, Bluetooth, Wi-Fi Direct, hotspot, same Wi-Fi) are not done until they
  are verified on a real device; the emulator cannot test them. Say plainly when that was not done.
- The CarPlay authentication files are never committed (`~/.carlink-private/`).
- File modes are noise on this drive: `git config core.fileMode false` hides the 644→755 changes.
