<#
.SYNOPSIS
  Local verification gate (PowerShell twin of scripts/verify.sh; keep the two in step).

.DESCRIPTION
  The answer to "is this change safe to ship?" that CI cannot give (the GitHub workflow is
  billing-blocked and never built the R8 release variant anyway).

    scripts\verify.ps1          full gate, several minutes:
      1. frozen files unchanged         (StrokeRenderer.kt, BrushStampCache.kt vs scripts\frozen-files.sha256)
      2. gate self-tests                (scripts\tests)
      3. README counts match the code   (scripts\check_docs.py)
      4. :app:testDebugUnitTest
      5. :app:lint
      6. :app:assembleRelease           (R8 on; signing per keystore.properties)
      7. release-apk assertions         (scripts\verify_apk.py: OpenCV / ML Kit / serializers not renamed,
                                         16 KB alignment, non-debug signer, manifest allow-list)
    scripts\verify.ps1 -Fast    steps 1-4 only (what the pre-push hook runs, via verify.sh).

  Stops at the FIRST failing step, writes one line "VERIFY FAILED: [step] reason" to stderr and exits 1.

  SIGNING: when tablet-app\keystore.properties exists the release APK must be signed with that key.
  When it does not (CI, fresh clone, secondary worktree) the script passes
  -PallowDebugSignedRelease=true to assembleRelease and says loudly that the signing assertion was
  skipped: that APK is debug-signed and NOT shippable.

  Environment overrides: VERIFY_GRADLE (command prefix that receives the gradle args, default
  .\gradlew.bat in tablet-app), ANDROID_HOME / ANDROID_SDK_ROOT, BUILD_TOOLS, PYTHON.
#>
[CmdletBinding()]
param([switch]$Fast)

$Repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$App = Join-Path $Repo 'tablet-app'
$Total = if ($Fast) { 4 } else { 7 }
$script:StepN = 0
$script:StepName = 'startup'

function Begin-Step([string]$name) {
    $script:StepN++
    $script:StepName = $name
    Write-Host ''
    Write-Host "=== [$($script:StepN)/$Total] $name"
}

function Fail([string]$reason) {
    [Console]::Error.WriteLine('')
    [Console]::Error.WriteLine("VERIFY FAILED: [$($script:StepName)] $reason")
    exit 1
}

function Find-Python {
    $candidates = @()
    if ($env:PYTHON) { $candidates += $env:PYTHON }
    $candidates += 'python3', 'python', 'py'
    foreach ($c in $candidates) {
        if (-not (Get-Command $c -ErrorAction SilentlyContinue)) { continue }
        & $c -c 'import sys; sys.exit(0 if sys.version_info[0] == 3 else 1)' *> $null
        if ($LASTEXITCODE -eq 0) { return $c }
    }
    return $null
}

function Invoke-Gradle {
    param([string[]]$GradleArgs)
    if ($env:VERIFY_GRADLE) {
        $parts = @($env:VERIFY_GRADLE -split '\s+' | Where-Object { $_ })
        $rest = if ($parts.Count -gt 1) { $parts[1..($parts.Count - 1)] } else { @() }
        & $parts[0] @rest @GradleArgs | Out-Host
    } else {
        Push-Location $App
        try { & .\gradlew.bat @GradleArgs '--console=plain' | Out-Host } finally { Pop-Location }
    }
    return $LASTEXITCODE
}

# SHA-256 of the file with CR bytes removed: same normalisation as check-frozen.sh, so a CRLF
# checkout (core.autocrlf=true) and an LF checkout hash identically.
function Get-NormalizedSha256([string]$path) {
    $bytes = [System.IO.File]::ReadAllBytes($path)
    $kept = New-Object System.Collections.Generic.List[byte] ($bytes.Length)
    foreach ($b in $bytes) { if ($b -ne 13) { $kept.Add($b) } }
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try { return (($sha.ComputeHash($kept.ToArray()) | ForEach-Object { $_.ToString('x2') }) -join '') } finally { $sha.Dispose() }
}

function Test-Frozen {
    $manifest = Join-Path $Repo 'scripts/frozen-files.sha256'
    if (-not (Test-Path $manifest)) { [Console]::Error.WriteLine("FROZEN CHECK FAILED: manifest $manifest is missing"); return $false }
    $frozen = @(
        'tablet-app/app/src/main/java/com/vellum/studio/canvas/StrokeRenderer.kt',
        'tablet-app/app/src/main/java/com/vellum/studio/canvas/BrushStampCache.kt'
    )
    $lines = Get-Content $manifest
    $ok = $true
    foreach ($f in $frozen) {
        $entry = $lines | Where-Object { $_.EndsWith("  $f") } | Select-Object -First 1
        if (-not $entry) { [Console]::Error.WriteLine("FROZEN CHECK FAILED: $f has no entry in frozen-files.sha256"); $ok = $false; continue }
        $want = ($entry -split ' ')[0]
        $full = Join-Path $Repo $f
        if (-not (Test-Path $full)) { [Console]::Error.WriteLine("FROZEN CHECK FAILED: $f is missing"); $ok = $false; continue }
        $got = Get-NormalizedSha256 $full
        if ($got -ne $want) { [Console]::Error.WriteLine("FROZEN CHECK FAILED: $f changed (sha256 $got, expected $want)"); $ok = $false }
    }
    if (-not $ok) {
        [Console]::Error.WriteLine(@'

StrokeRenderer.kt and BrushStampCache.kt are FROZEN BY DESIGN: they are the dab loop, and pen feel
and latency depend on them being exactly what was measured. An owner decision is required to change
them. New capability should wrap or compose around them instead (see docs/ARCHITECTURE.md).

If the owner HAS approved the change, update the manifest deliberately, in the same commit:
    bash scripts/check-frozen.sh --update
and say so in the commit message. To undo an accidental edit:
    git checkout -- <file>
'@)
        return $false
    }
    Write-Host "frozen files unchanged ($($frozen.Count) checked)"
    return $true
}

$Py = Find-Python
if (-not $Py) { Fail 'no Python 3 interpreter found (needed for the APK and docs checks); install Python 3 or set PYTHON' }

# ---- 1. frozen files -------------------------------------------------------------------------
Begin-Step 'frozen files unchanged (StrokeRenderer.kt, BrushStampCache.kt)'
if (-not (Test-Frozen)) { Fail 'a frozen file changed -- an owner decision is required (see the message above)' }

# ---- 2. gate self-tests ----------------------------------------------------------------------
Begin-Step 'gate self-tests (scripts/tests)'
& $Py -m unittest discover -s (Join-Path $Repo 'scripts/tests') -p 'test_*.py'
if ($LASTEXITCODE -ne 0) { Fail "the verification scripts' own tests failed: a check has stopped catching what it should" }

# ---- 3. docs ---------------------------------------------------------------------------------
Begin-Step 'README counts match the code'
& $Py (Join-Path $Repo 'scripts/check_docs.py') --root $Repo
if ($LASTEXITCODE -ne 0) { Fail 'README.md is stale relative to the code (see above)' }

# ---- 4. unit tests ---------------------------------------------------------------------------
Begin-Step 'unit tests (:app:testDebugUnitTest)'
if ((Invoke-Gradle @(':app:testDebugUnitTest')) -ne 0) { Fail 'unit tests failed (report: tablet-app/app/build/reports/tests/testDebugUnitTest/index.html)' }

if ($Fast) {
    Write-Host ''
    Write-Host 'VERIFY OK (fast subset: steps 1-4). Run scripts\verify.ps1 without -Fast before shipping: lint and the R8 release build were NOT checked.'
    exit 0
}

# ---- 5. lint ---------------------------------------------------------------------------------
Begin-Step 'lint (:app:lint)'
if ((Invoke-Gradle @(':app:lint')) -ne 0) { Fail 'lint failed (report: tablet-app/app/build/reports/lint-results-debug.html)' }

# ---- 6. release build ------------------------------------------------------------------------
Begin-Step 'release build (:app:assembleRelease, R8 on)'
$releaseArgs = @(':app:assembleRelease')
$expectSigning = @()
if (Test-Path (Join-Path $App 'keystore.properties')) {
    Write-Host 'tablet-app/keystore.properties found: the release APK must be signed with the real key.'
    $expectSigning = @('--expect-release-signing')
} else {
    $releaseArgs += '-PallowDebugSignedRelease=true'
    Write-Host '############################################################################'
    Write-Host '# tablet-app/keystore.properties is ABSENT.'
    Write-Host '# Building with -PallowDebugSignedRelease=true: the APK will be DEBUG-SIGNED.'
    Write-Host '# The signing-identity assertion is SKIPPED. This APK is NOT shippable.'
    Write-Host '############################################################################'
}
if ((Invoke-Gradle $releaseArgs) -ne 0) { Fail 'assembleRelease failed' }

# ---- 7. release apk assertions ---------------------------------------------------------------
Begin-Step 'release apk assertions (R8 keeps, 16 KB alignment, signer, manifest)'
$apkDir = Join-Path $App 'app/build/outputs/apk/release'
$apk = Join-Path $apkDir 'app-release.apk'
if (-not (Test-Path $apk)) {
    $found = Get-ChildItem $apkDir -Filter *.apk -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($found) { $apk = $found.FullName }
}
if (-not (Test-Path $apk)) { Fail "no release APK found under $apkDir" }

$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) {
    $lp = Join-Path $App 'local.properties'
    if (Test-Path $lp) {
        $line = Get-Content $lp | Where-Object { $_ -like 'sdk.dir=*' } | Select-Object -First 1
        if ($line) { $sdk = ($line.Substring(8) -replace '\\\\', '/' -replace '\\:', ':') }
    }
}
if (-not $sdk) { Fail 'cannot locate the Android SDK (set ANDROID_HOME, or sdk.dir in tablet-app/local.properties)' }
$bt = $env:BUILD_TOOLS
if (-not $bt) {
    $latest = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory -ErrorAction SilentlyContinue |
        Sort-Object { try { [version]$_.Name } catch { [version]'0.0' } } | Select-Object -Last 1
    if (-not $latest) { Fail "no build-tools under $sdk/build-tools" }
    $bt = $latest.FullName
}

$out = & $Py (Join-Path $Repo 'scripts/verify_apk.py') --apk $apk --build-tools $bt `
    --manifest-allowlist (Join-Path $Repo 'scripts/manifest-allowlist.json') @expectSigning 2>&1 | ForEach-Object { "$_" }
$rc = $LASTEXITCODE
$out | ForEach-Object { Write-Host $_ }
if ($rc -ne 0) {
    $reason = ($out | Where-Object { $_ -like 'REASON: *' } | Select-Object -Last 1)
    if ($reason) { $reason = $reason.Substring(8) } else { $reason = 'release apk assertions failed (see above)' }
    Fail $reason
}

Write-Host ''
if ($expectSigning.Count -eq 0) {
    Write-Host 'VERIFY OK -- BUT the signing assertion was SKIPPED (no keystore.properties): the release APK is debug-signed and NOT shippable.'
} else {
    Write-Host 'VERIFY OK (full gate, release APK signed with the real key).'
}
exit 0
