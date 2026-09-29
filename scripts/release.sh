#!/usr/bin/env bash
# Builds ONE signed release APK for the branch checked out in this worktree and archives everything
# needed to trust it and to read its crash traces later:
#
#   release-artifacts/vellum-studio-<versionName>-<branch>.apk
#   release-artifacts/vellum-studio-<versionName>-<branch>.mapping.txt   (R8 mapping, for `retrace`)
#   release-artifacts/SHA256SUMS                                          (every .apk and .mapping.txt there)
#
# Run it once per branch worktree (main, and each device/* branch); the outputs of all runs share
# release-artifacts/ if you point RELEASE_OUT_DIR at one folder. It builds, verifies and checksums --
# it does NOT tag, push, or publish anything.
#
# FAILS CLOSED, on purpose:
#   * no usable tablet-app/keystore.properties  -> Gradle refuses (the message points at
#     keystore.properties.example) and so does this script;
#   * dirty tracked files                       -> refused, so the sha in the About text really names the build;
#   * signature not verifiable / debug-signed   -> refused.
# Escape hatches (all explicit, all for builds that are NEVER distributed):
#   ALLOW_DEBUG_SIGNED=1   pass -PallowDebugSignedRelease=true and accept a debug-signed APK; files get a
#                          `-DEBUGSIGNED` suffix so one can never be mistaken for a real release.
#   ALLOW_DIRTY=1          build with uncommitted changes to tracked files.
#   SKIP_APKSIGNER=1       skip `apksigner verify` (only when the Android SDK build-tools are not installed).
# Other knobs: RELEASE_OUT_DIR (output folder), GRADLE_CMD (default ./gradlew; e.g. a serializing wrapper),
# APKSIGNER (path to apksigner), BRANCH_NAME (required on a detached HEAD).
#
# See docs/RELEASING.md for the full procedure and the retrace command.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_DIR="$REPO_ROOT/tablet-app"
OUT_DIR="${RELEASE_OUT_DIR:-$REPO_ROOT/release-artifacts}"
GRADLE_CMD="${GRADLE_CMD:-./gradlew}"

die() { echo "release.sh: ERROR: $*" >&2; exit 1; }

# --- identity: version + branch + commit ---------------------------------------------------------
VERSION_NAME="$(sed -n 's/^versionName=//p' "$APP_DIR/version.properties" | tr -d '\r' | head -n1)"
[ -n "$VERSION_NAME" ] || die "no versionName in tablet-app/version.properties"

BRANCH="$(git -C "$REPO_ROOT" rev-parse --abbrev-ref HEAD)"
if [ "$BRANCH" = "HEAD" ]; then
  BRANCH="${BRANCH_NAME:-}"
  [ -n "$BRANCH" ] || die "detached HEAD: set BRANCH_NAME=<branch> so the artifacts can be named"
fi
# device/galaxy-tab-s9fe -> galaxy-tab-s9fe ; anything else with a slash -> dashes ; nothing exotic in a file name
BRANCH_TOKEN="$(printf '%s' "${BRANCH#device/}" | tr '/' '-' | tr -c 'A-Za-z0-9._\n-' '_')"
GIT_SHA="$(git -C "$REPO_ROOT" rev-parse --short=10 HEAD)"

if [ -n "$(git -C "$REPO_ROOT" status --porcelain --untracked-files=no)" ] && [ "${ALLOW_DIRTY:-0}" != "1" ]; then
  die "tracked files have uncommitted changes, so sha $GIT_SHA would not describe this build. Commit them, or set ALLOW_DIRTY=1."
fi

GRADLE_FLAGS=()
SUFFIX=""
if [ "${ALLOW_DEBUG_SIGNED:-0}" = "1" ]; then
  GRADLE_FLAGS+=("-PallowDebugSignedRelease=true")
  SUFFIX="-DEBUGSIGNED"
  echo "release.sh: WARNING: ALLOW_DEBUG_SIGNED=1 -- the APK will be signed with the DEBUG key and must not be distributed." >&2
fi
NAME="vellum-studio-${VERSION_NAME}-${BRANCH_TOKEN}${SUFFIX}"

# --- build ----------------------------------------------------------------------------------------
echo "release.sh: building $NAME ($BRANCH @ $GIT_SHA)"
(cd "$APP_DIR" && $GRADLE_CMD :app:assembleRelease ${GRADLE_FLAGS[@]+"${GRADLE_FLAGS[@]}"}) || die "Gradle build failed (see above)"

APK_SRC="$APP_DIR/app/build/outputs/apk/release/app-release.apk"
MAP_SRC="$APP_DIR/app/build/outputs/mapping/release/mapping.txt"
[ -f "$APK_SRC" ] || die "expected APK not found: $APK_SRC"
[ -f "$MAP_SRC" ] || die "expected R8 mapping not found: $MAP_SRC (was minification disabled?)"

# --- verify the signature -------------------------------------------------------------------------
find_apksigner() {
  if [ -n "${APKSIGNER:-}" ]; then echo "$APKSIGNER"; return; fi
  local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [ -z "$sdk" ] && [ -f "$APP_DIR/local.properties" ]; then
    # local.properties stores Windows paths as C\:\\Users\\...; normalise back to something bash can open.
    sdk="$(sed -n 's/^sdk\.dir=//p' "$APP_DIR/local.properties" | tr -d '\r' | sed 's/\\\\/\//g; s/\\:/:/g; s/\\/\//g')"
    command -v cygpath >/dev/null 2>&1 && sdk="$(cygpath -u "$sdk" 2>/dev/null || echo "$sdk")"
  fi
  [ -n "$sdk" ] && [ -d "$sdk/build-tools" ] || return 0
  local newest
  newest="$(ls "$sdk/build-tools" | sort -V | tail -n1)"
  [ -n "$newest" ] || return 0
  if [ -f "$sdk/build-tools/$newest/apksigner.bat" ]; then echo "$sdk/build-tools/$newest/apksigner.bat"
  elif [ -f "$sdk/build-tools/$newest/apksigner" ]; then echo "$sdk/build-tools/$newest/apksigner"; fi
}

if [ "${SKIP_APKSIGNER:-0}" = "1" ]; then
  echo "release.sh: WARNING: SKIP_APKSIGNER=1 -- signature NOT verified." >&2
else
  APKSIGNER_BIN="$(find_apksigner)"
  [ -n "$APKSIGNER_BIN" ] || die "apksigner not found (set APKSIGNER or ANDROID_HOME, or SKIP_APKSIGNER=1 to skip verification)"
  CERTS="$("$APKSIGNER_BIN" verify --print-certs "$APK_SRC" 2>&1)" || { echo "$CERTS" >&2; die "apksigner verify FAILED for $APK_SRC"; }
  echo "$CERTS" | tr -d '\r'
  # The whole point of failing closed: a "release" carrying the Android debug certificate is not a release.
  if echo "$CERTS" | grep -qi "CN=Android Debug" && [ "${ALLOW_DEBUG_SIGNED:-0}" != "1" ]; then
    die "the APK is signed with the Android DEBUG certificate. Provide tablet-app/keystore.properties (see keystore.properties.example)."
  fi
fi

# --- archive + checksum ---------------------------------------------------------------------------
mkdir -p "$OUT_DIR"
cp -f "$APK_SRC" "$OUT_DIR/$NAME.apk"
cp -f "$MAP_SRC" "$OUT_DIR/$NAME.mapping.txt"

# One checksum file covering every APK and mapping in the folder, in `sha256sum -c` format, so a
# later run for another branch extends rather than replaces it.
if command -v sha256sum >/dev/null 2>&1; then SHA_CMD=(sha256sum); else SHA_CMD=(shasum -a 256); fi
(
  cd "$OUT_DIR"
  shopt -s nullglob
  files=(*.apk *.mapping.txt)
  "${SHA_CMD[@]}" "${files[@]}" > SHA256SUMS
)

echo
echo "release.sh: done."
echo "  APK      : $OUT_DIR/$NAME.apk"
echo "  mapping  : $OUT_DIR/$NAME.mapping.txt"
echo "  checksums: $OUT_DIR/SHA256SUMS"
grep -F "$NAME" "$OUT_DIR/SHA256SUMS"
