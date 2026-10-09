# CarLink UI Design Spec

> **Status (2026-10-09): partly superseded; kept for reference.** Written for BYD DiLink head
> units and the v1 app. The connection behaviour (state machine, stages, error handling) is
> replaced by [REBUILD_V2.md](REBUILD_V2.md) and `carlink-core/.../connection/`; the look and the Home and
> Settings structure by [RESEARCH_HOME_V2.md](RESEARCH_HOME_V2.md) and
> [HOME_V2_RATIONALE.md](HOME_V2_RATIONALE.md). Still useful for touch-target and text sizes,
> canvas scaling and the driver-facing error wording.

Status: v1.0, ready to build. Date: 2026-10-09.
Scope: CarLink's own UI on BYD DiLink head units: Home, Connecting, Phone detail, Settings, Onboarding, Diagnostics and About.
Out of scope: the projected CarPlay and Android Auto content. Those UIs belong to Apple and Google.

The spec is opinionated on purpose. Where it says MUST, build it that way. Where it says SHOULD, deviate only with a measured reason, such as a frame-time trace or an in-car glance test.

---

## 0. TL;DR

1. **One screen, one question.** Home answers "Is my phone connected, and if not, what do I tap?" in a single 2-second glance. The status hero replaces the marketing headline ("A familiar drive.").
2. **Auto-detect is the product.** The UI never asks whether the phone is an iPhone or an Android phone. It shows what it detected (phone type → engine, transport), then the five connection stages. Each failure gives one primary recovery action.
3. **Car-grade sizing.** Touch targets are at least 76dp, primary buttons 96dp, and text at least 24sp (28sp or more on anything a driver reads in motion). CarLink uses one design canvas: **1440×810dp in landscape and 810×1440dp in portrait**, scaled from the screen's pixels, not from the head unit's often-wrong DPI.
4. **Dark by default; follows the car.** There is one accent (Link Cyan) plus semantic colors. CarPlay and Android Auto each get an identity tint, used only on their glyph badge. Every pair passes WCAG AA.
5. **Settings are organized by driver goal** (the DiPlay rule, kept). Shared controls come first. Each category then has a **CarPlay** section and an **Android Auto** section. All changes auto-save (DiAuto's Save/Reset model is removed). Changes that need a reconnect say so and raise one "Reconnect now" bar.
6. **Parked-only content** (text entry, Advanced, log viewer, report export) is locked while moving when the app can tell the car is moving.

---

## 1. Research summary

### 1.1 Official guidance (normative inputs)

| Source | What it says | What CarLink does with it |
|---|---|---|
| NHTSA Visual-Manual Driver Distraction Guidelines (2013), [Federal Register text](https://www.govinfo.gov/content/pkg/FR-2013-04-26/html/2013-09883.htm) | Tasks should be completable with off-road glances of **≤2 s each** and **≤12 s total** (TEORT). Occlusion test: 1.5 s glances, ≤12 s cumulative. Per-se lockouts: automatically scrolling text, manual text entry, reading of book or web-like text, video not related to driving. | Every in-motion task (connect, cancel, switch phone, retry) is **1 tap from Home**, at most 2. No auto-scrolling text anywhere (no marquee device names). The keyboard, log reading and long help text are parked-only. |
| Android for Cars sizing ([AAOS](https://developers.google.com/cars/design/automotive-os/design-system/sizing), [Android Auto](https://developers.google.com/cars/design/android-auto/design-system/sizing)) | Minimum touch target **76×76dp**, "based on both the driver's distance from the head unit and the need to accommodate quick taps while driving." Icons: 44 / 36 / 24dp. Avatars: 56 / 76 / 96dp. Targets must not overlap, and content is centered in its target. | Touch-target floor is 76dp; there is no exception for "small" icon buttons. Icon tokens are 44 / 36 / 24dp. Phone avatars are 76dp on Home and 56dp in lists. |
| AAOS typography ([developers.google.com](https://developers.google.com/cars/design/automotive-os/design-system/typography), moved to [docs.partner.android.com/drivingux](https://docs.partner.android.com/drivingux/automotive-os/design-system/typography)) | "The minimum type size for Android Automotive OS is **24dp**." Sizes come in 4dp increments. Use Medium weight sparingly and **avoid bold** ("less legible than medium"). Roboto, with Noto Sans as fallback. | Type floor is 24sp. The scale steps by 4sp. Only weights 400 and 500 are used. The fonts are Roboto and Noto. |
| AAOS color ([developers.google.com](https://developers.google.com/cars/design/automotive-os/design-system/color)) | Text and icons need at least **4.5:1** contrast. The UI is "built from black," so day and night differ only slightly. There is one accent; other colors are used sparingly. White text opacities of 88 / 72 / 56 % create hierarchy. Full-contrast white should not be overused. | The scheme is based on near-black with a single accent. onSurface is #E8EDF3 (about 92 % white), not #FFFFFF. Three text levels map to 88/72/56. |
| AAOS dual-pane Settings ([source.android.com](https://source.android.com/docs/automotive/hmi/car_settings/dual_pane)) | Dual pane (a static L0 menu on the left, content on the right) is used when the window is **≥1400dp** wide; narrower windows get a single pane. | CarLink's landscape canvas is 1440dp, so Settings uses two panes in landscape. Portrait (810dp) and split-screen use a single pane with drill-in. |
| AAOS Car UX Restrictions ([source.android.com](https://source.android.com/docs/automotive/driver_distraction/car_uxr)) | Restriction flags include `no_keyboard`, `no_setup`/`no_config`, `no_video` and `fully_restricted`, with string-length and content-depth limits. | The same concepts drive CarLink's own "parked-only" gate (§3.5). |
| Apple CarPlay HIG ([developer.apple.com](https://developer.apple.com/design/human-interface-guidelines/carplay)) | "Never lock people out … because the connected iPhone requires input." "Report errors in CarPlay, not on the connected iPhone. Never direct people to pick up their iPhone." "Place the most important content and controls in the upper half of the screen." "Avoid using the same color for interactive and noninteractive elements." "Test … under a variety of lighting conditions in an actual car." Support light and dark. | Error copy never says "check your phone" while moving. It says what the car can do ("Retry", "Use cable"). Phone-side steps (for example "Allow CarPlay while locked") go into onboarding, which runs while parked. Interactive elements are the only things that use the accent. |
| Material 3 window size classes ([developer.android.com](https://developer.android.com/develop/ui/compose/layouts/adaptive/use-window-size-classes)) | Width classes: Compact <600, Medium 600–839, Expanded 840–1199, Large 1200–1599, XL ≥1600dp. Height classes: Compact <480, Medium 480–899, Expanded ≥900dp. | Layouts are keyed to these classes. DiLink split-screen (⅓ / ⅔) lands in Compact or Expanded, so both layouts are specified. |
| ISO 15008:2017 (in-vehicle display legibility; paywalled) | Sets character-size requirements as a visual angle. The commonly cited floor is about 20 arcmin character height. **This value is from memory and was not verified in the search; check the standard before citing it externally.** | Used only as a sanity check on the type scale (§5.2). The decisive floor is AAOS 24dp. |

### 1.2 Production UIs

- **Polestar / Android Automotive** ([Polestar UX statement via GearBrain review](https://www.gearbrain.com/amp/review-android-automotive-polestar-2-2646843862), [The Drive review](https://www.thedrive.com/car-reviews/polestar-2-android-automotive-os-infotainment-review-google-specs-system-tech-evs-electric-cars), [GoAuto on Polestar 3/4 HMI](https://goauto.com.au/amp/93827/polestar-details-hi-tech-hmi-for-3-and-4-suvs.html)). Polestar applies "Scandinavian minimalism … to both UI and UX." Home has four cards; developers praised the "fantastic, large icons." Polestar 3/4 menus are built to "stay navigable however deep a user goes into settings." *Takeaways:* a few big tiles; flat depth; a neutral palette with one accent (Polestar uses orange).
- **Rivian** ([Rivian Vehicle OS, Matt Metropulos, Behance](https://www.behance.net/gallery/166386511/Rivian-Vehicle-OS); [Pentagram typeface work](https://www.pentagram.com/work/rivian); [Brian Lucas R1 UI](https://www.brianlucasdesign.com/project/rivian-ui)). "Color palettes … needed to work during the brightest and darkest lighting conditions … a high contrast palette … hints of warmness for daytime views and coolness for the night." Rivian uses custom display numerals adapted from Söhne. *Takeaways:* contrast first; day and night are temperature shifts, not different designs; numerals deserve care (CarLink uses tabular figures for timers and stats).
- **Tesla V11/V12** ([TMC dark mode thread](https://teslamotorsclub.com/tmc/threads/dark-mode.252376/), [EVANNEX history](https://evannex.com/blogs/news/71958981-how-tesla-revolutionized-automotive-user-interface-design)). In V11, dark mode was "optimised for both day and night usage." The common critique is that later versions turned 1-touch actions into 2–3 touches, a "(poor) tablet app" feel. *Takeaways:* dark works in daytime when the contrast is right; never trade depth for minimalism on frequent tasks.
- **BYD DiLink** ([BYD AU DiLink support](https://www.byd.com/au/support/dilink), [BYDMate project notes](https://github.com/AndyShaman/BYDMate)). There is a system light / dark / auto setting. Split-screen runs apps left and right (BYDMate documents ⅓ + ⅔). DiLink 5.0 runs on Android 12L, and an OTA "UI7" exists. *Takeaways:* follow the car's light/dark mode by default; Compact width (split-screen) must be a first-class layout.

### 1.3 Competitor and adjacent apps (what users complain about)

- **Carlinkit AutoKit** ([Android Police 5.0 review](https://www.androidpolice.com/carlinkit-50-review/), [vendor settings guide](https://www.carlinkitfactory.com/explanation-of-autokit-app-options-settings-a-comprehensive-guide/), [AutoKit FAQ](https://carlinkitcarplay.com/pages/faq-en-autokit-for-aftermarket-android-screen-cars-version1-0)). Complaints: weak documentation; troubleshooting spread across "settings > environment testing", "three working modes" and "Sync mode"; resolution freezes that users must fix by trial and error; auto-start hidden. *CarLink response:* a single Diagnostics "Checks" list with pass/fail and a fix button per row; safe defaults; risky options behind Advanced with plain-language consequences.
- **AAWireless app** ([Android Central](https://www.androidcentral.com/accessories/audio/aawireless-review), [Android Police](https://www.androidpolice.com/aawireless-review/), [Android Authority TWO](https://www.androidauthority.com/aawireless-two-review-3491590/)). Praised: an "app walked me through every step," with setup in about 3–10 minutes. Criticized: "advanced features … can be confusing"; users wish it "was easier to pick which UI display you wanted instead of having to randomly pick DPI"; auto-launch breaks silently. *CarLink response:* a guided onboarding; an "Interface size" control with a **live preview and named presets** instead of a raw DPI number; auto-start health shown in Checks.
- **Headunit Reloaded** ([Play listing](https://play.google.com/store/apps/details?id=gb.xxy.hr&hl=en_US)). Users can't find how to disable speed-dependent volume, and the listing's black-screen fix is "enable software decoding." *CarLink response:* every behavior that changes audio or video has a findable, searchable setting, and a black screen offers "Try compatibility video mode" as a recovery action.
- **DiPlay field feedback** ([issue #11](https://github.com/shihabal3amri/DiPlay/issues/11)). On BYD Han DiLink 4.0: a "USB permission prompt **appears on every plug-in**" (no "always allow"), and the wired "Connecting… screen stays up for roughly **15 to 25 seconds**." *CarLink response:* a staged progress timeline so a 20-second wait feels deterministic; a "Request USB access" stage that explains the system prompt *before* it appears; and an onboarding step that sets CarLink as the default USB handler where firmware allows it.

### 1.4 Visual inspiration (Dribbble and Behance)

Dribbble and Behance block automated fetching (HTTP 202/403), so these shots were found through search and judged from their titles, tags and descriptions. Open them in a browser before using them as visual references.

| Shot | Author | URL | Why it's useful |
|---|---|---|---|
| Car UI Dashboard | Conceptzilla | https://dribbble.com/shots/18021625-Car-UI-Dashboard | Argues for dark mode because "elements [are] contrasting and easy to perceive while driving"; one-tap detail menu; media and route on one screen. Reference for the **hero card + side list** composition. |
| Car interactive split-screen concept | Luca Lombardi for Dynamo | https://dribbble.com/shots/18128789-Car-interactive-split-screen-concept | Centralizes commands in one access point and splits the screen. Reference for **Compact/split-screen** behavior. |
| HMI UI Electric car dashboard – Polestar | Jonathan Larradet | https://dribbble.com/shots/16689571-HMI-UI-Electric-car-dashboard-Polestar | Polestar-style restraint with a monochrome base and a single accent. Reference for **palette discipline**. |
| UI Animation of automotive infotainment system | Septembr | https://dribbble.com/shots/2004403-UI-Animation-of-automotive-infotainment-system | Motion study. Reference for **short, purposeful state transitions** (not looping decoration). |
| Tesla Model X UI redesign concept | Maksym Moshenskyi | https://dribbble.com/shots/5527749-Tesla-Model-X-UI-redesign-concept | Typography cleanup and accent restraint. |
| Car App UI Design | Hongge Chen | https://dribbble.com/shots/6859747-Car-App-Ui-Design | Card-based vehicle app; status-first cards. |
| Automotive App Design | Purrweb | https://dribbble.com/shots/14032665-Automotive-App-Design | Status chips and connection cards. |
| Tag pages for continued browsing | — | [car_settings](https://dribbble.com/tags/car_settings), [android-automotive](https://dribbble.com/tags/android-automotive), [carplay](https://dribbble.com/tags/carplay), [infotainment](https://dribbble.com/tags/infotainment), [car-display](https://dribbble.com/tags/car-display) | Shots noted there include "Vehicle Control Center – UI Concept", "HMI Concept – Setting", "Tabs – Android Automotive OS", "Pink Infotainment" (Ali Shirvani, settings UI) and "Tesla Explorations" (Wojciech Wasilewski). |
| Rivian Vehicle OS | Matt Metropulos (Behance) | https://www.behance.net/gallery/166386511/Rivian-Vehicle-OS | Day/night palette rationale (see §1.2). |
| AURA – Automotive HMI Case Study; Automotive IVI – Low-Distraction UX Case Study; TATA iDRIVE – A Unified Infotainment Design System; Hyundai Mobis IVI System UX Case Study; Designing the Lotus Infotainment System (Ignasi Boza) | various (Behance) | [search: automotive hmi](https://www.behance.net/search/projects/automotive%20hmi), [infotainment system hmi design](https://www.behance.net/search/projects/infotainment%20system%20hmi%20design) | Case studies; **TATA iDRIVE** is the closest analog to a tokenized system. |

Mobbin has no automotive category worth using. A search for Awwwards automotive HMI work returned nothing relevant (Awwwards rewards websites, not HMIs), so neither is cited.

### 1.5 Key takeaways

1. **Status-first, not brand-first.** Every strong reference leads with live state. The current apps lead with a slogan.
2. **Few, large, flat.** Polestar's four cards, AAOS's 76dp targets and the 24dp type floor all point the same way. Keep depth at one level for frequent tasks.
3. **Use one accent and keep it for interactive elements.** This is both Apple's and Google's rule.
4. **Dark can be the default for day and night**, as long as contrast holds (Tesla V11, Rivian). Follow the car's mode and treat it as a temperature shift, not a redesign.
5. **Long waits need visible stages.** Wired CarPlay on DiLink takes 15–25 s, and users read a static spinner as "broken."
6. **Troubleshooting needs a single checklist with fixes**, not scattered modes (the AutoKit lesson).
7. **Don't expose raw technical knobs** (DPI) without presets and a preview (the AAWireless lesson).

---

## 2. Critique of the current UIs

Evidence: `DiPlay/site/assets/home.png`, `settings.png`; `DiAuto/site/assets/home.png`, `settings.png`, `options.png`; `DiAuto/app/src/main/res/layout/fragment_home.xml`; `DiPlay/common/.../DiPlayPalette.kt`, `DiPlayActivity.kt`, `AGENTS.md`; `DiAuto/app/src/main/res/values/diauto.xml`.

The screenshots measure about 90px for a 68dp button on a 1920×1080 capture, so the head unit runs at an effective density of **≈1.33 (≈1440×810dp)**. All numbers below use that density.

### 2.1 What both apps do well (keep)

- **The dark navy palette** (`#0C111B` background, `#151E2C` surface, `#A6C8FF` accent). It is calm, low-glare and already familiar to users. CarLink keeps the family resemblance but neutralizes it toward near-black (§5.1).
- **A large, full-width primary button** for the main action, with the icon placed before the text.
- **`accessibilityLiveRegion="polite"`** on the DiAuto status text.
- **DiPlay's settings information architecture by driver goal** (`SettingsCategory`: Connection, Display, Audio, Navigation, Vehicle, Diagnostics, Advanced). These rules carry over: one category per setting, `(experimental)` suffix conventions, a "Reconnect now" bar, and no dropped session without consent. These are mature and tested (`SettingsLayoutPolicyTest`).
- **DiPlay's settings search**, the Overview quick settings (at most 4), and the dual-pane rail (`settingsRailScroll`).
- **DiAuto's radio-list language picker**, which shows each language in its own name ("Deutsch", "Tiếng Việt").
- Friendly, plain-language copy ("Plug your iPhone into a USB data port. Allow CarPlay when your iPhone asks.").

### 2.2 Home (both apps; nearly identical layouts)

| Problem | Evidence | Fix in CarLink |
|---|---|---|
| The prime top-left area holds a slogan ("YOUR PHONE. YOUR DRIVE. / A familiar drive.", 42sp) while the real status ("Ready when you are", 24sp) sits second, inside a card. | home.png ×2 | The **status hero** owns the top-left. The brand shrinks to a 36dp mark in the top bar. |
| No phone identity. The user can't see *which* phone will connect or whether it's present. | — | A **Phones** list with type, last transport and presence. |
| Three to four equal-weight actions ("Connect phone", "Choose iPhone", "Connect with USB", "Settings") with no clear hierarchy, and "Connect with USB" styled as a button when USB actually starts on plug-in. | DiPlay home | One primary action. USB becomes a *state* ("Plug in a cable to connect") that the hero reacts to, not a button. |
| Hint paragraphs at about 14–15sp in two lines ("Pair your phone in the car's Bluetooth settings, then choose Connect phone. Keep Bluetooth and Wi-Fi on."). This is reading while driving, and it's below the 24sp floor. | `fragment_home.xml` (`textSize=15sp`, `14sp`) | Hints appear only when relevant (for example Bluetooth off), at ≥24sp, as one sentence plus a fix button. |
| Buttons are **68dp** tall, below the 76dp floor. | `layout_height="68dp"` | Primary 96dp; secondary 76dp or taller. |
| ALL-CAPS tracked eyebrows (`letterSpacing 0.16`, 12sp) and "CAR HOME" as a low-contrast text button. All-caps is unreadable in Arabic and CJK and slower to read in Latin scripts. | DiAuto home | Sentence case everywhere. "Car home" becomes a 76dp icon button (`home`) with a label. |
| The logo appears twice on DiPlay (header and a big right-side icon), and the lower third of the screen is empty. | DiPlay home | One brand mark. The space goes to the phone list. |
| The version string "PUBLIC PREVIEW · 0.1.0" in 12sp tracked caps floats in the action column. | DiPlay home | Moves to About. |

### 2.3 Settings

| Problem | Evidence | Fix |
|---|---|---|
| **DiPlay: choice rows render as centered buttons** ("Icon and text size · Default", "Frame rate · 30 fps · lighter load"). Title and value merge into one centered string, which defeats left-edge scanning, and the triple "·" chain is ambiguous. | DiPlay settings.png | A standard **setting row**: title on the left (28sp), value on its own line or right-aligned (24sp, onSurfaceVariant), and a chevron. |
| Helper text sits *between* rows, detached from its control. | DiPlay settings.png ("Smaller sizes use more processing power…") | The description is the row's second line, inside the row's touch target. |
| Toggles are small (about 52×32dp visual) at the far right, about 1,700px from the label. | DiPlay settings.png | The **whole row** is the toggle target (≥96dp tall). The switch is scaled to 64×40dp. |
| **DiAuto: explicit Save/Reset model** (`options.png` top right, "unsaved changes" dialogs). This is error-prone and a different model from DiPlay. | options.png, `R.string.unsaved_changes` | **Auto-save** everything. "Reset" moves to General > Backup & reset, behind a confirmation. |
| DiAuto top-level Settings uses the **same gear icon for "Display and performance" and "Permissions"**, and the "Automatic connection" icon is Wi-Fi. | DiAuto settings.png | A distinct Material Symbol per category (§5.8). |
| DiAuto's full options page is one very long list of 13 flat categories (General, Wireless, Dark mode, Automation, Navigation, Graphic, Video, Input, Audio, Backup, Reset, Debug, Info) under Basic/Advanced tabs, with 12sp-ish section headers. | options.png, `SettingsFragment.kt` | Merged into 9 goal categories in a rail (§4.4). Basic/Advanced tabs become an Advanced category plus inline "(experimental)" markers. |
| A modal language dialog covers the settings it belongs to. | options.png | Language is its own page in the content pane; radio rows are 88dp. |
| A search field that needs a keyboard sits at the top of a driving app. | options.png | Search lives in the rail and is **parked-only** (the keyboard is a per-se lockout). |

### 2.4 Projection (DiAuto `projection-music.png`)

This is not in scope, but it shows the app's edge. The engine draws its own left rail. CarLink adds **no chrome over projection**: no floating buttons and no badges. The way back to CarLink is the engine's own exit (CarPlay's "back to car" or Android Auto's "Exit"), plus the configurable wheel key or gesture already in DiPlay (`settings_gesture_fingers`).

---

## 3. Design principles

1. **Glance in two seconds, finish in twelve.** Each screen has one primary question and one primary action. Any in-motion task must finish within 12 s total eyes-off-road and use at most 2 taps. If it doesn't fit, it is parked-only.
2. **Detect, don't ask.** CarLink infers the phone type, engine and transport. The UI *states* what it detected ("iPhone 15 · USB → CarPlay") so the driver can trust it, and allows an override only in Phone detail.
3. **Zero-tap is the goal; one tap is the fallback.** Auto-connect is on by default after onboarding. The Home primary button is the fallback. Cancel is always one tap.
4. **Every error has a way out.** An error shows *what failed*, in the driver's words, and **one** primary recovery action, plus at most one secondary action. Never just "Error -32." Technical detail goes to Diagnostics.
5. **Big, few, flat.** Touch targets are ≥76dp, text ≥24sp and navigation depth ≤2 (Home → Settings category). No hamburger menus and no hidden gestures for anything essential.
6. **Calm in motion.** No looping decoration, no auto-scrolling text and no surprise modals while driving. Motion only explains a state change.
7. **The car's light, the car's language.** Follow the head unit's light/dark mode and locale. Day and night are temperature and luminance shifts of the same layout.
8. **Honest about consequences.** If a setting reconnects the session, applies next time, or can break audio or video on some firmware, the row says so *before* the tap (badges, §6.3.4).
9. **Built for a weak GPU.** No blur, no shadows-as-depth, no large alpha overdraw. Tonal surfaces only. A 60 fps budget on a Snapdragon 625-class SoC.

### 3.5 Driving state gate (parked-only)

The **motion signal** is chosen in this order:

1. Android `CarUxRestrictionsManager`, where present (rare on DiLink).
2. BYD vehicle data (speed or gear), via the existing `BydVehicleFieldStore` / ADB access when the user enabled it.
3. Otherwise **unknown**, which is treated as *possibly moving* only for per-se lockouts (keyboard). Everything else stays enabled.

| Content | Moving | Parked / unknown |
|---|---|---|
| Home, Connecting, Phone list, Retry, Cancel, switch phone | ✓ | ✓ |
| Settings toggles and choices in Connection / Display / Audio / Navigation / Vehicle | ✓ (one tap each) | ✓ |
| Any text entry (search, rename phone, BSSID, SSID, password) | ✗ ("Available when parked") | ✓ (unknown: ✓) |
| Advanced category, Diagnostics log viewer, report export, Onboarding, About > licenses | ✗ (row shown with lock) | ✓ |

The locked state is a row that keeps its normal look at 56 % emphasis, adds a `lock` icon and the subtitle "Available when parked." Tapping it shows a toast. It never hides the row, because a missing setting makes users think it was removed.

---

## 4. Information architecture

### 4.1 Screen map

```
                         ┌───────────────┐
           first run ──▶ │  Onboarding   │──done──┐
                         └───────────────┘        ▼
┌────────────┐   tap/auto   ┌────────────┐  live  ┌───────────────────┐
│    Home    │─────────────▶│ Connecting │──────▶ │ Projection (engine)│
│ (hub)      │◀── cancel ───│ (timeline) │◀─exit──│ CarPlay / AA       │
└─┬───┬───┬──┘              └─────┬──────┘        └───────────────────┘
  │   │   │                        └─ error ─▶ inline recovery (same screen)
  │   │   └──▶ Phone detail (side sheet)
  │   └──────▶ Settings (rail: General, Connection, Display, Audio,
  │                      Navigation & HUD, Vehicle, Diagnostics, Advanced, About)
  └──────────▶ Diagnostics (shortcut from errors; also a Settings category)
```

- **Back** always goes up one level. From Home, back goes to the car's launcher (the old "Car home" action).
- There is no bottom navigation, because the app has only one hub. Settings is reached with one tap from Home's top bar.
- While a session is live, opening CarLink (from the launcher or the exit gesture) shows **Home in "Connected" state** with a big "Return to CarPlay" primary.

### 4.2 Connection model and auto-detection states

**Detection rules** (the UI copy mirrors these):

| Signal | Inference |
|---|---|
| USB attach, VID `0x05AC` (Apple) | iPhone → **CarPlay** |
| USB attach, another VID, AOA-capable | Android phone → **Android Auto** |
| Bluetooth-paired device that advertises the iAP2 / CarPlay UUID | iPhone → CarPlay (wireless) |
| Bluetooth-paired device that advertises the Android Auto RFCOMM service | Android → Android Auto (wireless) |
| Both or neither | Unknown. Show it as "Phone" and probe; if both engines fail, show an error with "Tell CarLink what this phone is" (parked-only). |

**Global connection state** (one source of truth, `ConnectionUiState`):

```
Idle(readiness)                       // nothing attached; readiness = checks summary
WaitingForPhone(phone?, transport?)   // auto-connect armed; e.g., BT on, waiting for phone to appear
Connecting(phone, engine, transport, stage, stageStartedAt, startedAt)
Live(phone, engine, transport, since)
Error(phone?, engine?, transport?, failedStage, cause, recoveries[])
```

**The five stages.** These are shown in every Connecting view. The labels are engine-aware but keep the same order:

| # | Stage (label) | USB meaning | Wireless meaning | Typical | Timeout → error |
|---|---|---|---|---|---|
| 1 | **Phone found** | USB device attached | Paired phone in range (BT link) | <1 s | — |
| 2 | **Identified as iPhone → CarPlay** / **Android → Android Auto** | VID / AOA probe | SDP/UUID probe | <1 s | 5 s → E-ID |
| 3 | **USB access** / **Joining Wi-Fi** | Android USB permission (system prompt), AOA re-enumeration | Phone joins the car hotspot / Wi-Fi Direct | 1–10 s (prompt is user-paced) | 30 s → E-USB / E-WIFI |
| 4 | **Secure handshake** | iAP2 auth + AirPlay session / AA SSL + version | same | 2–10 s | 20 s → E-AUTH |
| 5 | **Starting picture** | First decoded frame | same | 1–5 s | 15 s → E-VIDEO |

**Error catalog.** Each error has exactly one primary recovery action. Copy is final English; translate it.

| Code | Title (≤40 chars) | Body (≤90 chars, one sentence) | Primary | Secondary |
|---|---|---|---|---|
| E-BT-OFF | Bluetooth is off | Wireless connection needs the car's Bluetooth. | **Turn on Bluetooth** (opens system toggle) | Use a cable |
| E-NO-PAIRED | No phone paired yet | Pair your phone in the car's Bluetooth settings. | **Open Bluetooth settings** | Use a cable |
| E-NOT-IN-RANGE | {Phone} isn't nearby | Make sure its Bluetooth and Wi-Fi are on. | **Try again** | Choose another phone |
| E-ID | Couldn't identify this phone | Unplug it, wait a moment, then plug it in again. | **Try again** | Open diagnostics |
| E-USB-DENIED | USB access was declined | CarLink needs USB access to talk to your phone. | **Ask again** | How to stop this prompt *(parked)* |
| E-USB-CHARGE | Cable can't carry data | This cable or port only charges. Try another port or cable. | **Try again** | — |
| E-WIFI | Phone didn't join the car's Wi-Fi | The car's hotspot may be off. | **Turn on hotspot** (if ADB automation is available) / **Try again** | Use a cable |
| E-AUTH | {CarPlay/Android Auto} didn't start | The phone stopped the connection. Unlock it once, then try again. *(parked copy only; moving copy: "Try again or use a cable.")* | **Try again** | Use a cable |
| E-ENGINE-OFF | {Engine} is turned off on this phone | Turn on {CarPlay / Android Auto} in the phone's settings when parked. | **Try again** | Show me how *(parked)* |
| E-VIDEO | No picture from the phone | Try the compatibility video mode. | **Retry with compatibility mode** (H.264, software decode for AA) | Open diagnostics |
| E-DROPPED | Connection lost | Reconnecting automatically… (auto-retries ×3 with 2 s / 5 s / 10 s backoff) | **Reconnect now** | Stop |

"Use a cable" is a *mode switch*. It changes the hero to "Plug {phone} into a USB data port" and highlights the USB transport chip.

### 4.3 Phone model

`PairedPhone(id, displayName, type: IPHONE|ANDROID|UNKNOWN, engine, lastTransport: USB|WIRELESS, lastConnected, autoConnect: Boolean, priority)`

- Home lists phones by **presence** first (connected > in range > recently seen), then by priority.
- The **default phone** for auto-connect is the most recently connected phone that is present. Users can pin a preferred phone in Phone detail.
- Phone detail (side sheet) shows rename *(parked)*, engine (auto-detected; override *(parked)*), preferred transport (Auto / Wireless / USB only), auto-connect toggle, and **Forget phone** (red text, with confirmation).

### 4.4 Settings categories (merged)

The rail order matches the frequency of use. Shared controls come first. Each category page has engine sections only when it has engine-specific controls. Badges: **[R]** = reconnects now; **[N]** = applies at next connection; **[X]** = experimental; **[ADB]** = needs ADB access; **[P]** = parked-only.

> Placement rules carry over from `DiPlay/AGENTS.md` without change: one setting lives in one category; Overview quick settings (≤4) are the only duplicates; experimental settings use the `(experimental)` suffix in each locale's form; `markReconnectNeeded()` triggers the "Reconnect now" bar; never drop a running session without consent.

**0. Overview** (landing page in the content pane; not in the rail as a category, it *is* the rail header "Settings")
Contents: a connection readiness card (from Checks), quick settings (max 4: Theme, Auto-connect, Interface size, Audio stream), and "Open {category}" links.

**1. Connection** (`link`)
- *Shared:* Auto-connect on start [default on] · Start with the car (boot / screen-on / Bluetooth / USB triggers; replaces DiAuto Auto-start + DiPlay automatic connection) · Reopen after reconnection · Preferred phones (order) · Wireless method: Car hotspot / Wi-Fi Direct (Auto · 5 GHz / Auto · 2.4 GHz band) [N] · Wait for Wi-Fi before connecting (timeout) · USB: "Make CarLink the default for phones" helper (opens the system dialog or ADB fix) · Bluetooth adapter (only if more than one) · Permissions status → Onboarding permissions step.
- *CarPlay:* Choose iPhone · Car hotspot automation [ADB] · Hotspot join controls.
- *Android Auto:* Native AP transport (Hotspot / Wi-Fi Direct) [N] · Static BSSID [P] · Wi-Fi version exchange [X] · Server mode (Auto / Manual) · Hotspot SSID/password overrides [P].

**2. Display** (`display_settings`)
- *Shared:* Theme: Follow car (default) / Dark / Light · Full screen (hide system bars) · Interface size of CarLink's own UI (90 / 100 / 115 / 130 %) · Driving side (LHD/RHD; moved here from Vehicle because it changes layout for both engines) [N].
- *CarPlay:* Icon and text size (presets with preview) [R] · Resolution [N] · Frame rate (30 / 60) [N] · Dock position.
- *Android Auto:* Resolution (presets; "Recommended" badge from the high-bandwidth check) [N] · Interface density (named presets with preview, plus a "Custom DPI" disclosure) [N] · Frame rate [N] · View mode (Surface / Texture / GLES) [N] · Safe area & insets editor [P] · Stretch to fill · Pixel aspect ratio [P].

**3. Audio** (`volume_up`)
- *Shared:* Media stream / Navigation stream routing (with a "Play test tone" button) · Volume offset · Duck media during guidance.
- *CarPlay:* Music buffer · CarPlay audio over the car's Bluetooth (experimental) [X].
- *Android Auto:* Separate audio streams · AAC audio [N] · Microphone (sample rate, source) · Audio queue (Low / Balanced / High) · Latency multiplier [P].

**4. Navigation & HUD** (`navigation`)
- *Shared:* Send car location to phone (GPS) · BYD dashboard & HUD turn guidance [ADB] (shows "Not available on this head unit" when unsupported) · Show navigation notifications.
- *CarPlay:* Cluster turn card · Cluster map (experimental) [X].
- *Android Auto:* HUD mirroring · Sync media metadata.

**5. Vehicle** (`directions_car`)
- *Shared:* Steering-wheel keys (learn / map: Next, Previous, Voice, Map zoom; replaces both the DiAuto Keymap and DiPlay Wheel keys) · Car button action · Media key routing (Auto / Always / Never) · Rotary/joystick input · Vehicle info & speed source.
- *CarPlay:* Open-settings gesture (2/3/4 fingers).
- *Android Auto:* Fake speed (experimental) [X] · Touch surface measurement.

**6. General** (`tune`)
Language (system default + list, each in its own name) · Units (auto) · Backup: export / import settings [P] · Reset all settings [P] (confirmation) · Show toast messages.

**7. Diagnostics** (`monitor_heart`)
Opens the Diagnostics screen inside the content pane (§6.5). Log level · Start/stop log capture · Create report [P] · Show FPS counter.

**8. Advanced** (`science`) [P for the whole page]
A caution banner ("Change with care. These can break sound, video or the connection on some head units.") followed by:
- *Shared:* Split screen / screen rotation behaviors · Kill on disconnect (with conflict warnings) · Software video decoder (Device / Bundled) · Low-latency video (debug).
- *CarPlay:* Efficient video (HEVC) [X][N] · Dashboard map (experimental) · Side panel · Audio focus / channel mapping · Vehicle data.
- *Android Auto:* Force software decoding · libusb transport [N] · Legacy 5 GHz P2P channels · Force 2.4 GHz P2P · Memory profile · Static audio focus · Video fault injection (debug builds only).

**9. About** (`info`): see §6.6.

**Engine filter.** Categories 1–5 and 8 show a segmented control at the top of the content pane: **All · CarPlay · Android Auto**. The default is *All*, with the engine of the most recent phone's section ordered first. This lets a single-engine household hide the other half; the choice is remembered per head unit.

---

## 5. Visual system (tokens)

### 5.0 Canvas and scaling (read this first)

Head-unit DPI reporting is unreliable. DiLink 1920×1080 units report densities that render CarLink's UI at very different physical sizes, and the old apps worked around this with DPI settings. CarLink **overrides `LocalDensity`** so that the layout is always designed against a fixed canvas:

```
shortSidePx = min(widthPx, heightPx)
baseDensity = shortSidePx / 810f            // 1080px → 1.333; 720px → 0.889
density     = baseDensity * interfaceScale  // user: 0.90, 1.00 (default), 1.15, 1.30
fontScale   = system fontScale (clamped 1.0–1.3)
```

The resulting canvas is 1440×810dp on 16:9 landscape (1920×1080 or 1280×720), 810×1440dp on a rotated portrait screen, and 1920×810dp-ish on 1920×720 ultrawide.

Physical sanity check, 1920×1080 at 1.333: a 12.8″ panel is ≈172 ppi, so 1dp ≈ 0.197mm; a 15.6″ panel is ≈141 ppi, so 1dp ≈ 0.24mm.
- 76dp targets are ≈15–18mm, comfortably above the ~10–15mm fingertip-in-motion range.
- 28sp body text has a cap height of ≈3.9–4.8mm, which at a 700mm eye distance is about 19–24 arcmin. That is at or near the commonly cited ISO 15008 floor (verify), so **driving-critical text is ≥28sp** and settings descriptions (parked reading) may use 24sp.

Apply the override at the root:

```kotlin
val cfg = LocalConfiguration.current; val dm = LocalContext.current.resources.displayMetrics
val base = min(dm.widthPixels, dm.heightPixels) / 810f
CompositionLocalProvider(LocalDensity provides Density(base * uiScale, fontScale = LocalDensity.current.fontScale.coerceIn(1f, 1.3f))) { CarLinkApp() }
```

**Size classes** (computed from the canvas dp):

| Mode | Canvas | Width class | Layout |
|---|---|---|---|
| Landscape full | 1440×810 | Large | Two-column Home; two-pane Settings |
| Ultrawide 1920×720 | ~2160×810 | XL | Same as Large, with max content width 1600dp, centered |
| Portrait (rotating BYD screen) | 810×1440 | Medium | Stacked Home; single-pane Settings |
| DiLink split ⅔ | ~960×810 | Expanded | Two-column Home (narrow phones column); single-pane Settings with list→detail |
| DiLink split ⅓ | ~480×810 | Compact | Single column; hero + 2 phones; Settings single pane |

### 5.1 Color

Both palettes were contrast-checked with the WCAG 2.x relative-luminance formula. Text and icon pairs are ≥4.5:1; UI component boundaries (outline) are ≥3:1 on the surfaces where they are used.

The design builds from near-black, uses one interactive accent, and gives each engine a tint used *only* on its glyph badge. Semantic colors always come with an icon, never color alone.

#### Dark (default; also the "Night" look)

| Token (M3 role) | Hex | Use | Contrast notes |
|---|---|---|---|
| `background` | `#0B0F14` | App background | — |
| `surfaceContainerLowest` | `#080B0F` | Projection letterbox, splash | — |
| `surfaceContainerLow` | `#11161D` | Rail background, list background | — |
| `surfaceContainer` | `#161C24` | Cards (hero, phone rows) | — |
| `surfaceContainerHigh` | `#1C232D` | Side sheets, dialogs, selected rail item (if not using primaryContainer) | — |
| `surfaceContainerHighest` | `#232B36` | Pressed state of cards, text field fill | — |
| `onSurface` (88 % level) | `#E8EDF3` | Titles, body | 16.3 on bg, 12.1 on highest |
| `onSurfaceVariant` (72 %) | `#AEB8C5` | Descriptions, values | 9.6 on bg, 7.1 on highest |
| `onSurfaceTertiary`* (56 %) | `#8A95A3` | Metadata, timestamps, disabled-ish (still legible) | 6.3 on bg, 4.7 on highest |
| `outline` | `#6B7788` | Text field / segmented / focusable boundaries | 4.2 on bg, 3.1 on highest |
| `outlineVariant` | `#2C3540` | Dividers (decorative, no contrast requirement) | — |
| `primary` (Link Cyan) | `#5CCFE6` | Primary button fill, switches on, links, focus ring, active stage | 10.6 on bg |
| `onPrimary` | `#00222A` | Text and icons on primary | 9.1 on primary |
| `primaryContainer` | `#0E3A45` | Selected rail item, selected radio row, active timeline row | — |
| `onPrimaryContainer` | `#B8EEFA` | Text on primaryContainer | 9.7 |
| `engineCarPlay`* | `#6EDC8C` | CarPlay glyph tint and badge only | 11.2 on bg |
| `engineAndroidAuto`* | `#8AB4F8` | Android Auto glyph tint and badge only | 9.1 on bg |
| `success`* | `#6EDC8C` | Done stage check, "Pass" in Checks (always with `check_circle`) | 11.2 |
| `warning`* | `#FFC46B` | "Applies next connection", partial checks (with `warning`) | 12.2 |
| `warningContainer` / `onWarningContainer`* | `#3D2A00` / `#FFE2B3` | Reconnect bar, caution banners | 11.0 |
| `error` | `#FF8F87` | Error icon and title | 8.7 on bg |
| `onError` | `#3B0906` | Text on error fill (destructive confirm button) | 7.8 |
| `errorContainer` / `onErrorContainer` | `#4A1512` / `#FFDAD6` | Error card in Connecting | 11.6 |
| `scrim` | `#000000` @ 60 % | Behind side sheets and dialogs | — |

\* These are custom extension tokens (`CarLinkColors`) provided via a `CompositionLocal` next to `MaterialTheme.colorScheme`.

The fact that success and the CarPlay tint share a hue is intentional and harmless: CarPlay's tint appears only as a glyph next to the word "CarPlay," and success always carries a check icon.

#### Light ("Day"; when the car is in light mode, or forced)

| Token | Hex | Contrast notes |
|---|---|---|
| `background` | `#F2F4F7` | — |
| `surfaceContainerLowest` | `#FFFFFF` | — |
| `surfaceContainerLow` | `#FFFFFF` | cards on bg |
| `surfaceContainer` | `#EBEEF2` | — |
| `surfaceContainerHigh` | `#E3E7EC` | — |
| `surfaceContainerHighest` | `#DAE0E7` | — |
| `onSurface` | `#11161D` | 16.5 on bg, 13.7 on highest |
| `onSurfaceVariant` | `#45505D` | 7.5 on bg, 6.2 on highest |
| `onSurfaceTertiary` | `#55606D` | 5.8 on bg, 4.8 on highest |
| `outline` | `#737E8C` | 3.7 on bg, 3.1 on highest |
| `outlineVariant` | `#C4CBD4` | decorative |
| `primary` | `#006273` | 6.4 on bg |
| `onPrimary` | `#FFFFFF` | 7.0 |
| `primaryContainer` / `onPrimaryContainer` | `#C9EEF6` / `#002A33` | 12.4 |
| `engineCarPlay` | `#176B34` | 6.0 on bg |
| `engineAndroidAuto` | `#1D58B8` | 6.1 on bg |
| `success` | `#176B34` | 6.0 |
| `warning` | `#8A5000` | 5.9 |
| `warningContainer` / `onWarningContainer` | `#FFE2B3` / `#2B1700` | 13.7 |
| `error` / `onError` | `#B3261E` / `#FFFFFF` | 5.9 on bg; 6.5 |
| `errorContainer` / `onErrorContainer` | `#FFDAD6` / `#410002` | 13.3 |

**Rules**
- **Accent discipline:** only interactive elements and the *active* connection stage use `primary`. Never use primary for decorative text, headings or illustrations.
- **No pure white text on dark** (glare at night). `onSurface` is #E8EDF3.
- **Theme switching:** `Follow car` reads `UiModeManager.nightMode` / `Configuration.uiMode`. The switch cross-fades over 300 ms and never happens mid-tap. It is debounced by 2 s so it doesn't flicker in tunnels when the car uses headlight-based auto mode.
- **No dynamic color** (Material You). Head units rarely set a meaningful wallpaper, and engine identity needs to stay stable.
- **Projection waiting screen** (`WaitingScreenColors.kt` in DiPlay) uses `surfaceContainerLowest` so the transition into black CarPlay or AA video is seamless.

### 5.2 Typography

The font is Roboto (system) for Latin, Cyrillic, Greek and Vietnamese. CJK, Arabic and Georgian fall back to Noto through system fallback. **Do not bundle a display face**, because the APK must stay small and CJK fallback must be consistent. Weights: **400 Regular** and **500 Medium** only; no bold. Numbers in timers, stats and the timeline use `fontFeatureSettings = "tnum"`.

Size is in sp and line height in sp, on the 1440×810dp canvas. Everything is on a 4sp step.

| M3 role | Size / line | Weight | Letter spacing | Use |
|---|---|---|---|---|
| `displayLarge` | 64 / 72 | 400 | 0 | Hero status in Connected and Live states ("Connected"), portrait hero |
| `displayMedium` | 56 / 64 | 400 | 0 | Hero status in landscape ("Ready to connect", "Starting CarPlay") |
| `displaySmall` | 48 / 56 | 400 | 0 | Onboarding step title |
| `headlineLarge` | 40 / 48 | 400 | 0 | Settings category title, Diagnostics title |
| `headlineMedium` | 36 / 44 | 400 | 0 | Error title in Connecting, side sheet title |
| `headlineSmall` | 32 / 40 | 500 | 0 | Hero phone name, card titles |
| `titleLarge` | 32 / 40 | 400 | 0 | Section header in settings ("CarPlay", "Android Auto") |
| `titleMedium` | 28 / 36 | 500 | 0 | List-row title, setting title, rail item label |
| `titleSmall` | 24 / 32 | 500 | 0 | Engine section subtitle, badges' text |
| `bodyLarge` | 28 / 40 | 400 | 0 | Hero subtitle, stage labels, error body (**driving-critical text**) |
| `bodyMedium` | 24 / 32 | 400 | 0 | Setting description, phone-row metadata (**minimum for any reading text**) |
| `bodySmall` | 24 / 32 | 400 | 0 | (alias of bodyMedium; never smaller) |
| `labelLarge` | 28 / 32 | 500 | 0 | Primary/secondary button text |
| `labelMedium` | 24 / 28 | 500 | 0 | Chips, segmented buttons, badges |
| `labelSmall` | 20 / 24 | 500 | 0.2 | **Only** for non-essential metadata: version build hash, log timestamps (parked-only screens). Never actionable, never driving-critical. |
| `mono` (custom) | 22 / 32 | 400 | 0 | Diagnostics event log (`FontFamily.Monospace`), parked-only |

Rules:
- **No ALL CAPS and no tracked eyebrows.** Overlines are replaced by `titleSmall` in `onSurfaceVariant`.
- **Max line length** is about 60 characters for body copy; the content pane's max text width is 880dp.
- **Truncation:** never ellipsize a button label or an error title. Wrap to 2 lines and let the component grow. Phone names may ellipsize at the end after 1 line in rows (with the full name in Phone detail). **No marquee.**
- Respect the system font scale up to 1.3× (clamped). Layouts must reflow; test at 1.3.

### 5.3 Spacing and layout grid

The base unit is 4dp; layout uses 8dp multiples.

| Token | dp | Use |
|---|---|---|
| `space0_5` | 4 | Icon-to-badge, tight stacks |
| `space1` | 8 | Inside chips, between title and description |
| `space1_5` | 12 | Icon-to-label in buttons |
| `space2` | 16 | Between list rows (gap), inside compact cards |
| `space3` | 24 | Card padding, gutters, compact screen margin |
| `space4` | 32 | **Screen margin (landscape/portrait)**, section spacing |
| `space5` | 40 | Between hero blocks |
| `space6` | 48 | Settings section header top padding |
| `space8` | 64 | Hero vertical padding (Large) |

Layout:
- **Large (1440×810):** margins 32; 12-column grid; gutter 24. Home: hero spans 7 columns (≈820dp) and phones span 5 (≈560dp). Top bar 96dp tall.
- **Medium portrait (810×1440):** margins 32; 6 columns; gutter 24.
- **Compact (≤600):** margins 24; single column.
- **Settings two-pane:** rail fixed at 400dp (360dp at 1200–1399dp). Content max width 960dp, start-aligned with 48dp padding.
- **Spacing between adjacent touch targets** is ≥16dp. Road vibration causes mis-taps; this is stricter than the AAOS "no overlap" rule, on purpose.

### 5.4 Shape (corner radii)

| Token | dp | Use |
|---|---|---|
| `shape.extraSmall` | 8 | Badges, focus ring corner offset |
| `shape.small` | 12 | Text fields, segmented button group |
| `shape.medium` | 20 | List rows, setting rows (when carded), chips (pill alternative) |
| `shape.large` | 28 | Cards (hero, phones card), side sheets (leading corners), dialogs |
| `shape.extraLarge` | 36 | Onboarding illustration panel |
| `shape.full` | 50 % | Status chips, avatar, switch, FAB-like circular buttons |
| Buttons | 24 | Primary 96dp and secondary 76dp buttons. Not full pills: a 96dp pill wastes width and reads as a toy at car scale. This is a slight evolution of the current 20dp. |

### 5.5 Elevation

The system is **tonal only. There are no drop shadows** (`shadowElevation = 0`). Shadows are invisible on near-black, and they cost GPU time on DiLink.

| Level | Surface token | Use |
|---|---|---|
| 0 | `background` | Screen |
| 1 | `surfaceContainerLow` | Rail, list background |
| 2 | `surfaceContainer` | Cards |
| 3 | `surfaceContainerHigh` | Side sheets, dialogs, menus (+ scrim) |
| 4 | `surfaceContainerHighest` | Pressed / dragged |

Borders: cards have **no border** in dark mode (the tonal step is enough). In light mode, cards on `background` use a 1dp `outlineVariant` border. Focusable elements get a focus ring (§7.2).

### 5.6 Touch targets and component sizes

| Component | Size | Rationale |
|---|---|---|
| **Minimum touch target (anything)** | **76×76dp** | Android for Cars minimum, based on driver distance and quick taps. On the 1.333 canvas this is ≈15–18mm physical. |
| Primary button | h **96dp**, min w 320dp, label 28sp, icon 36dp | One per screen. Bigger than the floor because it's *the* action, and Fitts's law at arm's length. |
| Secondary / tonal / outlined button | h 76dp, min w 200dp | — |
| Icon button (top bar) | 76×76 target, 36dp icon | Settings, Home, Back. |
| Phone row | h **112dp** (avatar 56, two lines) | Two-line 28/24sp text needs height. |
| Setting row (1 line) | h ≥ **88dp** | — |
| Setting row (2–3 lines) | h ≥ **112dp**; grows with content | Whole row is the target. |
| Rail item | h 88dp, icon 36dp, label 28sp | — |
| Switch | visual 64×40dp (M3 Switch scaled 1.25×), inside a row target | — |
| Radio / checkbox | visual 36dp, row target ≥88dp | — |
| Slider | track 12dp, thumb 36dp, target height 76dp; **plus** −/+ icon buttons (76dp) on each side | Dragging is imprecise in motion; steppers are the in-motion path. |
| Segmented button | h 76dp, segment min w 160dp | — |
| Status chip (non-interactive) | h 48dp, icon 24dp, 24sp label | Not a target, so below 76 is OK. It *must not look tappable* (no outline, no chevron). |
| Avatar (phone glyph) | 76dp in hero, 56dp in rows | AAOS avatar sizes. |

### 5.7 Motion

Motion explains state; it is never decoration. Use M3 easing curves.

| Token | Duration | Easing | Use |
|---|---|---|---|
| `motion.instant` | 0 ms | — | When the system animator scale is 0 (reduce motion) |
| `motion.press` | 90 ms | standard `CubicBezier(0.2, 0, 0, 1)` | Press-state color change, ripple |
| `motion.short` | 150 ms | standard | Switch toggle, chip state, badge appear |
| `motion.medium` | 250 ms | emphasizedDecelerate `(0.05, 0.7, 0.1, 1)` in / emphasizedAccelerate `(0.3, 0, 0.8, 0.15)` out | Side sheet, stage advance, hero state change (cross-fade + 16dp vertical slide) |
| `motion.long` | 350 ms | emphasizedDecelerate | Screen transitions (Home ↔ Connecting ↔ Settings): shared-axis X, 48dp travel |
| `motion.themeFade` | 300 ms | linear | Day/night cross-fade |

Rules:
- **Max 350 ms** for anything. No spring overshoot and no bounces.
- **One looping animation is allowed:** the active stage's indeterminate ring (M3 `CircularProgressIndicator`, 36dp, 4dp stroke). It stops on Live and Error.
- No parallax, no animated gradients, no blur (`RenderEffect` is API 31+ and too slow on DiLink).
- Respect `Settings.Global.ANIMATOR_DURATION_SCALE == 0`: all durations become 0 and the progress ring becomes a static icon.
- Moving to projection: hero → `surfaceContainerLowest` fade (250 ms), then the engine surface. No zoom.

### 5.8 Iconography

Use **Material Symbols Rounded**, weight 400, fill 0 (fill 1 for selected and active states), grade 0 in light and **−25 in dark** to reduce halation, optical size 48. Sizes: **44dp** primary (hero, onboarding), **36dp** secondary (buttons, rail, rows), **24dp** tertiary (chips, badges). Ship as vector drawables (only the used glyphs), not the font, to save APK size. Mirror only directional glyphs in RTL (`autoMirrored = true` for arrows and chevrons).

| Concept | Symbol |
|---|---|
| iPhone / Android phone / unknown phone | `phone_iphone` / `phone_android` / `smartphone` |
| CarPlay / Android Auto engine glyph | Not in Material Symbols. Use a **neutral CarLink glyph** inside an engine-tinted badge plus the **word** "CarPlay" / "Android Auto". Do not use Apple's or Google's logos unless legal clears it (trademark), which is also why identity is by word, not logo. Fallback symbol: `directions_car` in the engine tint. |
| USB / wireless / Bluetooth / hotspot / Wi-Fi Direct | `usb` / `wifi` / `bluetooth` / `wifi_tethering` / `p2p` (or `devices`) |
| Bluetooth off / Wi-Fi off / USB off | `bluetooth_disabled` / `wifi_off` / `usb_off` |
| Connect / connected / disconnect | `link` / `check_circle` / `link_off` |
| Settings / home / back / close | `settings` / `home` / `arrow_back` / `close` |
| Categories: Connection, Display, Audio, Navigation & HUD, Vehicle, General, Diagnostics, Advanced, About | `link`, `display_settings`, `volume_up`, `navigation`, `directions_car`, `tune`, `monitor_heart`, `science`, `info` |
| Stage pending / active / done / failed | `radio_button_unchecked` / (progress ring) / `check_circle` (fill 1) / `error` (fill 1) |
| Warning / reconnect needed / locked (parked) | `warning` / `sync` / `lock` |
| Retry / test tone / export / share via QR | `refresh` / `graphic_eq` / `ios_share` / `qr_code_2` |
| Language / theme follow / dark / light | `translate` / `brightness_auto` / `dark_mode` / `light_mode` |
| Microphone / location / steering keys | `mic` / `location_on` / `sports_motorsports` (or `keyboard` as fallback; pick one and use it consistently) |
| Add phone / forget phone | `add` / `delete` |

---

## 6. Screen-by-screen specs

The wireframes use the dp canvas (§5.0). For 1920×1080 px, multiply by 1.333. `[ ]` marks a button, `( )` a chip, `▸` a chevron, `◉ ○` radio states, `═` the primary fill.

### 6.1 Home

**Purpose:** answer "connected? / what do I tap?" in one glance, and switch phones in one tap.

#### 6.1.1 Landscape 1440×810dp (Large)

```
 0                                                                                    1440
 ┌──────────────────────────────────────────────────────────────────────────────────────┐
 │ ◆ CarLink        (bluetooth On) (wifi_tethering Hotspot) (usb Ready)      [⚙]   [⌂]  │ 96  top bar
 ├──────────────────────────────────────────────────────┬───────────────────────────────┤
 │ ┌──────────────────────────────────────────────────┐ │ Phones                         │
 │ │  ┌────┐                                           │ │ ┌───────────────────────────┐ │
 │ │  │ 📱 │  Sara's iPhone                    (USB)   │ │ │ (●)📱 Sara's iPhone        │ │ 112
 │ │  └────┘  iPhone · CarPlay                         │ │ │      CarPlay · In range ✓  │ │
 │ │   76dp                                            │ │ ├───────────────────────────┤ │
 │ │                                                   │ │ │  🤖 Omar's Pixel 8        │ │ 112
 │ │  Ready to connect                    displayMedium│ │ │      Android Auto · 2d ago│ │
 │ │  Plug in or tap Connect. Wireless works too.      │ │ ├───────────────────────────┤ │
 │ │                                        bodyLarge  │ │ │  [+] Add a phone           │ │ 88
 │ │  ┌═════════════════════════════════════════════┐  │ │ └───────────────────────────┘ │
 │ │  ║  🔗  Connect Sara's iPhone                   ║  │ │                               │
 │ │  └═════════════════════════════════════════════┘  │ │  Tap a phone to connect it.   │
 │ │                      96dp primary                 │ │  Hold for options.  bodyMedium│
 │ └──────────────────────────────────────────────────┘ │                               │
 │                     hero card: 7 cols, padding 40     │  5 cols                       │
 └──────────────────────────────────────────────────────┴───────────────────────────────┘
   margins 32 · gutter 24 · hero card radius 28 · surfaceContainer
```

- **Top bar (96dp):** brand mark (36dp) + "CarLink" (`headlineSmall`). Status chips (non-interactive, `labelMedium`) show only *relevant* radios: Bluetooth, Wi-Fi mode, and USB when a cable is present. A chip turns `warning` plus a `bluetooth_disabled` icon when that blocks the current phone's preferred path. Settings and Home are 76dp icon buttons with content descriptions. The Home button exits to the car launcher.
- **Hero card:** this is the state machine's face. The table below defines its content per state. The phone avatar is 76dp with the phone-type glyph; the engine badge is a 48dp chip with an engine-tinted glyph and the engine name.
- **Phones panel:** `LazyColumn`, max 4 visible rows, scrollable (no auto-scroll). The row tap **connects that phone** (when idle) or **switches to it** (when another is live, after the confirm in §6.1.4). A long-press, or the trailing `more_vert` 76dp target, opens Phone detail. Row order: live > in range > others. The presence dot is 16dp, `success`, and paired with the text "In range" (never color alone).
- **Empty phones state:** "No phones yet", a "Plug in a phone or pair one in the car's Bluetooth settings." body, and a `[Open Bluetooth settings]` tonal button.

**Hero states:**

| State | Title (display) | Subtitle (bodyLarge) | Primary (96dp) | Secondary (76dp, text button) |
|---|---|---|---|---|
| Idle, default phone present | Ready to connect | Plug in or tap Connect. Wireless works too. | 🔗 Connect {phone} | — |
| Idle, no phone present | Waiting for your phone | Turn on its Bluetooth, or plug it in. | 🔄 Look again | Use a cable |
| WaitingForPhone (auto-connect armed) | Looking for {phone}… | It connects by itself when it's nearby. | ✕ Stop looking | — |
| Connecting | → navigates to **Connecting** screen (§6.2); Home never shows a spinner-only hero. | | | |
| Live (user returned to CarLink) | Connected | {Engine} is running · {transport} · {mm:ss} | ▶ Return to {Engine} | ⏏ Disconnect |
| Error (from auto-connect while on Home) | Error title (§4.2) | Error body | Error primary | Error secondary |
| Bluetooth off (blocks wireless) | Bluetooth is off | Turn it on for wireless, or plug in a cable. | Turn on Bluetooth | Use a cable |

#### 6.1.2 Portrait 810×1440dp (Medium)

The hero is in the upper half (Apple guidance), and the primary button sits at the hero's bottom, around y≈620dp, which on a 15.6″ rotated screen is mid-height and within reach.

```
 ┌──────────────────────────────────────────┐
 │ ◆ CarLink                     [⚙]  [⌂]   │ 96
 │ (bluetooth On) (wifi_tethering) (usb)     │ 64  chips row (wraps)
 ├──────────────────────────────────────────┤
 │ ┌──────────────────────────────────────┐ │
 │ │            ┌──────┐                  │ │
 │ │            │  📱  │ 96dp avatar      │ │
 │ │            └──────┘                  │ │
 │ │         Sara's iPhone                │ │
 │ │       (CarPlay)  (USB)               │ │
 │ │                                      │ │
 │ │        Ready to connect   displayLarge│ │
 │ │  Plug in or tap Connect. Wireless    │ │
 │ │  works too.                          │ │
 │ │ ┌══════════════════════════════════┐ │ │
 │ │ ║ 🔗  Connect Sara's iPhone         ║ │ │ 96
 │ │ └══════════════════════════════════┘ │ │
 │ └──────────────────────────────────────┘ │  ~ y 120–700
 │                                          │
 │ Phones                         titleLarge│
 │ ┌──────────────────────────────────────┐ │
 │ │ (●)📱 Sara's iPhone · CarPlay  ✓ near │ │ 112
 │ │   🤖 Omar's Pixel 8 · Android Auto    │ │ 112
 │ │  [+] Add a phone                      │ │ 88
 │ └──────────────────────────────────────┘ │
 └──────────────────────────────────────────┘
```

#### 6.1.3 Compact (DiLink ⅓ split, ≈480×810dp)

```
 ┌──────────────────────────┐
 │ ◆ CarLink     [⚙]  [⌂]   │ 96
 ├──────────────────────────┤
 │ 📱 Sara's iPhone          │ 76 avatar + name (1 line)
 │ (CarPlay)                │
 │ Ready to connect          │ headlineLarge (40)
 │ ┌══════════════════════┐ │
 │ ║ 🔗 Connect            ║ │ 96 (label shortens to "Connect")
 │ └══════════════════════┘ │
 │ Other phones              │
 │  🤖 Omar's Pixel 8      ▸ │ 112
 │  All phones (3)         ▸ │ 88
 └──────────────────────────┘
```

Chips are hidden in Compact. Their blocking states surface as the hero subtitle instead.

#### 6.1.4 Interactions

- **Auto-connect on launch:** if enabled and the default phone is present, go straight to Connecting. There is no countdown; Cancel is available there.
- **Switching while live:** tapping another phone shows a 2-button dialog: "Switch to {phone}? {current engine} will stop." with [Switch] (primary) and [Cancel]. This is the only confirmation on Home.
- **USB plug-in** always wins: it pre-empts a pending wireless attempt for a *different* phone, and shows a snackbar "Switched to {phone} (USB)" with an [Undo] action for 6 s.
- **Phone detail** is a side sheet: from the end edge in landscape (560dp wide), from the bottom in portrait (full width, max 70 % height).

### 6.2 Connecting (stage timeline)

**Purpose:** make a 3–25 s wait legible and recoverable.

#### 6.2.1 Landscape

```
 ┌──────────────────────────────────────────────────────────────────────────────────────┐
 │ [←]  Connecting                                                       00:07  (tnum)  │ 96
 ├─────────────────────────────────────────┬────────────────────────────────────────────┤
 │                                         │  ✓  Phone found                        0.4s│ 88
 │            ┌──────────┐                 │  │                                          │
 │            │   📱     │  96dp avatar    │  ✓  Identified as iPhone → CarPlay      0.1s│ 88
 │            └──────────┘                 │  │                                          │
 │          Sara's iPhone   headlineSmall  │ ┌────────────────────────────────────────┐ │
 │        (CarPlay) (usb USB)              │ │◌  USB access                            │ │ 112
 │                                         │ │   Tap Allow on the car screen if asked. │ │ primaryContainer
 │       Starting CarPlay   displayMedium  │ └────────────────────────────────────────┘ │
 │       Usually takes about 20 seconds.   │  ○  Secure handshake                        │ 88
 │                          bodyLarge      │  │                                          │
 │                                         │  ○  Starting picture                        │ 88
 │                                         │                                            │
 │  ┌──────────────────┐ ┌──────────────┐  │                                            │
 │  │ ✕  Cancel        │ │ wifi Try wireless│ │                                         │
 │  └──────────────────┘ └──────────────┘  │                                            │
 │        76dp tonal         76dp text     │                                            │
 └─────────────────────────────────────────┴────────────────────────────────────────────┘
        6 cols                                    6 cols; timeline rows 88dp; connector 4dp
```

- **Timeline:** vertical, with five rows and a 4dp connector line (`outlineVariant`; `success` once done).
- The **active row** expands to 112dp on a `primaryContainer` background. It has the progress ring, the stage label (`bodyLarge` 28sp, Medium) and one hint line (`bodyMedium`, 24sp) only when the driver can act ("Tap Allow on the car screen if asked.").
- Done rows show `check_circle` (`success`) and their duration in `onSurfaceTertiary` tnum. Pending rows show `radio_button_unchecked` at 56 % emphasis.
- **Expected-duration copy** comes from the median of this head unit's last 5 successful connections per transport and engine. The fallback is "about 20 seconds" for USB CarPlay and "about 10 seconds" for the others. The elapsed timer is in the top bar.
- **Slow stage:** when the stage exceeds 2× its typical time (§4.2), show a calm hint under the active row ("Still working… this phone is slower than usual.") with no new buttons. The secondary action becomes available at that point if it was hidden.
- **USB permission:** stage 3 shows its hint *before* the Android system dialog appears. After a denial, the stage becomes the E-USB-DENIED error.
- **Cancel** is always visible and always one tap. It returns to Home in Idle with a snackbar ("Stopped. Auto-connect is paused until you tap Connect."). Pausing auto-connect after a manual cancel prevents a fight loop.
- **Back** acts as Cancel.
- **Accessibility:** the stage label is a `liveRegion = Polite`. Each completed stage announces "{stage} done." Errors are `Assertive`.

#### 6.2.2 Error state (same screen, no modal)

```
 │                                         │  ✓  Phone found                             │
 │       (avatar, phone name, chips)       │  ✓  Identified as iPhone → CarPlay          │
 │                                         │ ┌────────────────────────────────────────┐ │
 │  ⚠ USB access was declined  headlineMed │ │⛔ USB access                            │ │ errorContainer
 │  CarLink needs USB access to talk to    │ │   Declined                              │ │
 │  your phone.                 bodyLarge  │ └────────────────────────────────────────┘ │
 │ ┌════════════════════════┐ ┌──────────┐ │  ○  Secure handshake                        │
 │ ║ 🔄  Ask again           ║ │ Help ▸   │ │  ○  Starting picture                        │
 │ └════════════════════════┘ └──────────┘ │                                            │
 │                    [✕ Back to home]     │                                            │
```

- Focus moves to the primary recovery action, so the rotary or joystick lands on it immediately.
- "Help ▸" (parked) opens Diagnostics, pre-filtered to this attempt, with a contextual explanation card.
- E-DROPPED auto-retries and shows "Reconnecting (2 of 3)…" with the timeline reset to stage 3 or 4.

#### 6.2.3 Portrait / Compact

Portrait stacks the hero (avatar, title, subtitle) on top, then the timeline (full width), then the buttons at the bottom, sticky with 32dp margins. Compact shows only the active stage plus "Step 3 of 5" (`titleMedium`) and a 5-segment horizontal progress bar (each segment 8dp tall, 8dp gaps). Tapping the bar expands the full timeline in a bottom sheet.

### 6.3 Settings (two-pane rail)

#### 6.3.1 Landscape (≥1400dp → two panes)

```
 ┌──────────────────────────────────────────────────────────────────────────────────────┐
 │ [←]  Settings                                                         ( ✓ CarPlay live)│ 96
 ├──────────────────────────────┬───────────────────────────────────────────────────────┤
 │ [🔍 Search settings     🔒]  │  Display                                 headlineLarge │
 │ ──────────────────────────── │  How CarPlay and Android Auto look.       bodyMedium   │
 │ ⊙ Overview                   │  ┌───────────┬──────────────┬──────────────────┐       │
 │ 🔗 Connection                │  │   All  ✓  │   CarPlay    │   Android Auto   │ 76    │
 │█🖥 Display███████████████████│  └───────────┴──────────────┴──────────────────┘       │
 │ 🔊 Audio                     │                                                       │
 │ ➤ Navigation & HUD           │  For both                                titleLarge    │
 │ 🚗 Vehicle                   │  ┌─────────────────────────────────────────────────┐  │
 │ ⚙ General                    │  │ Theme                                          ▸ │  │ 112
 │ ♥ Diagnostics                │  │ Follow car                                       │  │
 │ ⚗ Advanced              🔒   │  ├─────────────────────────────────────────────────┤  │
 │ ⓘ About                      │  │ Full screen                              [■■◯]  │  │ 112
 │                              │  │ Hide the car's bars while projecting.            │  │
 │  rail 400dp, rows 88dp       │  └─────────────────────────────────────────────────┘  │
 │  selected: primaryContainer  │                                                       │
 │                              │  ● CarPlay                               titleLarge    │
 │                              │  ┌─────────────────────────────────────────────────┐  │
 │                              │  │ Icon and text size      (sync Reconnects)      ▸ │  │
 │                              │  │ Default                                          │  │
 │                              │  ├─────────────────────────────────────────────────┤  │
 │                              │  │ Frame rate              (Next connection)      ▸ │  │
 │                              │  │ 30 fps · lighter load                            │  │
 │                              │  └─────────────────────────────────────────────────┘  │
 ├──────────────────────────────┴───────────────────────────────────────────────────────┤
 │ ⟳ Saved. Some changes apply when CarPlay reconnects.            [ Reconnect now ]     │ 96 warningContainer (only when pending)
 └──────────────────────────────────────────────────────────────────────────────────────┘
```

- **Rail:** `surfaceContainerLow`. The selected item uses a `primaryContainer` pill (radius 20, inset 12dp) with a filled icon. The selected rail item is saved in `rememberSaveable`. Search (top of the rail) is parked-only: tapping it while moving shows a toast, and the field shows a lock.
- **Content pane:** a category header, an optional engine filter, then sections. **Shared ("For both") comes first**, then engine sections, with the engine of the last-connected phone first. An engine section header is a 24dp engine-tinted dot or glyph, the engine name (`titleLarge`), and a subtitle "Applies to iPhone" / "Applies to Android phones" (`titleSmall`, onSurfaceVariant).
- **Rows** are grouped into cards (radius 20, `surfaceContainer`) with dividers (`outlineVariant`, inset 24dp).
- **Choice rows** open a **side sheet**, not a dialog. The sheet comes from the end edge, is 560dp wide, holds radio rows of 88dp, applies immediately on selection, and closes after 250 ms. Preset choices with visual impact (CarPlay icon size, AA density) show a **preview thumbnail** (a static illustration per preset, 320×180dp) in the sheet.
- **Reconnect bar:** sticky at the bottom of the content pane, `warningContainer`, 96dp tall, with a primary-styled [Reconnect now] (76dp). This keeps DiPlay's consent rule: Settings never reconnects without this tap, except for rows badged "Reconnects", whose sheet's confirm button reads "Apply and reconnect."
- **Live session indicator** in the top bar (a chip) tells the user that changes may need a reconnect.

#### 6.3.2 Setting-row anatomy

```
 ┌─────────────────────────────────────────────────────────────────────┐
 │ [icon 36]  Title (titleMedium 28)                (badge)   [control] │  ≥ 88dp (1 line) / ≥ 112dp (2–3)
 │            Value or description (bodyMedium 24, onSurfaceVariant)    │  padding 24 h, 20 v
 └─────────────────────────────────────────────────────────────────────┘
```

- The leading icon is optional; use it only in Overview and in category links, not on every row (avoid noise).
- The value goes on line 2 for choice rows (`"Title · Value"` in the old DiPlay `button()` maps to *title on line 1, value on line 2*). The search index keeps title + value + keywords.
- The description is at most 2 lines visible. More text goes behind an `info` icon (76dp target) that opens a sheet, parked-only.
- **Badges** (`labelMedium` 24sp, 40dp tall, radius 8, `surfaceContainerHighest` with an icon):
  - "Reconnects" (`sync`)
  - "Next connection" (`schedule`, `warning` icon tint)
  - "(experimental)" in the title text per locale form, *not* as a badge (DiPlay rule)
  - "Needs ADB" (`terminal`)
  - "Parked only" (`lock`)
- **Disabled vs unavailable:** a disabled row (dependency off) shows the reason in its description ("Turn on Car hotspot first."). A row unavailable on this head unit stays visible with "Not available on this head unit" only if users would look for it (for example BYD HUD); otherwise it is hidden. This keeps the existing `settings_byd_navigation_unavailable` behavior.

#### 6.3.3 Portrait / Compact (single pane)

Level 0 is the category list (88dp rows with icon, title and a one-line summary of current key values, for example "Display — Follow car · 60 fps"). Level 1 is the category page with an `[←]` back. The engine filter becomes a 76dp segmented control under the title, scrolled with the content. The reconnect bar is sticky at the bottom of the screen.

#### 6.3.4 Overview page

```
 Overview
 ┌──────────────────────────────────────────────┐
 │ ✓ Ready · Sara's iPhone connects by USB or   │   readiness card (from Checks)
 │   wireless.                     [ Details ▸ ] │   warning variant lists the first failing check + fix button
 └──────────────────────────────────────────────┘
 Quick settings
 [ Theme: Follow car ▸ ] [ Auto-connect  ■■◯ ] [ Interface size: 100 % ▸ ] [ Audio: Media ▸ ]   (2×2 grid of 112dp tiles)
 Categories
 Connection ▸   Display ▸   Audio ▸   Navigation & HUD ▸   Vehicle ▸   (rows; landscape hides these because the rail shows them)
```

### 6.4 Onboarding / permissions wizard

Onboarding runs on first launch and from Settings > Connection > "Run setup again." It is **parked-only**; if the car moves, a "Continue when parked" overlay appears and progress is kept. It has five steps; DiPlay's `SetupGuide` STEP_CAR / CONNECTION / IPHONE / FEATURES / DONE are generalized as follows.

1. **Welcome & safety:** "Set up CarLink while parked. It takes about 3 minutes." [Start] (primary). The safety disclaimer is inline (no extra dialog), and the start button acknowledges it.
2. **Your car:** detected DiLink generation, Android version and screen. Checks are auto-run with ✓/⚠. "Looks good" or specific fixes. This is where the user picks "Start with the car" (default on) and gets the USB default-handler fix.
3. **Permissions:** a checklist with one row per permission, each with its own **[Allow]** button. Required items come first; optional ones are labeled "Optional · {benefit}".

   | Permission | Required? | Why (row description, ≤60 chars) |
   |---|---|---|
   | Nearby devices / Bluetooth | Required | Find and connect to your phone wirelessly. |
   | Location (precise) | Required for wireless (Wi-Fi Direct/hotspot) | Needed by Android to set up the car's Wi-Fi link. |
   | Microphone | Required | Siri and Google Assistant hear you. |
   | Notifications (Android 13+) | Recommended | Shows connection status in the car's shade. |
   | Display over other apps | Optional | Lets CarLink return you to CarPlay or Android Auto quickly. |
   | Battery optimization exemption / autostart | Recommended | Keeps CarLink ready when the car wakes. |
   | ADB access (BYD) | Optional | Dashboard & HUD turn guidance, car hotspot automation. |

   Each row: avatar icon 56, title, description, and a trailing [Allow] (76dp tonal), which becomes `check_circle` "Allowed" once granted. If a permission was denied twice, the button becomes [Open settings]. **[Continue]** is enabled once the required items are granted. Users can skip optional items.
4. **Connect your phone:** two large choice cards (each about 600×320dp): **iPhone** and **Android phone**. This is the only place CarLink asks the phone type, and only to show the right phone-side instructions; detection still happens on connect. Each card leads to 3 numbered steps with an illustration:
   - iPhone: (1) Pair the iPhone in the car's Bluetooth settings, or plug it in. (2) On iPhone: Settings › General › CarPlay › {car} › Allow CarPlay While Locked. (3) Tap "Connect now."
   - Android: (1) Pair in the car's Bluetooth settings, or plug in. (2) On the phone, Android Auto settings › Start Android Auto automatically / Wireless Android Auto. (3) Tap "Connect now."

   [Connect now] (primary) runs a *real* connection inline using a compact timeline. Success goes to step 5; failure shows the error with recovery actions and "Skip for now."
5. **Done:** "You're set. CarLink connects {phone} automatically." [Go to Home].

**Landscape layout:** a 360dp step list on the left (numbered, ✓ for done, current step in `primaryContainer`; tappable only for completed steps). The content on the right has `displaySmall` titles and the primary button bottom-end, sticky. **Portrait:** a top 5-segment progress bar with "Step 2 of 5", content, then a sticky bottom button bar holding Back (text) and Continue (primary).

```
 ┌──────────────────────────────────────────────────────────────────────────────────────┐
 │ ◆ Set up CarLink                                                          [Skip setup]│ 96
 ├────────────────────────┬─────────────────────────────────────────────────────────────┤
 │ ✓ 1 Welcome            │  Allow access                                 displaySmall   │
 │ ✓ 2 Your car           │  CarLink needs these to connect your phone.   bodyLarge      │
 │█ 3 Permissions ████████│  ┌───────────────────────────────────────────────────────┐  │
 │   4 Connect phone      │  │ 🔵 Nearby devices                         [ Allow ]   │  │ 112
 │   5 Done               │  │    Find and connect to your phone wirelessly.          │  │
 │                        │  │ 📍 Location                           ✓ Allowed       │  │ 112
 │                        │  │ 🎤 Microphone                             [ Allow ]   │  │ 112
 │                        │  │ 🔔 Notifications · Recommended            [ Allow ]   │  │ 112
 │                        │  │ 🔋 Keep CarLink ready · Recommended       [ Allow ]   │  │ 112
 │                        │  └───────────────────────────────────────────────────────┘  │
 │                        │                                    ┌══════════════════════┐ │
 │                        │                                    ║  Continue  →          ║ │ 96
 │                        │                                    └══════════════════════┘ │
 └────────────────────────┴─────────────────────────────────────────────────────────────┘
```

### 6.5 Diagnostics

**Purpose:** a single place for "why isn't it working", replacing AutoKit-style scattered tests. It is reachable from Settings > Diagnostics and from error screens ("Help ▸"). The live panel and Checks are readable while moving; logs and export are parked-only.

```
 ┌──────────────────────────────────────────────────────────────────────────────────────┐
 │ [←]  Diagnostics                                         [ ⬆ Create report ] 🔒      │ 96
 ├──────────────────────────────────────────────┬───────────────────────────────────────┤
 │ Live session                      titleLarge │ Checks                     titleLarge  │
 │ ┌──────────────────────────────────────────┐ │ ┌───────────────────────────────────┐ │
 │ │ CarPlay · Sara's iPhone · USB · 12:04    │ │ │ ✓ Bluetooth on                     │ │ 88
 │ │ ┌────────┬────────┬────────┬────────┐   │ │ │ ✓ USB host mode                     │ │ 88
 │ │ │1920×1080│ 58 fps │ H.264  │ 42 ms  │   │ │ │ ⚠ Battery optimization on [ Fix ]  │ │ 88
 │ │ │ Video   │ Frame  │ Codec  │ Latency│   │ │ │ ✓ Microphone permission             │ │ 88
 │ │ └────────┴────────┴────────┴────────┘   │ │ │ ✓ Hardware H.264 decoder            │ │ 88
 │ │ ┌────────┬────────┬────────┬────────┐   │ │ │ ✗ Default USB handler     [ Fix ]  │ │ 88
 │ │ │ 0      │ 5 GHz  │ −52 dBm│ 0      │   │ │ │ ✓ Car hotspot available             │ │ 88
 │ │ │Audio   │ Wi-Fi  │ Signal │ Drops  │   │ │ └───────────────────────────────────┘ │
 │ │ │underruns│ band   │        │        │   │ │                                       │
 │ │ └────────┴────────┴────────┴────────┘   │ │ Recent events            🔒 parked    │
 │ └──────────────────────────────────────────┘ │ 12:03:58 USB attached 05ac:12a8       │
 │ Stat tiles: value headlineSmall tnum,        │ 12:03:59 iAP2 identified             │
 │ label bodyMedium; ⚠ tint when out of range   │ 12:04:16 First frame (17.9 s)        │
 │                                              │ [ Show full log ▸ ]                   │
 └──────────────────────────────────────────────┴───────────────────────────────────────┘
```

- **Stat tiles:** 8 maximum. Values use `headlineSmall` tnum; labels use `bodyMedium`. A tile turns `warning` (icon plus tint) when it crosses a threshold (fps <45, latency >120 ms, any underruns or drops in the last minute). The update rate is 1 Hz, with no animated counters.
- **Checks** use the same data as the Overview readiness card. Each failing check has exactly one [Fix] (76dp tonal) that deep-links to the setting or the system screen.
- **Events:** the last 50 events, monospace 22sp, newest at the top (no auto-scroll while the user reads). "Show full log" opens a full-pane log with level filter chips (Info / Warn / Error).
- **Create report** builds a zip of logs, settings and device info (no personal data; phone names are hashed). Output options:
  - **QR code** linking to a local Wi-Fi share page, or showing a short support code. The phone scans it, so the driver never types in the car.
  - **Save to USB storage.**
  - **Share** (system sheet).
- **Log capture toggle** and **log level** live here as rows below the panes in landscape, or as a section in portrait.

**Portrait:** sections stack (Live session, Checks, Events). **Compact:** Checks first, because readiness matters most.

### 6.6 About

```
 ┌──────────────────────────────────────────────────────────────────────────────────────┐
 │ [←]  About                                                                           │
 ├───────────────────────────────────┬──────────────────────────────────────────────────┤
 │        ◆  (96dp mark)             │ Version 1.0.0 (1234) · Up to date        [Check]  │ 112
 │        CarLink      displaySmall  │ CarPlay engine · DiPlay core 0.4.2               │ 88
 │  CarPlay and Android Auto for     │ Android Auto engine · DiAuto core 2.1.0          │ 88
 │  your BYD.             bodyLarge  │ Head unit · DiLink 4.0 · Android 10 · 1920×1080  │ 88
 │                                   │ Open-source licenses (AGPL-3.0 and others)     ▸ │ 88 🔒
 │   ┌─────────┐                     │ Credits: DiPlay, DiAuto, OpenHeadunit …        ▸ │ 88
 │   │ QR code │  Help & updates     │ Privacy                                        ▸ │ 88
 │   │ 240dp   │  Scan with phone    │ Report a problem → Diagnostics                 ▸ │ 88
 │   └─────────┘                     │                                                  │
 └───────────────────────────────────┴──────────────────────────────────────────────────┘
```

- **Update flow:** this reuses DiPlay's `UpdateStage` (IDLE → CHECKING → AVAILABLE → DOWNLOADING → VERIFYING → READY → FAILED) as a single row. The row's trailing control changes per stage: [Check] → progress ring → [Download 12 MB] → linear progress → [Install] (parked-only) → error with [Try again].
- **Links are QR codes**, never typed URLs. Long-form text (licenses) is parked-only.
- Version and build go here, not on Home.

### 6.7 Shared components (build once)

`CarScaffold(topBar, content, bottomBar?)`, `CarTopBar(title, navIcon, actions, statusChips)`, `StatusChip`, `PrimaryButton96`, `SecondaryButton76`, `IconButton76`, `PhoneAvatar(size, type)`, `EngineBadge(engine)`, `PhoneRow`, `StageTimeline(stages, active, compact)`, `ErrorPanel(error)`, `SettingRow.Toggle/Choice/Slider/Action/Info`, `SettingsSectionHeader(engine?)`, `EngineFilter`, `ReconnectBar`, `ChoiceSideSheet`, `ParkedGate { }` (a wrapper that renders the locked state), `StatTile`, `CheckRow`, `QrPanel`.

---

## 7. Accessibility and localization

### 7.1 Visual accessibility
- WCAG 2.2 AA contrast: ≥4.5:1 for text and icons, ≥3:1 for component boundaries. All tokens in §5.1 meet it on the surfaces they're allowed on. Run a Paparazzi/Roborazzi screenshot test that asserts contrast for every text node in both themes.
- **Never rely on color alone.** Stage states have distinct icons; presence has the text "In range"; errors have an icon plus a title.
- Font scale is supported to 1.3×. Interface size (90–130 %) is separate from font scale and covers drivers who need everything bigger.
- Flicker and flash: no flashing elements anywhere.

### 7.2 Input: touch, rotary, joystick, D-pad, wheel keys
BYD units have a center joystick on some models, steering-wheel keys and occasionally a rotary.
- Every interactive element is **focusable**, with a logical focus order (top bar → hero primary → phones → secondary).
- **Focus ring:** 4dp stroke, `primary`, 4dp outset, radius = component radius + 4. Always visible on D-pad/rotary focus; hidden for touch.
- **Initial focus:** Home focuses the hero primary; Connecting focuses Cancel; an error focuses the primary recovery.
- Rotary scrolling in lists moves by row, not by pixels. In settings the focused row scrolls into view with 96dp padding.
- **TalkBack:** content descriptions on all icon buttons, chips and avatars ("iPhone, Sara's iPhone, in range, CarPlay"). The hero title and timeline are `liveRegion`. Rows merge their descendants (`Modifier.semantics(mergeDescendants = true)`), with toggles exposing `Role.Switch` and state. Headings get `heading()`.

### 7.3 RTL (Arabic, used by the maintainer's market; `values-ar` exists in both apps)
- Use `start`/`end` everywhere; mirror layouts automatically with `LayoutDirection.Rtl`. The rail goes on the right, side sheets come from the left, and chevrons and back arrows mirror (`autoMirrored`).
- **Do not mirror:** phone, USB, Bluetooth and Wi-Fi glyphs, the progress ring direction, media glyphs, or QR codes.
- The timeline connector goes on the right side of the rows, and stage durations sit on the left.
- **Numbers:** technical values (resolution, fps, dBm, version, timers) are forced to LTR with Western digits (`⁦…⁩` isolates, or `BidiFormatter`), because mixed-direction "1920×1080" breaks otherwise. Counts in prose ("Step 2 of 5") use the locale's NumberFormat.
- Phone names are user data; wrap them in Unicode isolates (FSI…PDI) so a Latin name inside an Arabic sentence doesn't reorder the punctuation.
- Arabic text needs extra vertical room for diacritics. Don't set `includeFontPadding = false` on Arabic, and use `LineHeightStyle(Alignment.Center, Trim.None)` for all locales to be safe.

### 7.4 Long strings (Russian, Ukrainian, German, Hungarian, Georgian)
- Budget a **+40 % width** for every label. A button grows in height (2 lines max) rather than truncating, and the 96dp primary can grow to 128dp. Rows grow vertically.
- **Don't put two long strings side by side** without a wrap strategy. The engine filter switches to a vertical 3-option list in single-pane layouts if any label exceeds the segment width (measure with `TextMeasurer`).
- Georgian (ka) and Hungarian have long compounds. Enable `Hyphens.Auto` and `LineBreak.Paragraph` on body text (API 33+; it degrades gracefully).
- Keep the `(experimental)` suffix forms per locale, as `DiPlay/AGENTS.md` requires.

### 7.5 CJK (zh-CN, zh-TW, ja, ko)
- No letter spacing and no all-caps (already a rule). CJK fonts look larger at the same sp; that's fine, so don't shrink them.
- Use `LineBreak.Heading` for titles to avoid orphaned single characters (API 33+).
- Use full-width punctuation in copy; never concatenate strings with "·" in code. Use format strings with placeholders so translators control order (for example `settings_value_format = "%1$s · %2$s"` is *removed*; title and value render on separate lines).

### 7.6 Process
- All new UI strings get a `carlink_` prefix (the existing `settings_` prefix is kept for migrated strings). A `TranslationsTest` (as DiPlay's `SettingsTranslationsTest`) fails on missing locales.
- Union of the locale sets from both apps: **ar, cs, de, es, es-ES, hu, it, ja, ka, ko, nl, pl, pt-BR, ro, ru, tr, uk, vi, zh-CN, zh-TW** (DiPlay currently has ar, es, ru, uk, zh-CN, zh-TW; the DiPlay-only strings need back-fill for the others).
- Pseudolocales `en-XA` (accented, +30 %) and `ar-XB` (RTL) are turned on in debug builds and covered by screenshot tests at 1440×810, 810×1440 and 480×810.

---

## 8. Compose implementation sketch

```kotlin
// theme/CarLinkTokens.kt
object CarLinkDark {
    val scheme = darkColorScheme(
        primary = Color(0xFF5CCFE6), onPrimary = Color(0xFF00222A),
        primaryContainer = Color(0xFF0E3A45), onPrimaryContainer = Color(0xFFB8EEFA),
        secondary = Color(0xFFAEB8C5), onSecondary = Color(0xFF0B0F14),          // used for tonal buttons' content
        secondaryContainer = Color(0xFF232B36), onSecondaryContainer = Color(0xFFE8EDF3),
        background = Color(0xFF0B0F14), onBackground = Color(0xFFE8EDF3),
        surface = Color(0xFF0B0F14), onSurface = Color(0xFFE8EDF3),
        surfaceVariant = Color(0xFF1C232D), onSurfaceVariant = Color(0xFFAEB8C5),
        surfaceContainerLowest = Color(0xFF080B0F), surfaceContainerLow = Color(0xFF11161D),
        surfaceContainer = Color(0xFF161C24), surfaceContainerHigh = Color(0xFF1C232D),
        surfaceContainerHighest = Color(0xFF232B36),
        outline = Color(0xFF6B7788), outlineVariant = Color(0xFF2C3540),
        error = Color(0xFFFF8F87), onError = Color(0xFF3B0906),
        errorContainer = Color(0xFF4A1512), onErrorContainer = Color(0xFFFFDAD6),
        scrim = Color(0xFF000000),
    )
    val ext = CarLinkExtColors(
        onSurfaceTertiary = Color(0xFF8A95A3), success = Color(0xFF6EDC8C),
        warning = Color(0xFFFFC46B), warningContainer = Color(0xFF3D2A00), onWarningContainer = Color(0xFFFFE2B3),
        engineCarPlay = Color(0xFF6EDC8C), engineAndroidAuto = Color(0xFF8AB4F8),
    )
}
object CarLinkLight {
    val scheme = lightColorScheme(
        primary = Color(0xFF006273), onPrimary = Color.White,
        primaryContainer = Color(0xFFC9EEF6), onPrimaryContainer = Color(0xFF002A33),
        secondary = Color(0xFF45505D), onSecondary = Color.White,
        secondaryContainer = Color(0xFFDAE0E7), onSecondaryContainer = Color(0xFF11161D),
        background = Color(0xFFF2F4F7), onBackground = Color(0xFF11161D),
        surface = Color(0xFFF2F4F7), onSurface = Color(0xFF11161D),
        surfaceVariant = Color(0xFFE3E7EC), onSurfaceVariant = Color(0xFF45505D),
        surfaceContainerLowest = Color.White, surfaceContainerLow = Color.White,
        surfaceContainer = Color(0xFFEBEEF2), surfaceContainerHigh = Color(0xFFE3E7EC),
        surfaceContainerHighest = Color(0xFFDAE0E7),
        outline = Color(0xFF737E8C), outlineVariant = Color(0xFFC4CBD4),
        error = Color(0xFFB3261E), onError = Color.White,
        errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
        scrim = Color(0xFF000000),
    )
    val ext = CarLinkExtColors(
        onSurfaceTertiary = Color(0xFF55606D), success = Color(0xFF176B34),
        warning = Color(0xFF8A5000), warningContainer = Color(0xFFFFE2B3), onWarningContainer = Color(0xFF2B1700),
        engineCarPlay = Color(0xFF176B34), engineAndroidAuto = Color(0xFF1D58B8),
    )
}

val CarLinkTypography = Typography(
    displayLarge  = TextStyle(fontSize = 64.sp, lineHeight = 72.sp, fontWeight = FontWeight.Normal),
    displayMedium = TextStyle(fontSize = 56.sp, lineHeight = 64.sp, fontWeight = FontWeight.Normal),
    displaySmall  = TextStyle(fontSize = 48.sp, lineHeight = 56.sp, fontWeight = FontWeight.Normal),
    headlineLarge = TextStyle(fontSize = 40.sp, lineHeight = 48.sp, fontWeight = FontWeight.Normal),
    headlineMedium= TextStyle(fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.Normal),
    headlineSmall = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Medium),
    titleLarge    = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Normal),
    titleMedium   = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium),
    titleSmall    = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium),
    bodyLarge     = TextStyle(fontSize = 28.sp, lineHeight = 40.sp, fontWeight = FontWeight.Normal),
    bodyMedium    = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Normal),
    bodySmall     = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Normal),
    labelLarge    = TextStyle(fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium),
    labelMedium   = TextStyle(fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
    labelSmall    = TextStyle(fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
).let { t -> /* apply LineHeightStyle(Center, Trim.None) + platformStyle(includeFontPadding=false except ar) to all */ t }

val CarLinkShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp), extraLarge = RoundedCornerShape(36.dp),
)

object CarDimens {
    val minTouch = 76.dp; val primaryButton = 96.dp; val secondaryButton = 76.dp; val buttonRadius = 24.dp
    val rowOneLine = 88.dp; val rowTwoLine = 112.dp; val phoneRow = 112.dp; val railItem = 88.dp; val railWidth = 400.dp
    val topBar = 96.dp; val screenMargin = 32.dp; val compactMargin = 24.dp; val gutter = 24.dp; val targetGap = 16.dp
    val iconPrimary = 44.dp; val iconSecondary = 36.dp; val iconTertiary = 24.dp
    val avatarHero = 76.dp; val avatarRow = 56.dp; val focusRing = 4.dp
}

object CarMotion {
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    const val PRESS = 90; const val SHORT = 150; const val MEDIUM = 250; const val LONG = 350; const val THEME = 300
}
```

Platform notes:
- minSdk is 25 (Android 7.1), which is fine for current Compose.
- Avoid `RenderEffect`, `Modifier.blur` and large `graphicsLayer` alpha stacks.
- Use `Modifier.minimumInteractiveComponentSize()`, overridden via `LocalMinimumInteractiveComponentSize provides 76.dp` at the root, so *every* M3 component inherits the 76dp floor.
- Disable overscroll stretch (`LocalOverscrollFactory provides null`); it reads as lag on DiLink panels.

---

## 9. Acceptance checklist (for review of the implementation)

- [ ] Home shows status, phone identity, engine and transport within one glance. There is exactly one primary button.
- [ ] Connect, Cancel, Retry and Switch phone take ≤2 taps from Home and are each ≥76dp.
- [ ] No text below 24sp outside parked-only screens. Driving-critical text is ≥28sp.
- [ ] No ALL CAPS, no marquee, no auto-scrolling text, no blur, no shadows.
- [ ] Every error in §4.2 is reachable in a debug "error gallery" and shows one primary recovery.
- [ ] Settings: every DiAuto and DiPlay setting is mapped to exactly one category (a migration table test like `SettingsLayoutPolicyTest`). The Shared / CarPlay / Android Auto sections render, and the engine filter is remembered.
- [ ] Auto-save everywhere, with the reconnect bar for [N] and [R] rows. No session is dropped without consent.
- [ ] Parked-only gate blocks the keyboard, Advanced, logs and export when motion = moving.
- [ ] Screenshot tests pass in dark and light, LTR / `ar` / `ru` / `zh-CN` / `en-XA`, and at 1440×810, 810×1440, 960×810 and 480×810.
- [ ] D-pad/rotary can reach and operate every control, with a visible focus ring.
- [ ] Contrast assertion test passes for all text nodes.
