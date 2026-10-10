#!/usr/bin/env bash
# Builds, checks and (optionally) publishes a CarLink release from THIS computer.
# Release APKs are never built by GitHub Actions: the signing key and the CarPlay accessory
# identity live only in ~/.carlink-private. See docs/RELEASING.md.
#
#   scripts/release.sh 1.0.0                         build and check; stops at dist/
#   scripts/release.sh 1.0.0 --publish               ... then tag v1.0.0, push the tag and create the
#                                                    GitHub release (notes from CHANGELOG.md)
#   scripts/release.sh 1.0.0 --notes-file F --publish   ... with the release notes in F
#
# Every check stops the script with "release: ERROR: ...". Never run it in CI.
# Rehearsal on another branch (never publishes): CARLINK_RELEASE_ALLOW_BRANCH=1 scripts/release.sh 1.0.0
set -euo pipefail

die() { echo "release: ERROR: $*" >&2; exit 1; }
step() { echo; echo "==> $*"; }
usage() { sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }

[ $# -ge 1 ] || usage
VERSION="$1"; shift
NOTES_FILE=""
PUBLISH=0
while [ $# -gt 0 ]; do
  case "$1" in
    --notes-file) [ $# -ge 2 ] || die "--notes-file needs a file"; NOTES_FILE="$2"; shift 2 ;;
    --publish) PUBLISH=1; shift ;;
    -h|--help) usage ;;
    *) die "unknown argument: $1" ;;
  esac
done
[ -n "${CI:-}" ] && die "this script is for a release computer, not CI"
[[ "$VERSION" =~ ^[0-9]{1,4}\.[0-9]{1,2}\.[0-9]{1,2}$ ]] || die "version must be X.Y.Z (minor and patch below 100), got '$VERSION'"
TAG="v$VERSION"
APK_NAME="CarLink-$VERSION.apk"

ROOT="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)" || die "not inside the CarLink repository"
cd "$ROOT"
[ -f app/build.gradle.kts ] && [ -f settings.gradle.kts ] || die "$ROOT is not the CarLink repository"
if [ -n "$NOTES_FILE" ]; then
  NOTES_FILE="$(cd "$(dirname "$NOTES_FILE")" && pwd)/$(basename "$NOTES_FILE")" || die "notes file not found"
fi

# ---- 1. Clean tree on main, version matches ------------------------------------------------
step "1/8 Repository state"
BRANCH="$(git rev-parse --abbrev-ref HEAD)"
if [ "$BRANCH" != main ]; then
  if [ "${CARLINK_RELEASE_ALLOW_BRANCH:-}" = 1 ] && [ "$PUBLISH" = 0 ]; then
    echo "rehearsal on branch '$BRANCH' (CARLINK_RELEASE_ALLOW_BRANCH=1); --publish is not allowed"
  else
    die "releases are made from main; you are on '$BRANCH'"
  fi
fi
[ -z "$(git status --porcelain --untracked-files=normal)" ] || die "the working tree is not clean (commit or stash first):
$(git status --short)"
GRADLE_VERSION="$(sed -n 's/^[[:space:]]*versionName = "\([^"]*\)".*/\1/p' app/build.gradle.kts | head -n1)"
[ "$GRADLE_VERSION" = "$VERSION" ] || die "app/build.gradle.kts has versionName \"$GRADLE_VERSION\", not \"$VERSION\""
IFS=. read -r MAJOR MINOR PATCH <<<"$VERSION"
VERSION_CODE=$((10#$MAJOR * 10000 + 10#$MINOR * 100 + 10#$PATCH))
PIN="$(sed -n 's/.*const val CERT_SHA256 = "\([0-9A-Fa-f:]*\)".*/\1/p' app/src/main/java/com/carlink/app/update/UpdateVerifier.kt)"
[ ${#PIN} -eq 95 ] || die "cannot read the pinned release certificate from update/UpdateVerifier.kt"
PIN_NORM="$(echo "$PIN" | tr -d ':' | tr 'A-F' 'a-f')"
git check-ignore -q dist/ || die "dist/ must be in .gitignore"
if [ "$PUBLISH" = 1 ]; then
  git rev-parse -q --verify "refs/tags/$TAG" >/dev/null && die "tag $TAG already exists locally"
  git fetch --quiet origin main --tags || die "cannot reach origin"
  [ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] || die "main is not the same as origin/main; push first (the release's source must be public, AGPL-3.0)"
  git ls-remote --exit-code --tags origin "refs/tags/$TAG" >/dev/null 2>&1 && die "tag $TAG already exists on GitHub"
  command -v gh >/dev/null || die "the GitHub CLI (gh) is not installed"
  gh auth status >/dev/null 2>&1 || die "gh is not logged in (gh auth login)"
  if [ -z "$NOTES_FILE" ]; then
    NOTES_FILE="$(mktemp)"
    trap 'rm -f "$NOTES_FILE"' EXIT
    # The "## [X.Y.Z]" section of CHANGELOG.md, without its heading.
    awk -v v="$VERSION" '
      $0 ~ "^## \\[" v "\\]" { on = 1; next }
      on && /^## / { exit }
      on { print }' CHANGELOG.md > "$NOTES_FILE"
  fi
  [ -s "$NOTES_FILE" ] || die "no release notes: pass --notes-file F or add a '## [$VERSION]' section to CHANGELOG.md"
fi
echo "version $VERSION (versionCode $VERSION_CODE), tag $TAG, commit $(git rev-parse --short HEAD)"

# ---- 2. Signing key (never echoed) -----------------------------------------------------------
step "2/8 Release signing key"
SIGNING_ENV="$HOME/.carlink-private/release/signing.env"
[ -f "$SIGNING_ENV" ] || die "$SIGNING_ENV is missing (see docs/RELEASING.md)"
set +x
# shellcheck disable=SC1090
source "$SIGNING_ENV"
for var in ANDROID_KEYSTORE_PATH ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_ALIAS ANDROID_KEY_PASSWORD; do
  [ -n "${!var:-}" ] || die "$var is not set by $SIGNING_ENV"
  export "${var?}"
done
[ -f "$ANDROID_KEYSTORE_PATH" ] || die "the keystore named by ANDROID_KEYSTORE_PATH does not exist"
echo "signing key loaded"

# ---- 3. CarPlay accessory identity -----------------------------------------------------------
step "3/8 CarPlay authentication files"
export DIPLAY_AUTH_ASSETS_DIR="$HOME/.carlink-private"
[ -f "$DIPLAY_AUTH_ASSETS_DIR/offline-mfi/identity.pk8" ] || die "$DIPLAY_AUTH_ASSETS_DIR/offline-mfi/identity.pk8 is missing; CarPlay would not work"
echo "found offline-mfi/"

export ANDROID_HOME="$HOME/android-sdk"
export ANDROID_SDK_ROOT="$HOME/android-sdk"

# ---- 4. Tests --------------------------------------------------------------------------------
step "4/8 Unit tests"
.claude/skills/carlink-dev/scripts/dev.sh test || die "tests failed"

# ---- 5. Release build ------------------------------------------------------------------------
step "5/8 Release build (R8, lint vital)"
APK="app/build/outputs/apk/release/app-release.apk"
rm -f "$APK"
./gradlew --no-daemon -q :app:assembleRelease || die "assembleRelease failed"
[ -f "$APK" ] || die "no APK at $APK"

# ---- 6. Checks on the APK --------------------------------------------------------------------
step "6/8 Checking the APK"
BUILD_TOOLS="$(ls -d "$ANDROID_HOME"/build-tools/*/ | sort -V | tail -n1)"
APKSIGNER="${BUILD_TOOLS}apksigner"
AAPT2="${BUILD_TOOLS}aapt2"
[ -x "$APKSIGNER" ] && [ -x "$AAPT2" ] || die "apksigner/aapt2 not found in $BUILD_TOOLS"
CERTS="$("$APKSIGNER" verify --print-certs "$APK")" || die "apksigner verify failed"
SIGNERS="$(grep -c '^Signer #[0-9]* certificate SHA-256 digest:' <<<"$CERTS" || true)"
[ "$SIGNERS" = 1 ] || die "expected exactly one signer, found $SIGNERS"
CERT_SHA="$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<<"$CERTS" | tr -d ': ' | tr 'A-F' 'a-f')"
[ "$CERT_SHA" = "$PIN_NORM" ] || die "the APK is signed with $CERT_SHA, not the pinned release key $PIN_NORM"
echo "signed with the release key ($PIN)"
LISTING="$(unzip -l "$APK")" || die "cannot list the APK"
grep -q ' assets/offline-mfi/' <<<"$LISTING" || die "the APK has no assets/offline-mfi/ (CarPlay authentication); DIPLAY_AUTH_ASSETS_DIR was not picked up"
echo "contains assets/offline-mfi/"
BADGING="$("$AAPT2" dump badging "$APK")" || die "aapt2 cannot read the APK"
grep -q '^application-debuggable' <<<"$BADGING" && die "the APK is debuggable"
PACKAGE_LINE="$(grep '^package:' <<<"$BADGING")"
grep -q "name='com.carlink.app' " <<<"$PACKAGE_LINE" || die "unexpected package: $PACKAGE_LINE"
grep -q "versionCode='$VERSION_CODE' " <<<"$PACKAGE_LINE" || die "expected versionCode $VERSION_CODE: $PACKAGE_LINE"
grep -q "versionName='$VERSION'" <<<"$PACKAGE_LINE" || die "expected versionName $VERSION: $PACKAGE_LINE"
echo "not debuggable; $PACKAGE_LINE"

# ---- 7. dist/ --------------------------------------------------------------------------------
step "7/8 dist/"
mkdir -p dist
cp "$APK" "dist/$APK_NAME"
(cd dist && sha256sum "$APK_NAME" > "$APK_NAME.sha256")
echo "dist/$APK_NAME ($(du -h "dist/$APK_NAME" | cut -f1))"
echo "dist/$APK_NAME.sha256: $(cut -d' ' -f1 "dist/$APK_NAME.sha256")"

if [ "$PUBLISH" = 0 ]; then
  echo
  echo "Done; not published. Test dist/$APK_NAME on a device, then run: scripts/release.sh $VERSION --publish"
  exit 0
fi

# ---- 8. Publish ------------------------------------------------------------------------------
step "8/8 Publishing $TAG"
git tag -a "$TAG" -m "CarLink $VERSION" || die "cannot create tag $TAG"
git push origin "refs/tags/$TAG" || die "cannot push tag $TAG (delete the local tag with: git tag -d $TAG)"
# The release page: the changelog text (all the in-app popup shows), then "<!-- more -->" and
# scripts/release-notes-footer.md (screenshots, install, checksums, notices) filled in.
PAGE_FILE="$(mktemp)"
{
  cat "$NOTES_FILE"
  if ! grep -qx '[[:space:]]*<!-- more -->[[:space:]]*' "$NOTES_FILE"; then
    printf '\n<!-- more -->\n\n'
    sed -e "s|{{TAG}}|$TAG|g" -e "s|{{APK}}|$APK_NAME|g" \
        -e "s|{{SIZE}}|$(du -h "dist/$APK_NAME" | cut -f1 | sed 's/\([KMG]\)$/ \1B/')|g" \
        -e "s|{{SHA256}}|$(cut -d' ' -f1 "dist/$APK_NAME.sha256")|g" \
        -e "s|{{CERT}}|$PIN|g" scripts/release-notes-footer.md
  fi
} > "$PAGE_FILE"
grep -q '{{' "$PAGE_FILE" && die "release page still has an unfilled {{placeholder}}"
gh release create "$TAG" "dist/$APK_NAME" "dist/$APK_NAME.sha256" \
  --verify-tag --title "CarLink $VERSION" --notes-file "$PAGE_FILE" --latest \
  || die "gh release create failed; the tag $TAG is pushed, finish the release on GitHub or delete the tag"
echo "published: $(gh release view "$TAG" --json url --jq .url)"
