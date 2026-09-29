# Releasing Vellum Studio (tablet app)

Releases fail closed and leave a trail: a release APK is only produced with real signing, it says which
commit it came from, it is checksummed, and the R8 mapping needed to read its crash traces is archived
next to it. Everything here is local and free; nothing is uploaded anywhere.

## One-time setup (per checkout / worktree)

1. Copy the template and fill it in:

   ```
   cp tablet-app/keystore.properties.example tablet-app/keystore.properties
   ```

   `storeFile` should be an absolute path to a keystore that lives OUTSIDE every git worktree.
   `keystore.properties`, `*.jks` and `*.keystore` are gitignored; never commit them.
2. That is all. Without a usable `keystore.properties` (missing, a key blank, or `storeFile` pointing at
   nothing) `assembleRelease`, `bundleRelease`, `installRelease` and `packageRelease` **fail at task-graph
   time** with a message pointing at `keystore.properties.example`. Debug builds, unit tests, lint and the
   `benchmark` build type are unaffected (CI only runs those).

   A bare `gradlew build` / `gradlew assemble` includes `assembleRelease`, so it now needs the keystore too.
   Use `assembleDebug` / `testDebugUnitTest` for day-to-day work.

### Opting out (verification builds only)

`-PallowDebugSignedRelease=true` signs the release variant with the debug key and prints a warning. Such
an APK **cannot be installed over a real-signed one** (signature mismatch) and must never be distributed.
It exists for local verification in trees that have no keystore. `scripts/release.sh` exposes it as
`ALLOW_DEBUG_SIGNED=1` and names the output `...-DEBUGSIGNED.apk`.

## Versioning

`tablet-app/version.properties` is the single source for `versionCode` and `versionName`
(`app/build.gradle.kts` reads it). To bump for a release, edit that one file. `versionCode` must strictly
increase over anything ever installed. Main and both device branches carry the same file, so bump it on
main and merge/cherry-pick that one commit into the device branches -- do not hand-edit three copies.

## Cutting a release build

From the worktree of the branch you are releasing (main, and each `device/*` branch):

```
bash scripts/release.sh
```

It refuses to run on uncommitted changes to tracked files (so the sha baked into the build is honest),
builds `:app:assembleRelease`, runs `apksigner verify --print-certs`, refuses an APK signed with the
Android debug certificate, and writes into `release-artifacts/` (gitignored):

| File | What |
| --- | --- |
| `vellum-studio-<versionName>-<branch>.apk` | the signed APK (`device/` prefix stripped from the branch) |
| `vellum-studio-<versionName>-<branch>.mapping.txt` | R8 mapping for that exact APK -- keep it forever |
| `SHA256SUMS` | `sha256sum -c` format, covering every `.apk` and `.mapping.txt` in the folder |

Point `RELEASE_OUT_DIR` at one shared folder to collect all three branches' outputs and one `SHA256SUMS`.
Check them with `cd release-artifacts && sha256sum -c SHA256SUMS`. The script does not tag, push or publish;
that stays a deliberate manual step. Other knobs are documented at the top of the script (`ALLOW_DIRTY`,
`SKIP_APKSIGNER`, `GRADLE_CMD`, `APKSIGNER`, `BRANCH_NAME`).

## Knowing what a build is

`BuildConfig.GIT_SHA` (short commit, `unknown` if git was unavailable at build time) and `BuildConfig.BRANCH`
(`detached` for a detached HEAD) are stamped in at configuration time. They appear in **Settings > About**
(`0.2.1 (d9bee6c663 on main)`) and in the first line of every `diagnostic.log` session (`App started (...)`).
An exported log therefore says which of the branch builds it came from.

## Reading a release crash

Release builds are minified with R8. `proguard-rules.pro` keeps `SourceFile,LineNumberTable` and renames the
source file to `SourceFile`, so frames in a `[CRASH]` entry of `diagnostic.log` look like
`at a.b.c.d(SourceFile:37)`. Decode them with the archived mapping for that exact APK (`retrace` ships in the
Android SDK's cmdline-tools):

```
retrace vellum-studio-0.2.1-main.mapping.txt crash-trace.txt
```

Without the matching `mapping.txt` the trace cannot be recovered, which is why the release script archives it.

### Deaths the app itself cannot log

On API 30+ the app reads `ActivityManager.getHistoricalProcessExitReasons` at start and writes one
`[ExitInfo]` entry per new previous-process exit into `diagnostic.log` (deduplicated, so relaunching does not
repeat them): the reason (`LOW_MEMORY`, `ANR`, `CRASH_NATIVE`, `USER_REQUESTED`, ...), status, importance at
death, pss/rss, the system's description and, for ANRs and native crashes, the head of the trace. That is the
record of low-memory kills, ANRs and native crashes in OpenCV / ML Kit that an in-process handler never sees.
On API 29 it is a no-op.

## What was verified when this was written

Run against a tree with no `keystore.properties` (Gradle 8.13, AGP 8.7.2):

* `:app:assembleRelease`, `:app:bundleRelease`, `:app:installRelease` -> `BUILD FAILED`, message names
  `keystore.properties.example`. A copy of the `.example` left unedited fails the same way (storeFile missing).
* `-PallowDebugSignedRelease=true` -> proceeds with a warning.
* `:app:assembleDebug`, `:app:lintDebug`, `:app:lintVitalRelease`, `:app:assembleBenchmark`,
  `:benchmark:assemble` -> not affected.
* With a throwaway keystore: `scripts/release.sh` built, `apksigner verify` passed, `SHA256SUMS` verified with
  `sha256sum -c`, and the merged R8 configuration contained the SourceFile/LineNumberTable rules.
