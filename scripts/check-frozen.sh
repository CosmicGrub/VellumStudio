#!/usr/bin/env bash
# Fails if a FROZEN file no longer matches the SHA-256 recorded in scripts/frozen-files.sha256.
#
# StrokeRenderer.kt and BrushStampCache.kt are the dab loop: the per-stamp inner loop that every
# brush, every stroke and every latency measurement runs through. They are frozen BY DESIGN -- new
# pen-feel and painting capability wraps or composes AROUND them (see docs/ARCHITECTURE.md, hard
# constraint 3). Changing one is an owner decision, not a refactor, so an edit must fail loudly here
# instead of riding along inside an unrelated diff.
#
# usage:
#   scripts/check-frozen.sh                          check (exit 0 ok, 1 changed/missing, 2 usage)
#   scripts/check-frozen.sh --root DIR --manifest F  check another tree/manifest (used by the self-tests)
#   scripts/check-frozen.sh --update                 rewrite the manifest from the CURRENT files.
#                                                    Only run this after the owner has approved the change.
#
# Hashing is over the file content with CR bytes removed, because this repo is developed on Windows
# with core.autocrlf=true: the same commit is CRLF in one checkout and LF in another, and a raw
# byte hash would fail on a clean tree. Removing CR makes the hash a property of the source text.
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MANIFEST=""
UPDATE=0
while [ $# -gt 0 ]; do
  case "$1" in
    --root) ROOT="$(cd "$2" && pwd)"; shift 2 ;;
    --manifest) MANIFEST="$2"; shift 2 ;;
    --update) UPDATE=1; shift ;;
    *) echo "check-frozen: unknown argument: $1" >&2; exit 2 ;;
  esac
done
[ -n "$MANIFEST" ] || MANIFEST="$ROOT/scripts/frozen-files.sha256"

FROZEN_FILES=(
  "tablet-app/app/src/main/java/com/vellum/studio/canvas/StrokeRenderer.kt"
  "tablet-app/app/src/main/java/com/vellum/studio/canvas/BrushStampCache.kt"
)

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    tr -d '\r' < "$1" | sha256sum | cut -d' ' -f1
  else
    tr -d '\r' < "$1" | shasum -a 256 | cut -d' ' -f1
  fi
}

if [ "$UPDATE" = 1 ]; then
  : > "$MANIFEST"
  for f in "${FROZEN_FILES[@]}"; do
    [ -f "$ROOT/$f" ] || { echo "check-frozen: cannot update, $f does not exist" >&2; exit 1; }
    printf '%s  %s\n' "$(sha256_of "$ROOT/$f")" "$f" >> "$MANIFEST"
  done
  echo "check-frozen: rewrote $MANIFEST -- commit it ONLY with the owner's approval of the change to the frozen file(s)."
  exit 0
fi

[ -f "$MANIFEST" ] || { echo "FROZEN CHECK FAILED: manifest $MANIFEST is missing" >&2; exit 1; }

bad=0
for f in "${FROZEN_FILES[@]}"; do
  want="$(grep -F "  $f" "$MANIFEST" | cut -d' ' -f1)"
  if [ -z "$want" ]; then
    echo "FROZEN CHECK FAILED: $f has no entry in $(basename "$MANIFEST")" >&2; bad=1; continue
  fi
  if [ ! -f "$ROOT/$f" ]; then
    echo "FROZEN CHECK FAILED: $f is missing" >&2; bad=1; continue
  fi
  got="$(sha256_of "$ROOT/$f")"
  if [ "$got" != "$want" ]; then
    echo "FROZEN CHECK FAILED: $f changed (sha256 $got, expected $want)" >&2; bad=1
  fi
done

if [ "$bad" != 0 ]; then
  cat >&2 <<'EOF'

StrokeRenderer.kt and BrushStampCache.kt are FROZEN BY DESIGN: they are the dab loop, and pen feel
and latency depend on them being exactly what was measured. An owner decision is required to change
them. New capability should wrap or compose around them instead (see docs/ARCHITECTURE.md).

If the owner HAS approved the change, update the manifest deliberately, in the same commit:
    bash scripts/check-frozen.sh --update
and say so in the commit message. To undo an accidental edit:
    git checkout -- <file>
EOF
  exit 1
fi
echo "frozen files unchanged (${#FROZEN_FILES[@]} checked)"
