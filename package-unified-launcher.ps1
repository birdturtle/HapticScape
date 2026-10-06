param(
    [Parameter(Mandatory = $true)][string]$Version,
    [Parameter(Mandatory = $true)][ValidateSet('x64', 'arm64')][string]$Architecture
)
$ErrorActionPreference = 'Stop'
if ($env:OS -ne 'Windows_NT') { throw 'Run this packager on Windows, after both Java bundles have been built.' }
$root = $PSScriptRoot
$package = Join-Path $root 'build\windows-package\HapticScape'
$bridge = Join-Path $root 'build\bridge-windows-package\LumBridge'
if (!(Test-Path "$package\HapticScape.exe") -or !(Test-Path "$bridge\app\lumbridge.jar")) { throw 'Build both Java packages first with package-all.ps1.' }
Push-Location (Join-Path $root 'suite-launcher')
try {
    & npm.cmd ci
    if ($LASTEXITCODE -ne 0) { throw 'Launcher dependency installation failed.' }
    & npm.cmd run check
    if ($LASTEXITCODE -ne 0) { throw 'Launcher frontend checks failed.' }
    & cargo test --manifest-path src-tauri/Cargo.toml
    if ($LASTEXITCODE -ne 0) { throw 'Launcher native tests failed.' }
    $config = @{ version = $Version } | ConvertTo-Json -Compress
    & npx.cmd tauri build --no-bundle --config $config
    if ($LASTEXITCODE -ne 0) { throw 'Launcher build failed.' }
} finally { Pop-Location }
$launcher = Join-Path $package 'launcher'
New-Item -ItemType Directory -Path $launcher -Force | Out-Null
Copy-Item "$root\suite-launcher\src-tauri\target\release\hapticscape-launcher.exe" "$launcher\HapticScapeLauncher.exe"
Copy-Item $bridge "$package\LumBridge" -Recurse -Force
Move-Item "$package\HapticScape.exe" "$package\HapticScapeLegacy.exe" -Force
$csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
& $csc /nologo /target:winexe /optimize+ /reference:System.dll /reference:System.Windows.Forms.dll "/win32icon:$root\hapticscape.ico" "/out:$package\HapticScape.exe" "$root\launcher\UnifiedLauncherBootstrap.cs" "$root\launcher\WebViewRuntime.cs"
if ($LASTEXITCODE -ne 0) { throw 'Compatibility bootstrap compilation failed.' }
# Ship Microsoft's signed Evergreen bootstrapper. It runs only if the runtime is absent.
$installer = Join-Path $launcher 'MicrosoftEdgeWebview2Setup.exe'
Invoke-WebRequest 'https://go.microsoft.com/fwlink/p/?LinkId=2124703' -OutFile $installer
$signature = Get-AuthenticodeSignature $installer
if ($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -notmatch 'O=Microsoft Corporation(?:,|$)') { throw 'The browser runtime installer did not pass Microsoft signature validation.' }
@{ schemaVersion = 1; version = $Version; repository = 'birdturtle/HapticScape'; launcher = 'launcher/HapticScapeLauncher.exe' } | ConvertTo-Json | Set-Content "$package\app\suite.json" -Encoding UTF8
$zip = Join-Path $root "build\distribution\HapticScape-Windows-$Architecture-$Version.zip"
Remove-Item $zip -Force
Compress-Archive -Path $package -DestinationPath $zip -CompressionLevel Optimal
$hash = (Get-FileHash $zip -Algorithm SHA256).Hash.ToLowerInvariant()
"$hash  $([IO.Path]::GetFileName($zip))" | Set-Content "$zip.sha256" -Encoding ASCII
Write-Host "Unified launcher compatibility package: $zip"
