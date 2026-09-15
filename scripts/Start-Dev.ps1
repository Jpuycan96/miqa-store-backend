# LOCAL DEVELOPMENT ONLY. Uses the shared DEV database through an existing SSH tunnel.
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$tunnelHost = '127.0.0.1'
$tunnelPort = 5433
$sshCommand = 'ssh -p 2222 -N -L 5433:localhost:5432 -o ServerAliveInterval=60 root@64.176.22.247'

$tcpClient = New-Object Net.Sockets.TcpClient
try {
    $connectTask = $tcpClient.ConnectAsync($tunnelHost, $tunnelPort)
    if (!$connectTask.Wait(2000) -or !$tcpClient.Connected) {
        throw 'SSH tunnel is not available.'
    }
} catch {
    Write-Host "The SSH tunnel at ${tunnelHost}:${tunnelPort} is not accessible. Open it in another terminal:" -ForegroundColor Yellow
    Write-Host $sshCommand
    exit 1
} finally {
    $tcpClient.Dispose()
}

$mediaPath = Join-Path $projectRoot '.local\media'
New-Item -ItemType Directory -Force -Path $mediaPath | Out-Null

$env:DB_HOST = $tunnelHost
$env:DB_PORT = [string]$tunnelPort
$env:DB_NAME = 'miqa_store_dev_db'
$env:DB_USERNAME = 'miqa_store_dev'
$env:SPRING_PROFILES_ACTIVE = 'local'
$env:CORS_ALLOWED_ORIGINS = 'http://localhost:4200'
$env:MEDIA_STORAGE_PATH = $mediaPath
$env:MEDIA_BASE_URL = 'http://localhost:8081/media'

$securePassword = Read-Host 'DB password for miqa_store_dev (not saved)' -AsSecureString
$passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
try {
    $env:DB_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
}

$mavenExitCode = 1
try {
    $secretBytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $rng.GetBytes($secretBytes)
        $env:ADMIN_JWT_SECRET = [Convert]::ToBase64String($secretBytes)
    } finally {
        if ($null -ne $rng) { $rng.Dispose() }
        [Array]::Clear($secretBytes, 0, $secretBytes.Length)
    }

    Set-Location -LiteralPath $projectRoot
    & .\mvnw.cmd spring-boot:run
    $mavenExitCode = $LASTEXITCODE
} finally {
    $env:DB_PASSWORD = $null
    $env:ADMIN_JWT_SECRET = $null
    $securePassword.Dispose()
}

if ($mavenExitCode -ne 0) {
    exit $mavenExitCode
}
