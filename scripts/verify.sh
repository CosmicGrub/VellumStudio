#!/usr/bin/env bash
# Local verification gate: the answer to "is this change safe to ship?" that CI cannot give
# (the GitHub workflow is billing-blocked and never built the R8 release variant anyway).
# Run from anywhere; it locates the repo from its own path. Windows/PowerShell equivalent: verify.ps1.
#
#   scripts/verify.sh          full gate, several minutes:
#       1. frozen files unchanged            (scripts/check-frozen.sh)
#       2. gate self-tests                   (scripts/tests: the checks below still catch what they should)
#       3. README counts match the code      (scripts/check_docs.py)
#       4. :app:testDebugUnitTest
#       5. :app:lint
#       6. :app:assembleRelease              (R8 on; signing per keystore.properties, see below)
#       7. release-apk assertions            (scripts/verify_apk.py: OpenCV / ML Kit / serializers not
#                                             renamed, 16 KB alignment, non-debug signer, manifest allow-list)
#   scripts/verify.sh --fast   steps 1-4 only. This is what the pre-push hook runs.
#
# Stops at the FIRST failing step, prints one line "VERIFY FAILED: [step] reason" on stderr and
# exits 1. Exit 0 means every step run passed.
#
# SIGNING. When tablet-app/keystore.properties exists the release APK must be signed with that key
# (step 7 fails if it is the Android Debug certificate). When it does not exist (CI, a fresh clone, a
# secondary worktree) the script passes -PallowDebugSignedRelease=true to assembleRelease so the build
# is allowed to fall back to debug signing, and says LOUDLY that the signing assertion was skipped:
# that APK verified everything else but is not shippable.
#
# Environment overrides:
#   VERIFY_GRADLE   command prefix that receives the gradle arguments, e.g.
#                   VERIFY_GRADLE="bash /z/Dev/gradle-serial.sh /z/Dev/VellumStudio/tablet-app"
#                   (default: ./gradlew inside tablet-app)
#   ANDROID_HOME / ANDROID_SDK_ROOT   SDK location (default: sdk.dir from tablet-app/local.properties)
#   BUILD_TOOLS     build-tools directory (default: the highest version under the SDK)
#   PYTHON          python 3 interpreter (default: first of python3, python, py that works)
set -u

REPO="$(cd "$(dirname "$0")/.." && pwd)"
APP="$REPO/tablet-app"
FAST=0
case "${1:-}" in
  "") ;;
  --fast) FAST=1 ;;
  -h|--help) sed -n '2,32p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
  *) echo "verify.sh: unknown argument: $1 (try --help)" >&2; exit 2 ;;
esac

if [ "$FAST" = 1 ]; then TOTAL=4; else TOTAL=7; fi
STEP_N=0
STEP_NAME="startup"

begin() { STEP_N=$((STEP_N + 1)); STEP_NAME="$1"; echo; echo "=== [$STEP_N/$TOTAL] $1"; }
fail() { echo >&2; echo "VERIFY FAILED: [$STEP_NAME] $1" >&2; exit 1; }

# Windows-native path for tools that are not MSYS-aware (python.exe, apksigner.bat).
native() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

find_python() {
  for c in ${PYTHON:-} python3 python py; do
    [ -n "$c" ] || continue
    if "$c" -c 'import sys; sys.exit(0 if sys.version_info[0] == 3 else 1)' >/dev/null 2>&1; then echo "$c"; return 0; fi
  done
  return 1
}

gradle() {
  if [ -n "${VERIFY_GRADLE:-}" ]; then
    $VERIFY_GRADLE "$@"
  else
    (cd "$APP" && ./gradlew "$@" --console=plain)
  fi
}

find_sdk() {
  if [ -n "${ANDROID_HOME:-}" ]; then echo "$ANDROID_HOME"; return 0; fi
  if [ -n "${ANDROID_SDK_ROOT:-}" ]; then echo "$ANDROID_SDK_ROOT"; return 0; fi
  if [ -f "$APP/local.properties" ]; then
    # local.properties stores a Java-escaped path: C\:\\Users\\me\\android-sdk
    sed -n 's/^sdk\.dir=//p' "$APP/local.properties" | tr -d '\r' | sed -e 's/\\\\/\//g' -e 's/\\:/:/g'
    return 0
  fi
  return 1
}

PY="$(find_python)" || fail "no Python 3 interpreter found (needed for the APK and docs checks); install Python 3 or set PYTHON"

# ---- 1. frozen files -------------------------------------------------------------------------
begin "frozen files unchanged (StrokeRenderer.kt, BrushStampCache.kt)"
bash "$REPO/scripts/check-frozen.sh" || fail "a frozen file changed -- an owner decision is required (see the message above)"

# ---- 2. gate self-tests ----------------------------------------------------------------------
begin "gate self-tests (scripts/tests)"
"$PY" -m unittest discover -s "$(native "$REPO/scripts/tests")" -p "test_*.py" || fail "the verification scripts' own tests failed: a check has stopped catching what it should"

# ---- 3. docs ---------------------------------------------------------------------------------
begin "README counts match the code"
"$PY" "$(native "$REPO/scripts/check_docs.py")" --root "$(native "$REPO")" || fail "README.md is stale relative to the code (see above)"

# ---- 4. unit tests ---------------------------------------------------------------------------
begin "unit tests (:app:testDebugUnitTest)"
gradle :app:testDebugUnitTest || fail "unit tests failed (report: tablet-app/app/build/reports/tests/testDebugUnitTest/index.html)"

if [ "$FAST" = 1 ]; then
  echo
  echo "VERIFY OK (fast subset: steps 1-4). Run scripts/verify.sh without --fast before shipping: lint and the R8 release build were NOT checked."
  exit 0
fi

# ---- 5. lint ---------------------------------------------------------------------------------
begin "lint (:app:lint)"
gradle :app:lint || fail "lint failed (report: tablet-app/app/build/reports/lint-results-debug.html)"

# ---- 6. release build ------------------------------------------------------------------------
begin "release build (:app:assembleRelease, R8 on)"
RELEASE_ARGS=()
EXPECT_SIGNING=()
if [ -f "$APP/keystore.properties" ]; then
  echo "tablet-app/keystore.properties found: the release APK must be signed with the real key."
  EXPECT_SIGNING=(--expect-release-signing)
else
  RELEASE_ARGS=(-PallowDebugSignedRelease=true)
  echo "############################################################################"
  echo "# tablet-app/keystore.properties is ABSENT."
  echo "# Building with -PallowDebugSignedRelease=true: the APK will be DEBUG-SIGNED."
  echo "# The signing-identity assertion is SKIPPED. This APK is NOT shippable."
  echo "############################################################################"
fi
gradle :app:assembleRelease "${RELEASE_ARGS[@]+"${RELEASE_ARGS[@]}"}" || fail "assembleRelease failed"

# ---- 7. release apk assertions ---------------------------------------------------------------
begin "release apk assertions (R8 keeps, 16 KB alignment, signer, manifest)"
APK_DIR="$APP/app/build/outputs/apk/release"
APK="$APK_DIR/app-release.apk"
[ -f "$APK" ] || APK="$(ls "$APK_DIR"/*.apk 2>/dev/null | head -n 1)"
[ -n "$APK" ] && [ -f "$APK" ] || fail "no release APK found under $APK_DIR"
SDK="$(find_sdk)" || fail "cannot locate the Android SDK (set ANDROID_HOME, or sdk.dir in tablet-app/local.properties)"
BT="${BUILD_TOOLS:-}"
if [ -z "$BT" ]; then
  BT_NAME="$(ls "$SDK/build-tools" 2>/dev/null | sort -V | tail -n 1)"
  [ -n "$BT_NAME" ] || fail "no build-tools under $SDK/build-tools"
  BT="$SDK/build-tools/$BT_NAME"
fi
OUT="$(mktemp)"
"$PY" "$(native "$REPO/scripts/verify_apk.py")" --apk "$(native "$APK")" --build-tools "$(native "$BT")" \
  --manifest-allowlist "$(native "$REPO/scripts/manifest-allowlist.json")" "${EXPECT_SIGNING[@]+"${EXPECT_SIGNING[@]}"}" | tee "$OUT"
RC=${PIPESTATUS[0]}
REASON="$(grep '^REASON: ' "$OUT" | tail -n 1 | sed 's/^REASON: //')"
rm -f "$OUT"
[ "$RC" = 0 ] || fail "${REASON:-release apk assertions failed (see above)}"

echo
if [ ${#EXPECT_SIGNING[@]} -eq 0 ]; then
  echo "VERIFY OK -- BUT the signing assertion was SKIPPED (no keystore.properties): the release APK is debug-signed and NOT shippable."
else
  echo "VERIFY OK (full gate, release APK signed with the real key)."
fi
