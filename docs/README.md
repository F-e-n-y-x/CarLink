# CarLink documentation

The index of CarLink's documentation: what each page covers and who it is for. Start with the
[project README](../README.md) for what CarLink is and how to install it.

## For users

| Page | What it covers |
|---|---|
| [README › Install](../README.md#install) | Download, install and verify the APK |
| [USER_GUIDE.md](USER_GUIDE.md) | First-time setup, how a phone is detected and connected, updates, the screens and settings, car brand, slower head units, connection times, known limits |
| [TROUBLESHOOTING.md](TROUBLESHOOTING.md) | Every "why it's waiting" and failure message on Home and what to do, plus how to report a problem with Diagnostics › Share log |
| [screenshots/README.md](screenshots/README.md) | Every screen and state, on tablet, car-display and phone sizes, light and dark |
| [CHANGELOG.md](../CHANGELOG.md) | What changed in each release |

## For developers

| Page | What it covers |
|---|---|
| [BUILDING.md](BUILDING.md) | Toolchain, build and test commands, CarPlay authentication files, the screenshot gallery, testing on a real device |
| [ARCHITECTURE.md](ARCHITECTURE.md) | The one-APK, two-process design, the modules, where each part of the connection layer lives |
| [INTEGRATION.md](INTEGRATION.md) | Reusing the backend: the `:carlink-core` library, `CarLinkCore.install`, the host interface, a minimal launcher |
| [RELEASING.md](RELEASING.md) | Versions, the signing key, `scripts/release.sh`, and what the in-app updater checks |
| [VENDOR_CHANGES.md](VENDOR_CHANGES.md) | Every change made inside the vendored DiPlay and DiAuto engines, and how to audit them |
| [LOW_END_PERFORMANCE.md](LOW_END_PERFORMANCE.md) | Measurements and rules for cheap head units: lite rendering, deferred start-up, smaller APK |
| [CONTRIBUTING.md](../CONTRIBUTING.md) | How to build, test and submit a change; engine-edit rules; commit style |
| [SECURITY.md](../SECURITY.md) | Reporting a vulnerability; the extractable CarPlay identity; update verification |
| [dissection/DIPLAY.md](dissection/DIPLAY.md), [dissection/DIAUTO.md](dissection/DIAUTO.md) | How each engine works and fails (cited elsewhere as DP§… and DA§…) |
| [`carlink-dev` skill](../.claude/skills/carlink-dev/SKILL.md), [`carlink-device` skill](../.claude/skills/carlink-device/SKILL.md) | The working rules and the device-test loop used in this repository |

## Design and research

Each note says at the top whether it is current or superseded.

| Note | Status | What it covers |
|---|---|---|
| [REBUILD_V2.md](../design/REBUILD_V2.md) | current | The connection layer the app runs (`SessionManager`, adapters, wireless policy) |
| [RESEARCH_PLUG_AND_PLAY.md](../design/RESEARCH_PLUG_AND_PLAY.md) | current | How shipping receivers make wireless CarPlay and Android Auto "just work" |
| [HOME_V2_RATIONALE.md](../design/HOME_V2_RATIONALE.md) | current | The "Instrument" UI: Home, Settings, Diagnostics, About |
| [RESEARCH_HOME_V2.md](../design/RESEARCH_HOME_V2.md) | current | Research behind the UI |
| [RESEARCH_2026-10_R8_PLATFORM.md](../design/RESEARCH_2026-10_R8_PLATFORM.md) | current | R8 shrinking, platform changes, other receiver projects |
| [RESEARCH_README.md](../design/RESEARCH_README.md) | current | Research behind this documentation and the README |
| [logo/LOGO_RESEARCH.md](../design/logo/LOGO_RESEARCH.md) | current | Logo research, the four concepts and the chosen "Cast" mark |
| [RESEARCH_PREMIUM_UX.md](../design/RESEARCH_PREMIUM_UX.md) | superseded (visual direction) | Earlier in-car UX research |
| [DESIGN_SPEC.md](../design/DESIGN_SPEC.md) | partly superseded | The v1 UI spec for BYD DiLink units |
| [REBUILD_PLAN.md](../design/REBUILD_PLAN.md) | superseded | The v1 connection layer plan |

## Archive

Dated records, kept as written. They describe the code as it was on their date.

| Page | What it records |
|---|---|
| [RELIABILITY_AUDIT_2026-10-09.md](RELIABILITY_AUDIT_2026-10-09.md) | Connection reliability audit on the test tablet with an iPhone and a Samsung S25 Ultra |
| [dissection/](dissection/DIPLAY.md) | The engine dissections cite CarLink v1 classes as they were when written (see each file's note) |
