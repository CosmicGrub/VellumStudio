#!/usr/bin/env python3
"""Fails when README.md's headline numbers no longer match the code.

The README used to say "five brush presets" for a codebase that defined twenty, and nobody noticed
for weeks because nothing compared the two. This counts the real thing in the source and compares it
with the machine-readable marker in README.md:

    <!-- verify-docs: brush-presets=20 academy-courses=10 academy-lessons=47 -->

Counted from source, not from a running app, so it needs no Gradle and finishes instantly:
  brush-presets    `val X = Brush(` declarations inside `object BrushPresets` (canvas/Brush.kt)
  academy-courses  `CourseXxx.course` entries in AcademyLibrary.all
  academy-lessons  sum of `lessons` over assets/academy/*.json (the bundled, authored course content)

usage: check_docs.py [--root DIR]      exit 0 ok, 1 stale, 2 cannot count
"""
from __future__ import annotations

import argparse
import glob
import json
import os
import re
import sys

MARKER_RE = re.compile(r"<!--\s*verify-docs:\s*([^>]*?)\s*-->")


def count_presets(brush_kt: str) -> int:
    start = brush_kt.index("object BrushPresets")
    return len(re.findall(r"^    val \w+ = Brush\(", brush_kt[start:], re.M))


def count_courses(library_kt: str) -> int:
    return len(re.findall(r"^\s+Course\w+\.course,", library_kt, re.M))


def count_lessons(academy_dir: str) -> int:
    total = 0
    for path in glob.glob(os.path.join(academy_dir, "*.json")):
        with open(path, encoding="utf-8") as f:
            total += len(json.load(f)["lessons"])
    return total


def parse_marker(readme: str) -> dict[str, int]:
    m = MARKER_RE.search(readme)
    if not m:
        raise ValueError("README.md has no '<!-- verify-docs: ... -->' marker")
    out = {}
    for part in m.group(1).split():
        key, _, val = part.partition("=")
        out[key] = int(val)
    return out


def actual_counts(root: str) -> dict[str, int]:
    src = os.path.join(root, "tablet-app", "app", "src", "main")
    java = os.path.join(src, "java", "com", "vellum", "studio")
    with open(os.path.join(java, "canvas", "Brush.kt"), encoding="utf-8") as f:
        presets = count_presets(f.read())
    with open(os.path.join(java, "academy", "AcademyLibrary.kt"), encoding="utf-8") as f:
        courses = count_courses(f.read())
    return {
        "brush-presets": presets,
        "academy-courses": courses,
        "academy-lessons": count_lessons(os.path.join(src, "assets", "academy")),
    }


def compare(claimed: dict[str, int], actual: dict[str, int]) -> list[str]:
    problems = []
    for key, real in actual.items():
        if key not in claimed:
            problems.append(f"README marker has no '{key}' (code has {real})")
        elif claimed[key] != real:
            problems.append(f"README says {key}={claimed[key]} but the code has {real}")
    return problems


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
    args = ap.parse_args(argv)
    root = os.path.abspath(args.root)
    try:
        with open(os.path.join(root, "README.md"), encoding="utf-8") as f:
            claimed = parse_marker(f.read())
        actual = actual_counts(root)
    except (OSError, ValueError, KeyError) as e:
        print(f"DOCS CHECK FAILED: cannot compare README with the code: {e}", file=sys.stderr)
        return 2
    problems = compare(claimed, actual)
    if problems:
        for p in problems:
            print(f"DOCS CHECK FAILED: {p}", file=sys.stderr)
        print("Update the prose AND the verify-docs marker in README.md (the prose says the same numbers).", file=sys.stderr)
        return 1
    print("README counts match the code: " + ", ".join(f"{k}={v}" for k, v in actual.items()))
    return 0


if __name__ == "__main__":
    sys.exit(main())
