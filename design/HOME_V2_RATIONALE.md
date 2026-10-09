# Home v2 "Instrument": design rationale

> **Status: current** for Home, Settings, Diagnostics and About.

October 2026. This records what was built from `RESEARCH_HOME_V2.md` and why. Where a decision
differs from the research, the reason is given. Screens: `docs/screenshots/*/*_tablet_*.png`
(the user's Redmi Pad Pro) first, then the car and phone configs.

## Home

| Decision | Why (research) |
|---|---|
| **Link dial** replaces the phone mock-up (`premium/LinkDial.kt`). It has a tick bezel (5° fine ticks, 45° majors), a hairline orbit, a 270° gauge track open at the bottom, and a lit core disc. | §2.1 "Instrument, not illustration": premium cabins draw state as dials and arcs (Lucid cluster, Rivian's flat redraw, Polestar calm mode). The gadget render was the most "stock template" element. Everything is drawn with strokes and two gradients, with no bitmaps, blur or shadows (PREMIUM_UX §5.1, head-unit GPUs). |
| The dial reads the state. **Ready with a phone**: a quiet, armed full ring. **Connecting**: four step segments; finished steps are lit, and the current step charges its slot (a still image shows it half charged). **Connected**: a full Live ring with a running timer in tabular figures. **Failed**: a dimmed Fault ring with no bloom. **Not answering**: held on step 1 in Caution, nothing charges. Lit ticks follow the progress. | §4 "Connecting fills the track step by step, like a charge arc"; §2.6 motion only where something is happening: idle states are still. The timer makes Connected an instrument reading rather than a label. |
| **Honest steps**: Reaching phone → Phone answered → Linking → Picture (`LinkState.step` 1–4). Step 1 is never shown as done before step 2. | The user asked whether the progress was fake when the iPhone's Bluetooth was off. Each name now says what has actually happened. |
| **Status bar docked to the bottom edge**: one glass panel, split by hairlines into Bluetooth, Network and Phone. Each cell has an icon tile, a tracked label with a status dot, the value and a chevron. Compact windows stack the cells as rows; short windows fall back to the one-line pill. | §2.2 "Zones, docked to edges" (Tesla, Rivian, Lucid group status into one bar). The chevron fixes v1's missing tap affordance. Cell widths follow their content, so "On" never takes room a phone name needs. |
| **Tell-tale** instead of a bare eyebrow: a lit dot with a halo and a short tracked label on a faint tinted pill (Ion, Live, Fault, Caution, neutral). Dense layouts drop the pill. | §2.3: the colour goes on a small lit indicator, not on big fills. |
| **Stage light** behind the dial: the Atmosphere's key light and the state glow are centred on the dial's measured position. A **floor vignette** grounds the status bar. | §2.5 "Depth from light, with restraint": one key light behind the focal object, plus a ground. The glow belongs to the Atmosphere so no scroll container can clip it. |
| **Lit primary button**: a top-lit sheen, a hairline highlight, and a darker pressed state. | §4: "reads as a lit control rather than a flat Material pill". |
| **Type and measure**: three title sizes by length; balanced line breaking (`LineBreak.Heading`) for titles; body text capped at about 60 characters (760dp car, 520dp handheld). | §3 found 1000dp lines and orphaned words on the car canvas. AAOS: sizes step in 4dp, medium weight used sparingly. |
| **Layout**: landscape puts the copy on the start 55% and centres the dial in the rest. Portrait stacks the dial above centred copy. The status bar is always on the bottom edge. | §4 "Stage layout". |
| **Nothing clipped or cut short** (`HomeStage`): the richest arrangement that fits wins. Status falls back panel → strip → none, and copy falls back regular → dense. The dial takes the room that is left (up to 560dp car / 520dp handheld) and steps aside below 300dp / 176dp. When the dial is gone, the copy shows the step line itself. The stage scrolls only at extreme font scales. | FitColumn's rule, kept from v1. |
| **"Your phone isn't answering"** (wireless, no answer for 10 s): Caution, what to check on the phone, Try again and Cancel. | Home must stop looking busy when nothing is happening. The CarPlay waiting window renders the same `HomeContent`, so it shows this too. |

## Cable or wireless, everywhere

The user asked that the app always tell cable (USB) connections apart from wireless ones
(Bluetooth + Wi-Fi). The rule is the same on every surface, with a glyph and short words: a USB
plug with "Cable" / "USB", or the wireless path's glyph with "Wireless · Wi-Fi Direct"
(`premium/Transport.kt`).

- **Home**: while connecting or connected, a transport badge sits under the headline ("Connecting
  by cable", "Connected wirelessly · Same Wi-Fi"). The connected dial repeats it in its core, under
  the engine name. In a USB session the Network cell says "USB cable" and the Phone cell shows the
  session's phone name: a USB session has no Bluetooth address, which used to leave "No phone yet".
- **Phones sheet and switcher list**: each phone says how it can connect now: "Plugged in (USB)"
  when a phone of its kind is attached by USB, otherwise "Wireless". The current session's phone
  shows "Connected · Cable" or "Connected · Wireless · …". A wired session's own phone is not also
  counted as "plugged in" for other phones of its kind.
- **Switcher header**: the transport is a badge instead of a "· USB" suffix.
- **CarPlay's waiting window** builds its phone from the session's name when the address is null,
  so wired CarPlay shows the phone and "USB cable" too.

## Large-handheld scale: app-wide, not Home-only

The research (§4) scaled only Home's stage by `min(long/1100dp, short/690dp)`, clamped to 1–1.35×,
and left sheets unscaled. In the gallery, the Phones and Network sheets then looked miniature over a
1.32× stage, and Settings, Diagnostics and About kept phone-sized type on a 1460dp-wide tablet,
which is the "off" layout the user complained about. The scale now lives in `CarLinkTheme`
(`largeHandheldScale`) and applies to every screen, sheet and dialog on the HANDHELD profile. The
Redmi Pad gets 1.32×, an 800×1280dp tablet gets 1.16×, phones stay at 1×, and the car profile is
unchanged. The window is measured rather than the panel, so a split-screen tablet window shrinks
back towards 1×.

## Shared language (Settings, Diagnostics, About, sheets)

- **One sheet** (`premium/GlassSheet.kt`): graphite, a slim handle, and the same header for
  Settings choices, Phones and Network. Diagnostics and About side sheets use the same graphite.
- **Icon tiles everywhere**: setting rows, preset tiles, the Phones list, Diagnostics checks and its
  summary use the rounded tile from Home's status cells instead of circles, so every list in CarLink
  reads alike.
- **Hairline token** for dividers inside glass. On the day palette, glass gets a faint graphite
  lower edge so white cards keep their outline without shadows.
- **Selection**: the selected Settings category shows a lit Ion bar on its leading edge instead of
  a 2dp Ion outline (PREMIUM_UX §1.2: Ion is reserved for action). Selected presets keep a thinner
  outline.
- **Tell-tales** for live state outside Home as well (the Diagnostics session tag).

## Unresolved

- Measured only in Robolectric renders. On the real tablet, check the dial's stroke weights, the
  noise and floor vignette on its OLED/LCD, and the charge animation (the gallery renders with motion
  off).
- The Robolectric `tablet` config uses 280dpi (1463×914dp); the panel is 281dpi.
- In very short windows (phone landscape with the Wi-Fi offer, BYD ⅓ split with long failure copy)
  the status bar drops out to keep the copy whole. That follows the rule, but a future pass could
  move the strip into the top bar there.
