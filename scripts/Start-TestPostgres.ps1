# Prepared for review. This script starts a dedicated TEST cluster, never runs migrations or tests.
param(
    [string]$PostgresBin = 'C:\Program Files\PostgreSQL\17\bin',
    [switch]$CreateDatabase
)
$ErrorActionPreference = 'Stop'
$testHostAddress = '127.0.0.1'
$testPort = 55432
$testDatabase = 'miqa_store_test_db'
$testUsername = 'miqa_store_local'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$testRoot = Join-Path $projectRoot '.local\postgres-test'
$testData = Join-Path $testRoot 'data'
$testMarker = Join-Path $testRoot 'test-cluster.marker'
$markerValue = 'MIQA TEST ONLY: 127.0.0.1:55432/miqa_store_test_db'

foreach ($binary in @('initdb.exe', 'pg_ctl.exe', 'psql.exe', 'createdb.exe')) {
    if (!(Test-Path -LiteralPath (Join-Path $PostgresBin $binary))) { throw 'PostgreSQL binaries are missing; supply -PostgresBin.' }
}
foreach ($directory in @((Join-Path $projectRoot '.local'), $testRoot, $testData)) {
    if ((Test-Path -LiteralPath $directory) -and ((Get-Item -LiteralPath $directory).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw 'TEST directories must not be links or junctions.'
    }
}
$initialized = Test-Path -LiteralPath (Join-Path $testData 'PG_VERSION')
if ($initialized -and (!(Test-Path -LiteralPath $testMarker) -or [IO.File]::ReadAllText($testMarker) -ne $markerValue)) {
    throw 'Existing cluster has no valid TEST marker; refusing to reuse it.'
}
if (!$initialized -and !$CreateDatabase) {
    throw 'Dedicated TEST cluster is not initialized. Review and explicitly use -CreateDatabase to initialize it.'
}
$running = $false
if ($initialized) {
    & (Join-Path $PostgresBin 'pg_ctl.exe') -D $testData status | Out-Null
    $running = $LASTEXITCODE -eq 0
}
if (!$running) {
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $testPort)
    try { $listener.Start() } catch { throw 'Port 55432 is occupied; no other instance will be reused or stopped.' }
    finally { $listener.Stop() }
} else {
    $pidLines = [IO.File]::ReadAllLines((Join-Path $testData 'postmaster.pid'))
    if ($pidLines.Length -lt 4 -or $pidLines[3] -ne [string]$testPort) { throw 'TEST cluster is running on an unexpected port.' }
}

if ([string]::IsNullOrWhiteSpace($env:TEST_DB_PASSWORD)) {
    $securePassword = Read-Host 'Password for the dedicated TEST cluster (miqa_store_local)' -AsSecureString
    $passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
    try { $env:TEST_DB_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer); $securePassword.Dispose() }
}
if ([string]::IsNullOrWhiteSpace($env:TEST_DB_PASSWORD) -or $env:TEST_DB_PASSWORD.Contains("`r") -or $env:TEST_DB_PASSWORD.Contains("`n")) {
    throw 'A nonempty single-line TEST password is required.'
}
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
if (!$initialized) {
    # initdb accepts a password file, not a secret on the command line. Remove this exact temporary file in finally.
    $passwordFile = Join-Path $testRoot ('init-password-' + [Guid]::NewGuid().ToString('N') + '.tmp')
    try {
        [IO.File]::WriteAllText($passwordFile, $env:TEST_DB_PASSWORD, [Text.UTF8Encoding]::new($false))
        & (Join-Path $PostgresBin 'initdb.exe') -D $testData -U $testUsername --encoding=UTF8 --locale=C --auth=scram-sha-256 "--pwfile=$passwordFile" | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Dedicated TEST initdb failed; no existing cluster was changed.' }
        [IO.File]::WriteAllText($testMarker, $markerValue)
    } finally {
        if (Test-Path -LiteralPath $passwordFile) { Remove-Item -LiteralPath $passwordFile -Force }
    }
}
if (!$running) {
    $inputPath = Join-Path $testRoot 'server-input.txt'
    [IO.File]::WriteAllText($inputPath, '')
    $pgArguments = @('-D', ('"' + $testData + '"'), '-l', ('"' + (Join-Path $testRoot 'postgres.log') + '"'),
        '-o', '"-h 127.0.0.1 -p 55432"', '-w', 'start')
    $controller = Start-Process -FilePath (Join-Path $PostgresBin 'pg_ctl.exe') -ArgumentList $pgArguments -WindowStyle Hidden -PassThru -Wait `
        -RedirectStandardInput $inputPath -RedirectStandardOutput (Join-Path $testRoot 'start.log') -RedirectStandardError (Join-Path $testRoot 'start-error.log')
    if ($controller.ExitCode -ne 0) { throw 'Dedicated TEST cluster did not start; review its local log without publishing secrets.' }
}

# libpq environment options (PGSERVICE/PGOPTIONS/etc.) must not redirect the verification/creation commands.
$savedPgEnvironment = @{}
Get-ChildItem Env: | Where-Object { $_.Name -like 'PG*' } | ForEach-Object {
    $savedPgEnvironment[$_.Name] = $_.Value
    Remove-Item -LiteralPath ('Env:' + $_.Name)
}
try {
    $env:PGPASSWORD = $env:TEST_DB_PASSWORD
    $env:PGCONNECT_TIMEOUT = '5'
    $checkSql = 'SELECT current_database()'
    $probeErrorPreference = $ErrorActionPreference
    try {
        # Windows PowerShell can turn native stderr into terminating errors before LASTEXITCODE is examined.
        $ErrorActionPreference = 'Continue'
        $result = & (Join-Path $PostgresBin 'psql.exe') -X -w -h $testHostAddress -p $testPort -U $testUsername -d $testDatabase -tAc $checkSql 2>$null
        $probeExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $probeErrorPreference }
    if ($probeExit -ne 0) {
        if (!$CreateDatabase) { throw 'Cannot connect to TEST. Check credentials or explicitly prepare it with -CreateDatabase.' }
        # The postgres maintenance database is used only by createdb to create the exact TEST database; no schema SQL.
        & (Join-Path $PostgresBin 'createdb.exe') -w -h $testHostAddress -p $testPort -U $testUsername --maintenance-db=postgres --owner=$testUsername --encoding=UTF8 $testDatabase
        if ($LASTEXITCODE -ne 0) { throw 'Could not create TEST database; no migration was attempted.' }
        $result = & (Join-Path $PostgresBin 'psql.exe') -X -w -h $testHostAddress -p $testPort -U $testUsername -d $testDatabase -tAc $checkSql
        if ($LASTEXITCODE -ne 0) { throw 'TEST connection verification failed.' }
    }
    if (([string]$result).Trim() -ne $testDatabase) { throw 'Database verification failed.' }
} finally {
    Remove-Item Env:PGPASSWORD,Env:PGCONNECT_TIMEOUT -ErrorAction SilentlyContinue
    foreach ($name in $savedPgEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name, $savedPgEnvironment[$name], 'Process') }
}
$env:SPRING_PROFILES_ACTIVE = 'test'
Write-Host 'Dedicated TEST ready: 127.0.0.1:55432/miqa_store_test_db, user miqa_store_local. No Flyway or tests executed.'
