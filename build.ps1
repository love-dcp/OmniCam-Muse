# Native tools (java/gradle/apksigner) write progress and notices to stderr. PowerShell 5.1 turns
# a native stderr write into an error record, and under 'Stop' the first one becomes a terminating
# NativeCommandError even when the tool exits 0 (javac's "unchecked operation" note is enough to
# abort the script). Silence those records and gate every native step on $LASTEXITCODE instead.
$ErrorActionPreference = 'SilentlyContinue'
$project = $PSScriptRoot
$python = if ($env:OMNICAM_PYTHON) { $env:OMNICAM_PYTHON } else { 'python' }
# SilentlyContinue also suppresses throw in this PowerShell runtime. Keep native stderr quiet,
# but fail through an explicit exit so a compile or packaging error can never continue into a
# stale artifact.
function Stop-Build([string]$message) {
    [Console]::Error.WriteLine("BUILD ERROR: $message")
    exit 1
}
# This runner can report user.home as C:\ even when the real profile lives below
# C:\Users. Gradle then tries to create C:\.gradle and fails before the build
# starts. Prefer the checked-in local cache when one is available.
if (-not $env:GRADLE_USER_HOME) {
    $localGradleHome = Join-Path $project '.gradle-user-home'
    if (Test-Path -LiteralPath $localGradleHome) {
        $env:GRADLE_USER_HOME = $localGradleHome
    }
}
$gradleUserProfile = if ($env:USERPROFILE) {
    $env:USERPROFILE
} else {
    [Environment]::GetFolderPath('UserProfile')
}
if ($gradleUserProfile -and
        ($env:JAVA_TOOL_OPTIONS -notmatch '(^|\s)-Duser\.home=')) {
    $env:JAVA_TOOL_OPTIONS = (($env:JAVA_TOOL_OPTIONS + ' -Duser.home=' +
        $gradleUserProfile).Trim())
}
$version = ((Get-Content (Join-Path $project 'module/module.prop') | Where-Object { $_ -like 'version=*' }) -replace '^version=(Muse )?', '').Trim()
# module.prop is the single source of truth for the version. app/build.gradle.kts used to carry its
# own copy, so bumping module.prop alone produced an APK that still announced the old release
# (r149 shipped an APK reporting "Muse 4.4.4-r147"). Both values are passed to Gradle below and the
# built APK is read back afterwards, so this cannot drift again without failing the build.
$versionName = ((Get-Content (Join-Path $project 'module/module.prop') | Where-Object { $_ -like 'version=*' }) -replace '^version=', '').Trim()
$versionCode = ((Get-Content (Join-Path $project 'module/module.prop') | Where-Object { $_ -like 'versionCode=*' }) -replace '^versionCode=', '').Trim()
if (-not $versionName -or -not $versionCode) { Stop-Build 'module/module.prop is missing version= or versionCode=.' }
$sdkProperties = Join-Path $project 'local.properties'
if (-not (Test-Path -LiteralPath $sdkProperties)) {
    if (-not $env:ANDROID_HOME) { Stop-Build 'Set ANDROID_HOME or create local.properties with sdk.dir.' }
    Set-Content -LiteralPath $sdkProperties -Encoding utf8NoBOM -Value ('sdk.dir=' + $env:ANDROID_HOME.Replace('\', '/'))
}
$sdkDir = ((Get-Content -LiteralPath $sdkProperties | Where-Object { $_ -like 'sdk.dir=*' }) -replace '^sdk.dir=', '').Trim()
$buildToolsDir = $null
$btPath = Join-Path $sdkDir.Replace('/', '\') 'build-tools'
if (Test-Path -LiteralPath $btPath) {
    $buildToolsDir = Get-ChildItem -Path $btPath -Directory | Sort-Object Name | Select-Object -Last 1
}
& $python (Join-Path $project 'tools/mastercolor/check_catalog.py')
if ($LASTEXITCODE -ne 0) { Stop-Build 'Master colour catalog validation failed.' }
& (Join-Path $project 'tools/offline_tests/run.ps1')
if ($LASTEXITCODE -ne 0) { Stop-Build 'Offline tests failed.' }
& 'C:/Program Files/Git/bin/bash.exe' -n (Join-Path $project 'module/post-fs-data.sh') (Join-Path $project 'module/post-mount.sh') (Join-Path $project 'module/service.sh') (Join-Path $project 'module/uninstall.sh') (Join-Path $project 'module/uninstall_full.sh') (Join-Path $project 'module/customize.sh') (Join-Path $project 'module/selfheal.sh') (Join-Path $project 'module/action.sh') (Join-Path $project 'module/apply-now.sh')
if ($LASTEXITCODE -ne 0) { Stop-Build 'Module shell syntax validation failed.' }
$buildStart = Get-Date
Push-Location $project
try {
    $gradleLines = @(
        & .\gradlew.bat :app:assembleRelease --no-daemon --console=plain `
            "-Pkotlin.compiler.execution.strategy=in-process" `
            "-PomnicamVersionCode=$versionCode" "-PomnicamVersionName=$versionName" 2>&1
    )
    $gradleExitCode = $LASTEXITCODE
    $gradleOutput = $gradleLines -join "`n"
    $gradleLines | ForEach-Object { Write-Output $_ }
    $gradleLog = Join-Path $project 'app/build/reports/gradle-release-build.log'
    $gradleLogDir = Split-Path -Parent $gradleLog
    if (-not (Test-Path -LiteralPath $gradleLogDir)) {
        New-Item -ItemType Directory -Path $gradleLogDir -Force | Out-Null
    }
    Set-Content -LiteralPath $gradleLog -Value $gradleOutput -Encoding utf8
    if ($gradleExitCode -ne 0 -or
            $gradleOutput -match '(?m)^\s*(BUILD FAILED|> Task .* FAILED)\s*$') {
        Stop-Build "Gradle build failed with exit code $gradleExitCode. See $gradleLog"
    }
} finally {
    Pop-Location
}
$signed = Test-Path -LiteralPath (Join-Path $project 'port.keystore')
if ($env:OMNICAM_KEYSTORE) { $signed = Test-Path -LiteralPath $env:OMNICAM_KEYSTORE }
$apkFile = if ($signed) { 'app-release.apk' } else { 'app-release-unsigned.apk' }
$releaseDir = Join-Path $project 'app/build/outputs/apk/release'
$gradleBuilt = Join-Path $releaseDir $apkFile
if (-not (Test-Path -LiteralPath $gradleBuilt)) { Stop-Build "Missing Gradle output: $gradleBuilt" }
# Check the APK produced by Gradle before the fallback signing step below. Re-aligning and
# re-signing an old unsigned APK gives it a new timestamp, which previously hid a failed Gradle
# run and let the stale APK pass the freshness gate.
if ((Get-Item -LiteralPath $gradleBuilt).LastWriteTime -lt $buildStart) {
    Stop-Build "Stale Gradle output: $gradleBuilt was not rebuilt by this run."
}
# Without a release keystore the output stays unsigned and the phone refuses the update over the
# installed app, so a plain .\build.ps1 used to leave module/ stale with no error. Fall back to the
# standard debug keystore - the certificate the module has been signed with all along - and align
# first, the way the manual step did.
$debugKey = Join-Path $env:USERPROFILE '.android\debug.keystore'
if (-not $signed -and (Test-Path -LiteralPath $debugKey)) {
    $sdkDir = ((Get-Content (Join-Path $project 'local.properties') | Where-Object { $_ -like 'sdk.dir=*' }) -replace '^sdk.dir=', '').Trim()
    $buildTools = Get-ChildItem -Path (Join-Path $sdkDir.Replace('/', '\') 'build-tools') -Directory |
        Sort-Object Name | Select-Object -Last 1
    $unsigned = Join-Path $releaseDir 'app-release-unsigned.apk'
    $aligned = Join-Path $releaseDir 'app-release-aligned.apk'
    $signedApk = Join-Path $releaseDir 'app-release.apk'
    & (Join-Path $buildTools.FullName 'zipalign.exe') -f -p 4 $unsigned $aligned
    if ($LASTEXITCODE -ne 0) { Stop-Build 'zipalign failed.' }
    & (Join-Path $buildTools.FullName 'apksigner.bat') sign --ks $debugKey --ks-pass pass:android `
        --ks-key-alias androiddebugkey --key-pass pass:android --out $signedApk $aligned
    if ($LASTEXITCODE -ne 0) { Stop-Build 'apksigner failed.' }
    $signed = $true
    $apkFile = 'app-release.apk'
}
$built = Join-Path $project "app/build/outputs/apk/release/$apkFile"
if (-not (Test-Path -LiteralPath $built)) { Stop-Build "Missing output: $built" }
# The freshness gate: a Gradle task can fail while the launcher still reports success (seen with
# Kotlin's incremental cache: "this and base files have different roots", gradle exit 0 and the
# previous APK left in place). Packaging that would ship a stale build under a new version, so the
# artifact has to have been written by this run.
if ((Get-Item -LiteralPath $built).LastWriteTime -lt $buildStart) {
    Stop-Build "Stale output: $built was not rebuilt by this run (Gradle failed silently)."
}
# Read the version back out of the APK instead of trusting the Gradle call. A wrong versionName is
# invisible until the user opens the settings page, and it already shipped once (r149 announced
# r147). aapt2 ships with the build tools, so this costs nothing.
if ($buildToolsDir) {
    $aapt2 = Join-Path $buildToolsDir.FullName 'aapt2.exe'
    if (Test-Path -LiteralPath $aapt2) {
        $badging = (& $aapt2 dump badging $built 2>$null) -join "`n"
        if ($badging -notmatch [regex]::Escape("versionName='$versionName'")) {
            Stop-Build "APK version name mismatch: expected '$versionName', aapt2 reports: $($badging -split "`n" | Where-Object { $_ -like 'package:*' })"
        }
        if ($badging -notmatch [regex]::Escape("versionCode='$versionCode'")) {
            Stop-Build "APK version code mismatch: expected '$versionCode', aapt2 reports: $($badging -split "`n" | Where-Object { $_ -like 'package:*' })"
        }
        Write-Output "APK version verified: $versionName (code $versionCode)"
    }
}
# R8 keeps these user-visible log strings. Require every string from the current
# direct-output work before packaging, so a stale APK cannot pass merely because
# zipalign/apksigner touched it and gave it a new timestamp.
$requiredLogLutStrings = @(
    'Log LUT App encoder override installed',
    'recorder appcodec probe',
    'recorder selector key ',
    'recorder config read key=',
    'recorder builder forced key=',
    'recorder creation entered',
    'recorder constructor requested=',
    'recorder selected actual=',
    'app encoder orientation hint',
    'app encoder startup',
    'app encoder initialized',
    'GL synthetic LUT requested=',
    'GL direct LUT draw guard',
    'GL cleared stale Pro Video LUT index=',
    'bypassOesPreviewLut=',
    'ordinary LUT made effective on ',
    'preview LUT contract ',
    'd7(0) original=',
    'BaseMode si original=',
    'BaseMode v2=',
    'preview LUT type original=',
    'preview support called by ',
    'camera mode scope reason=',
    'cleared Pro Video custom LUT path',
    'operation flags 0x',
    'LUT recording badge kept visible',
    'preview effect flags',
    'preview effect process',
    'preview cache copy',
    'GL final shader choice',
    'AE exposure compensation fix'
)
Add-Type -AssemblyName System.IO.Compression.FileSystem
$apkZip = [IO.Compression.ZipFile]::OpenRead($built)
try {
    $dexText = New-Object Text.StringBuilder
    foreach ($entry in ($apkZip.Entries | Where-Object { $_.Name -match '^classes\d*\.dex$' })) {
        $stream = $entry.Open()
        try {
            $memory = New-Object IO.MemoryStream
            try {
                $stream.CopyTo($memory)
                [void] $dexText.Append([Text.Encoding]::UTF8.GetString($memory.ToArray()))
            } finally {
                $memory.Dispose()
            }
        } finally {
            $stream.Dispose()
        }
    }
} finally {
    $apkZip.Dispose()
}
$dexAll = $dexText.ToString()
foreach ($sentinel in $requiredLogLutStrings) {
    if (-not $dexAll.Contains($sentinel)) {
        Stop-Build "Stale or incomplete APK: missing direct-output probe string '$sentinel'."
    }
}
Write-Output "APK direct-output probes verified: $($requiredLogLutStrings.Count) strings"
$requiredGalleryPaletteStrings = @(
    'palette output write guard enabled',
    'palette output triggered reroute from small same-aspect texture ',
    'palette output geometry alignment enabled'
)
foreach ($sentinel in $requiredGalleryPaletteStrings) {
    if (-not $dexAll.Contains($sentinel)) {
        Stop-Build "Stale or incomplete APK: missing gallery palette probe string '$sentinel'."
    }
}
Write-Output "APK gallery palette probes verified: $($requiredGalleryPaletteStrings.Count) strings"
$requiredPortraitZoomStrings = @(
    'PortraitDefaultZoom',
    'portrait initial 1x armed',
    'portrait initial 1x request '
)
foreach ($sentinel in $requiredPortraitZoomStrings) {
    if (-not $dexAll.Contains($sentinel)) {
        Stop-Build "Stale or incomplete APK: missing portrait zoom string '$sentinel'."
    }
}
Write-Output "APK portrait default-zoom probes verified: $($requiredPortraitZoomStrings.Count) strings"
$requiredXpanStrings = @(
    'XPAN menu visibility hook failed',
    'XPAN capture request live photo t1=',
    'XPAN capture request live photo hook failed',
    'XPAN capture request live photo stamp failed',
    'XPAN capture started live photo t1=',
    'XPAN capture started live photo stamp failed',
    'XPAN top menu list injected ',
    'XPAN live photo menu requested through stock container helper',
    'XPAN live photo menu created through stock container helper',
    'XPAN live photo menu stock helper failed',
    'XPAN live photo menu cache adopted after ',
    'XPAN live photo menu membership failed',
    'XPAN live photo menu restored after ',
    'XPAN live photo click before, ',
    'XPAN live photo click after, ',
    'XPAN shared live state ',
    'XPAN live photo menu GONE request restored',
    'XPAN top-left menu still has no live photo item after ',
    'XPAN wheel gesture forwarded to live',
    'XPAN wheel live gesture completed',
    'XPAN live exposure fix armed',
    'XPAN live exposure correction mode=',
    'XPAN live EIS bypass armed',
    'XPAN live EIS bypass active',
    'XPAN live EIS bypass unavailable',
    'XPAN live EIS bypass probe failed',
    'XPAN synthetic gyro fallback armed',
    'XPAN synthetic gyro injected',
    'XPAN synthetic gyro injection failed',
    'XPAN synthetic gyro fallback unavailable',
    'XPAN video crop fallback armed',
    'XPAN null video crop fallback, neutral=[1.0, 1.0]',
    'XPAN video crop fallback unavailable',
    'XPAN live BasicTone binding armed',
    'XPAN live BasicTone direct probe',
    'XPAN live BasicTone direct shader prepared',
    'XPAN live BasicTone parameters enabled',
    'XPAN live BasicTone direct attachment',
    'XPAN live transform capture probe',
    'XPAN live transform draw probe',
    'XPAN live transform upload probe',
    'XPAN live BasicTone texture created',
    'XPAN live BasicTone texture data unavailable',
    'XPAN live BasicTone bound, lutType=1',
    'XPAN common renderer BasicTone uniforms bound',
    'XPAN live BasicTone binding failed',
    'XPAN live BasicTone binding unavailable',
    'XPAN main frame path',
    'XPAN main frame capture failed',
    'XPAN common draw observed',
    'XPAN transform capture method observed'
)
foreach ($sentinel in $requiredXpanStrings) {
    if (-not $dexAll.Contains($sentinel)) {
        Stop-Build "Stale or incomplete APK: missing XPAN probe string '$sentinel'."
    }
}
Write-Output "APK XPAN probes verified: $($requiredXpanStrings.Count) strings"
if ($signed) {
    # Remove-Item by pipeline input fails silently under a profile that wraps it with a
    # parameter-binding guard (seen 2026-10-29: the stale r22 apk shipped inside r23);
    # bind each path explicitly instead.
    Get-ChildItem -Path (Join-Path $project 'module') -Filter 'omnicam-muse-*.apk' |
        ForEach-Object { Remove-Item -LiteralPath $_.FullName -Force }
    Copy-Item -LiteralPath $built -Destination (Join-Path $project "module/omnicam-muse-$version.apk")
    & $python (Join-Path $project 'tools/zip_module.py') (Join-Path $project 'module') (Join-Path $project "OmniCam-Muse-$version-KSU.zip")
    if ($LASTEXITCODE -ne 0) { Stop-Build 'Module packaging failed.' }
} else {
    Write-Output "Unsigned APK: $built. Configure OMNICAM_KEYSTORE to produce a signed module."
}
