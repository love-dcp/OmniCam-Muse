# Build the standalone OmniCam Muse uninstaller module.
#
# The uninstaller is plain shell (no APK, no native code); all it needs is a zip with
# module.prop and the three phase scripts at the archive root.
$ErrorActionPreference = 'Stop'
$project = $PSScriptRoot
$python = if ($env:OMNICAM_PYTHON) { $env:OMNICAM_PYTHON } else { 'python' }
$versionLine = Get-Content -Encoding UTF8 (Join-Path $project 'uninstaller/module.prop') | Where-Object { $_ -like 'version=*' } | Select-Object -First 1
$version = ((([string]$versionLine) -replace '^version=', '')).Trim()
# Android's /system/bin/sh treats CR as part of the line and fails on blank lines/case
# patterns. Never package a CRLF shell script or module.prop.
$bad = @()
foreach ($f in Get-ChildItem (Join-Path $project 'uninstaller') -File) {
    if ($f.Name -like '*.sh' -or $f.Name -eq 'module.prop') {
        $bytes = [IO.File]::ReadAllBytes($f.FullName)
        if ([Array]::IndexOf($bytes, [byte]13) -ge 0) { $bad += $f.Name }
    }
}
if ($bad.Count -gt 0) { throw "CRLF line endings in: $($bad -join ', '). Convert to LF first." }
$out = Join-Path $project "OmniCam-Muse-Uninstall-$version-KSU.zip"
& $python (Join-Path $project 'tools/zip_module.py') (Join-Path $project 'uninstaller') $out
if ($LASTEXITCODE -ne 0) { throw 'Uninstaller packaging failed.' }
Write-Output "Uninstaller: $out"
