# CarLink: premium UX research and direction

> **Status (2026-10-09): superseded for the visual direction** by
> [RESEARCH_HOME_V2.md](RESEARCH_HOME_V2.md) and [HOME_V2_RATIONALE.md](HOME_V2_RATIONALE.md)
> (the "Instrument" language the app uses now). The research itself (in-car UX sources, settings
> structure, head-unit constraints) is kept and still cited.

Status: research and recommendations, 2026-10-09. No code changes.
Audience: whoever rebuilds the CarLink UI after the rejection of the Material 3 settings-style attempt.
User-chosen direction: **"Premium dashboard": Tesla/Rivian-like, full-bleed, large hero imagery of the phone/car, glass cards, bold type.**
Companion document: `design/DESIGN_SPEC.md`, which stays valid for **behavior** (connection state machine, error catalog, stage timeouts, 76dp targets, canvas scaling, parked-only gate). Where this document conflicts with it on **look and settings structure**, this document wins (see §10).

---

## 0. TL;DR (the opinionated version)

1. **Premium in cars comes from restraint, scale and light, not from effects.** Every reference studied (Tesla, Polestar, Rivian, Lucid, NIO) uses a near-black base, one accent, few elements, very large type or imagery for the one thing that matters, and depth from imagery and light falloff instead of shadows or chrome. Reproduce that and the app looks expensive even on an Adreno-610-class GPU.
2. **Home is a stage, not a dashboard.** A full-bleed dark "atmosphere" with one hero object (the phone, rendered large), one headline that answers "what is happening", and one primary action. Status details live in at most three glass chips. No lists on Home.
3. **Glass without blur.** Real-time backdrop blur is off the table (minSdk 25; `RenderEffect` is API 31+; DiLink GPUs are mid-range). Because the hero background is a *static* bitmap, CarLink ships (or computes once) a pre-blurred copy and draws the matching crop inside each card. The result looks exactly like frosted glass and costs one bitmap draw per frame.
4. **Imagery: generic device renders, never the car.** CarLink can't know which car it is in, and a wrong car render looks cheaper than none. Use two bundled, stylized phone renders (an "island" phone and a "punch-hole" phone, neither a trademarked device) plus Compose-drawn light effects (connection beam, glow). The atmosphere background is a bundled WebP with baked-in noise to stop banding.
5. **Type: one neo-grotesk, bold only at display sizes.** Tesla (Universal Sans), Rivian (Söhne-derived) and Polestar (Unica77) all use neo-grotesks. CarLink bundles **Inter** (OFL), subset to Latin, Cyrillic and Greek, with a budget of ≤400 KB. Hero statements are 64–96sp SemiBold. Anything ≤32sp stays Regular or Medium, per AAOS guidance that bold is less legible at reading sizes.
6. **Zero-config is the product.** No first-run wizard. CarLink starts in "Ready", asks for one permission bundle with one inline card when needed, and picks the transport automatically in the order **same Wi-Fi → car hotspot / Wi-Fi Direct → USB**. When phone and car share a Wi-Fi network, Home says so with a green "Same Wi-Fi · fastest" chip.
7. **Settings: "Essentials" by default, plus an inline "Show advanced" switch, plus a hidden Lab tier.** Essentials covers about 12 settings that a beginner can't break. "Show advanced" (the Plex / UniFi pattern) reveals advanced rows *inside the same categories* and is parked-only. Lab (experimental and debug) unlocks by tapping the version 7 times (the Android Developer options pattern).
8. **One settings set for both engines: "intent, translated."** Every user-facing setting expresses intent ("Interface size: Large", "Video: Smooth") and each engine translates it into its own parameters (AA DPI, CarPlay display size hint, resolution, fps, codec). Per-engine values exist only as **overrides in Advanced** ("Override for Android Auto"), never as two parallel pages. The segmented filter (All / CarPlay / Android Auto) from DESIGN_SPEC is dropped.

---

## 1. What makes premium in-car UIs feel premium

### 1.1 Findings by reference

| Reference | Observed traits | Source(s) |
|---|---|---|
| **Tesla (v11/v12 and brand system)** | Starkly achromatic palette with **one electric blue (#3E6AE1)** reserved for primary actions. Near-black "Carbon Dark" (#171A20) surface. Depth comes from full-bleed imagery and white space; community token analyses report zero shadows, zero gradients and no card borders. Custom **Universal Sans** (Display and Text cuts) unifies web, app and car. In-car: left third is a live car visualization, the rest is the map, and the bottom bar is a customizable launcher. **Critique:** v11 pushed one-tap controls two taps deeper and drew "revert" demands; owners say night-mode contrast and type size regressed. | [OpenDesign Tesla tokens](https://open-design.ai/plugins/design-system-tesla/), [Fudge Tesla analysis](https://design.withfudge.com/share/tesla.com-design), [designmd Tesla](https://www.designmd.co/d/tesla), [Not a Tesla App: first look at v11](https://www.notateslaapp.com/news/663/first-look-at-tesla-s-new-v11-ui), [Not a Tesla App: v11 changes](https://www.notateslaapp.com/news/668/tesla-v11-changes-coming-may-lead-to-best-ui-yet), [TOO font-size thread](https://www.teslaownersonline.com/threads/font-size-on-screen.21544/) |
| **Rivian R1 / R2** | The team grew to 19 people, including **3D artists, motion designers and illustrators**, which shows that imagery and motion carry the brand. A high-contrast palette that is warm for day and cool for night. **Custom numerals adapted from Söhne** (Pentagram), with a monospace cut for the speedometer. R2 adds a floating status "island", rounder elements and controls on the driver's side. | [Rivian Vehicle OS (Behance, M. Metropulos)](https://www.behance.net/gallery/166386511/Rivian-Vehicle-OS), [Pentagram: Rivian](https://www.pentagram.com/work/rivian/story), [Brian Lucas: R1 UI](https://www.brianlucasdesign.com/project/rivian-ui), [R2 UI capture thread](https://www.rivianforums.com/future-r1-software-updates-preview-captured-from-r2/), [RivianTrackr R2 preview](https://riviantrackr.com/news/r2-ui-preview-shows-rivians-next-gen-design/) |
| **Lucid Air (Glass Cockpit)** | "Spartan, warm and easy on the eyes, with readable fonts atop **sparse gradient backgrounds**." The 34″ panel is split into grouped zones. | [Jalopnik](https://www.jalopnik.com/lucid-airs-user-interface-has-four-screens-some-button-1846975789/) |
| **Polestar 2/3/4** | Scandinavian minimalism; an "uncluttered look [that] borders on austere." Brand typeface **Unica77** (Lineto). Critique: too many functions nested in the screen. | [The Drive: Polestar 4](https://www.thedrive.com/car-reviews/2026-polestar-4-first-drive-review), [designboom: Unica77](https://www.designboom.com/design/polestar-unica77-typeface-zurich-design-week-2023-09-13-2023/), [two-worlds critique](https://two-worlds.com/the-golden-age-of-lousy-design-1-polestar-4/) |
| **Mercedes MBUX Hyperscreen** | **"Zero layer"**: the most important functions are surfaced contextually at the top level, with no submenus. "Magic Modules" are suggestion tiles. The next-generation UI is built on **Unity** for real-time 3D, running on an 8-core, 24 GB RAM system, which is far beyond a head unit. | [Mercedes-Benz Group: Hyperscreen](https://group.mercedes-benz.com/innovation/digitalisation/connectivity/mbux-hyperscreen.html), [MBUSA EQS release](https://media.mbusa.com/releases/release-bf1efe5aaf5bd27d95a735b6a402895a-eqs-with-unique-mbux-hyperscreen-the-big-picture), [Carscoops: Unity OS](https://www.carscoops.com/2022/08/mercedes-benz-introducing-unity-powered-operating-system-in-2024my/) |
| **BMW Panoramic iDrive (Neue Klasse)** | A black windscreen-base strip with a few "particle" slots; a zero-layer central display with QuickSelect widgets on the left edge; "a scalable canvas." No published colors or type. | [BMW Group press](https://www.press.bmwgroup.com/global/article/detail/T0447111EN/new-bmw-panoramic-idrive-revolutionises-vehicle-operation-visionary-technology-package-for-the-neue-klasse-celebrates-world-premiere-at-ces-2025?language=en), [designboom interview](https://www.designboom.com/technology/bmw-panoramic-idrive-experience-ces-interview-01-07-2025/), [autoevolution M5 spy](https://www.autoevolution.com/news/the-2028-bmw-m5-s-neue-klasse-interior-comes-into-focus-with-idrive-x-and-panoramic-vision-266343.html) |
| **NIO (Aspen 3.0 / Banyan)** | "We use **black as the main tone**, the accent color is purer, clearer, the saturation is higher." **3D car scenes** are used because "3D UI can better represent car scenes." Half-screen media cards and a "Mini mode" that tucks cards away to give the map room. Red Dot and iF award winners. | [Glen Gao: NIO Aspen 3.0 UI/UX (Medium)](https://medium.com/@glengao/nio-aspen-3-0-uiux-design-341b38df3656), [iF: NIO Aspen 3.0](https://ifdesign.com/en/winner-ranking/project/nio-aspen-30-design/349716), [Red Dot: NIO OS](https://www.red-dot.org/project/nio-os-49280), [NIO OS 3.0 blog](https://www.nio.com/blog/nio-os-300-our-all-new-user-interface-and-user-experience) |
| **Xiaomi SU7 (HyperOS)** | iF awards for the cockpit and HMI; jurors cited seamless interconnectivity across five screens. Runs on a Snapdragon 8295 class chip, again far above DiLink. Editable home widgets and playful "virtual pets." | [autoevolution: five iF awards](https://www.autoevolution.com/news/xiaomi-wins-five-if-design-awards-for-the-su7-digital-cockpit-and-infotainment-system-248412.html), [news18a: HyperOS 1.16](https://english.news18a.com/m/news/english_255784.html) |
| **Zeekr** | Only hardware details were found (OS 6.0, 2.5K OLED). | [Wikipedia: Zeekr 001](https://en.wikipedia.org/wiki/Zeekr_001) |
| **BYD DiLink / Kinex launcher (on DiLink hardware)** | No official BYD design documentation was found. The best evidence for *what is feasible on DiLink* is **Kinex**, a third-party BYD launcher. It has a 3D model of the user's car with **Low / Medium / High quality tiers**, reflection and shadow, and headlights at night. Its developer writes "still limited by hardware," says one layout "may still feel slower … because of CPU and GPU limits," and notes that a Medium tier "looks almost the same as High with better performance." A calm home with large targets and "without heavy animation or flashy effects." | [Kinex](https://kinex.lexwah.com/), [BYDMate](https://github.com/AndyShaman/BYDMate), [42how: Dolphin SoC (Snapdragon 660, reported)](https://en.42how.com/?p=7094) |

### 1.2 Synthesis: the six ingredients of "premium"

1. **One hero per screen, at heroic scale.** Tesla's car, NIO's 3D scene and Rivian's numerals each take 30–50% of the screen. Everything else is small and quiet.
2. **A dark, near-black base with light falloff.** Lucid's "sparse gradient backgrounds" and Tesla's photography over black: the background isn't flat. It has one soft light source.
3. **One saturated accent, used only for action** (Tesla blue, Polestar orange, NIO's "purer, clearer" accent).
4. **A custom-feeling neo-grotesk with tabular numerals** (Universal Sans, Söhne-derived, Unica77).
5. **Depth from imagery and translucency, not shadows** (the Tesla token analyses; Rivian's 3D artists).
6. **Calm, purposeful motion.** Rivian staffs motion designers. Kinex, which runs on the same class of hardware as CarLink, deliberately avoids heavy animation.

Anti-patterns seen in the references: functions buried under extra taps (Tesla v11, Polestar 4), contrast regressions at night (Tesla), and real-time 3D that weak hardware can't carry (Kinex's own notes).

### 1.3 Dribbble / Behance: what to open (manual browsing needed)

Dribbble and Behance return HTTP 403 to automated fetching, so these were judged from their titles and tags. Open them in a browser.

| Shot / collection | Why it's relevant | Relevance |
|---|---|---|
| [Rivian Vehicle OS (Behance)](https://www.behance.net/gallery/166386511/Rivian-Vehicle-OS) | The real production system, including 3D, illustration and motion | **Highest** |
| [Conceptzilla: Car UI Dashboard](https://dribbble.com/shots/18021625-Car-UI-Dashboard) | Dark, hero card plus side panel; one-tap detail | High |
| [Jonathan Larradet: HMI EV dashboard, Polestar](https://dribbble.com/shots/16689571-HMI-UI-Electric-car-dashboard-Polestar) | Monochrome with one accent | High |
| [Luca Lombardi: split-screen concept](https://dribbble.com/shots/18128789-Car-interactive-split-screen-concept) | Behavior in DiLink's ⅓ and ⅔ split | Medium |
| [Collection: "Car UI" (TsaiLueng)](https://dribbble.com/TsaiLueng/collections/5034375-car-ui) | Dark dashboards and cluster motion | Medium |
| [Collection: "Auto" (anw)](https://dribbble.com/anw/collections/6953541-Auto) and [Automotive (apostol)](https://dribbble.com/apostol/collections/6668468-Automotive) | "Automotive dashboard cubic style HMI … 3D animation EV" shots | Medium (mood only; full 3D is too heavy for CarLink) |
| [Elementz UX (kurtcepe)](https://dribbble.com/kurtcepe) | Minimal single-display HMI | Medium |

No Dribbble shot specific to "CarPlay connect screen" turned up in search. The closest real-world pattern is Apple's AirPods/iPhone proximity setup card (big device render, one sentence, one button), which is the model for CarLink's "phone detected" moment (§7.2).

---

## 2. Competitor connection apps

| Product | Onboarding / connection presentation | Praise | Complaints | Source(s) |
|---|---|---|---|---|
| **Carlinkit AutoKit** (head-unit app) | A flat list of about 20 settings: Detection, Decoding method, Frame rate, iPhone mode, Android mode, Connection mode, **Display size (applies to Android Auto only)**, Night mode, Automatic start, Driving position, Image scale, logs, Advanced settings, Echo cancellation, Sync mode, "Ambient waiting". | Works with many radios | Settings mix engine-specific items into shared ones without labeling them (Display size silently applies only to AA). Users fix resolution freezes by trial and error. Connection time is "unpredictable"; random prompts; audio skips. | [AutoKit settings guide](https://www.carlinkitfactory.com/explanation-of-autokit-app-options-settings-a-comprehensive-guide/), [Android Police 5.0 review](https://www.androidpolice.com/carlinkit-50-review/), [Team-BHP thread](https://www.team-bhp.com/forum/car-entertainment/257077-carlinkit-4-0-wireless-carplay-android-auto-same-adapter-8.html), [Trustpilot](https://ie.trustpilot.com/review/autokitcarplay.com?page=5) |
| **AAWireless** (phone companion app) | The app "walked me through every step"; Settings → **Change DPI** with a raw number, then save and reboot. Wi-Fi band option (the developers discourage 2.4 GHz). Passthrough / Developer options. | Guided setup; DPI control was "the main reason" one reviewer bought it | A raw DPI number with no preview; older firmware boot-looped on DPI change; "advanced features can be confusing". | [Android Central](https://www.androidcentral.com/accessories/audio/aawireless-review), [Android Police](https://www.androidpolice.com/aawireless-review/), [9to5Google DPI guide](https://9to5google.com/2023/03/06/android-auto-widgets-dpi/), [Audizine DPI thread](https://www.audizine.com/forum/showthread.php/971226-Changing-DPI-settings-of-AA-Wireless-dongle), [Ranger5G review thread](https://www.ranger5g.com/forum/threads/review-aawireless-two-wireless-android-auto.39351/post-838813) |
| **Motorola MA1** | No app at all: "basic connection with no customization … besides on and off." | Plug and forget | No escape hatch when something doesn't fit | [Ranger5G](https://www.ranger5g.com/forum/threads/review-aawireless-two-wireless-android-auto.39351/post-838813), [JL Wrangler forum](https://www.jlwranglerforums.com/forum/threads/aawireless-motorola-ma1-wireless-android-auto-adapter-talk.86856/page-36) |
| **Carsifi** | Bluetooth pairing, then Wi-Fi Direct | "Super easy setup", stable | The app is the only way to update firmware and hasn't been updated since 2022; one reviewer's issues were down to settings | [Android Central](https://www.androidcentral.com/accessories/carsifi-review), [9to5Google](https://9to5google.com/2022/09/20/carsifi-android-auto-review/), [Car Audio Now](https://www.caraudionow.com/carsifi-android-adapter-review/) |
| **Ottocast U2 family** | Pair "AUTO-xxxx" over Bluetooth, then accept the CarPlay prompt. Settings in a web page or vendor app. | — | Wireless AA needs 5 GHz; full screen depends on the factory head unit | [Ottocast FAQ](https://www.ottocast.com/pages/faqs), [addcarwidgets review](https://addcarwidgets.com/blog/ottocast-review-2026), [NerdTechy](https://nerdtechy.com/ottocast-review) |
| **Headunit Reloaded / Revived** | Classic flow: phone hotspot plus a "WiFi Launcher" app. Revived adds **Wi-Fi Direct and a "shared Wi-Fi on the same router"** mode, with auto-scan or manual IP. | Free, flexible | Black screens fixed with "software decoding"; hotspot juggling. Since **AA 17.4, wireless start without Bluetooth or Headunit Server was removed**. | [Play listing](https://play.google.com/store/apps/details?id=gb.xxy.hr&hl=en_US), [open-headunit wiki: Wireless](https://github.com/andreknieriem/open-headunit/wiki/Wireless), [headunit-revived](https://github.com/andreknieriem/headunit-revived), [wireless-helper wiki](https://github.com/andreknieriem/wireless-helper/wiki) |

**What this means for CarLink:**
- No competitor has a *beautiful* waiting or connecting state. They show a spinner, a logo or a settings list. That is CarLink's opening.
- The two biggest pain points are **unpredictable connect time** and **raw technical knobs** (DPI numbers, decoder modes). The answers are a staged progress line with honest timing, and intent-based presets with a live preview.
- Shared Wi-Fi is a real, supported topology in open-source head-unit emulators (Revived). It still depends on Bluetooth or Headunit Server to *trigger* AA on current Android Auto versions, so CarLink must keep Bluetooth as the wake-up channel even when video goes over the shared LAN. This is a technical constraint to verify; this document doesn't settle it.

---

## 3. Beginner vs. advanced: patterns and the pick

| Pattern | How it works | Strength | Weakness | Source |
|---|---|---|---|---|
| **Plex "Show Advanced"** | A toggle at the top right of each settings page reveals advanced rows in place; "Be careful when adjusting…" | Advanced items stay in their natural category, so they're findable | The toggle is per page and easy to miss | [Plex: General](https://support.plex.tv/articles/200289496-general/), [Plex: Network](https://support.plex.tv/articles/200430283-network/), [Plex Web App settings](https://support.plex.tv/articles/200289408-plex-web-app-settings/) |
| **UniFi simplified default + Advanced** | The default view is simplified; an "Advanced" toggle or section reveals many more options across Wi-Fi, Network and Security. UniFi 7.0 "revamped settings to simplify". | One global switch; beginners never see the scary rows | Users report "missing" settings and don't know about the toggle | [UniFi 7.0 blog](https://blog.ui.com/2022/03/01/unifi-network-version-7-0-introduces-revamped-settings-to-simplify-system-configuration/), [scos troubleshooting](https://scos.co.uk/troubleshooting/unifi/unifi-missing-features), [HostiFi](https://support.hostifi.com/en/articles/6240303-how-to-change-between-unifi-interface-versions) |
| **Android Developer options** | Hidden until you tap Build number 7 times, with a countdown ("X steps away") and a lock-screen confirmation | A deliberate act keeps casual users out | Undiscoverable by design, so only for things support will tell you about | [techbone](https://www.techbone.net/android/developer-settings), [technobezz](https://www.technobezz.com/how-to-enable-developer-options-on-android) |
| **Tesla Controls vs. Service Mode** | Normal Controls for everyone. Service Mode requires a long press on the model badge plus a code, adds a **red border** and a red wrench in the dock, and says "Do NOT drive" | A strong visual "you are in danger mode" signal | Not for end users | [Not a Tesla App: Service Mode](https://www.notateslaapp.com/news/2046/tesla-service-mode-how-to-access-it-and-what-it-does) |
| **Progressive disclosure (NN/g lineage)** | Show essentials; make the hidden layer discoverable with an explicit "Advanced" affordance; keep it to 2–3 levels; never hide consequences | "Novices learn faster and err less, while experts pay 1 click" | — | [Jakob Nielsen: Progressive Disclosure](https://jakobnielsenphd.substack.com/p/progressive-disclosure), [UXPin overview](https://www.uxpin.com/studio/?p=41489) |

### 3.1 Recommendation for CarLink: "Essentials / Advanced / Lab"

1. **Essentials (default).** About 12 settings (hard cap 14) in total across all categories. Each is safe: no setting in Essentials can produce a black screen, no audio or a failed connection. Defaults are labeled with a small "Recommended" pill.
2. **Advanced (one global switch, parked-only).** The switch lives at the **top of the Settings root** (the UniFi model: one switch, not per page), as a large glass toggle row: "Show advanced settings. For experienced users. Some options can affect sound or picture." When it's on, advanced rows appear **inline in their own category** (the Plex model: findable where you'd expect them), with a subtle `ADVANCED` tag. The switch is remembered. Turning it off hides the rows but **keeps their values**, and a banner at the top of Essentials says "3 advanced settings are changed · Review" so a beginner who inherited a tweaked head unit can see why things differ.
3. **Lab (hidden).** Tap the version row in About 7 times, with a countdown toast (the Android pattern). Lab holds experimental (`[X]`) and debug items such as fault injection, the FPS counter, view mode and libusb. While Lab is on, Settings gets a **thin amber frame** (the Tesla Service Mode cue) so a screenshot sent to support shows it immediately.
4. **Safety nets at every tier.** Every category has "Reset this section to recommended". Any changed-from-default row shows a small dot. Changes that need a reconnect raise the single "Reconnect now" bar (kept from DESIGN_SPEC). If the next connection after an Advanced change fails twice, CarLink offers "Undo recent changes" as the error's secondary action. This is the cheapest protection against the AutoKit-style "resolution froze, now what" trap.

---

## 4. One settings set for both engines

### 4.1 Evidence

- AutoKit presents one flat list, but "Display size is applicable to Android Auto" only: a shared-looking control with engine-specific effect. That is the worst of both worlds ([AutoKit guide](https://www.carlinkitfactory.com/explanation-of-autokit-app-options-settings-a-comprehensive-guide/)).
- AAWireless exposes the protocol parameter (DPI) directly, and users wish they could "pick which UI display you wanted instead of having to randomly pick DPI" (see DESIGN_SPEC §1.3 citing [Android Central](https://www.androidcentral.com/accessories/audio/aawireless-review)).
- Ottocast and other 2-in-1 adapters mostly expose no per-protocol settings at all and inherit the factory head unit's behavior ([Ottocast FAQ](https://www.ottocast.com/pages/faqs)).

### 4.2 Rule: intent, translated; override only in Advanced

A setting is **unified** when it expresses something the *driver* wants and both engines can express it, even if the mechanics differ. It is **engine-specific** only when one engine has no counterpart. Engine-specific values are modeled as **overrides** of a unified value:

```
effective(engine, key) = override[engine][key] ?: unified[key] ?: recommended(engine, headUnit)
```

In the UI, an override appears under the unified row (Advanced only) as "Different for Android Auto…". When it's set, the unified row's summary reads "Large · Android Auto: Custom 160 dpi".

| Setting (UI name) | Tier | Type | CarPlay translation | Android Auto translation |
|---|---|---|---|---|
| **Interface size**: Compact / Standard / Large, with live preview | Essentials | Unified | Display physical-size / scale hint sent at session setup | DPI computed from the panel's physical size × preset; "Custom DPI" is an AA override in Advanced |
| **Picture**: Auto (Recommended) / Sharpest / Smoothest / Compatibility | Essentials | Unified | Resolution + 30/60 fps + H.264/HEVC | Resolution tier + fps + codec; Compatibility = H.264 + software decode |
| **Projection theme**: Follow car / Always dark / Always light | Essentials | Unified | Night-mode signal | Night-mode sensor signal |
| **Driving side**: Auto / Left / Right | Essentials | Unified | Dock side | Rail/taskbar side |
| **Sound through**: Car speakers (default) / Car Bluetooth | Essentials | Unified | Audio channel mapping | Audio stream routing |
| **Microphone**: Car mic (default) / Phone mic | Essentials | Unified | Siri/call audio source | Assistant/call audio source |
| **Voice button** (wheel key): Voice assistant / Off | Essentials | Unified | Siri | Google Assistant / Gemini |
| **Connect automatically** | Essentials | Unified | — | — |
| **Start with the car** | Essentials | Unified | — | — |
| **Preferred phone** | Essentials | Unified (per phone, not per engine) | — | — |
| **Connection method**: Auto (same Wi-Fi → hotspot/Direct → USB) / Wireless only / USB only | Essentials | Unified | Wi-Fi join path | Wi-Fi transport |
| **Share car location (GPS)** | Advanced | Unified | Location data | Location sensor |
| Steering-wheel key mapping | Advanced | Unified | | |
| Media stream volume offset, ducking | Advanced | Unified | | |
| Wi-Fi band (Auto / 5 GHz / 2.4 GHz) | Advanced | Unified | | |
| Video decoder (Hardware / Software) | Advanced | Unified + per-engine override | | |
| Safe area / insets editor | Advanced | Unified + override | | |
| **Custom DPI (number)** | Advanced | **AA only** (override of Interface size) | — | ✔ |
| **HEVC / efficient video** | Advanced | **CarPlay only** | ✔ | — |
| Cluster / instrument turn card | Advanced | **CarPlay only** (labelled as such) | ✔ | — |
| View mode (Surface / Texture / GLES), audio queue, AAC, Wi-Fi version exchange, fake speed | Lab | **AA only** | — | ✔ |
| Debug, fault injection, FPS overlay | Lab | Unified | | |

UI rules:
- **No engine filter, no CarPlay and Android Auto sections in Essentials.** A beginner never sees the words "CarPlay" or "Android Auto" in Settings except in Phones.
- Engine-only rows (Advanced and Lab) carry a small engine glyph and a suffix ("· Android Auto only"), so nobody thinks they affect both.
- **Recommended values are computed per head unit** (panel size and decoder probe), not hard-coded. "Auto" is the default everywhere it exists.

---

## 5. Rendering constraints on head units, and premium on a budget

### 5.1 Constraints

- **Blur.** Compose `Modifier.blur` blurs only the composable's *own* content, not what is behind it, and needs API 31+ (`RenderEffect`). Backdrop blur needs a library such as Haze, which falls back to RenderScript below API 31 with known stale-sample bugs. Haze's own benchmarks show progressive blur at about 1.25× frame cost, and a shader recompilation bug cost 20–45 ms of GPU time on transitions until it was fixed. CarLink's `minSdk` is 25 and DiLink runs Android 10 to 12L. Sources: [Haze 1.0 post](https://chrisbanes.me/posts/haze-1.0/), [Haze issue #1366](https://github.com/chrisbanes/haze/issues/1366), [Haze issue #77](https://github.com/chrisbanes/haze/issues/77), [Haze PR #1351](https://github.com/chrisbanes/haze/pull/1351), [Canopas: RenderEffect](https://canopas.com/how-to-use-render-effects-in-jetpack-compose-for-stunning-visuals-01287d7f00db). **Verdict: no runtime blur.**
- **GPU class.** BYD DiLink units reportedly use Snapdragon 660-class SoCs, and many aftermarket units use a QCM6125 / Adreno 610. Both are mid-range from around 2018–2019 ([42how](https://en.42how.com/?p=7094), [android-headunits: Adreno 610](https://android-headunits.com/adreno-610-gpu/)). Kinex's notes confirm that 3D on DiLink is "limited by hardware" ([Kinex](https://kinex.lexwah.com/)).
- **Vectors.** Each VectorDrawable has one bitmap cache per instance. A size change (rotation, split screen) forces a re-raster, and complex paths have a first-draw cost ([Android VectorDrawable reference](https://developer.android.com/reference/kotlin/android/graphics/drawable/VectorDrawable), [Styling Android](https://blog.stylingandroid.com/vectordrawables-part-1/), [42gears](https://techblogs.42gears.com/optimize-vector-drawable-path/)). Vectors suit icons. Large illustrative art is better as bitmaps.

### 5.2 Techniques (Compose, all API 25+)

| Effect | Technique | Cost |
|---|---|---|
| **Frosted glass card** | The hero background is a static `ImageBitmap` (`atmosphere.webp`). Ship `atmosphere_blur.webp` (pre-blurred at a radius of about 60px and downscaled to ¼ size; it upscales invisibly because it's blurred), or compute it once on a background thread with a CPU stack blur and cache it to disk. Each glass card draws the **same-offset crop** of the blurred bitmap (`drawImage(src, srcOffset = cardPositionInRoot, …)`), then a 6% white fill, then a 1dp top-lit border (vertical gradient stroke, white 16% → 4%). | 1 bitmap draw + 2 cheap shapes |
| Glass over *moving* content (rare: the Projection overlay bar) | Don't fake it. Use an opaque `#0C1016` at 92% with the same border. Nobody notices at a glance. | trivial |
| **Light falloff / atmosphere** | Baked into the WebP. Also a `Brush.radialGradient` "glow" behind the hero phone that can tint by state (cyan while connecting, green when live). Animate only alpha and scale through `graphicsLayer`. | 1 gradient draw |
| **Gradient banding** (8-bit and dithered head-unit panels) | Bake 1–2% monochrome noise into the WebP. For runtime gradients, overlay a 128px tiled noise texture at 3% alpha. | 1 tiled draw |
| **Hero device** | Bundled WebP renders (see §6.4), 2 variants × 2 sizes (portrait/landscape hero). Each about 80–150 KB. | static |
| **Connection "beam"** | A Compose `Canvas` arc or line with a moving `Brush.linearGradient` offset (shader translate only) | negligible |
| **Shadows** | None on cards. One soft baked shadow under the phone render, inside the image. | 0 |
| **Motion** | Animate only `graphicsLayer` properties (alpha, translation, scale). No layout animation on Home. Precompile with Baseline Profiles so the first transition doesn't jank. | low |
| **Lottie** | Avoid. It adds a library and parsing cost for effects Compose can draw natively. | — |

**APK budget for the visual layer: ≤1.5 MB.** That covers the font at ≤400 KB, 2 atmosphere WebPs (landscape and portrait) plus their blurred twins at about 300 KB total, 4 phone renders at about 500 KB, a noise tile under 5 KB, and vector icons.

**Quality tiers (the Kinex lesson).** Detect performance class once: GPU renderer string plus a 2-second `FrameMetrics` probe on first launch. A Lite tier drops the animated glow and the beam shimmer, and uses solid glass with no blurred-crop draw. It switches automatically and appears as an Advanced setting, "Visual effects: Auto / Full / Lite".

---

## 6. Visual language

### 6.1 Palette, "Graphite and Ion" (dark is the default; it is also Night)

Contrast ratios were computed with the WCAG 2.x formula. "Glass" is white 6% over `#0C1016`, which gives `#1B1E24`; "Glass Hi" is white 10%, which gives `#24282D`.

| Token | Hex | Use | Contrast (on bg / on Glass / on Glass Hi) |
|---|---|---|---|
| `bgDeep` | `#07090D` | Letterbox, projection hand-off, splash | — |
| `bgBase` | `#0C1016` | Base under the atmosphere image; flat fallback | — |
| `atmosphereLight` | `#16324A` → transparent | The single soft "light source" (top-left in landscape, top in portrait) baked into the background | — |
| `glassFill` | `#FFFFFF` @ 6% | Cards and chips | — |
| `glassFillPressed` | `#FFFFFF` @ 10% | Pressed and selected glass | — |
| `glassStroke` | `#FFFFFF` @ 16% → 4% (top → bottom) | 1dp card border, "top-lit" | — |
| `textPrimary` | `#EEF2F6` | Headlines, values (not pure white, to limit night glare) | 17.7 / 14.8 / 13.2 |
| `textSecondary` | `#A9B3C0` | Descriptions | 9.4 / 7.9 / 7.0 |
| `textTertiary` | `#8A95A3` | Metadata only | 6.6 / – / 4.9 |
| `accent` ("Ion") | `#5BD6F0` | Primary button fill, active stage, toggles on, focus ring. **Interaction only.** | 11.7 / 9.8 / 8.7 |
| `onAccent` | `#03222B` | Text on Ion | 9.7 on Ion |
| `live` | `#7EE2A8` | "Connected", the live glow, success checks (always with an icon) | 12.7 / 10.6 / 9.4 |
| `caution` | `#FFB547` | "Applies next connection", Lab frame | 11.3 / 9.5 / 8.4 |
| `fault` | `#FF7A70` | Error title and icon | 7.9 / 6.6 / 5.8 |
| `engineCarPlay` | `#EEF2F6` glyph on a `#FFFFFF` @ 10% disc | Engine badge. Neutral, so the accent stays unique; CarPlay's mark is used per Apple's guidelines. | — |
| `engineAndroidAuto` | `#9DB8FF` | AA badge tint | 10.2 / 8.5 / 7.6 |

**Day (light) variant**, used when the car is in light mode. It is a "warm graphite day", as in Rivian's day/night temperature shift, not a white Material sheet.

| Token | Hex | Contrast on `#E8EBEF` |
|---|---|---|
| `bgBase` | `#E8EBEF` (atmosphere: a warm `#F3EEE6` light source) | — |
| `glassFill` | `#FFFFFF` @ 62% (comes out near `#F7F8FA`) | — |
| `textPrimary` | `#0D1117` | 15.8 |
| `textSecondary` | `#46505C` | 6.9 |
| `textTertiary` | `#5A6470` | 5.0 |
| `accent` | `#006A7E` (`onAccent` `#FFFFFF`, 6.2) | 5.2 |
| `live` | `#18703F` | 5.1 |
| `caution` | `#965A00` | 4.7 |
| `fault` | `#C2352B` | 4.6 |
| `engineAndroidAuto` | `#2F5BD0` | 5.0 |

Rules: one accent; accent never on headings or illustrations; status colors always paired with an icon; no Material You dynamic color.

### 6.2 Typography

- **Family: Inter v4 (OFL)**, bundled as a variable font, subset to Latin, Latin-Ext, Cyrillic, Greek and Vietnamese, with a budget of ≤400 KB (measure after subsetting). Use the Display optical size where the variable font provides it. Enable `tnum` for all numbers and `cv11` (single-storey a) optionally. CJK, Arabic and Georgian fall back to system Noto. Rationale: the references all use neo-grotesks (Universal Sans, Söhne-derived, Unica77); Inter is the closest free, broad-script equivalent. Geist is an acceptable alternative, but its Cyrillic coverage must be checked first.
- **Bold policy.** The user wants bold type, and AAOS warns bold is less legible at reading sizes ([AAOS typography](https://developers.google.com/cars/design/automotive-os/design-system/typography)). Both are satisfied by making only display sizes (≥48sp) SemiBold (600) with slightly tight tracking. Everything ≤32sp is 400 or 500.

| Role | Size / line (sp, on the 1440×810dp canvas) | Weight | Tracking | Use |
|---|---|---|---|---|
| `hero` | 96 / 100 | 600 | −2% | Single-word state in landscape Home ("Connected", "Ready") |
| `display` | 64 / 72 | 600 | −1.5% | Hero headline when it's a sentence ("Plug in or bring your phone close"); portrait hero |
| `headline` | 40 / 48 | 600 | −1% | Settings category title, onboarding title |
| `title` | 32 / 40 | 500 | 0 | Phone name, card titles |
| `body` | 28 / 40 | 400 | 0 | Hero subtitle, error body (**driving-critical minimum**) |
| `bodySm` | 24 / 32 | 400 | 0 | Setting descriptions (parked reading) |
| `label` | 24 / 28 | 500 | +1% | Chips, buttons (28 for primary buttons) |
| `eyebrow` | 20 / 24 | 600 | +8%, UPPERCASE | **Only** on a glass chip header ("CONNECTION", "PHONE"). Never alone and never a sentence. This deliberately relaxes DESIGN_SPEC's "no caps" rule, because tracked caps are a key premium-dashboard cue; it is limited to ≤2 words. |
| `numeral` | 72 / 72 | 500, `tnum` | 0 | Timers, connection time, latency stats |

### 6.3 Materials and shape

- **Three materials only:** *Atmosphere* (full-bleed background), *Glass* (cards and chips; §5.2 technique) and *Solid Ion* (primary button). There are no other surfaces.
- **Radii:** cards 32dp, chips and buttons fully rounded (pill), hero device n/a. Large radii read as automotive and premium (R2 "much more round").
- **Borders:** a 1dp top-lit glass stroke. No drop shadows on UI.
- **Spacing:** 48dp outer gutter in landscape and 32dp in portrait. The hero object may **bleed** past the gutter and off-edge, which is a key full-bleed cue.

### 6.4 Imagery

- **Do: generic phone renders.** Commission or produce in Blender two stylized, unbranded handsets: "Island" (stands in for an iPhone) and "Punch-hole" (stands in for Android). Both are graphite, at a three-quarter angle with a soft rim light, and the **screen area is left as a separate layer** so Compose can draw state onto it (an engine glyph, a pulsing ring). Export as WebP (lossy q≈85 with alpha) at 2 sizes, with a baked contact shadow.
- **Don't: car renders.** CarLink runs in any BYD model and on generic tablets, so a wrong car is worse than no car. Kinex solves this by making users pick their model and then fights the GPU to render it in 3D. CarLink's hero is the *phone*, which is what the user actually brought.
- **Optional later:** a "dash silhouette" line illustration (vector, about 20 paths) for onboarding only.
- **Engine marks:** CarPlay and Android Auto logos are trademarks. Use them only as their brand guidelines allow (as identifying badges), never recolored into the accent. Verify both guidelines before shipping.
- **Icons:** Material Symbols Rounded at weight 300, 44/36/24dp sizes (the AAOS icon sizes).

### 6.5 Motion

| Moment | Motion | Duration / easing |
|---|---|---|
| Phone detected | The phone render slides in from the transport side (USB = from bottom; wireless = from the light source) and scales 0.92 → 1, while the glow fades in | 450 ms, emphasized decelerate `(0.05, 0.7, 0.1, 1)` |
| Identified → engine | The screen layer cross-fades to the engine glyph; the headline morphs ("iPhone found" → "Starting CarPlay") | 250 ms cross-fade |
| Connecting | A beam travels from the phone to a car-edge mark in a loop; the active step on the progress line pulses its opacity | 1.6 s loop, linear; stops at "Starting picture" |
| Live | The glow shifts from Ion to Live green, then the screen hands off to projection with a 200 ms fade to `bgDeep` | 300 ms + 200 ms |
| Error | No shake. The glow dims to 30%, the error card rises 24dp | 300 ms standard |
| Theme change | Whole-screen cross-fade, debounced 2 s (kept from DESIGN_SPEC) | 300 ms |

Respect the system animator duration scale. If it is 0, swap to cross-fades only. On the Lite tier, loops are replaced by static states.

---

## 7. Home and the zero-config connecting experience

### 7.1 Principles

1. **No setup wizard.** First launch lands on Home in *Ready*. Permissions (Bluetooth, nearby devices, location for Wi-Fi SSID) are requested by **one inline glass card**, "Allow CarLink to find your phone · Allow", which opens the system dialogs in sequence. Phone-side steps (enable wireless CarPlay, AA settings) appear only as contextual help when a step fails (DESIGN_SPEC error catalog).
2. **Auto everything.** Engine (by phone type), transport (same Wi-Fi → hotspot/Direct → USB) and picture (per head-unit probe) are all chosen automatically, and the choice is *shown* in a chip, never asked about.
3. **One primary action per state**, Ion-filled, 96dp tall, on the driver side (follows Driving side).
4. **Hand off and get out of the way.** When the stream is live, projection is full screen. Home is only seen again when the user comes back to it, and then it offers "Return to CarPlay".

### 7.2 Layout (landscape 1440×810dp)

```
┌──────────────────────────────────────────────────────────────────────────────┐
│  CarLink                                              (gear) Settings        │ ← 64dp top bar, transparent
│                                                                              │
│   READY                          ░░░░ atmosphere light ░░░░                  │
│   Plug in or bring                         ┌────────┐                        │
│   your phone close                         │ phone  │  ← hero render,        │
│                                            │ render │    ~560dp tall, bleeds │
│   iPhone → CarPlay · Android → Android Auto│  glow  │    off bottom edge     │
│                                            └────────┘                        │
│  ┌─ CONNECTION ──┐ ┌─ NETWORK ───────┐ ┌─ PHONE ─────┐                        │
│  │ Bluetooth on  │ │ Same Wi-Fi ·    │ │ Ayush's     │   ← 3 glass chips      │
│  │ ✓ Ready       │ │ fastest  ●      │ │ iPhone ›    │     (max), 120dp tall  │
│  └───────────────┘ └─────────────────┘ └─────────────┘                        │
└──────────────────────────────────────────────────────────────────────────────┘
```

Left 55%: text column (eyebrow, display headline, body). Right 45%: hero phone. Chips sit along the bottom-left. In portrait (810×1440) the hero is on top (~50% of the height, bleeding off the top edge), then the headline, then the chips stacked 2+1, with the primary action at the bottom. DiLink ⅓ split: the headline and one chip, with the hero shrunk to a 200dp glyph.

### 7.3 States (copy is final English)

| State | Eyebrow | Headline | Hero | Chips | Primary action |
|---|---|---|---|---|---|
| Ready, nothing known | READY | Plug in or bring your phone close | Generic phone, dim glow | Connection · Network | — (none; nothing to do) |
| Ready, known phone in range | READY | Connect Ayush's iPhone? | Island render | Connection · Network · Phone | **Connect** (auto-fires after 3 s if Connect automatically is on, with a visible countdown ring on the button) |
| Detected | FOUND | iPhone found | Render slides in | — | Cancel (secondary) |
| Connecting | CONNECTING | Starting CarPlay | Beam animation | A **4-step progress line** replaces the chips: Found · Identified · Linked (USB access / Wi-Fi) · Picture, with elapsed time in `tnum` | Cancel |
| Live (user returned to Home) | CONNECTED | CarPlay is running | Live green glow; the phone screen layer shows the engine glyph | Network (quality) · Phone · Time connected | **Return to CarPlay** |
| Error | Same eyebrow as the failed step | Error title (catalog) | Glow dimmed | The failing step marked in `fault` | The error's single recovery (catalog), plus "Use a cable" secondary |
| Permission missing | SETUP | One quick permission | Generic phone | Permission card | **Allow** |

### 7.4 Shared Wi-Fi (the best-case path)

- **Detection.** If the head unit is connected to Wi-Fi as a client, CarLink reads its SSID/BSSID. When a phone is seen over Bluetooth, CarLink checks for it on the LAN through mDNS / the engine's discovery. If found, the transport is **Same Wi-Fi**.
- **Presentation.** The Network chip shows "Same Wi-Fi · {SSID} · fastest" with a green dot. No hotspot UI appears at all. When not on a shared network, the chip shows "Car Wi-Fi" (hotspot / Direct) neutrally. If wireless isn't possible, it shows "Cable" with a one-line hint.
- **Bluetooth stays the doorbell.** Current Android Auto needs Bluetooth (or Headunit Server) to *start* wireless sessions ([wireless-helper wiki](https://github.com/andreknieriem/wireless-helper/wiki)), so the chip must never imply that Wi-Fi alone suffices. If Bluetooth is off, the Connection chip shows the fix.
- **Settings.** Connection method stays at "Auto" for beginners. Advanced adds "Prefer: Same Wi-Fi / Car Wi-Fi" and the Wi-Fi band.
- Whether CarPlay can run over an infrastructure LAN rather than the accessory's own access point depends on the CarPlay engine implementation. Verify this with the engine owners before promising it in copy.

---

## 8. Settings information architecture

### 8.1 Shape

- **Entry:** the gear on Home opens Settings as a full screen over the same atmosphere (continuity). There are no separate white pages.
- **Landscape:** a left rail of 6 large glass category tiles with icon and name, ~120dp each. Content on the right: glass group cards, rows of ≥96dp. **Portrait:** category tiles in a 2-column grid, then drill-in.
- **Root header:** "Settings", then a **Show advanced** switch row (parked-only), then a "Changed settings (n) · Review" banner when there are any.

### 8.2 Categories and Essentials contents

| Category | Essentials (beginner sees only these) | Added by Show advanced | Lab |
|---|---|---|---|
| **Phones** | Phone list (rename, forget) · Preferred phone · Connect automatically | Per-phone connection method · Per-phone engine override | — |
| **Connection** | Start with the car · Connection method (Auto / Wireless only / USB only) | Prefer same Wi-Fi vs car Wi-Fi · Wi-Fi band · Hotspot automation [ADB] · USB default-handler helper | Wi-Fi version exchange · Static BSSID · libusb |
| **Display** | Interface size (preset + live preview) · Picture (Auto/Sharpest/Smoothest/Compatibility) · Projection theme · Driving side | Video decoder · Safe area editor · Custom DPI (AA) · HEVC (CarPlay) · Full screen · Visual effects (Auto/Full/Lite) | View mode · Pixel aspect · FPS overlay |
| **Sound & voice** | Sound through · Microphone · Voice button | Volume offset · Ducking · Separate streams · Echo cancellation · Test tone | Audio queue · AAC · Latency multiplier · Sample rate |
| **Car** | — (empty in Essentials; hidden until Advanced, or shown with a "Steering-wheel keys" row if keys are detected) | Steering-wheel keys · Share car location · Cluster/HUD guidance [ADB] | Fake speed |
| **General** | Theme of CarLink (Follow car) · Language | Backup / restore · Reset all · Diagnostics | Log level · Fault injection |
| **About** | Version (7-tap → Lab), licenses | — | — |

Essentials total: 13 controls (Preferred phone, Connect automatically, Start with the car, Connection method, Interface size, Picture, Projection theme, Driving side, Sound through, Microphone, Voice button, Theme, Language). Projection theme defaults to "Same as CarLink", so a beginner effectively decides 12 things or fewer. Hard cap: 14.

### 8.3 Row anatomy

Title (28sp Medium), description (24sp `textSecondary`, one line, plain consequence language), value on the trailing side, changed-dot, engine glyph when engine-only, and an `ADVANCED` tag when in that tier. Choice settings open a **glass bottom sheet with large preset tiles and a live preview** (for Interface size: a miniature CarPlay/AA mock rendered at the chosen scale), never a radio list of numbers.

---

## 9. Reference screens to emulate (eight)

| # | Screen | What to take | What to avoid | Link |
|---|---|---|---|---|
| 1 | **Tesla Model 3/Y main screen (v11/v12)**: car visualization left, map right | The full-bleed hero object; achromatic and one blue; no card chrome | Burying frequent actions (the v11 backlash) | [Not a Tesla App v11 first look](https://www.notateslaapp.com/news/663/first-look-at-tesla-s-new-v11-ui) |
| 2 | **Rivian R1 home and drive modes** (Behance case study) | Day/night temperature shift; numerals as heroes; 3D-artist-grade imagery | — | [Behance: Rivian Vehicle OS](https://www.behance.net/gallery/166386511/Rivian-Vehicle-OS), [Pentagram](https://www.pentagram.com/work/rivian/story) |
| 3 | **Rivian R2 "floating island" status** | Status as one rounded floating element, which is CarLink's chip row | — | [R2 capture thread](https://www.rivianforums.com/future-r1-software-updates-preview-captured-from-r2/) |
| 4 | **Lucid Air Glass Cockpit** | Sparse, warm gradient backgrounds as the atmosphere layer | — | [Jalopnik](https://www.jalopnik.com/lucid-airs-user-interface-has-four-screens-some-button-1846975789/) |
| 5 | **NIO Aspen 3.0 home** | A black main tone with a high-saturation accent; half-screen cards; Mini mode | Real-time 3D scenes | [Glen Gao on Medium](https://medium.com/@glengao/nio-aspen-3-0-uiux-design-341b38df3656) |
| 6 | **MBUX zero layer** | The Home surfaces the *one* contextual action ("Connect Ayush's iPhone?") with no menu | AI suggestion clutter | [Mercedes-Benz Group](https://group.mercedes-benz.com/innovation/digitalisation/connectivity/mbux-hyperscreen.html) |
| 7 | **Kinex status tab on BYD** | Proof of what DiLink can carry; quality tiers; calm layout | Full 3D car | [Kinex](https://kinex.lexwah.com/) |
| 8 | **Conceptzilla "Car UI Dashboard"** (Dribbble) | Dark hero card + side panel composition for landscape Home | — | [Dribbble](https://dribbble.com/shots/18021625-Car-UI-Dashboard) |

Bonus pattern without a URL: Apple's AirPods proximity setup card (a big device render, one sentence, one button) for the "Ready, known phone" state.

---

## 10. Changes against DESIGN_SPEC.md

| DESIGN_SPEC rule | This document | Why |
|---|---|---|
| Roboto only, 400/500 only, no bundled face | Bundle Inter (≤400 KB); 600 allowed at ≥48sp | The user asked for bold type and premium; neo-grotesks are the reference standard; reading sizes still follow AAOS |
| No ALL CAPS / eyebrows | Tracked-caps eyebrow allowed on chips only, ≤2 words | A premium-dashboard cue; kept off any sentence |
| M3 surfaceContainer tonal cards | Atmosphere + faux-glass + Ion | The previous look was rejected as "generic Material 3" |
| Link Cyan `#5CCFE6`, `onSurface #E8EDF3` | Ion `#5BD6F0`, `textPrimary #EEF2F6` | A small shift; the contrast rules are kept |
| Settings: shared first, then CarPlay and Android Auto sections, plus an engine filter | Essentials / Advanced / Lab tiers; unified "intent" settings; engine overrides only in Advanced | The user wants one settings set for both engines and beginner safety |
| A separate "Advanced" category | Advanced rows inline in their own category, behind one global switch | Findability (the Plex lesson) without exposure (the UniFi lesson) |

Everything else in DESIGN_SPEC stays: canvas scaling (§5.0), 76dp/96dp targets, the error catalog, stage timeouts, the parked-only gate, RTL/CJK rules and the acceptance checklist.

---

## 11. Open questions to verify

1. Inter's subset size after `pyftsubset` for Latin + Cyrillic + Greek + Vietnamese (target ≤400 KB).
2. CarPlay over an infrastructure LAN (versus the accessory's own AP) in CarLink's engine; Android Auto's Bluetooth-trigger requirement on the current AA version.
3. CarPlay and Android Auto logo usage rules for third-party receivers.
4. The real GPU in the target DiLink units, measured on device (renderer string and a frame-time trace of the faux-glass Home). Build the Lite tier before you need it.
5. The ISO 15008 character-size floor (noted as unverified in DESIGN_SPEC).
