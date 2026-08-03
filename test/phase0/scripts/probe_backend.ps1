param(
    [int]$Port = 18765
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$venvPython = Join-Path $repoRoot 'python-backend\.venv\Scripts\python.exe'
if (-not (Test-Path -LiteralPath $venvPython)) {
    throw "Phase 0 virtual environment not found: $venvPython"
}

$phaseTemp = Join-Path ([IO.Path]::GetTempPath()) ('opengis-phase0-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $phaseTemp | Out-Null
$stdoutPath = Join-Path $phaseTemp 'stdout.log'
$stderrPath = Join-Path $phaseTemp 'stderr.log'
$logPath = Join-Path $phaseTemp 'logs'
$arguments = @(
    '-m', 'opengis_backend',
    '--host', '127.0.0.1',
    '--port', "$Port",
    '--log-dir', $logPath
)

$stopwatch = [Diagnostics.Stopwatch]::StartNew()
$process = Start-Process `
    -FilePath $venvPython `
    -ArgumentList $arguments `
    -WorkingDirectory (Join-Path $repoRoot 'python-backend') `
    -WindowStyle Hidden `
    -RedirectStandardOutput $stdoutPath `
    -RedirectStandardError $stderrPath `
    -PassThru

try {
    $ready = $false
    for ($attempt = 0; $attempt -lt 120; $attempt++) {
        Start-Sleep -Milliseconds 250
        if (Test-Path -LiteralPath $stdoutPath) {
            $raw = Get-Content -LiteralPath $stdoutPath -Raw -ErrorAction SilentlyContinue
            if ($raw -match 'OPENGIS_READY') {
                $ready = $true
                break
            }
        }
        if ($process.HasExited) { break }
    }
    $stopwatch.Stop()

    $stdoutLines = @(Get-Content -LiteralPath $stdoutPath -ErrorAction SilentlyContinue)
    $tokenLine = $stdoutLines | Where-Object { $_ -like 'OPENGIS_WS_TOKEN=*' } | Select-Object -First 1
    $readyLine = $stdoutLines | Where-Object { $_ -eq 'OPENGIS_READY' } | Select-Object -First 1
    $token = $tokenLine -replace '^OPENGIS_WS_TOKEN=', ''
    $tokenIndex = [array]::IndexOf($stdoutLines, $tokenLine)
    $readyIndex = [array]::IndexOf($stdoutLines, $readyLine)

    Write-Output "READY=$ready"
    Write-Output ("STARTUP_READY_SECONDS={0:N3}" -f $stopwatch.Elapsed.TotalSeconds)
    Write-Output "TOKEN_PRESENT=$([bool]$token)"
    Write-Output "TOKEN_BEFORE_READY=$($tokenIndex -ge 0 -and $readyIndex -ge 0 -and $tokenIndex -lt $readyIndex)"

    if (-not $ready) {
        Write-Output '--- STDOUT ---'
        $stdoutLines
        Write-Output '--- STDERR ---'
        Get-Content -LiteralPath $stderrPath -ErrorAction SilentlyContinue
        throw 'Backend did not become ready within 30 seconds.'
    }

    $health = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/health" -TimeoutSec 5
    Write-Output ("HEALTH=" + ($health | ConvertTo-Json -Compress))

    $invalidSocket = [Net.WebSockets.ClientWebSocket]::new()
    $null = $invalidSocket.ConnectAsync(
        [Uri]"ws://127.0.0.1:$Port/ws?token=invalid",
        [Threading.CancellationToken]::None
    ).GetAwaiter().GetResult()
    $invalidBuffer = New-Object byte[] 4096
    $invalidReceive = $invalidSocket.ReceiveAsync(
        [ArraySegment[byte]]::new($invalidBuffer),
        [Threading.CancellationToken]::None
    ).GetAwaiter().GetResult()
    $invalidText = [Text.Encoding]::UTF8.GetString($invalidBuffer, 0, $invalidReceive.Count)
    Write-Output "INVALID_TOKEN_RESPONSE=$invalidText"
    $invalidSocket.Dispose()

    $socket = [Net.WebSockets.ClientWebSocket]::new()
    $null = $socket.ConnectAsync(
        [Uri]"ws://127.0.0.1:$Port/ws?token=$token",
        [Threading.CancellationToken]::None
    ).GetAwaiter().GetResult()
    $request = '{"jsonrpc":"2.0","id":"phase0-probe","method":"rpc.debug.get_log_level","params":{}}'
    $requestBytes = [Text.Encoding]::UTF8.GetBytes($request)
    $null = $socket.SendAsync(
        [ArraySegment[byte]]::new($requestBytes),
        [Net.WebSockets.WebSocketMessageType]::Text,
        $true,
        [Threading.CancellationToken]::None
    ).GetAwaiter().GetResult()
    $responseBuffer = New-Object byte[] 4096
    $responseReceive = $socket.ReceiveAsync(
        [ArraySegment[byte]]::new($responseBuffer),
        [Threading.CancellationToken]::None
    ).GetAwaiter().GetResult()
    $responseText = [Text.Encoding]::UTF8.GetString($responseBuffer, 0, $responseReceive.Count)
    Write-Output "VALID_TOKEN_RPC_RESPONSE=$responseText"
    $null = $socket.CloseAsync(
        [Net.WebSockets.WebSocketCloseStatus]::NormalClosure,
        'phase0 clean disconnect',
        [Threading.CancellationToken]::None
    ).GetAwaiter().GetResult()
    Write-Output "CLEAN_DISCONNECT_STATE=$($socket.State)"
    $socket.Dispose()

    # The backend does not retain a client session: a fresh authenticated
    # socket must be able to reconnect and issue a new request immediately.
    $reconnectedSocket = [Net.WebSockets.ClientWebSocket]::new()
    $null = $reconnectedSocket.ConnectAsync(
        [Uri]"ws://127.0.0.1:$Port/ws?token=$token",
        [Threading.CancellationToken]::None
    ).GetAwaiter().GetResult()
    $reconnectRequest = '{"jsonrpc":"2.0","id":"phase0-reconnect","method":"rpc.debug.get_log_level","params":{}}'
    $reconnectBytes = [Text.Encoding]::UTF8.GetBytes($reconnectRequest)
    $null = $reconnectedSocket.SendAsync(
        [ArraySegment[byte]]::new($reconnectBytes),
        [Net.WebSockets.WebSocketMessageType]::Text,
        $true,
        [Threading.CancellationToken]::None
    ).GetAwaiter().GetResult()
    $reconnectBuffer = New-Object byte[] 4096
    $reconnectReceive = $reconnectedSocket.ReceiveAsync(
        [ArraySegment[byte]]::new($reconnectBuffer),
        [Threading.CancellationToken]::None
    ).GetAwaiter().GetResult()
    $reconnectText = [Text.Encoding]::UTF8.GetString($reconnectBuffer, 0, $reconnectReceive.Count)
    Write-Output "RECONNECT_RPC_RESPONSE=$reconnectText"
    $reconnectedSocket.Dispose()

    Write-Output '--- STARTUP STDOUT ---'
    $stdoutLines
    Write-Output '--- STARTUP STDERR LAST 20 ---'
    Get-Content -LiteralPath $stderrPath -ErrorAction SilentlyContinue | Select-Object -Last 20
}
finally {
    if (-not $process.HasExited) {
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        $process.WaitForExit(5000) | Out-Null
    }

    $resolved = [IO.Path]::GetFullPath($phaseTemp)
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    $safeLeaf = (Split-Path $resolved -Leaf) -like 'opengis-phase0-*'
    if ($safeLeaf -and $resolved.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase)) {
        Remove-Item -LiteralPath $resolved -Recurse -Force -ErrorAction SilentlyContinue
    }
}
