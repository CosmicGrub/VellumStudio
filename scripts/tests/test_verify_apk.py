"""Regression tests for the checks in scripts/verify_apk.py.

These run as step 2 of scripts/verify.{sh,ps1}. They exist so the gate cannot rot silently: each test
builds a tiny synthetic DEX / ELF / manifest that exhibits exactly one failure the gate is meant to
catch, and asserts the check reports it. Without them a refactor of the parsers could turn a check
into "always passes" and nothing would notice until a broken release shipped.

Run alone:  python -m unittest discover -s scripts/tests
"""
import json
import os
import struct
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import verify_apk as v  # noqa: E402


def uleb(n: int) -> bytes:
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def build_dex(class_descriptors, extra_strings=()):
    """A structurally valid-enough DEX: header, string_ids, type_ids, string data. Class descriptors
    become both strings and types; extra_strings are strings only."""
    strings = sorted(set(class_descriptors) | set(extra_strings))
    index = {s: i for i, s in enumerate(strings)}
    types = sorted(class_descriptors)
    str_ids_off = 0x70
    type_ids_off = str_ids_off + 4 * len(strings)
    data_off = type_ids_off + 4 * len(types)
    blobs, offsets, pos = [], [], data_off
    for s in strings:
        raw = uleb(len(s)) + s.encode("utf-8") + b"\0"
        offsets.append(pos)
        blobs.append(raw)
        pos += len(raw)
    header = bytearray(0x70)
    header[0:4] = b"dex\n"
    struct.pack_into("<IIII", header, 0x38, len(strings), str_ids_off, len(types), type_ids_off)
    body = b"".join(struct.pack("<I", o) for o in offsets)
    body += b"".join(struct.pack("<I", index[t]) for t in types)
    return bytes(header) + body + b"".join(blobs)


def build_elf64(aligns):
    """ELF64 LE with one PT_LOAD per entry in `aligns` (plus a PT_NOTE that must be ignored)."""
    phnum = len(aligns) + 1
    ehdr = bytearray(64)
    ehdr[0:4] = b"\x7fELF"
    ehdr[4] = 2  # ELFCLASS64
    ehdr[5] = 1  # little endian
    struct.pack_into("<Q", ehdr, 0x20, 64)  # e_phoff
    struct.pack_into("<HH", ehdr, 0x36, 56, phnum)  # e_phentsize, e_phnum
    phdrs = bytearray()
    phdrs += struct.pack("<IIQQQQQQ", 4, 0, 0, 0, 0, 0, 0, 4)  # PT_NOTE, p_align 4: must not count
    for a in aligns:
        phdrs += struct.pack("<IIQQQQQQ", v.PT_LOAD, 5, 0, 0, 0, 0, 0, a)
    return bytes(ehdr) + bytes(phdrs)


def build_elf32(aligns):
    ehdr = bytearray(52)
    ehdr[0:4] = b"\x7fELF"
    ehdr[4] = 1
    ehdr[5] = 1
    struct.pack_into("<I", ehdr, 0x1C, 52)
    struct.pack_into("<HH", ehdr, 0x2A, 32, len(aligns))
    phdrs = b"".join(struct.pack("<IIIIIIII", v.PT_LOAD, 0, 0, 0, 0, 0, 5, a) for a in aligns)
    return bytes(ehdr) + phdrs


ALL_REQUIRED = {v.descriptor(n) for n in v.REQUIRED_CLASSES}


class DexClassChecks(unittest.TestCase):
    def test_parser_reads_types_and_strings(self):
        types, strings = v.parse_dex(build_dex(["Lorg/opencv/core/Mat;"], ["nativeObj"]))
        self.assertIn("Lorg/opencv/core/Mat;", types)
        self.assertIn("nativeObj", strings)
        self.assertNotIn("nativeObj", types)

    def test_healthy_minified_dex_passes(self):
        types, strings = v.parse_dex(build_dex(ALL_REQUIRED | {"La/b;"}, ["nativeObj"]))
        self.assertEqual(v.check_classes(types, strings), [])

    def test_renamed_opencv_mat_is_caught(self):
        # What R8 does to org.opencv.core.Mat when its keep rule is lost: the JNI lookup breaks at runtime.
        classes = (ALL_REQUIRED - {"Lorg/opencv/core/Mat;"}) | {"La/a;"}
        types, strings = v.parse_dex(build_dex(classes, ["nativeObj"]))
        problems = v.check_classes(types, strings)
        self.assertTrue(any("org.opencv.core.Mat" in p for p in problems), problems)

    def test_stripped_serializer_is_caught(self):
        classes = ALL_REQUIRED - {"Lcom/vellum/studio/model/ProjectMeta$$serializer;"}
        types, strings = v.parse_dex(build_dex(classes, ["nativeObj"]))
        problems = v.check_classes(types, strings)
        self.assertTrue(any("ProjectMeta$$serializer" in p for p in problems), problems)

    def test_renamed_mlkit_entry_point_is_caught(self):
        classes = ALL_REQUIRED - {"Lcom/google/mlkit/vision/pose/PoseDetection;"}
        types, strings = v.parse_dex(build_dex(classes, ["nativeObj"]))
        self.assertTrue(any("PoseDetection" in p for p in v.check_classes(types, strings)))

    def test_renamed_jni_field_is_caught(self):
        types, strings = v.parse_dex(build_dex(ALL_REQUIRED, []))  # no 'nativeObj' string
        self.assertTrue(any("nativeObj" in p for p in v.check_classes(types, strings)))

    def test_unminified_build_is_caught_by_the_negative_control(self):
        # If R8 never ran, every class keeps its name and "not renamed" would pass vacuously.
        unminified = ALL_REQUIRED | {v.descriptor(n) for n in v.MUST_BE_OBFUSCATED}
        types, strings = v.parse_dex(build_dex(unminified, ["nativeObj"]))
        problems = v.check_classes(types, strings)
        self.assertTrue(any("R8 did not run" in p for p in problems), problems)

    def test_rejects_non_dex(self):
        with self.assertRaises(ValueError):
            v.parse_dex(b"PK\x03\x04 not a dex")


class ElfAlignmentChecks(unittest.TestCase):
    def test_reads_only_pt_load_aligns(self):
        self.assertEqual(v.elf_load_aligns(build_elf64([0x4000, 0x10000])), [0x4000, 0x10000])

    def test_reads_elf32(self):
        self.assertEqual(v.elf_load_aligns(build_elf32([0x1000])), [0x1000])

    def test_aligned_library_passes(self):
        problems, _ = v.check_elf({"lib/arm64-v8a/libgood.so": build_elf64([0x4000, 0x4000])}, {})
        self.assertEqual(problems, [])

    def test_4k_library_fails(self):
        problems, _ = v.check_elf({"lib/arm64-v8a/libbad.so": build_elf64([0x4000, 0x1000])}, {})
        self.assertEqual(len(problems), 1)
        self.assertIn("libbad.so", problems[0])
        self.assertIn("4096", problems[0])

    def test_libxeno_is_a_recorded_exception_not_a_silent_one(self):
        libs = {"lib/arm64-v8a/libxeno_native.so": build_elf64([0x1000])}
        problems, notes = v.check_elf(libs, v.ELF_ALLOWLIST)
        self.assertEqual(problems, [])
        self.assertTrue(any("KNOWN EXCEPTION" in n and "libxeno_native.so" in n for n in notes), notes)
        # ...and the same 4 KB library under any OTHER name is still a failure.
        problems, _ = v.check_elf({"lib/arm64-v8a/libother.so": build_elf64([0x1000])}, v.ELF_ALLOWLIST)
        self.assertEqual(len(problems), 1)

    def test_allowlist_entry_that_became_aligned_asks_to_be_removed(self):
        libs = {"lib/arm64-v8a/libxeno_native.so": build_elf64([0x4000])}
        problems, notes = v.check_elf(libs, v.ELF_ALLOWLIST)
        self.assertEqual(problems, [])
        self.assertTrue(any("remove" in n for n in notes), notes)

    def test_32_bit_abis_are_exempt(self):
        problems, _ = v.check_elf({"lib/armeabi-v7a/libold.so": build_elf32([0x1000])}, {})
        self.assertEqual(problems, [])

    def test_garbage_so_is_a_problem_not_a_crash(self):
        problems, _ = v.check_elf({"lib/arm64-v8a/libjunk.so": b"not an elf"}, {})
        self.assertEqual(len(problems), 1)


SAMPLE_XMLTREE = """N: android=http://schemas.android.com/apk/res/android (line=2)
  E: manifest (line=2)
    A: package="com.example.app" (Raw: "com.example.app")
      E: uses-permission (line=11)
        A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.permission.INTERNET" (Raw: "android.permission.INTERNET")
      E: application (line=28)
        A: http://schemas.android.com/apk/res/android:allowBackup(0x01010280)=true
          E: activity (line=65)
            A: http://schemas.android.com/apk/res/android:name(0x01010003)="com.example.app.MainActivity" (Raw: "com.example.app.MainActivity")
            A: http://schemas.android.com/apk/res/android:exported(0x01010010)=true
              E: intent-filter (line=71)
                  E: action (line=72)
                    A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.intent.action.MAIN" (Raw: "android.intent.action.MAIN")
          E: receiver (line=139)
            A: http://schemas.android.com/apk/res/android:name(0x01010003)="androidx.profileinstaller.ProfileInstallReceiver" (Raw: "androidx.profileinstaller.ProfileInstallReceiver")
            A: http://schemas.android.com/apk/res/android:permission(0x01010006)="android.permission.DUMP" (Raw: "android.permission.DUMP")
            A: http://schemas.android.com/apk/res/android:exported(0x01010010)=true
          E: provider (line=84)
            A: http://schemas.android.com/apk/res/android:name(0x01010003)="androidx.core.content.FileProvider" (Raw: "androidx.core.content.FileProvider")
            A: http://schemas.android.com/apk/res/android:exported(0x01010010)=false
"""


def sample_summary(extra=""):
    return v.summarize_manifest(v.parse_xmltree(SAMPLE_XMLTREE + extra))


class ManifestChecks(unittest.TestCase):
    def test_parse_extracts_permissions_components_and_export_state(self):
        s = sample_summary()
        self.assertEqual(s["package"], "com.example.app")
        self.assertEqual(s["usesPermissions"], ["android.permission.INTERNET"])
        comps = {c["name"]: c for c in s["components"]}
        self.assertEqual(comps["com.example.app.MainActivity"]["exported"], "true")
        self.assertEqual(comps["androidx.profileinstaller.ProfileInstallReceiver"]["permission"], "android.permission.DUMP")
        self.assertEqual(comps["androidx.core.content.FileProvider"]["exported"], "false")
        # intent-filter / action children must not be mistaken for components
        self.assertEqual(len(s["components"]), 3)

    def test_matching_allowlist_passes(self):
        s = sample_summary()
        self.assertEqual(v.diff_manifest(s, v.allowlist_document(s)), [])

    def test_new_permission_is_caught(self):
        allowed = v.allowlist_document(sample_summary())
        extra = """      E: uses-permission (line=99)
        A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.permission.READ_CONTACTS" (Raw: "android.permission.READ_CONTACTS")
"""
        # the extra element sits at the manifest child indent, so it parses as a manifest child
        problems = v.diff_manifest(sample_summary(extra), allowed)
        self.assertTrue(any("READ_CONTACTS" in p and "uses-permission" in p for p in problems), problems)

    def test_new_exported_component_is_caught(self):
        allowed = v.allowlist_document(sample_summary())
        extra = """          E: service (line=120)
            A: http://schemas.android.com/apk/res/android:name(0x01010003)="com.example.sdk.SneakyService" (Raw: "com.example.sdk.SneakyService")
            A: http://schemas.android.com/apk/res/android:exported(0x01010010)=true
"""
        problems = v.diff_manifest(sample_summary(extra), allowed)
        self.assertTrue(any("EXPORTED" in p and "SneakyService" in p for p in problems), problems)

    def test_component_flipping_to_exported_is_caught(self):
        allowed = v.allowlist_document(sample_summary())
        flipped = SAMPLE_XMLTREE.replace(
            'FileProvider" (Raw: "androidx.core.content.FileProvider")\n            A: http://schemas.android.com/apk/res/android:exported(0x01010010)=false',
            'FileProvider" (Raw: "androidx.core.content.FileProvider")\n            A: http://schemas.android.com/apk/res/android:exported(0x01010010)=true',
        )
        self.assertNotEqual(flipped, SAMPLE_XMLTREE)
        actual = v.summarize_manifest(v.parse_xmltree(flipped))
        problems = v.diff_manifest(actual, allowed)
        self.assertTrue(any("FileProvider" in p for p in problems), problems)

    def test_removed_permission_is_reported_so_the_allowlist_cannot_go_stale(self):
        allowed = v.allowlist_document(sample_summary())
        allowed["usesPermissions"].append("android.permission.CAMERA")
        problems = v.diff_manifest(sample_summary(), allowed)
        self.assertTrue(any("CAMERA" in p for p in problems), problems)

    def test_debuggable_release_is_caught(self):
        dbg = SAMPLE_XMLTREE.replace(
            "        A: http://schemas.android.com/apk/res/android:allowBackup(0x01010280)=true\n",
            "        A: http://schemas.android.com/apk/res/android:allowBackup(0x01010280)=true\n"
            "        A: http://schemas.android.com/apk/res/android:debuggable(0x0101000f)=true\n",
        )
        actual = v.summarize_manifest(v.parse_xmltree(dbg))
        problems = v.diff_manifest(actual, v.allowlist_document(sample_summary()))
        self.assertTrue(any("debuggable" in p for p in problems), problems)

    def test_empty_dump_is_an_error(self):
        with self.assertRaises(ValueError):
            v.parse_xmltree("not a manifest")

    def test_checked_in_allowlist_is_well_formed(self):
        # Structure only. WHICH permissions/components are allowed is the allow-list's own content and
        # is enforced against the built apk in step 7; asserting it here too would report a deliberate
        # re-pin as "the gate's self-tests failed".
        path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "manifest-allowlist.json")
        with open(path, encoding="utf-8") as f:
            doc = json.load(f)
        self.assertTrue(doc["package"])
        self.assertTrue(doc["usesPermissions"])
        self.assertTrue(doc["components"])
        for c in doc["components"]:
            self.assertEqual(set(c), {"type", "name", "exported", "permission"})
            self.assertIn(c["type"], v.COMPONENT_TAGS)
        # A round trip through the writer's format must diff clean against itself.
        summary = {"package": doc["package"], "usesPermissions": doc["usesPermissions"],
                   "declaredPermissions": doc["declaredPermissions"], "components": doc["components"],
                   "applicationFlags": {}}
        self.assertEqual(v.diff_manifest(summary, doc), [])


class SigningNotes(unittest.TestCase):
    def test_debug_marker_matches_apksigner_dn_format(self):
        # apksigner prints: "Signer #1 certificate DN: C=US, O=Android, CN=Android Debug"
        self.assertIn(v.DEBUG_CERT_MARKER, "C=US, O=Android, CN=Android Debug")


if __name__ == "__main__":
    unittest.main()
