[CmdletBinding()]
param(
    [ValidateSet("Status", "Apply", "Restore", "Capture", "ClearLog")]
    [string]$Action = "Status",
    [string]$Name = "capture",
    [string]$Serial = "95c2d261",
    [string]$AdbPath = "C:\Users\Mr-DCP\AppData\Local\Temp\pkj110_toolbox_lite\v2026.10.06-31\adb.exe",
    [string]$CandidateSo = "",
    [string]$CandidateCfg = "",
    [string]$CandidateVcfg = "",
    [string]$ExpectedSo = "",
    [string]$ExpectedCfg = "",
    [string]$ExpectedVcfg = ""
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$deviceScript = Join-Path $PSScriptRoot "x8u_isz_device.sh"
$captureDir = Join-Path $repo "output\isz_test"

if ([string]::IsNullOrWhiteSpace($CandidateSo)) {
    $CandidateSo = Join-Path $repo "module\payload_pkj110\lib64\libextensionlayer.so"
}
if ([string]::IsNullOrWhiteSpace($CandidateCfg)) {
    $CandidateCfg = Join-Path $repo "module\payload_pkj110\etc\camera\CameraHWConfiguration.config"
}

function Get-LocalMd5 {
    param([string]$Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "missing artifact: $Path"
    }
    return (Get-FileHash -Algorithm MD5 -LiteralPath $Path).Hash.ToLowerInvariant()
}

$candidateSo = (Resolve-Path -LiteralPath $CandidateSo).Path
$candidateCfg = (Resolve-Path -LiteralPath $CandidateCfg).Path
if ([string]::IsNullOrWhiteSpace($ExpectedSo)) {
    $expectedSo = Get-LocalMd5 $candidateSo
} else {
    $expectedSo = $ExpectedSo.ToLowerInvariant()
}
if ([string]::IsNullOrWhiteSpace($ExpectedCfg)) {
    $expectedCfg = Get-LocalMd5 $candidateCfg
} else {
    $expectedCfg = $ExpectedCfg.ToLowerInvariant()
}

$hasVcfg = -not [string]::IsNullOrWhiteSpace($CandidateVcfg)
if (-not $hasVcfg) {
    $candidateVcfg = ""
    if ([string]::IsNullOrWhiteSpace($ExpectedVcfg)) {
        $expectedVcfg = "none"
    } else {
        $expectedVcfg = $ExpectedVcfg.ToLowerInvariant()
    }
} else {
    $candidateVcfg = (Resolve-Path -LiteralPath $CandidateVcfg).Path
    if ([string]::IsNullOrWhiteSpace($ExpectedVcfg)) {
        $expectedVcfg = Get-LocalMd5 $candidateVcfg
    } else {
        $expectedVcfg = $ExpectedVcfg.ToLowerInvariant()
    }
}

$stageTag = "{0}_{1}_{2}" -f $expectedSo.Substring(0, 8),
    $expectedCfg.Substring(0, 8),
    $(if ($expectedVcfg -eq "none") { "stockvcfg" } else { $expectedVcfg.Substring(0, 8) })
$remoteStage = "/data/local/tmp/omnicam_isz_$stageTag"
$remoteScript = "$remoteStage/x8u_isz_device.sh"

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)

    & $AdbPath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "adb command failed ($LASTEXITCODE): $($Arguments -join ' ')"
    }
}

function Invoke-DeviceScript {
    param([string]$DeviceAction)

    Invoke-Adb -s $Serial "shell" `
        "su -c 'sh $remoteScript $DeviceAction $remoteStage $expectedSo $expectedCfg $expectedVcfg'"
}

function Assert-Hash {
    param([string]$Path, [string]$Expected)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "missing artifact: $Path"
    }
    $actual = (Get-FileHash -Algorithm MD5 -LiteralPath $Path).Hash.ToLowerInvariant()
    if ($actual -ne $Expected) {
        throw "hash mismatch for ${Path}: $actual (want $Expected)"
    }
}

function Get-RemoteMd5 {
    param([string]$RemotePath)

    $output = & $AdbPath -s $Serial "shell" "md5sum $RemotePath" 2>$null
    if ($LASTEXITCODE -ne 0) {
        return $null
    }
    $text = $output | Out-String
    if ($text -match "([0-9a-fA-F]{32})") {
        return $Matches[1].ToLowerInvariant()
    }
    return $null
}

function Push-LabFiles {
    Assert-Hash $candidateSo $expectedSo
    Assert-Hash $candidateCfg $expectedCfg
    if ($hasVcfg) {
        Assert-Hash $candidateVcfg $expectedVcfg
    }

    Invoke-Adb -s $Serial "shell" "mkdir -p $remoteStage"
    Invoke-Adb -s $Serial "push" $deviceScript $remoteScript

    $remoteSo = "$remoteStage/libextensionlayer.so"
    $remoteCfg = "$remoteStage/CameraHWConfiguration.config"
    $remoteSoHash = Get-RemoteMd5 $remoteSo
    $remoteCfgHash = Get-RemoteMd5 $remoteCfg

    if ($null -eq $remoteSoHash) {
        Invoke-Adb -s $Serial "push" $candidateSo $remoteSo
    } elseif ($remoteSoHash -ne $expectedSo) {
        throw "staged library hash drifted: $remoteSoHash (want $expectedSo)"
    }

    if ($null -eq $remoteCfgHash) {
        Invoke-Adb -s $Serial "push" $candidateCfg $remoteCfg
    } elseif ($remoteCfgHash -ne $expectedCfg) {
        throw "staged config hash drifted: $remoteCfgHash (want $expectedCfg)"
    }

    if ($hasVcfg) {
        $remoteVcfg = "$remoteStage/oplus_eis_camera.vcfg"
        $remoteVcfgHash = Get-RemoteMd5 $remoteVcfg
        if ($null -eq $remoteVcfgHash) {
            Invoke-Adb -s $Serial "push" $candidateVcfg $remoteVcfg
        } elseif ($remoteVcfgHash -ne $expectedVcfg) {
            throw "staged EIS config hash drifted: $remoteVcfgHash (want $expectedVcfg)"
        }
    }

    Invoke-Adb -s $Serial "shell" "su -c 'sh -n $remoteScript'"
}

function Save-Capture {
    New-Item -ItemType Directory -Force -Path $captureDir | Out-Null
    $safeName = $Name -replace "[^A-Za-z0-9._-]", "_"
    $remotePng = "/data/local/tmp/omnicam_isz_${safeName}.png"
    $remoteXml = "/data/local/tmp/omnicam_isz_${safeName}.xml"
    $png = Join-Path $captureDir "$safeName.png"
    $xml = Join-Path $captureDir "$safeName.xml"
    $log = Join-Path $captureDir "$safeName.log"
    $dump = Join-Path $captureDir "$safeName.dumpsys.txt"
    $meta = Join-Path $captureDir "$safeName.candidate.txt"

    Invoke-Adb -s $Serial "shell" "screencap -p $remotePng"
    Invoke-Adb -s $Serial "pull" $remotePng $png
    Invoke-Adb -s $Serial "shell" "uiautomator dump $remoteXml"
    Invoke-Adb -s $Serial "pull" $remoteXml $xml

    $lines = & $AdbPath -s $Serial "logcat" "-d" "-v" "threadtime"
    if ($LASTEXITCODE -ne 0) {
        throw "logcat capture failed ($LASTEXITCODE)"
    }
    $lines | Set-Content -LiteralPath $log -Encoding utf8

    $cameraDump = & $AdbPath -s $Serial "shell" "dumpsys media.camera"
    if ($LASTEXITCODE -ne 0) {
        throw "camera dumpsys capture failed ($LASTEXITCODE)"
    }
    $cameraDump | Set-Content -LiteralPath $dump -Encoding utf8
    @(
        "library=$candidateSo"
        "library_md5=$expectedSo"
        "config=$candidateCfg"
        "config_md5=$expectedCfg"
        "eis_config=$(if ($hasVcfg) { $candidateVcfg } else { '(stock)' })"
        "eis_config_md5=$expectedVcfg"
        "remote_stage=$remoteStage"
    ) | Set-Content -LiteralPath $meta -Encoding utf8

    Write-Output "captured:"
    Write-Output "  image: $png"
    Write-Output "  ui:    $xml"
    Write-Output "  log:   $log"
    Write-Output "  state: $dump"
    Write-Output "  hashes: $meta"
    Write-Output "ISZ evidence in this log:"
    $evidence = $lines | Select-String -Pattern `
        "m_modeDescriptor|isTriggerIzoom|SensorMode:(9|13|23)\b|RecoveryErr|ErrorCode:1047" |
        Select-Object -Last 60
    if ($evidence) {
        $evidence | ForEach-Object { Write-Output "  $($_.Line)" }
    } else {
        Write-Output "  (none)"
    }
}

if (-not (Test-Path -LiteralPath $AdbPath -PathType Leaf)) {
    throw "adb not found: $AdbPath"
}

$devices = & $AdbPath devices
if ($LASTEXITCODE -ne 0 -or -not ($devices -match [regex]::Escape($Serial) + "\s+device")) {
    throw "device $Serial is not available"
}

switch ($Action) {
    "Status" {
        Push-LabFiles
        Invoke-DeviceScript "status"
    }
    "Apply" {
        Push-LabFiles
        & $AdbPath -s $Serial "logcat" "-c"
        if ($LASTEXITCODE -ne 0) {
            throw "could not clear logcat"
        }
        Invoke-DeviceScript "apply"
    }
    "Restore" {
        Push-LabFiles
        Invoke-DeviceScript "restore"
    }
    "Capture" {
        Save-Capture
    }
    "ClearLog" {
        & $AdbPath -s $Serial "logcat" "-c"
        if ($LASTEXITCODE -ne 0) {
            throw "could not clear logcat"
        }
        Write-Output "logcat cleared"
    }
}
