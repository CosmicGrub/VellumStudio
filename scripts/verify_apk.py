#!/usr/bin/env python3
"""Assertions over the built RELEASE apk -- the half of scripts/verify.{sh,ps1} that unit tests and
lint cannot see.

Why this exists as one shared Python file rather than logic duplicated in verify.sh and verify.ps1:
every check here parses a binary format (DEX string table, ELF program headers, aapt2's text dump of
the binary manifest). Written twice, in two shell languages, the two copies would drift and one of
them would quietly stop checking something. Standard library only, so the prerequisite is just a
Python 3 interpreter (present on the dev host and on GitHub's runners).

The release variant is where R8 (renaming, stripping), signing, ABI packaging and manifest merging
all happen, and none of it runs for testDebugUnitTest / lint / assembleDebug, which is all the
(billing-blocked) CI workflow ever did. A change can be green on all three and still ship an app
whose photo import dies with UnsatisfiedLinkError because R8 renamed org.opencv.core.Mat.

Checks, in order (the first failing one decides the exit code; all are still reported):

  classes    R8 did NOT rename/strip the classes that JNI, ML Kit's component loader and
             kotlinx.serialization reach by name -- and R8 DID run (negative control), otherwise
             "not renamed" would be vacuously true for a build with minification switched off.
  zipalign   `zipalign -c -P 16 -v 4`: uncompressed .so entries are 16 KB aligned inside the zip.
  elf        every 64-bit .so's PT_LOAD p_align is >= 16 KB. zipalign does NOT check this (it only
             looks at zip-entry offsets), which is how libxeno_native.so passes zipalign yet still
             draws Android 15+'s "not 16 KB page aligned" warning. See ELF_ALLOWLIST.
  signing    the signer is not the Android Debug certificate -- only asserted when the caller says
             a release keystore is configured (--expect-release-signing); otherwise SKIPPED loudly.
  manifest   the merged manifest's permissions and component set equal scripts/manifest-allowlist.json,
             so a dependency upgrade that quietly adds an exported component or a permission fails
             here instead of shipping.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import struct
import subprocess
import sys
import zipfile

# --------------------------------------------------------------------------------------------
# Configuration: what must survive R8, and the known exceptions. Change these deliberately.
# --------------------------------------------------------------------------------------------

# Classes that are reached by NAME from something R8 cannot see (JNI FindClass, reflection,
# kotlinx.serialization's generated serializer lookup, ML Kit's component discovery). Each maps to a
# keep rule in tablet-app/app/proguard-rules.pro; if one of these disappears, that rule stopped
# working. Dotted names; the DEX descriptor form (Lorg/opencv/core/Mat;) is derived.
REQUIRED_CLASSES = [
    # OpenCV: libopencv_java4.so resolves these by name from native code.
    "org.opencv.core.Mat",
    "org.opencv.android.OpenCVLoader",
    "org.opencv.android.Utils",
    "org.opencv.imgproc.Imgproc",
    # ML Kit pose detection (bundled model): the public API PoseOverlay calls plus the options class.
    "com.google.mlkit.vision.pose.PoseDetection",
    "com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions",
    # kotlinx.serialization runtime: KSerializer is the type every generated serializer() returns and
    # the keep rules are written against. (kotlinx.serialization.json.Json is deliberately NOT listed:
    # it is only called directly, never by name, so R8 renaming it is normal and harmless.)
    "kotlinx.serialization.KSerializer",
    # The app's on-disk JSON formats (see the keep list in proguard-rules.pro). A renamed field or
    # a stripped $$serializer here means save/load breaks at RUNTIME, on a user's device.
    "com.vellum.studio.model.ProjectMeta",
    "com.vellum.studio.model.ProjectMeta$$serializer",
    "com.vellum.studio.model.LayerMeta",
    "com.vellum.studio.model.LayerMeta$$serializer",
    "com.vellum.studio.model.Palette",
    "com.vellum.studio.model.Palette$$serializer",
    "com.vellum.studio.model.UserPhotoTemplate",
    "com.vellum.studio.model.UserPhotoTemplate$$serializer",
    "com.vellum.studio.canvas.Brush",
    "com.vellum.studio.canvas.Brush$$serializer",
    "com.vellum.studio.canvas.BrushCategory",
    # Bundled Academy content is decoded with the strict Json at startup; progress is lenient user data.
    "com.vellum.studio.academy.CourseContentDto$$serializer",
    "com.vellum.studio.academy.AcademyProgressData$$serializer",
]

# String-table entries (not classes) that native code looks up: the JNI field OpenCV's Mat keeps its
# native pointer in. If R8 renames the field the string disappears from the dex.
REQUIRED_DEX_STRINGS = ["nativeObj"]

# Negative control: classes that are NOT kept by any rule, so a minified build must have renamed or
# removed them. If one is still present under its own name, R8 did not run, and the REQUIRED_CLASSES
# check above proves nothing. If a future keep rule legitimately covers one of these, pick another
# unkept class here rather than deleting the control.
MUST_BE_OBFUSCATED = [
    "com.vellum.studio.network.SyncServer",
    "com.vellum.studio.model.ProjectRepository",
]

PAGE_16K = 16 * 1024

# KNOWN EXCEPTION, recorded honestly instead of silently ignored: libxeno_native.so is the native
# runtime bundled inside com.google.mlkit:pose-detection-accurate:17.0.0. It is a closed-source
# prebuilt whose PT_LOAD segments are only 4096-byte aligned (verified with direct ELF inspection,
# see the comment in app/build.gradle.kts `packaging`). We cannot re-link it, and Google publishes no
# newer STABLE release of that artifact (only 17.0.1-beta*/18.0.0-beta*). It zip-aligns fine (so
# `zipalign -P 16` passes) but is not 16 KB ELF-aligned: on a 16 KB-page device it can trigger the
# compatibility-mode warning. Both target devices (Tab S9 FE, Z Fold5) run 4 KB pages today.
# Remove this entry the day ML Kit ships an aligned build: the check below prints a NOTE when the
# allow-listed library turns out to be aligned, so the exception cannot outlive its reason.
ELF_ALLOWLIST = {
    "libxeno_native.so": "closed-source ML Kit prebuilt, 4 KB ELF-aligned, no stable fixed release (see app/build.gradle.kts)",
}

# ABIs whose libraries must be 16 KB ELF-aligned. The requirement is about 64-bit devices only.
ELF_CHECKED_ABIS = ("arm64-v8a", "x86_64")

DEBUG_CERT_MARKER = "CN=Android Debug"

COMPONENT_TAGS = ("activity", "activity-alias", "service", "receiver", "provider")


# --------------------------------------------------------------------------------------------
# DEX
# --------------------------------------------------------------------------------------------

def _uleb128(buf: bytes, pos: int) -> tuple[int, int]:
    result = shift = 0
    while True:
        b = buf[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        shift += 7
        if not b & 0x80:
            return result, pos


def parse_dex(data: bytes) -> tuple[set[str], set[str]]:
    """Returns (class descriptors in the type table, every string in the string table).

    Descriptors look like 'Lorg/opencv/core/Mat;'. Only the two id tables are read (header offsets
    0x38 string_ids, 0x40 type_ids), which is all that is needed to know whether a name survived.
    """
    if data[:4] != b"dex\n":
        raise ValueError("not a DEX file")
    str_count, str_off, type_count, type_off = struct.unpack_from("<IIII", data, 0x38)
    strings: list[str] = []
    for i in range(str_count):
        (off,) = struct.unpack_from("<I", data, str_off + 4 * i)
        _, start = _uleb128(data, off)
        end = data.index(b"\0", start)
        strings.append(data[start:end].decode("utf-8", "replace"))
    types = set()
    for i in range(type_count):
        (idx,) = struct.unpack_from("<I", data, type_off + 4 * i)
        types.add(strings[idx])
    return types, set(strings)


def descriptor(dotted: str) -> str:
    return "L" + dotted.replace(".", "/") + ";"


def check_classes(types: set[str], strings: set[str]) -> list[str]:
    problems = []
    for name in REQUIRED_CLASSES:
        if descriptor(name) not in types:
            problems.append(f"class {name} is missing or was renamed by R8 (check its keep rule in proguard-rules.pro)")
    for s in REQUIRED_DEX_STRINGS:
        if s not in strings:
            problems.append(f"dex string '{s}' is gone -- R8 renamed or stripped a member native code looks up by name")
    for name in MUST_BE_OBFUSCATED:
        if descriptor(name) in types:
            problems.append(
                f"{name} is still present under its own name: R8 did not run on this apk, so the "
                "'not renamed' assertions above are meaningless (is isMinifyEnabled off, or did a keep rule start covering it?)"
            )
    return problems


# --------------------------------------------------------------------------------------------
# ELF
# --------------------------------------------------------------------------------------------

PT_LOAD = 1


def elf_load_aligns(data: bytes) -> list[int]:
    """p_align of every PT_LOAD program header of an ELF32/ELF64 little-endian shared object."""
    if data[:4] != b"\x7fELF":
        raise ValueError("not an ELF file")
    is64 = data[4] == 2
    if data[5] != 1:
        raise ValueError("big-endian ELF is not expected on Android")
    if is64:
        (phoff,) = struct.unpack_from("<Q", data, 0x20)
        phentsize, phnum = struct.unpack_from("<HH", data, 0x36)
        align_at = 0x30
        fmt = "<Q"
    else:
        (phoff,) = struct.unpack_from("<I", data, 0x1C)
        phentsize, phnum = struct.unpack_from("<HH", data, 0x2A)
        align_at = 0x1C
        fmt = "<I"
    aligns = []
    for i in range(phnum):
        base = phoff + i * phentsize
        (p_type,) = struct.unpack_from("<I", data, base)
        if p_type == PT_LOAD:
            (a,) = struct.unpack_from(fmt, data, base + align_at)
            aligns.append(a)
    return aligns


def check_elf(libs: dict[str, bytes], allowlist: dict[str, str]) -> tuple[list[str], list[str]]:
    """libs maps the in-apk path (lib/arm64-v8a/libfoo.so) to its bytes. Returns (problems, notes)."""
    problems: list[str] = []
    notes: list[str] = []
    for path in sorted(libs):
        abi = path.split("/")[1] if path.count("/") >= 2 else ""
        if abi not in ELF_CHECKED_ABIS:
            continue
        base = path.rsplit("/", 1)[-1]
        try:
            aligns = elf_load_aligns(libs[path])
        except (ValueError, struct.error) as e:
            problems.append(f"{path}: cannot read ELF program headers ({e})")
            continue
        if not aligns:
            problems.append(f"{path}: no PT_LOAD segments found")
            continue
        worst = min(aligns)
        aligned = worst >= PAGE_16K
        if base in allowlist:
            if aligned:
                notes.append(f"{path} is now 16 KB aligned (p_align={worst}); remove '{base}' from ELF_ALLOWLIST in scripts/verify_apk.py")
            else:
                notes.append(f"{path}: p_align={worst} -- KNOWN EXCEPTION, allow-listed: {allowlist[base]}")
            continue
        if not aligned:
            problems.append(f"{path}: PT_LOAD p_align={worst} < {PAGE_16K} (not 16 KB page aligned)")
    return problems, notes


# --------------------------------------------------------------------------------------------
# Manifest (aapt2 dump xmltree text)
# --------------------------------------------------------------------------------------------

_ELEM_RE = re.compile(r"^(\s*)E: ([\w.:-]+)")
# `A: http://schemas.android.com/apk/res/android:name(0x01010003)="x" (Raw: "x")` or `A: package="x"`.
_ATTR_RE = re.compile(r"^(\s*)A: (?:[^\s=]*?:)?([A-Za-z_][\w.-]*)(?:\(0x[0-9a-fA-F]+\))?=(.*)$")
_QUOTED_RE = re.compile(r'^"((?:[^"\\]|\\.)*)"')


class Node:
    def __init__(self, tag: str, indent: int):
        self.tag = tag
        self.indent = indent
        self.attrs: dict[str, str] = {}
        self.children: list[Node] = []


def parse_xmltree(text: str) -> Node:
    """Parses `aapt2 dump xmltree --file AndroidManifest.xml <apk>` into a tree of Nodes.

    Nesting is recovered from indentation (aapt2 indents children deeper than their parent; the exact
    step is not constant, so only 'deeper than' is relied on). Attribute values are unquoted strings;
    booleans come out as 'true'/'false', resource refs as '@0x7f...'.
    """
    root = Node("#root", -1)
    stack = [root]
    last: Node | None = None
    for line in text.splitlines():
        m = _ELEM_RE.match(line)
        if m:
            indent = len(m.group(1))
            while stack[-1].indent >= indent:
                stack.pop()
            node = Node(m.group(2), indent)
            stack[-1].children.append(node)
            stack.append(node)
            last = node
            continue
        m = _ATTR_RE.match(line)
        if m and last is not None:
            raw = m.group(3)
            q = _QUOTED_RE.match(raw)
            last.attrs[m.group(2)] = q.group(1) if q else raw.split(" ")[0]
    if not root.children:
        raise ValueError("no elements found in the aapt2 xmltree output")
    return root


def summarize_manifest(root: Node) -> dict:
    manifest = next((c for c in root.children if c.tag == "manifest"), None)
    if manifest is None:
        raise ValueError("no <manifest> element")
    uses = sorted(c.attrs["name"] for c in manifest.children if c.tag == "uses-permission" and "name" in c.attrs)
    declared = sorted(c.attrs["name"] for c in manifest.children if c.tag == "permission" and "name" in c.attrs)
    app = next((c for c in manifest.children if c.tag == "application"), None)
    components = []
    app_flags = {}
    if app is not None:
        app_flags = {k: app.attrs.get(k) for k in ("debuggable", "testOnly")}
        for c in app.children:
            if c.tag in COMPONENT_TAGS:
                components.append({
                    "type": c.tag,
                    "name": c.attrs.get("name", "?"),
                    "exported": c.attrs.get("exported"),
                    "permission": c.attrs.get("permission"),
                })
    components.sort(key=lambda c: (c["type"], c["name"]))
    return {
        "package": manifest.attrs.get("package"),
        "usesPermissions": uses,
        "declaredPermissions": declared,
        "components": components,
        "applicationFlags": app_flags,
    }


def _component_key(c: dict) -> tuple:
    return (c["type"], c["name"], c.get("exported"), c.get("permission"))


def diff_manifest(actual: dict, allowed: dict) -> list[str]:
    problems = []
    flags = actual.get("applicationFlags", {})
    if flags.get("debuggable") == "true":
        problems.append("release manifest has android:debuggable=true")
    if flags.get("testOnly") == "true":
        problems.append("release manifest has android:testOnly=true")
    if actual.get("package") != allowed.get("package"):
        problems.append(f"package is {actual.get('package')!r}, allow-list says {allowed.get('package')!r}")
    for key, label in (("usesPermissions", "uses-permission"), ("declaredPermissions", "permission")):
        got, want = set(actual[key]), set(allowed.get(key, []))
        for p in sorted(got - want):
            problems.append(f"new {label} not in the allow-list: {p}")
        for p in sorted(want - got):
            problems.append(f"{label} in the allow-list but no longer in the apk: {p}")
    got_c = {_component_key(c): c for c in actual["components"]}
    want_c = {_component_key(c): c for c in allowed.get("components", [])}
    for k in sorted(set(got_c) - set(want_c), key=str):
        exported = "EXPORTED " if got_c[k].get("exported") == "true" else ""
        problems.append(f"new {exported}component not in the allow-list: {k[0]} {k[1]} (exported={k[2]}, permission={k[3]})")
    for k in sorted(set(want_c) - set(got_c), key=str):
        problems.append(f"component in the allow-list but not in the apk (or changed exported/permission): {k[0]} {k[1]} (exported={k[2]}, permission={k[3]})")
    return problems


def allowlist_document(summary: dict) -> dict:
    return {
        "_comment": (
            "Pinned from the merged RELEASE manifest by scripts/verify_apk.py. A diff here is a deliberate decision: "
            "every new permission or exported component is attack surface or a privacy change. Regenerate with "
            "`python scripts/verify_apk.py --apk <apk> --build-tools <dir> --write-manifest-allowlist` and review the git diff."
        ),
        "package": summary["package"],
        "usesPermissions": summary["usesPermissions"],
        "declaredPermissions": summary["declaredPermissions"],
        "components": summary["components"],
    }


# --------------------------------------------------------------------------------------------
# External tools (build-tools)
# --------------------------------------------------------------------------------------------

def find_tool(build_tools: str, name: str) -> str:
    for ext in (".exe", ".bat", ".cmd", ""):
        p = os.path.join(build_tools, name + ext)
        if os.path.isfile(p):
            return p
    raise FileNotFoundError(f"{name} not found in {build_tools}")


def run(cmd: list[str]) -> tuple[int, str]:
    p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, errors="replace")
    return p.returncode, p.stdout


def check_zipalign(build_tools: str, apk: str) -> list[str]:
    rc, out = run([find_tool(build_tools, "zipalign"), "-c", "-P", "16", "-v", "4", apk])
    problems = []
    lib_ok = 0
    for line in out.splitlines():
        if "lib/" in line:
            if "(OK" in line:
                lib_ok += 1
            else:
                problems.append(f"zipalign: {line.strip()}")
    if rc != 0 and not problems:
        problems.append(f"zipalign -c -P 16 -v 4 exited {rc}: {out.strip().splitlines()[-1] if out.strip() else '(no output)'}")
    if lib_ok == 0 and not problems:
        problems.append("zipalign reported no lib/ entries -- the native libraries are missing from the apk")
    return problems


def check_signing(build_tools: str, apk: str, expect_release: bool) -> tuple[list[str], list[str]]:
    rc, out = run([find_tool(build_tools, "apksigner"), "verify", "--print-certs", apk])
    problems, notes = [], []
    if rc != 0:
        problems.append(f"apksigner verify failed: {out.strip().splitlines()[0] if out.strip() else rc}")
        return problems, notes
    dn = next((l.split(":", 1)[1].strip() for l in out.splitlines() if "certificate DN:" in l), "")
    sha = next((l.split(":", 1)[1].strip() for l in out.splitlines() if "SHA-256 digest:" in l), "")
    notes.append(f"signer: {dn} (SHA-256 {sha})")
    if expect_release and DEBUG_CERT_MARKER in dn:
        problems.append("apk is signed with the Android Debug certificate although tablet-app/keystore.properties exists (signing config silently fell back to debug)")
    return problems, notes


# --------------------------------------------------------------------------------------------
# Driver
# --------------------------------------------------------------------------------------------

def load_apk(apk: str) -> tuple[list[tuple[str, bytes]], dict[str, bytes]]:
    dexes, libs = [], {}
    with zipfile.ZipFile(apk) as z:
        for name in z.namelist():
            if re.fullmatch(r"classes\d*\.dex", name):
                dexes.append((name, z.read(name)))
            elif name.startswith("lib/") and name.endswith(".so"):
                libs[name] = z.read(name)
    return dexes, libs


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--apk", required=True)
    ap.add_argument("--build-tools", required=True, help="Android SDK build-tools directory (zipalign, apksigner, aapt2)")
    ap.add_argument("--manifest-allowlist", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "manifest-allowlist.json"))
    ap.add_argument("--expect-release-signing", action="store_true", help="fail if the apk is signed with the Android Debug certificate")
    ap.add_argument("--write-manifest-allowlist", action="store_true", help="regenerate the allow-list from this apk and exit (a deliberate act; review the diff)")
    args = ap.parse_args(argv)

    if not os.path.isfile(args.apk):
        print(f"REASON: release apk not found at {args.apk}")
        return 1

    results: list[tuple[str, list[str]]] = []

    def report(name: str, problems: list[str], notes: list[str] | None = None) -> None:
        for n in notes or []:
            print(f"  note: {n}")
        status = "FAIL" if problems else "ok"
        print(f"[{status}] {name}")
        for p in problems:
            print(f"    - {p}")
        results.append((name, problems))

    print(f"APK: {args.apk} ({os.path.getsize(args.apk) / 1e6:.1f} MB)")

    tools_problem = None
    try:
        aapt2 = find_tool(args.build_tools, "aapt2")
    except FileNotFoundError as e:
        tools_problem = str(e)

    if args.write_manifest_allowlist:
        if tools_problem:
            print(f"REASON: {tools_problem}")
            return 1
        rc, out = run([aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", args.apk])
        if rc != 0:
            print(f"REASON: aapt2 dump xmltree failed: {out.strip()[:200]}")
            return 1
        doc = allowlist_document(summarize_manifest(parse_xmltree(out)))
        with open(args.manifest_allowlist, "w", encoding="utf-8", newline="\n") as f:
            json.dump(doc, f, indent=2)
            f.write("\n")
        print(f"wrote {args.manifest_allowlist}")
        return 0

    dexes, libs = load_apk(args.apk)

    # classes
    if not dexes:
        report("classes survive R8", ["no classes*.dex in the apk"])
    else:
        types, strings = set(), set()
        for _, blob in dexes:
            t, s = parse_dex(blob)
            types |= t
            strings |= s
        report("classes survive R8 (OpenCV, ML Kit, serializers) and R8 actually ran", check_classes(types, strings))

    # zipalign
    if tools_problem:
        report("zipalign -c -P 16 -v 4", [tools_problem])
    else:
        report("zipalign -c -P 16 -v 4 (zip-level 16 KB alignment of .so entries)", check_zipalign(args.build_tools, args.apk))

    # elf
    elf_problems, elf_notes = check_elf(libs, ELF_ALLOWLIST)
    if not any(p.split("/")[1] in ELF_CHECKED_ABIS for p in libs if p.count("/") >= 2):
        elf_problems.append("no 64-bit native libraries found in the apk")
    report("ELF PT_LOAD p_align >= 16 KB (libxeno_native.so is a documented exception)", elf_problems, elf_notes)

    # signing
    if tools_problem:
        report("signing certificate", [tools_problem])
    else:
        sp, sn = check_signing(args.build_tools, args.apk, args.expect_release_signing)
        if not args.expect_release_signing:
            sn.append("SIGNING-IDENTITY ASSERTION SKIPPED: no tablet-app/keystore.properties, so this apk is expected to be debug-signed. It is NOT shippable.")
        report("signing certificate is not the Android Debug one" if args.expect_release_signing else "apk signature verifies (identity assertion skipped)", sp, sn)

    # manifest
    if tools_problem:
        report("manifest allow-list", [tools_problem])
    else:
        rc, out = run([aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", args.apk])
        if rc != 0:
            report("manifest allow-list", [f"aapt2 dump xmltree failed: {out.strip()[:200]}"])
        elif not os.path.isfile(args.manifest_allowlist):
            report("manifest allow-list", [f"{args.manifest_allowlist} does not exist (create it with --write-manifest-allowlist)"])
        else:
            with open(args.manifest_allowlist, encoding="utf-8") as f:
                allowed = json.load(f)
            report("merged manifest matches the permission/component allow-list", diff_manifest(summarize_manifest(parse_xmltree(out)), allowed))

    failed = [(n, p) for n, p in results if p]
    if failed:
        name, probs = failed[0]
        print(f"REASON: release-apk check '{name}' failed: {probs[0]}")
        return 1
    print("release apk assertions: all passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
