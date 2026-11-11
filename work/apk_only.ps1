# Fast iteration: builds and signs the injected APK only, no module zip.
# build.ps1 builds through a SUBST drive because the Android toolchain chokes on the long path and
# on the backslash form; this keeps that, and stops before packaging.
$ErrorActionPreference = 'Stop'
# Derive the root from this script's own location. It used to be a hardcoded 'G:\OmniCam-Muse-main',
# which silently built a stale copy left on the G: data disk instead of the working tree.
$project = Split-Path -Parent $PSScriptRoot
$versionName = ((Get-Content (Join-Path $project 'module/module.prop') | Where-Object { $_ -like 'version=*' }) -replace '^version=', '').Trim()
$versionCode = ((Get-Content (Join-Path $project 'module/module.prop') | Where-Object { $_ -like 'versionCode=*' }) -replace '^versionCode=', '').Trim()
$drive = (70..90 | ForEach-Object { [char]$_ } | Where-Object { -not (Test-Path "$($_):\") } | Select-Object -Last 1)
if (-not $drive) { throw 'No free drive letter' }
subst "$($drive):" $project
try {
    Push-Location "$($drive):\"
    try {
        & .\gradlew.bat :app:assembleRelease --console=plain "-PomnicamVersionCode=$versionCode" "-PomnicamVersionName=$versionName"
        if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed.' }
    } finally { Pop-Location }
} finally { subst "$($drive):" /D }

$debugKey = Join-Path $env:USERPROFILE '.android\debug.keystore'
$sdkDir = ((Get-Content (Join-Path $project 'local.properties') | Where-Object { $_ -like 'sdk.dir=*' }) -replace '^sdk.dir=', '').Trim()
$buildTools = Get-ChildItem -Path (Join-Path $sdkDir.Replace('/', '\') 'build-tools') -Directory | Sort-Object Name | Select-Object -Last 1
$releaseDir = Join-Path $project 'app/build/outputs/apk/release'
$unsigned = Join-Path $releaseDir 'app-release-unsigned.apk'
$aligned = Join-Path $releaseDir 'app-release-aligned.apk'
$signedApk = Join-Path $releaseDir 'app-release.apk'
& (Join-Path $buildTools.FullName 'zipalign.exe') -f -p 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed.' }
& (Join-Path $buildTools.FullName 'apksigner.bat') sign --ks $debugKey --ks-pass pass:android --ks-key-alias androiddebugkey --key-pass pass:android --out $signedApk $aligned
if ($LASTEXITCODE -ne 0) { throw 'apksigner failed.' }
$aapt2 = Join-Path $buildTools.FullName 'aapt2.exe'
$badging = (& $aapt2 dump badging $signedApk 2>$null) -join "`n"
if ($badging -notmatch [regex]::Escape("versionCode='$versionCode'")) { throw 'version code mismatch' }
Write-Output "APK ready: $signedApk ($versionName / $versionCode)"
