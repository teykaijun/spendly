#!/usr/bin/env bash
#
# Builds, verifies and publishes a release that the in-app updater can install.
#
#   ./scripts/release.sh            # release the version in app/build.gradle.kts
#   ./scripts/release.sh --dry-run  # build and verify, publish nothing
#
# Refuses to publish an APK signed with the Android debug key. That is not
# pedantry: whatever key the first release ships with, every later release must
# use forever, and the debug keystore is generated per machine. Publishing one
# signed that way quietly guarantees updates break later.

set -euo pipefail

cd "$(dirname "$0")/.."

DRY_RUN=0
[[ "${1:-}" == "--dry-run" ]] && DRY_RUN=1

fail() { printf '\nERROR: %s\n' "$*" >&2; exit 1; }
step() { printf '\n==> %s\n' "$*"; }

# --- toolchain -------------------------------------------------------------

if [[ -z "${JAVA_HOME:-}" ]]; then
  for candidate in \
    "/c/Program Files/Android/openjdk/jdk-21.0.8" \
    "/c/Program Files/Android/Android Studio/jbr" \
    "/usr/lib/jvm/default-java"
  do
    [[ -d "$candidate" ]] && export JAVA_HOME="$candidate" && break
  done
fi
[[ -n "${JAVA_HOME:-}" ]] || fail "JAVA_HOME is not set and no JDK was found."
step "JDK: $JAVA_HOME"

# --- preconditions ---------------------------------------------------------

[[ -f keystore.properties ]] || fail \
"keystore.properties is missing, so the build would fall back to the debug key.

Create the signing key once:

  keytool -genkeypair -v \\
    -keystore spendly-release.jks \\
    -alias spendly \\
    -keyalg RSA -keysize 4096 -validity 10000

then copy keystore.properties.example to keystore.properties and fill it in.
Back up the .jks somewhere other than this repository."

if [[ -n "$(git status --porcelain)" ]]; then
  fail "Working tree is dirty. Commit or stash before releasing."
fi

VERSION="$(grep -oE 'versionName = "[^"]+"' app/build.gradle.kts | head -1 | cut -d'"' -f2)"
[[ -n "$VERSION" ]] || fail "Could not read versionName from app/build.gradle.kts"
TAG="v${VERSION}"
step "Releasing $TAG"

if git rev-parse "$TAG" >/dev/null 2>&1; then
  fail "Tag $TAG already exists. Bump versionCode and versionName first."
fi

# --- build -----------------------------------------------------------------

step "Running tests and lint"
./gradlew --console=plain testDebugUnitTest lintDebug

step "Building release APK"
./gradlew --console=plain assembleRelease

APK="app/build/outputs/apk/release/app-release.apk"
[[ -f "$APK" ]] || fail "Expected $APK to exist"

# --- the check that matters ------------------------------------------------

APKSIGNER="$(find "${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}/build-tools" \
  -maxdepth 2 -name 'apksigner.bat' -o -maxdepth 2 -name 'apksigner' 2>/dev/null \
  | sort -r | head -1)"
[[ -n "$APKSIGNER" ]] || fail "Could not find apksigner in the Android SDK build-tools"

step "Verifying signature"
CERTS="$("$APKSIGNER" verify --print-certs "$APK")"
echo "$CERTS" | grep -E 'certificate DN|SHA-256 digest' || true

if echo "$CERTS" | grep -qi 'CN=Android Debug'; then
  fail "This APK is signed with the Android DEBUG key.

Publishing it would lock every future release to a per-machine throwaway
keystore. Fix keystore.properties and rebuild."
fi

SIZE_MB=$(( $(wc -c < "$APK") / 1024 / 1024 ))
step "Signed release APK ready (${SIZE_MB} MB)"

if [[ "$DRY_RUN" == "1" ]]; then
  step "Dry run — nothing published."
  exit 0
fi

# --- publish ---------------------------------------------------------------

command -v gh >/dev/null || fail "The GitHub CLI (gh) is not installed."
gh auth status >/dev/null 2>&1 || fail "gh is not authenticated. Run: gh auth login"

# Release notes come from the matching CHANGELOG section, so the text the
# updater shows in-app is the same text the repository documents.
NOTES_FILE="$(mktemp)"
trap 'rm -f "$NOTES_FILE"' EXIT
awk -v tag="## ${TAG}" '
  $0 ~ "^" tag { found=1; next }
  found && /^## / { exit }
  found { print }
' CHANGELOG.md > "$NOTES_FILE"

if [[ ! -s "$NOTES_FILE" ]]; then
  echo "Spendly $VERSION" > "$NOTES_FILE"
  echo "(No CHANGELOG section found for $TAG.)" >> "$NOTES_FILE"
fi

step "Tagging $TAG"
git tag -a "$TAG" -m "Spendly $VERSION"
git push origin "$TAG"

step "Publishing GitHub release"
gh release create "$TAG" "$APK#spendly-${VERSION}.apk" \
  --title "Spendly $VERSION" \
  --notes-file "$NOTES_FILE"

step "Done. In the app: Settings > Updates > source = $(gh repo view --json nameWithOwner -q .nameWithOwner)"
