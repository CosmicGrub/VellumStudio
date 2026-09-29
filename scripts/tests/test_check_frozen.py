"""Regression tests for scripts/check-frozen.sh (the frozen dab-loop guard) and scripts/check_docs.py.

check-frozen.sh is exercised against a scratch copy of the two frozen paths, never the real ones:
the tests build a throwaway tree, record a manifest with --update, then break it in the ways the
guard exists to catch.
"""
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPTS = os.path.abspath(os.path.join(HERE, ".."))
REPO = os.path.abspath(os.path.join(SCRIPTS, ".."))
sys.path.insert(0, SCRIPTS)
import check_docs  # noqa: E402

FROZEN = [
    "tablet-app/app/src/main/java/com/vellum/studio/canvas/StrokeRenderer.kt",
    "tablet-app/app/src/main/java/com/vellum/studio/canvas/BrushStampCache.kt",
]


def find_bash():
    """Git's bash, not the WSL launcher (System32 or WindowsApps bash.exe), which cannot see Windows paths."""
    candidates = []
    git = shutil.which("git")
    if git:
        candidates.append(os.path.join(os.path.dirname(os.path.dirname(git)), "bin", "bash.exe"))
    candidates += [r"C:\Program Files\Git\bin\bash.exe", shutil.which("bash") or ""]
    for c in candidates:
        if c and os.path.isfile(c) and "System32" not in c and "WindowsApps" not in c:
            return c
    return None


BASH = find_bash()


@unittest.skipIf(BASH is None, "bash not available")
class CheckFrozen(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="frozen-test-")
        self.addCleanup(shutil.rmtree, self.tmp, True)
        for rel in FROZEN:
            path = os.path.join(self.tmp, *rel.split("/"))
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w", newline="\n") as f:
                f.write(f"// {os.path.basename(rel)}\nfun dab() {{}}\n")
        self.manifest = os.path.join(self.tmp, "frozen.sha256")
        self.assertEqual(self.run_script("--update").returncode, 0)

    def run_script(self, *extra):
        cmd = [BASH, os.path.join(SCRIPTS, "check-frozen.sh").replace("\\", "/"), "--root", self.tmp.replace("\\", "/"),
               "--manifest", self.manifest.replace("\\", "/"), *extra]
        return subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)

    def path(self, i):
        return os.path.join(self.tmp, *FROZEN[i].split("/"))

    def test_untouched_files_pass(self):
        r = self.run_script()
        self.assertEqual(r.returncode, 0, r.stderr)

    def test_edit_to_stroke_renderer_fails_with_the_frozen_by_design_message(self):
        with open(self.path(0), "a", newline="\n") as f:
            f.write("// innocent looking tweak\n")
        r = self.run_script()
        self.assertEqual(r.returncode, 1)
        self.assertIn("StrokeRenderer.kt changed", r.stderr)
        self.assertIn("FROZEN BY DESIGN", r.stderr)
        self.assertIn("owner decision", r.stderr)
        self.assertIn("--update", r.stderr)  # says how to update the manifest deliberately

    def test_edit_to_brush_stamp_cache_fails(self):
        with open(self.path(1), "a", newline="\n") as f:
            f.write("val x = 1\n")
        r = self.run_script()
        self.assertEqual(r.returncode, 1)
        self.assertIn("BrushStampCache.kt changed", r.stderr)

    def test_crlf_checkout_of_unchanged_source_still_passes(self):
        # core.autocrlf=true turns every LF into CRLF on this machine; that is not a change.
        with open(self.path(0), "rb") as f:
            data = f.read()
        with open(self.path(0), "wb") as f:
            f.write(data.replace(b"\n", b"\r\n"))
        r = self.run_script()
        self.assertEqual(r.returncode, 0, r.stderr)

    def test_deleted_file_fails(self):
        os.remove(self.path(1))
        r = self.run_script()
        self.assertEqual(r.returncode, 1)
        self.assertIn("missing", r.stderr)

    def test_missing_manifest_fails_closed(self):
        os.remove(self.manifest)
        r = self.run_script()
        self.assertEqual(r.returncode, 1)

    def test_update_makes_a_deliberate_change_pass(self):
        with open(self.path(0), "a", newline="\n") as f:
            f.write("// owner-approved change\n")
        self.assertEqual(self.run_script().returncode, 1)
        self.assertEqual(self.run_script("--update").returncode, 0)
        self.assertEqual(self.run_script().returncode, 0)


@unittest.skipIf(BASH is None, "bash not available")
class RealFrozenManifest(unittest.TestCase):
    def test_checked_in_manifest_matches_the_checked_in_files(self):
        cmd = [BASH, os.path.join(SCRIPTS, "check-frozen.sh").replace("\\", "/")]
        r = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        self.assertEqual(r.returncode, 0, r.stderr)


class CheckDocs(unittest.TestCase):
    def test_counts_are_read_from_source(self):
        brush = "object BrushPresets {\n    val A = Brush(\n    )\n    val B = Brush(\n    )\n    fun helper() = Brush(\n}\n"
        self.assertEqual(check_docs.count_presets("val Outside = Brush(\n" + brush), 2)
        self.assertEqual(check_docs.count_courses("listOf(\n        CourseA.course,\n        CourseB.course,\n    )"), 2)

    def test_stale_readme_is_reported(self):
        # The original bug: README said five presets while the code had twenty.
        claimed = check_docs.parse_marker("<!-- verify-docs: brush-presets=5 academy-courses=10 academy-lessons=47 -->")
        problems = check_docs.compare(claimed, {"brush-presets": 20, "academy-courses": 10, "academy-lessons": 47})
        self.assertEqual(len(problems), 1)
        self.assertIn("brush-presets=5", problems[0])
        self.assertIn("20", problems[0])

    def test_missing_marker_is_an_error(self):
        with self.assertRaises(ValueError):
            check_docs.parse_marker("# no marker here")

    # Deliberately no "the real README matches the real code" test here: that is step 3 of verify.sh
    # (check_docs.py against the repo), and a stale README failing THIS suite would report it as
    # "the gate's self-tests failed", which is the wrong diagnosis.


if __name__ == "__main__":
    unittest.main()
