param(
    [ValidateSet('Devices', 'Shell', 'Exec', 'Install')]
    [string]$Mode = 'Devices',
    [string]$Serial,
    [string]$Command,
    [string]$LocalPath,
    [string]$OutputPath
)

$ErrorActionPreference = 'Stop'

function Read-ExactBytes {
    param(
        [System.IO.Stream]$Stream,
        [int]$Count
    )

    $buffer = [byte[]]::new($Count)
    $offset = 0
    while ($offset -lt $Count) {
        $read = $Stream.Read($buffer, $offset, $Count - $offset)
        if ($read -eq 0) {
            throw "ADB 连接提前关闭，期望读取 $Count 字节，实际读取 $offset 字节。"
        }
        $offset += $read
    }
    return $buffer
}

function Read-AdbStatus {
    param([System.IO.Stream]$Stream)

    $okBytes = [Text.Encoding]::ASCII.GetBytes('OKAY')
    $statusBytes = Read-ExactBytes -Stream $Stream -Count 4
    if ([Linq.Enumerable]::SequenceEqual([byte[]]$okBytes, [byte[]]$statusBytes)) {
        return
    }

    if ([Text.Encoding]::ASCII.GetString($statusBytes) -eq 'FAIL') {
        $length = [Convert]::ToInt32(
            [Text.Encoding]::ASCII.GetString((Read-ExactBytes -Stream $Stream -Count 4)),
            16
        )
        $message = [Text.Encoding]::UTF8.GetString((Read-ExactBytes -Stream $Stream -Count $length))
        throw "ADB 服务返回失败：$message"
    }

    throw "ADB 服务返回未知状态：$([Text.Encoding]::ASCII.GetString($statusBytes))"
}

function Send-AdbPacket {
    param(
        [System.IO.Stream]$Stream,
        [string]$Payload
    )

    $bytes = [Text.Encoding]::UTF8.GetBytes($Payload)
    $length = [Text.Encoding]::ASCII.GetBytes('{0:x4}' -f $bytes.Length)
    $Stream.Write($length, 0, $length.Length)
    $Stream.Write($bytes, 0, $bytes.Length)
    $Stream.Flush()
}

function Read-ToEndBytes {
    param([System.IO.Stream]$Stream)

    $memory = [System.IO.MemoryStream]::new()
    try {
        $buffer = [byte[]]::new(64 * 1024)
        while (($read = $Stream.Read($buffer, 0, $buffer.Length)) -gt 0) {
            $memory.Write($buffer, 0, $read)
        }
        return ,$memory.ToArray()
    } finally {
        $memory.Dispose()
    }
}

$client = [System.Net.Sockets.TcpClient]::new()
try {
    $client.Connect('127.0.0.1', 5037)
    $client.ReceiveTimeout = 120000
    $stream = $client.GetStream()

    if ($Mode -eq 'Devices') {
        Send-AdbPacket -Stream $stream -Payload 'host:devices'
        Read-AdbStatus -Stream $stream
        $length = [Convert]::ToInt32(
            [Text.Encoding]::ASCII.GetString((Read-ExactBytes -Stream $stream -Count 4)),
            16
        )
        $payload = Read-ExactBytes -Stream $stream -Count $length
        [Text.Encoding]::UTF8.GetString($payload)
        return
    }

    if ([string]::IsNullOrWhiteSpace($Serial)) {
        throw '使用 Shell 或 Exec 模式时必须提供 -Serial。'
    }
    if ($Mode -ne 'Install' -and [string]::IsNullOrWhiteSpace($Command)) {
        throw '使用 Shell 或 Exec 模式时必须提供 -Command。'
    }

    Send-AdbPacket -Stream $stream -Payload "host:transport:$Serial"
    Read-AdbStatus -Stream $stream

    if ($Mode -eq 'Install') {
        if ([string]::IsNullOrWhiteSpace($LocalPath)) {
            throw '使用 Install 模式时必须提供 -LocalPath。'
        }
        $resolvedLocal = [System.IO.Path]::GetFullPath($LocalPath)
        if (-not [System.IO.File]::Exists($resolvedLocal)) {
            throw "安装包不存在：$resolvedLocal"
        }
        $packageLength = ([System.IO.FileInfo]::new($resolvedLocal)).Length
        Send-AdbPacket -Stream $stream -Payload (
            "exec:pm install -r -d -S $packageLength")
        Read-AdbStatus -Stream $stream

        $package = [System.IO.File]::OpenRead($resolvedLocal)
        try {
            $package.CopyTo($stream)
            $stream.Flush()
        } finally {
            $package.Dispose()
        }
        $client.Client.Shutdown([System.Net.Sockets.SocketShutdown]::Send)
        $payload = Read-ToEndBytes -Stream $stream
        [Text.Encoding]::UTF8.GetString($payload)
        return
    }

    $servicePrefix = if ($Mode -eq 'Shell') { 'shell:' } else { 'exec:' }
    Send-AdbPacket -Stream $stream -Payload ($servicePrefix + $Command)
    Read-AdbStatus -Stream $stream

    $payload = Read-ToEndBytes -Stream $stream
    if ([string]::IsNullOrWhiteSpace($OutputPath)) {
        [Text.Encoding]::UTF8.GetString($payload)
        return
    }

    $resolvedOutput = [System.IO.Path]::GetFullPath($OutputPath)
    $parent = [System.IO.Path]::GetDirectoryName($resolvedOutput)
    if (-not [string]::IsNullOrWhiteSpace($parent)) {
        [System.IO.Directory]::CreateDirectory($parent) | Out-Null
    }
    [System.IO.File]::WriteAllBytes($resolvedOutput, $payload)
} finally {
    $client.Dispose()
}
