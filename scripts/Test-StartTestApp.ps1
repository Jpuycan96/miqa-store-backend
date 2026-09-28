# Launcher regression tests. Fake Maven only: no Java, database, network or real credentials.
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$fixtureRoot = Join-Path $projectRoot ('.tmp/launcher-test-' + [Guid]::NewGuid().ToString('N'))
$fixture = Join-Path $fixtureRoot 'project with spaces'
$saved = @{}
$names = @('TEST_DB_PASSWORD', 'SPRING_PROFILES_ACTIVE', 'SPRING_DATASOURCE_URL', 'SPRING_DATASOURCE_USERNAME',
    'SPRING_DATASOURCE_PASSWORD', 'SPRING_DATASOURCE_HIKARI_JDBCURL', 'SPRING_FLYWAY_URL', 'SPRING_APPLICATION_JSON',
    'SPRING_CONFIG_LOCATION', 'SPRING_CONFIG_ADDITIONAL_LOCATION', 'SPRING_PROFILES_INCLUDE', 'APP_ADMIN_JWTSECRET',
    'ADMIN_JWT_SECRET', 'DB_NAME', 'SERVER_ADDRESS', 'SERVER_PORT', 'MAVEN_OPTS', 'MAVEN_ARGS',
    'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'spring.datasource.url')
foreach ($name in $names) { $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process'); [Environment]::SetEnvironmentVariable($name, $null, 'Process') }
$count = 0
try {
    New-Item -ItemType Directory -Force -Path (Join-Path $fixture 'scripts'), (Join-Path $fixture 'src/main/resources'), (Join-Path $fixture 'src/test/resources'), (Join-Path $fixture '.mvn') | Out-Null
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'Start-TestApp.ps1') -Destination (Join-Path $fixture 'scripts/Start-TestApp.ps1')
    [IO.File]::WriteAllText((Join-Path $fixture 'src/main/resources/application.properties'), '# fake')
    [IO.File]::WriteAllText((Join-Path $fixture 'src/test/resources/application-test.properties'), '# fake')
    [IO.File]::WriteAllText((Join-Path $fixture 'mvnw.cmd'), '@powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0assert-launch.ps1"')
    [IO.File]::WriteAllText((Join-Path $fixture 'assert-launch.ps1'), @'
$ErrorActionPreference = 'Stop'
if ($env:SPRING_PROFILES_ACTIVE -ne 'test' -or $env:SERVER_ADDRESS -ne '127.0.0.1' -or $env:SERVER_PORT -ne '8081') { exit 10 }
$expected = ([Uri](Join-Path $PSScriptRoot 'src/main/resources/application.properties')).AbsoluteUri + ',' + ([Uri](Join-Path $PSScriptRoot 'src/test/resources/application-test.properties')).AbsoluteUri
if ($env:SPRING_CONFIG_LOCATION -ne $expected -or [string]::IsNullOrWhiteSpace($env:TEST_DB_PASSWORD)) { exit 11 }
if ($env:MEDIA_STORAGE_PATH -ne (Join-Path $PSScriptRoot '.local/test-app-media')) { exit 12 }
if ($env:SPRING_JPA_SHOW_SQL -ne 'false') { exit 13 }
[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'launched'), 'validated without Java')
exit 0
'@)
    $launcher = Join-Path $fixture 'scripts/Start-TestApp.ps1'
    foreach ($name in $names | Where-Object { $_ -ne 'TEST_DB_PASSWORD' }) {
        [Environment]::SetEnvironmentVariable($name, 'unsafe-test-override', 'Process')
        $rejected = $false
        try { & $launcher } catch { $rejected = $_.Exception.Message.StartsWith('Inherited override rejected:') }
        finally { [Environment]::SetEnvironmentVariable($name, $null, 'Process') }
        if (!$rejected -or (Test-Path -LiteralPath (Join-Path $fixture 'launched'))) { throw "Expected pre-launch rejection for $name" }
        $count++
    }
    foreach ($configuration in @('jvm.config', 'maven.config')) {
        $configPath = Join-Path $fixture ('.mvn/' + $configuration)
        [IO.File]::WriteAllText($configPath, '-Dspring.profiles.active=prod')
        $rejected = $false
        try { & $launcher } catch { $rejected = $_.Exception.Message.StartsWith('Manual TEST does not allow') }
        finally { [IO.File]::WriteAllText($configPath, '') }
        if (!$rejected) { throw 'Expected Maven configuration rejection' }
        $count++
    }
    $env:TEST_DB_PASSWORD = [Guid]::NewGuid().ToString() # Synthetic in-memory value, never written or printed.
    $originalPassword = $env:TEST_DB_PASSWORD
    $originalLocation = (Get-Location).Path
    & $launcher
    if (!(Test-Path -LiteralPath (Join-Path $fixture 'launched')) -or $env:TEST_DB_PASSWORD -ne $originalPassword -or
        $env:SPRING_CONFIG_LOCATION -or (Get-Location).Path -ne $originalLocation) { throw 'Launcher did not restore its environment/location' }
    $count++
    # Simulate hidden prompt without exposing a credential or requiring interactive input.
    $env:TEST_DB_PASSWORD = $null
    function Read-Host { param([string]$Prompt, [switch]$AsSecureString); ConvertTo-SecureString ([Guid]::NewGuid().ToString()) -AsPlainText -Force }
    & $launcher
    if ($env:TEST_DB_PASSWORD) { throw 'Prompted password was not cleared' }
    $count++
    [IO.File]::WriteAllText((Join-Path $fixture 'mvnw.cmd'), '@exit /b 17')
    $failed = $false
    try { & $launcher } catch { $failed = $_.Exception.Message.StartsWith('Manual TEST startup failed') }
    if (!$failed -or $env:TEST_DB_PASSWORD -or $env:SPRING_CONFIG_LOCATION -or (Get-Location).Path -ne $originalLocation) { throw 'Failed launch did not clean up' }
    $count++
    Write-Host "$count launcher checks passed; no Java, database or network used."
} finally {
    foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
    # Delete only this test's verified, unique directory inside the backend .tmp.
    $resolvedFixture = [IO.Path]::GetFullPath($fixtureRoot)
    $allowedRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot '.tmp')) + [IO.Path]::DirectorySeparatorChar
    if (!$resolvedFixture.StartsWith($allowedRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe fixture cleanup path' }
    if (Test-Path -LiteralPath $resolvedFixture) { Remove-Item -LiteralPath $resolvedFixture -Recurse -Force }
}
