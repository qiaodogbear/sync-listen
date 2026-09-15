param(
    [string]$JavaHome = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot',
    [string]$SigningDirectory = (Join-Path $env:USERPROFILE '.synclisten\signing'),
    [switch]$InitializeSigning
)
$ErrorActionPreference = 'Stop'
$repository = Split-Path $PSScriptRoot -Parent
$keystore = Join-Path $SigningDirectory 'synclisten-release.p12'
$passwordFile = Join-Path $SigningDirectory 'password.dpapi.xml'
if ($InitializeSigning) {
    if ((Test-Path -LiteralPath $keystore) -or (Test-Path -LiteralPath $passwordFile)) {
        throw 'Signing material already exists. It will not be overwritten.'
    }
    New-Item -ItemType Directory -Path $SigningDirectory -Force | Out-Null
    $bytes = [System.Security.Cryptography.RandomNumberGenerator]::GetBytes(48)
    $secure = ConvertTo-SecureString ([Convert]::ToBase64String($bytes)) -AsPlainText -Force
    $secure | Export-Clixml -LiteralPath $passwordFile
}
if (!(Test-Path -LiteralPath $passwordFile)) { throw 'No signing credentials. Initialize once with -InitializeSigning.' }
$secure = Import-Clixml -LiteralPath $passwordFile
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
$oldJavaHome = $env:JAVA_HOME
try {
    $env:ORG_GRADLE_PROJECT_RELEASE_KEYSTORE_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    $env:ORG_GRADLE_PROJECT_RELEASE_KEY_PASSWORD = $env:ORG_GRADLE_PROJECT_RELEASE_KEYSTORE_PASSWORD
    $env:ORG_GRADLE_PROJECT_RELEASE_KEYSTORE_PATH = $keystore
    $env:ORG_GRADLE_PROJECT_RELEASE_KEY_ALIAS = 'synclisten'
    $env:JAVA_HOME = $JavaHome
    if ($InitializeSigning) {
        & "$JavaHome\bin\keytool.exe" -genkeypair -alias synclisten -keyalg RSA -keysize 4096 -validity 10000 -storetype PKCS12 -keystore $keystore -storepass:env ORG_GRADLE_PROJECT_RELEASE_KEYSTORE_PASSWORD -keypass:env ORG_GRADLE_PROJECT_RELEASE_KEY_PASSWORD -dname 'CN=Sync Listen, OU=Release, O=Sync Listen'
        if ($LASTEXITCODE -ne 0) { throw 'Key generation failed.' }
    }
    Push-Location (Join-Path $repository 'android-app')
    try {
        & .\gradlew.bat assembleRelease --no-daemon
        if ($LASTEXITCODE -ne 0) { throw 'Release build failed.' }
    } finally { Pop-Location }
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    $env:ORG_GRADLE_PROJECT_RELEASE_KEYSTORE_PASSWORD = $null
    $env:ORG_GRADLE_PROJECT_RELEASE_KEY_PASSWORD = $null
    $env:ORG_GRADLE_PROJECT_RELEASE_KEYSTORE_PATH = $null
    $env:ORG_GRADLE_PROJECT_RELEASE_KEY_ALIAS = $null
    $env:JAVA_HOME = $oldJavaHome
}
