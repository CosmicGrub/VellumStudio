#!/usr/bin/env bash
# Points this clone at the tracked hooks in .githooks/ so `git push` runs the fast verification gate.
# `core.hooksPath` is repository config, shared by every worktree of the clone, and is NOT applied
# automatically: git never runs hooks from a fresh clone until its owner opts in by running this.
#
# Undo with:  git config --unset core.hooksPath
set -eu
root="$(git rev-parse --show-toplevel)"
cd "$root"
git config core.hooksPath .githooks
echo "core.hooksPath set to .githooks for $root"
echo "pre-push now runs: bash scripts/verify.sh --fast"
