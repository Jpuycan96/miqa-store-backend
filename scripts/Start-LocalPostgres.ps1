param(
    [string]$PostgresBin = 'C:\Program Files\PostgreSQL\17\bin',
    [ValidateRange(1024,65535)][int]$Port = 55432
)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$localRoot = Join-Path $projectRoot '.local'
$dataPath = Join-Path $localRoot 'postgres'
$connectionPath = Join-Path $localRoot 'connection.json'
if (!(Test-Path (Join-Path $PostgresBin 'initdb.exe'))) { throw 'PostgreSQL binaries not found. Supply -PostgresBin.' }
New-Item -ItemType Directory -Force $localRoot | Out-Null
if (!(Test-Path (Join-Path $dataPath 'PG_VERSION'))) {
    if (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue) { throw "Port $Port is already in use; no existing server was touched." }
    $passwordBytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    $rng.GetBytes($passwordBytes)
    $rng.Dispose()
    $connection = @{ host='127.0.0.1'; port=$Port; username='miqa_store_local'; password=[Convert]::ToBase64String($passwordBytes) }
    [IO.File]::WriteAllText($connectionPath, ($connection | ConvertTo-Json))
    $passwordPath = Join-Path $localRoot 'init-password.txt'
    [IO.File]::WriteAllText($passwordPath, $connection.password)
    & (Join-Path $PostgresBin 'initdb.exe') -D $dataPath -U $connection.username --encoding=UTF8 --locale=C --auth=scram-sha-256 "--pwfile=$passwordPath"
    $initExit = $LASTEXITCODE
    [IO.File]::WriteAllText($passwordPath, '')
    if ($initExit -ne 0) { throw 'initdb failed' }
} else {
    if (!(Test-Path $connectionPath)) { throw 'Existing cluster has no project connection file; refusing to infer credentials.' }
    $connection = Get-Content $connectionPath -Raw | ConvertFrom-Json
    if ($connection.port -ne $Port) { throw 'Use the port recorded when this isolated cluster was created.' }
}
& (Join-Path $PostgresBin 'pg_ctl.exe') -D $dataPath status | Out-Null
if ($LASTEXITCODE -ne 0) {
    if (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue) { throw "Port $Port is in use; no other server was touched." }
    # Detach standard handles from the invoking shell so the server does not keep it waiting.
    $inputPath = Join-Path $localRoot 'server-input.txt'
    [IO.File]::WriteAllText($inputPath, '')
    $pgArguments = @('-D', ('"' + $dataPath + '"'), '-l', ('"' + (Join-Path $localRoot 'postgres.log') + '"'), '-o', ('"-h 127.0.0.1 -p ' + $Port + '"'), '-w', 'start')
    $controller = Start-Process -FilePath (Join-Path $PostgresBin 'pg_ctl.exe') -ArgumentList $pgArguments -WindowStyle Hidden -PassThru -RedirectStandardInput $inputPath -RedirectStandardOutput (Join-Path $localRoot 'pgctl-start.log') -RedirectStandardError (Join-Path $localRoot 'pgctl-start-error.log')
    $controller.WaitForExit()
    & (Join-Path $PostgresBin 'pg_ctl.exe') -D $dataPath status | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Local PostgreSQL could not start; see .local/pgctl-start-error.log' }
}
$previousPassword = $env:PGPASSWORD
try {
    $env:PGPASSWORD = $connection.password
    foreach ($database in @('miqa_store_db','miqa_store_test_db')) {
        $exists = & (Join-Path $PostgresBin 'psql.exe') -h 127.0.0.1 -p $Port -U $connection.username -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='$database'"
        if ($LASTEXITCODE -ne 0) { throw 'Could not query isolated PostgreSQL' }
        if ($exists -ne '1') {
            & (Join-Path $PostgresBin 'createdb.exe') -h 127.0.0.1 -p $Port -U $connection.username $database
            if ($LASTEXITCODE -ne 0) { throw "Could not create $database" }
        }
    }
} finally { $env:PGPASSWORD = $previousPassword }
& (Join-Path $PSScriptRoot 'Use-LocalDatabase.ps1')
Write-Host "Isolated PostgreSQL ready at 127.0.0.1:$Port. Database environment loaded for this PowerShell session."
