# Home v2: research and redesign notes

October 2026. This builds on `RESEARCH_PREMIUM_UX.md` (§1, §6, §7) and `DESIGN_SPEC.md` §6.1. Where the
two disagree, this file wins for Home.

The trigger: the user looked at Home on their Redmi Pad Pro 5G (2560×1600 px, 281 dpi, so about
1458×911 dp landscape). They said the look "is not good at all" and the layout on the tablet is off.

## 1. What the references do (new findings)

| Reference | What matters for Home | Source |
|---|---|---|
| Rivian (RivianOS 2, 2025–26) | The R1 is moving to a **vertical status bar** that shows key vehicle state and opens the frequent controls. The 2025 visuals moved from glossy photo-realism to **flat, high-contrast, cel-shaded** rendering: "no fades or gradients, flatter colors, simple shapes". Rivian chose clarity over rendered realism. | [The Drive](https://www.thedrive.com/news/rivians-new-cel-shaded-infotainment-update-is-cool-and-mandatory), [Rivian: RivianOS 2](https://rivian.com/stories/new-software-rivianos-2), [Unreal Engine spotlight](https://www.unrealengine.com/spotlights/rivian-brings-adventurous-spirit-to-new-display-ui-powered-by-unreal-engine) |
| Polestar 4 (Android Automotive) | The driver display has a **calm mode** that shows only speed and battery. Polestar's UX researcher describes the aim as "the right information at the right moment, content on demand", with a strong belief in "providing something that is beautiful". | [Polestar 4 infotainment](https://www.polestar.com/uk/polestar-4/infotainment/) |
| Lucid Air (Glass Cockpit) | "Spartan, warm and easy on the eyes, with readable fonts atop **sparse gradient backgrounds**". The panel is split into **grouped zones**, and the instrument zone is "clutter-free". | [Jalopnik](https://jalopnik.com/lucid-airs-user-interface-has-four-screens-some-button-1846975789) |
| Tesla | Status icons were moved **back to the top status bar**. Trip, odometer and tyre cards came back to the corner after owners objected to burying them. Lesson: people want glanceable status *on the stage*, grouped in one place. | [Not a Tesla App: cards](https://www.notateslaapp.com/news/1101/tesla-brings-back-ui-cards-to-model-3-and-y-with-holiday-update/1000), [Not a Tesla App: 2022.28](https://www.notateslaapp.com/news/978/all-the-undocumented-changes-found-in-tesla-update-2022-28) |
| Android Automotive design system | Minimum type is 24dp; sub-24 sizes only for tertiary status-bar content. Use medium weight sparingly and avoid bold at reading sizes. The scale steps in 4dp increments. | [AAOS typography](https://developers.google.com/cars/design/automotive-os/design-system/typography) |
| Apple, wireless CarPlay systems (WWDC 2017 session 717) | The idle head unit should tell the driver **what to do on the phone**: show the car's name and offer USB as the fallback. | [WWDC17 717](https://developer.apple.com/videos/play/wwdc2017/717) |

Dribbble, Behance and Mobbin still block automated fetching (HTTP 403), as noted in the earlier research.
The recurring pattern in the in-car concepts listed in `RESEARCH_PREMIUM_UX.md` §1.3 is the same:
a dark stage, **one circular or gauge-like focal object**, a single row of status or tiles docked to an
edge, and no decorative devices.

## 2. Synthesis: what makes it premium (and what we got wrong)

1. **Instrument, not illustration.** Premium cabins show state as *instruments*: dials, rings, arcs
   and ticks drawn with hairlines (Lucid's cluster, Rivian's flat redraw, Polestar's calm mode).
   The v1 hero was a large literal phone mock-up, about 690dp tall on the tablet. A generic gadget
   render is the most "stock template" element on a screen and the first thing that reads as amateur.
2. **Zones, docked to edges.** Tesla, Rivian and Lucid group status into one **bar or zone pinned to an
   edge**. They never scatter separate floating chips. v1's three chips floated mid-column, with uneven
   widths and a lot of empty canvas under them.
3. **Calm hierarchy: one state word, one sentence, one action.** State is shown by a small lit
   indicator: a dot plus a short tracked label, like a cabin tell-tale. The colour goes there, not into
   big fills.
4. **Scale to the viewing distance, not the device class.** A tablet mounted in a dash is read from
   arm's length, like a head unit. v1 used phone Material sizes on the tablet (a 56sp headline, 17sp
   body on a 1458dp-wide canvas), so the copy looked small next to the giant phone. The layout read as
   "off" because the type was phone-sized and the illustration was dash-sized.
5. **Depth from light, with restraint.** Keep a single key light, now **behind the focal object**, plus
   a floor vignette so the stage has a ground. Keep strokes 1dp and top-lit. Still no blur and no shadows
   (head-unit GPUs).
6. **Motion only where something is happening.** The connecting ring sweeps; idle states are still.
   The v1 phone floated forever, which is decoration and not information.

## 3. Critique of Home v1 (from `docs/screenshots/home`, before this change)

- **Phone mock-up dominates.** On car landscape it takes about 40% of the width at roughly 75% of the
  height. The engine name and glyph sit tiny in its middle, and its dark "screen" fights the copy for
  attention. In light theme it is a black slab on a pale canvas.
- **Tablet sizing.** On the HANDHELD profile at 1458×911dp, the copy column used phone type and the
  phone mock-up filled 86% of the height. Empty bands appeared above and below the copy.
- **Status chips.** There were three separate glass boxes with uneven widths ("On" next to "Home Wi-Fi ·
  fastest" wrapping to two lines). Their tracked eyebrows competed with the main eyebrow, and nothing
  showed that they could be tapped.
- **Eyebrow.** Bare tracked caps ("READY") looked like a form label, not a status indicator.
- **Flat stage.** The light source sat top-start, away from the hero, and nothing grounded the bottom of
  the screen, so the content seemed to float.
- **Body measure.** On car landscape, line lengths ran to about 1000dp and orphaned one word
  ("too.", "seconds.").

## 4. Direction for v2 ("Instrument")

- **Link dial** replaces the phone. It is a gauge-like instrument: a fine tick bezel, a hairline orbit,
  a progress track and a lit core disc. The core shows the state glyph (Cast mark, phone kind, check or
  fault) and the engine name. Connecting fills the track step by step, like a charge arc. Connected is a
  complete green ring. Failed is a dimmed red ring. It is drawn in code, works at any aspect ratio, and
  costs a handful of strokes.
- **Status bar docked at the bottom.** Bluetooth, Network and Phone sit in **one** glass panel split by
  hairlines into three cells. Each cell has an icon tile, a label with a status dot, a value and a
  chevron (the tap affordance). Compact windows get the same panel as rows. Short windows keep the
  one-line pill.
- **State tell-tale.** The eyebrow becomes a small pill with a lit dot, tinted by state (ion, live,
  fault, caution, neutral).
- **Stage light.** The key light and state glow sit behind the dial. A floor vignette grounds the
  status bar.
- **Large-handheld scale.** On the HANDHELD profile, Home's content scales by
  `min(longSide / 1100dp, shortSide / 690dp)`, clamped to 1–1.35×. The Redmi Pad in landscape gets
  about 1.32×, so it reads like the 1440×810dp car canvas. An 800×1280dp tablet gets 1.16×, and
  phones stay at 1×. Sheets and dialogs are not scaled.
- **Stage layout.** The status bar is docked to the bottom edge. The copy and the dial are centred in the
  room above it: the dial sits beside the copy in landscape and above it in portrait. The old
  `FitColumn` fallbacks are kept (panel → strip → none; regular → dense copy; scroll as a last resort).
  Dense copy shows the tell-tale without its pill so short windows such as the BYD ⅓ split keep the
  strip.
- **Palette is unchanged.** Graphite & Ion and the Cast logo stay. The research showed restraint, not
  new colour, is what's missing. Ion stays reserved for actions and the active arc. The primary button
  gains a subtle top-lit sheen so it reads as a lit control rather than a flat Material pill.
