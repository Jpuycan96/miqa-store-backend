# Manual integration only. Uses the existing TEST properties without adding test classes to the runtime.
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))

# Reject inherited overrides before prompting for a password or invoking Maven.
# Do not print values: connection options and JVM arguments can contain secrets.
foreach ($entry in Get-ChildItem Env:) {
    if ([string]::IsNullOrWhiteSpace($entry.Value)) { continue }
    if ($entry.Name -eq 'SPRING_PROFILES_ACTIVE' -and $entry.Value -eq 'test') { continue }
    $propertyName = $entry.Name -replace '[.-]', '_'
    if ($propertyName -match '^(SPRING_|SERVER_|MIQA_|APP_|DB_|ADMIN_|CORS_|MEDIA_|LOGGING_|MANAGEMENT_)' -or
        $propertyName -match '^TEST_DB_(?!PASSWORD$)' -or
        $entry.Name -in @('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'MAVEN_OPTS', 'MAVEN_ARGS')) {
        throw "Inherited override rejected: $($entry.Name). Use a clean PowerShell session with only TEST_DB_PASSWORD and Java/Maven paths configured."
    }
}
foreach ($relative in @('.mvn/jvm.config', '.mvn/maven.config')) {
    $configuration = Join-Path $projectRoot $relative
    if ((Test-Path -LiteralPath $configuration) -and ![string]::IsNullOrWhiteSpace([IO.File]::ReadAllText($configuration))) {
        throw "Manual TEST does not allow additional options in $relative. Review them before using this launcher."
    }
}
$mainProperties = Join-Path $projectRoot 'src/main/resources/application.properties'
$testProperties = Join-Path $projectRoot 'src/test/resources/application-test.properties'
$wrapper = Join-Path $projectRoot 'mvnw.cmd'
foreach ($file in @($mainProperties, $testProperties, $wrapper)) {
    if (!(Test-Path -LiteralPath $file -PathType Leaf)) { throw 'Required project configuration or Maven wrapper is missing.' }
}

$savedEnvironment = @{}
$settings = @{
    SPRING_PROFILES_ACTIVE = 'test'
    # Explicit locations replace default discovery (including ./config). TEST is loaded last.
    SPRING_CONFIG_LOCATION = ([Uri]$mainProperties).AbsoluteUri + ',' + ([Uri]$testProperties).AbsoluteUri
    SERVER_ADDRESS = '127.0.0.1'
    SERVER_PORT = '8081'
    MEDIA_STORAGE_PATH = (Join-Path $projectRoot '.local/test-app-media')
    MEDIA_BASE_URL = ''
    # Avoid request/SQL parameter logging in this manual process.
    LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_WEB = 'INFO'
    LOGGING_LEVEL_ORG_HIBERNATE_SQL = 'OFF'
    LOGGING_LEVEL_ORG_HIBERNATE_ORM_JDBC_BIND = 'OFF'
    SPRING_MVC_LOG_REQUEST_DETAILS = 'false'
    SPRING_JPA_SHOW_SQL = 'false'
}
foreach ($name in @($settings.Keys) + @('TEST_DB_PASSWORD')) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
$previousLocation = Get-Location
try {
    if ([string]::IsNullOrWhiteSpace($env:TEST_DB_PASSWORD)) {
        $securePassword = Read-Host 'TEST_DB_PASSWORD for local miqa_store_test_db (not saved)' -AsSecureString
        $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
        try { $env:TEST_DB_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
        finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer); $securePassword.Dispose() }
    }
    if ([string]::IsNullOrWhiteSpace($env:TEST_DB_PASSWORD)) { throw 'TEST_DB_PASSWORD is required.' }
    foreach ($name in $settings.Keys) { [Environment]::SetEnvironmentVariable($name, $settings[$name], 'Process') }
    Set-Location -LiteralPath $projectRoot
    Write-Host 'Manual TEST: http://127.0.0.1:8081 -> 127.0.0.1:55432/miqa_store_test_db (miqa_store_local). Ctrl+C to stop.'
    # No password in arguments, files or Maven options. LocalDatabaseGuard runs before DataSource/Flyway.
    & $wrapper spring-boot:run
    if ($LASTEXITCODE -ne 0) { throw 'Manual TEST startup failed. Review the preceding application error; no alternate database will be used.' }
} finally {
    Set-Location -LiteralPath $previousLocation
    foreach ($name in $savedEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process') }
}
