# LOCAL ONLY. Creates an admin only when admin_users is empty. Never prints the password or JWT key.
param([string]$Username='miqa-local')
$ErrorActionPreference='Stop'
$adminRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
Set-Location -LiteralPath $adminRoot
& ./scripts/Start-LocalPostgres.ps1
& ./scripts/Use-LocalDatabase.ps1
if (!$env:ADMIN_JWT_SECRET) {
    $keyFile=Join-Path $adminRoot '.local/admin-jwt.key'
    if (!(Test-Path -LiteralPath $keyFile)) {
        $keyBytes=New-Object byte[] 32
        $rng=[Security.Cryptography.RandomNumberGenerator]::Create()
        $rng.GetBytes($keyBytes)
        $rng.Dispose()
        [IO.File]::WriteAllText($keyFile,[Convert]::ToBase64String($keyBytes))
    }
    $env:ADMIN_JWT_SECRET=[IO.File]::ReadAllText($keyFile).Trim()
}
$env:ADMIN_BOOTSTRAP_USERNAME=$Username
$securePassword=Read-Host 'Contrasena LOCAL (minimo 12 caracteres; no se guarda en archivos)' -AsSecureString
$passwordPointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
try { $env:ADMIN_BOOTSTRAP_PASSWORD=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer) }
finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer) }
try {
    if (!$env:JAVA_HOME) { $env:JAVA_HOME=(Resolve-Path '.tmp/jdk21/jdk-21.0.12.1+1').Path }
    & (Join-Path $env:JAVA_HOME 'bin/java.exe') -jar target/miqa-store-backend-0.0.1-SNAPSHOT.jar
} finally { $env:ADMIN_BOOTSTRAP_PASSWORD=$null }
