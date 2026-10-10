# README and documentation: research notes

> **Status: current.** Research behind the October 2026 README rewrite and the `docs/` index.
> Sources read on 2026-10-10 (default branch of each repository on that day).

The goal: one README that tells a driver what CarLink is, whether it suits their stereo and how
to install it, and a `docs/` folder that carries the developer detail.

## Sources

Guidance:

- awesome-readme, <https://github.com/matiassingers/awesome-readme>: the READMEs it praises share
  a logo or banner, badges, screenshots, a one-line description, a feature list, copy-paste
  install steps, contributing links, and license and credits.
- Make a README, <https://www.makeareadme.com/>: name, description, badges, visuals, install
  (with requirements), usage, support, contributing, license, project status. Move long detail
  into separate docs.
- GitHub, "About READMEs",
  <https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/about-readmes>:
  GitHub builds an outline from headings and anchors every heading. It recommends relative links
  and moving long material out of the README.
- GitHub, "Customizing your repository's social media preview",
  <https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/customizing-your-repositorys-social-media-preview>:
  PNG, JPG or GIF under 1 MB. 640×320 is the minimum and 1280×640 looks best. Use a solid
  background. It is uploaded in the repository settings; there is no API for it.
- GitHub, "Basic writing and formatting syntax", and the GitHub changelog (2022-05-19 beta,
  2022-08-15 GA): `<picture>` with `prefers-color-scheme` sources picks a light or dark image.
  Alerts (`[!NOTE]`, `[!WARNING]`, …) should be used sparingly, one or two per page.
- GitHub, "Syntax for issue forms" and "Configuring issue templates": `.github/ISSUE_TEMPLATE/*.yml`
  with `name`, `description` and `body`. `textarea` takes `render: shell` for logs. `config.yml`
  holds `blank_issues_enabled` and `contact_links`.

Android app READMEs:

- Signal-Android (<https://github.com/signalapp/Signal-Android>): short, no screenshots, and a
  plain trademark line for the third-party marks it names.
- NewPipe (<https://github.com/TeamNewPipe/NewPipe>): centred logo, title and tagline, then badges
  and screenshot thumbnails that link to the full images. The install section lists every
  source, explains which builds can update each other (signing keys) and gives the certificate's
  SHA-256 fingerprint.
- AntennaPod (<https://github.com/AntennaPod/AntennaPod>): about 600 words. Badges, one tagline
  and one row of screenshots, then feedback, building and license.
- Organic Maps (<https://github.com/organicmaps/organicmaps>): centred logo, bold tagline,
  screenshots, feature bullets, then links to separate INSTALL, CONTRIBUTING and governance docs.
- Termux (<https://github.com/termux/termux-app>): gives the Android version clearly and warns
  not to mix APKs signed with different keys. Asks for the app's own report text rather than
  screenshots.
- Aegis (<https://github.com/beemdevelopment/Aegis>): two rows of four screenshots at a fixed
  width. A Verification section with `apksigner verify --print-certs` and the expected
  fingerprint.

Car projects:

- DiPlay (<https://github.com/shihabal3amri/DiPlay>): names each tested setup exactly (unit,
  firmware, phone, iOS). Puts the "not an Apple-certified product" notice near the top.
  Troubleshooting asks for the app's diagnostic report and says not to post Wi-Fi passwords.
- DiAuto (<https://github.com/shihabal3amri/DiAuto>): states what was tested and that other units
  are not verified. One trademark sentence.
- open-headunit (<https://github.com/andreknieriem/open-headunit>): a prominent warning when a
  phone-side update breaks something; setup and troubleshooting live in a wiki.
- aa-proxy-rs (<https://github.com/manio/aa-proxy-rs>): a numbered first-connection walkthrough,
  tested boards as a list, and GitHub alerts for its warnings.
- WirelessAndroidAutoDongle (<https://github.com/nisargjhaveri/WirelessAndroidAutoDongle>):
  troubleshooting by symptom, then cause, then fix, plus a section on collecting logs. It says
  plainly that only a few head units were tried and asks for reports.

## Patterns adopted, and why

1. **Centred header**: the logo through `<picture>` (a light-ink and a dark-ink SVG of the Cast
   mark), the name, a one-line tagline and four shields.io badges (release, downloads, license,
   Android version). No CI badge, because the project uses no GitHub Actions. This follows
   NewPipe and Organic Maps; `<picture>` keeps the mark readable in both GitHub themes.
2. **Screenshots as fixed-width thumbnails in a table, each linking to the full image**, two per
   row, with one row at car-display sizes. Aegis and NewPipe do this. Full-size landscape images
   (open-headunit) push the install steps off the screen.
3. **Install names one official source and shows how to verify it**: the SHA-256 file and the
   signing certificate's fingerprint, with an `apksigner` command (Aegis, NewPipe). It also says
   that builds signed with other keys cannot update the release (Termux), because CarLink is in
   no store and repackaged APKs are the main risk.
4. **An honest tested / not-tested section** naming the exact setup (DiPlay, DiAuto,
   WirelessAndroidAutoDongle). It also says that the 1.0.0 release build has not yet been tested
   on a device.
5. **One `[!WARNING]` alert, for the CarPlay notice only.** Its wording stays close to DiPlay's
   own. GitHub recommends one or two alerts at most; it is the only risk a driver must know
   before installing.
6. **Troubleshooting by symptom**, using the wording of the app's own messages, plus how to send
   Diagnostics › Share log and a reminder not to post Wi-Fi passwords (DiPlay, Termux,
   WirelessAndroidAutoDongle). The issue forms ask for the same fields.
7. **README of about 1,000-1,300 words; developer detail in `docs/`** behind an index (Make a
   README, GitHub, Organic Maps). The architecture, build and development sections moved to
   `docs/ARCHITECTURE.md` and `docs/BUILDING.md` unchanged in substance.
8. **Credits, license and trademarks at the end**, keeping the upstream notices both engines
   ask for.

Not adopted: store badges (CarLink is only on GitHub Releases), donation and sponsor sections,
an anchor navigation bar (GitHub's outline button already gives one), and animated GIFs (the
gallery is rendered without a device, so there is nothing real to record yet).
