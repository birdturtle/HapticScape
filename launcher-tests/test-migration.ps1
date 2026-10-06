$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root 'build\native-migration-tests'
New-Item -ItemType Directory -Path $out -Force | Out-Null
$csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
& $csc /nologo /target:exe "/out:$out\fixture.exe" "$PSScriptRoot\MigrationLauncherFixture.cs"
if ($LASTEXITCODE -ne 0) { throw 'Migration fixture compilation failed.' }
& $csc /nologo /target:exe /main:UpdateMigrationTests /reference:System.dll /reference:System.Windows.Forms.dll "/out:$out\tests.exe" "$PSScriptRoot\UpdateMigrationTests.cs" "$root\launcher\HapticScapeUpdater.cs" "$root\launcher\ApplicationLayoutValidation.cs" "$root\launcher\LauncherStartupValidation.cs" "$root\launcher\WebViewRuntime.cs"
if ($LASTEXITCODE -ne 0) { throw 'Migration test compilation failed.' }
& "$out\tests.exe" "$out\fixture.exe"
if ($LASTEXITCODE -ne 0) { throw 'Migration transaction tests failed.' }
& $csc /nologo /target:exe /main:SuiteInstallerTests /reference:System.dll /reference:System.Windows.Forms.dll "/out:$out\installer-tests.exe" "$PSScriptRoot\SuiteInstallerTests.cs" "$root\launcher\SuiteInstaller.cs" "$root\launcher\ApplicationLayoutValidation.cs"
if ($LASTEXITCODE -ne 0) { throw 'Launcher installer test compilation failed.' }
& "$out\installer-tests.exe"
if ($LASTEXITCODE -ne 0) { throw 'Launcher installer tests failed.' }
