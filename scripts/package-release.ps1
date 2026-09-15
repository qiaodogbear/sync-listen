param([ValidatePattern('^\d+\.\d+\.\d+$')][string]$Version = '0.3.0')
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$output = Join-Path $root 'artifacts'
$desktop = Join-Path $root 'desktop/build/compose/binaries/main/app/SyncListen'
$apk = Join-Path $root 'android-app/app/build/outputs/apk/release/app-release.apk'
if (!(Test-Path -LiteralPath $apk) -or !(Test-Path -LiteralPath "$desktop/SyncListen.exe")) {
    throw 'Build the signed Android APK and desktop distributable first.'
}
$runtimeRelease = Get-Content -LiteralPath "$desktop/runtime/release" -Raw
if ($runtimeRelease -notmatch 'JAVA_VERSION="21\.0\.11"') { throw 'Update the corresponding runtime source version before packaging a different JDK.' }
New-Item -ItemType Directory -Path $output -Force | Out-Null
$stage = Join-Path $output ('staging-' + [guid]::NewGuid().ToString('N'))
$app = Join-Path $stage 'SyncListen'
$sources = Join-Path $stage 'third-party-sources'
New-Item -ItemType Directory -Path $sources -Force | Out-Null
Copy-Item -LiteralPath $desktop -Destination $app -Recurse
foreach ($file in @('LICENSE','README.md','THIRD_PARTY_NOTICES.md','SECURITY.md')) {
    Copy-Item -LiteralPath (Join-Path $root $file) -Destination $app
}
Copy-Item -LiteralPath (Join-Path $root 'docs') -Destination (Join-Path $app 'docs') -Recurse
$licenses = Join-Path $app 'licenses'
New-Item -ItemType Directory -Path $licenses -Force | Out-Null
foreach ($jar in Get-ChildItem -LiteralPath "$app/app" -Filter '*.jar') {
    $zip = [IO.Compression.ZipFile]::OpenRead($jar.FullName)
    try {
        foreach ($entry in $zip.Entries | Where-Object { $_.Name -match '^(LICENSE|NOTICE|COPYING|AL2\.0|LGPL2\.1)' }) {
            $name = $jar.BaseName + '-' + ($entry.FullName -replace '[^a-zA-Z0-9._-]', '_')
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $licenses $name), $true)
        }
    } finally { $zip.Dispose() }
}
Invoke-WebRequest 'https://www.gnu.org/licenses/old-licenses/lgpl-2.1.txt' -OutFile "$licenses/LGPL-2.1.txt"
Invoke-WebRequest 'https://www.apache.org/licenses/LICENSE-2.0.txt' -OutFile "$licenses/Apache-2.0.txt"
$libraries = @{jlayer='1.0.1.4';mp3spi='1.9.5.4';jorbis='0.0.17.4';'tritonus-share'='0.3.7.4'}
foreach ($id in $libraries.Keys) {
    $v = $libraries[$id]
    $base = "https://repo.maven.apache.org/maven2/com/googlecode/soundlibs/$id/$v/$id-$v"
    Invoke-WebRequest "$base-sources.jar" -OutFile "$sources/$id-$v-sources.jar"
    Invoke-WebRequest "$base.pom" -OutFile "$sources/$id-$v.pom"
}
Invoke-WebRequest 'https://repo.maven.apache.org/maven2/com/googlecode/soundlibs/soundlibs/1.4/soundlibs-1.4.pom' -OutFile "$sources/soundlibs-1.4.pom"
Copy-Item -LiteralPath $sources -Destination "$app/third-party-sources" -Recurse
Invoke-WebRequest 'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.11%2B10/OpenJDK21U-jdk-sources_21.0.11_10.tar.gz' -OutFile "$sources/OpenJDK21U-jdk-sources_21.0.11_10.tar.gz"
Copy-Item -LiteralPath (Join-Path $root 'THIRD_PARTY_NOTICES.md') -Destination $sources
Copy-Item -LiteralPath $apk -Destination "$output/SyncListen-v$Version-android.apk"
$archives = @(
    @{Source=$app;Target="$output/SyncListen-v$Version-windows-x64.zip"},
    @{Source=$sources;Target="$output/SyncListen-v$Version-third-party-sources.zip"}
)
foreach ($archive in $archives) {
    if (Test-Path -LiteralPath $archive.Target) { throw "Release archive already exists: $($archive.Target)" }
    [IO.Compression.ZipFile]::CreateFromDirectory($archive.Source, $archive.Target, [IO.Compression.CompressionLevel]::Optimal, $true)
}
$assets = @("$output/SyncListen-v$Version-android.apk") + @($archives.Target)
$checksums = foreach ($asset in $assets) {
    $hash = (Get-FileHash -LiteralPath $asset -Algorithm SHA256).Hash.ToLowerInvariant()
    "$hash  $([IO.Path]::GetFileName($asset))"
}
[IO.File]::WriteAllLines("$output/SHA256SUMS.txt", $checksums, [Text.UTF8Encoding]::new($false))
Get-Item -LiteralPath $assets | Select-Object Name,Length
Write-Output "Checksums: $output/SHA256SUMS.txt"
