# Releasing CarLink

CarLink releases are built, signed and uploaded **on the maintainer's computer**, never by GitHub
Actions. Two things exist only on that computer: the release signing key and the CarPlay
accessory identity (`offline-mfi/`). The project uses no GitHub Actions at all: tests run locally
(`dev.sh test`, and `scripts/release.sh` runs them before every release).

Installed copies of CarLink find new versions through GitHub's "latest release" API and install
them only after checking them (see "What the app checks" below). A release is therefore
published exactly as `scripts/release.sh` produces it.

## Versions

- `versionName` in `app/build.gradle.kts` is `major.minor.patch` (for example `1.2.3`). It is the
  only number you change.
- `versionCode` is computed from it: `major * 10000 + minor * 100 + patch` (1.0.0 → 10000,
  1.2.3 → 10203). Minor and patch must stay below 100; the build fails otherwise.
- The Git tag and GitHub release are `vX.Y.Z`; the assets are `CarLink-X.Y.Z.apk` and
  `CarLink-X.Y.Z.apk.sha256`. The app ignores releases with other tags or asset names, drafts
  and pre-releases.
- `CHANGELOG.md` gets a `## [X.Y.Z] - YYYY-MM-DD` section, written for drivers. Its text becomes
  the release notes that the update popup shows (unless you pass `--notes-file`).

## The release page

The GitHub release page is the changelog text, then a line that is exactly `<!-- more -->`,
then `scripts/release-notes-footer.md` with its placeholders filled in (`{{TAG}}`, `{{APK}}`,
`{{SIZE}}`, `{{SHA256}}`, `{{CERT}}`): screenshots, download and install steps, the checksum
and signing certificate, the CarPlay notice, and source and license. `scripts/release.sh`
refuses to publish a page that still contains a `{{placeholder}}`.

- **The in-app popup shows only the text above `<!-- more -->`** (`ReleaseInfo.MORE_MARKER`), so
  keep everything a driver should read in the popup above it, and the install material below.
- If your notes file already contains a `<!-- more -->` line, the script adds no footer: you
  then write the whole page yourself.
- Keep the footer consistent with the README: the same CarPlay notice, the same "Android 7.1 or
  newer", the same certificate fingerprint and the same Diagnostics path. When you change one,
  change the other. A published page can be edited on GitHub (or with `gh release edit`)
  without touching its assets.

## The signing key

| File | Holds |
|---|---|
| `~/.carlink-private/release/carlink-release.jks` | the release keystore |
| `~/.carlink-private/release/signing.env` | `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` (read by `app/build.gradle.kts`) |
| `~/.carlink-private/offline-mfi/` | the CarPlay accessory identity (`identity.pk8`, `certificate.p7b`) |

The certificate's SHA-256 is
`03:74:68:CA:5B:EB:70:EA:5F:68:54:33:29:B0:33:68:24:7D:FB:39:3D:3F:62:6A:6B:47:2D:10:E5:65:7D:21`.
It is pinned in `app/src/main/java/com/carlink/app/update/UpdateVerifier.kt` (`ReleaseKey`), and
`scripts/release.sh` reads it from there.

**Back the key up.** Copy `~/.carlink-private/` (keystore, `signing.env` and `offline-mfi/`) to
at least two places you control and that are not this computer, for example an encrypted USB
stick and an encrypted password-manager attachment. Keep the passwords with it.

**If the key is lost, nobody can update CarLink any more.** Android installs an update only when
it is signed with the same key as the installed app, and the in-app updater refuses any other
key. A new key would mean a new app: every user would have to uninstall CarLink (losing its
settings and phone pairings) and install it again by hand. If the key leaks, anyone can sign an
"update" that installed copies would accept; treat it like a password.

Never commit any of these files. `.gitignore` blocks `*.jks`, `*.keystore`, `*.pk8`, `*.p7b`,
`*.apk` and `dist/`, but check `git status` anyway.

## Release steps

1. On `main`, with everything committed and pushed: set `versionName` in
   `app/build.gradle.kts` and add the `CHANGELOG.md` section. Commit and push.
2. Build and check, without publishing:

   ```sh
   scripts/release.sh 1.0.0
   ```

   It stops with `release: ERROR: …` at the first problem. In order it checks that:
   1. the tree is clean, the branch is `main`, and `versionName` is `1.0.0`;
   2. `~/.carlink-private/release/signing.env` exists and sets all four signing variables (it is
      sourced, never printed);
   3. `~/.carlink-private/offline-mfi/identity.pk8` exists (`DIPLAY_AUTH_ASSETS_DIR` is set to
      `~/.carlink-private`, so the APK carries CarPlay's identity);
   4. `dev.sh test` passes (resource collisions and all unit-test suites);
   5. `:app:assembleRelease` builds (R8 and lint vital);
   6. `apksigner verify --print-certs` shows exactly one signer, the pinned certificate; the APK
      contains `assets/offline-mfi/`; it is not debuggable; and its package, versionCode and
      versionName are right;
   7. it copies the APK to `dist/CarLink-1.0.0.apk` and writes `dist/CarLink-1.0.0.apk.sha256`.
3. Install `dist/CarLink-1.0.0.apk` on the test tablet and check CarPlay and Android Auto over
   USB and wirelessly (the `carlink-device` skill). The release package `com.carlink.app` installs
   next to the debug build; stop or uninstall one of them for a clean test.
4. Publish:

   ```sh
   scripts/release.sh 1.0.0 --publish                 # notes from CHANGELOG.md
   scripts/release.sh 1.0.0 --notes-file notes.md --publish
   ```

   This repeats every check and the build, then also requires that `main` equals `origin/main`
   (the release's source must be public under the AGPL), that the tag does not exist yet, and
   that `gh` is logged in. It then creates the annotated tag `v1.0.0`, pushes it, and runs
   `gh release create v1.0.0 dist/CarLink-1.0.0.apk dist/CarLink-1.0.0.apk.sha256 --title
   "CarLink 1.0.0" --notes-file … --latest` (a full release, not a pre-release, marked latest),
   with the notes followed by `<!-- more -->` and the footer (see "The release page").
   Installed copies see it within six hours.

A rehearsal on another branch: `CARLINK_RELEASE_ALLOW_BRANCH=1 scripts/release.sh 1.0.0`. It runs
every check except the branch and refuses `--publish`.

If publishing fails after the tag was pushed, finish the release in GitHub's web page with the two
files from `dist/`, or delete the tag (`git push origin :refs/tags/v1.0.0` and `git tag -d v1.0.0`)
and run the script again. Never replace the assets of a published release: copies that already
downloaded it would see a checksum mismatch. Publish a new patch version instead.

## What the app checks

The updater lives in `app/src/main/java/com/carlink/app/update/` (logic, no UI) and
`ui/update/UpdateDialog.kt` (the popup).

- Only the release package `com.carlink.app`, signed with the pinned key, checks for updates.
  Debug builds (`com.carlink.app.hudtest`) and builds signed with any other key never do.
- `GET https://api.github.com/repos/F-e-n-y-x/CarLink/releases/latest`, at most every 6 hours
  (Home in front, setup finished, "Automatically check for updates" on, and no projection
  session starting, running or asking to switch), or when the driver taps Check for updates.
  GitHub's rate limits (403/429) are handled quietly.
- A release is offered when its tag `vX.Y.Z` is newer than the installed version and the driver
  has not chosen "Ignore this version" for that tag.
- The APK is downloaded into the app's cache. Before installing, all three must pass, or the file
  is deleted and the popup says why: its SHA-256 equals the `.sha256` asset; PackageManager reads
  it as `com.carlink.app` with a higher versionCode; its only signer is the pinned certificate.
- It is installed with a PackageInstaller session. When Android does not yet allow CarLink to
  install apps, the popup explains it and opens "Install unknown apps" for CarLink; the install
  continues when CarLink comes back to the front.
