param(
	[ValidatePattern("^[0-9]+(?:\.[0-9]+){0,3}(?:-[0-9A-Za-z]+(?:\.[0-9A-Za-z]+)*)?$")]
	[string]$Version
)

$ErrorActionPreference = 'Stop'

if ($env:OS -ne 'Windows_NT')
{
	throw 'This packaging script must be run on Windows.'
}

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$gradleWrapper = Join-Path $projectRoot 'gradlew.bat'

$cscCandidates = @(
	(Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'),
	(Join-Path $env:WINDIR 'Microsoft.NET\Framework\v4.0.30319\csc.exe')
)
$cscPath = $cscCandidates | Where-Object { Test-Path $_ -PathType Leaf } | Select-Object -First 1

if (-not $cscPath)
{
	throw 'The Windows .NET Framework C# compiler was not found. Enable .NET Framework 4.x in Windows Features and try again.'
}

Push-Location $projectRoot
try
{
	Write-Host 'Running tests and building the standalone HapticScape desktop app...'
	& $gradleWrapper "-PappVersion=$Version" clean test verifyStandaloneJar collectRuntimeLicenses
	if ($LASTEXITCODE -ne 0)
	{
		throw "Gradle failed with exit code $LASTEXITCODE."
	}

	$jarPath = Join-Path $projectRoot 'build\libs\hapticscape-desktop.jar'
	if (-not (Test-Path $jarPath -PathType Leaf))
	{
		throw "The expected standalone desktop JAR was not created: $jarPath"
	}

	$packageRoot = Join-Path $projectRoot 'build\windows-package'
	$appDirectory = Join-Path $packageRoot 'HapticScape'
	$appFilesDirectory = Join-Path $appDirectory 'app'
	$licensesDirectory = Join-Path $appFilesDirectory 'licenses'
	$distributionDirectory = Join-Path $projectRoot 'build\distribution'
	$nativeTestDirectory = Join-Path $projectRoot 'build\native-tests'
	$runtimeDirectory = Join-Path $appDirectory 'runtime'

	switch ($env:PROCESSOR_ARCHITECTURE)
	{
		'ARM64' { $architecture = 'arm64' }
		'AMD64' { $architecture = 'x64' }
		default { $architecture = ([string] $env:PROCESSOR_ARCHITECTURE).ToLowerInvariant() }
	}

	if (Test-Path $packageRoot)
	{
		Remove-Item -Recurse -Force $packageRoot
	}
	New-Item -ItemType Directory -Force $appFilesDirectory | Out-Null
	New-Item -ItemType Directory -Force $licensesDirectory | Out-Null
	New-Item -ItemType Directory -Force $distributionDirectory | Out-Null
	New-Item -ItemType Directory -Force $nativeTestDirectory | Out-Null

	Copy-Item $jarPath (Join-Path $appFilesDirectory 'hapticscape-desktop.jar')
	# Transitional compatibility copy: HapticScape 2.4.x updaters validate this
	# legacy filename before replacing themselves with the standalone launcher.
	# The bytes are the standalone desktop JAR and this alias can be removed
	# after the first standalone release has become the update baseline.
	Copy-Item $jarPath (Join-Path $appFilesDirectory 'hapticscape-client.jar')
	Copy-Item (Join-Path $projectRoot 'LICENSE') (Join-Path $licensesDirectory 'HapticScape.txt')
	Copy-Item (Join-Path $projectRoot 'licenses\*') $licensesDirectory -Recurse
	Copy-Item (Join-Path $projectRoot 'build\generated\runtime-licenses') (Join-Path $licensesDirectory 'resolved-artifacts') -Recurse
	Copy-Item (Join-Path $projectRoot 'README.md') (Join-Path $appDirectory 'README-FIRST.md')

	$jlinkCandidates = @()
	if (-not [string]::IsNullOrWhiteSpace($env:HAPTICSCAPE_JAVA_HOME))
	{
		$jlinkCandidates += (Join-Path $env:HAPTICSCAPE_JAVA_HOME 'bin\jlink.exe')
	}
	if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME))
	{
		$jlinkCandidates += (Join-Path $env:JAVA_HOME 'bin\jlink.exe')
	}
	$jlinkCommand = Get-Command 'jlink.exe' -ErrorAction SilentlyContinue
	if ($jlinkCommand)
	{
		$jlinkCandidates += $jlinkCommand.Source
	}
	$jlinkPath = $jlinkCandidates | Where-Object { $_ -and (Test-Path $_ -PathType Leaf) } | Select-Object -First 1
	if (-not $jlinkPath)
	{
		throw 'A JDK with jlink is required to package HapticScape. Set HAPTICSCAPE_JAVA_HOME to a redistributable JDK 11+ and try again.'
	}

	$runtimeModules = @(
		'java.base',
		'java.desktop',
		'java.logging',
		'java.management',
		'java.naming',
		'java.sql',
		'jdk.crypto.ec',
		'jdk.unsupported'
	)
	Write-Host 'Creating the bundled HapticScape Java runtime...'
	& $jlinkPath `
		'--add-modules' ($runtimeModules -join ',') `
		'--strip-debug' `
		'--no-header-files' `
		'--no-man-pages' `
		'--compress=2' `
		'--output' $runtimeDirectory
	if ($LASTEXITCODE -ne 0)
	{
		throw "jlink failed with exit code $LASTEXITCODE."
	}
	$bundledJava = Join-Path $runtimeDirectory 'bin\javaw.exe'
	if (-not (Test-Path $bundledJava -PathType Leaf))
	{
		throw "The bundled HapticScape Java runtime was not created correctly: $bundledJava"
	}

	$releaseManifest = @{
		version = $Version
		architecture = $architecture
		repository = 'ashy0019/HapticScape'
	} | ConvertTo-Json -Compress
	$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
	[System.IO.File]::WriteAllText(
		(Join-Path $appFilesDirectory 'release.json'),
		$releaseManifest,
		$utf8NoBom
	)

	$updateCoreSource = Join-Path $projectRoot 'launcher\UpdateCore.cs'
	$deepLinkCoreSource = Join-Path $projectRoot 'launcher\DeepLinkCore.cs'
	$launchOptionsCoreSource = Join-Path $projectRoot 'launcher\LaunchOptionsCore.cs'
	$applicationLayoutValidationSource = Join-Path $projectRoot 'launcher\ApplicationLayoutValidation.cs'
	$nativeTestSource = Join-Path $projectRoot 'launcher-tests\UpdateCoreTests.cs'
	$nativeTestPath = Join-Path $nativeTestDirectory 'HapticScapeUpdateCoreTests.exe'
	$nativeReferences = @(
		'/reference:System.dll',
		'/reference:System.Web.Extensions.dll',
		'/reference:System.IO.Compression.dll',
		'/reference:System.IO.Compression.FileSystem.dll'
	)
	$nativeTestArguments = @(
		'/nologo',
		'/target:exe',
		'/optimize+',
		'/platform:anycpu',
		"/out:$nativeTestPath"
	) + $nativeReferences + @(
		$updateCoreSource,
		$deepLinkCoreSource,
		$launchOptionsCoreSource,
		$applicationLayoutValidationSource,
		$nativeTestSource
	)

	Write-Host 'Compiling and running native updater tests...'
	& $cscPath @nativeTestArguments
	if ($LASTEXITCODE -ne 0)
	{
		throw "The updater test compiler failed with exit code $LASTEXITCODE."
	}
	& $nativeTestPath
	if ($LASTEXITCODE -ne 0)
	{
		throw "The updater tests failed with exit code $LASTEXITCODE."
	}

	$updaterSource = Join-Path $projectRoot 'launcher\HapticScapeUpdater.cs'
	$updaterPath = Join-Path $appFilesDirectory 'HapticScapeUpdater.exe'
	$updaterArguments = @(
		'/nologo',
		'/target:winexe',
		'/optimize+',
		'/platform:anycpu',
		'/reference:System.dll',
		'/reference:System.Windows.Forms.dll',
		"/out:$updaterPath",
		$updaterSource,
		$applicationLayoutValidationSource
	)

	$iconPath = Join-Path $projectRoot 'hapticscape.ico'
	if (Test-Path $iconPath -PathType Leaf)
	{
		$updaterArguments += "/win32icon:$iconPath"
	}

	Write-Host 'Creating HapticScapeUpdater.exe...'
	& $cscPath @updaterArguments
	if ($LASTEXITCODE -ne 0)
	{
		throw "The Windows updater compiler failed with exit code $LASTEXITCODE."
	}

	$launcherSource = Join-Path $projectRoot 'launcher\HapticScapeLauncher.cs'
	$launcherPath = Join-Path $appDirectory 'HapticScape.exe'
	$cscArguments = @(
		'/nologo',
		'/target:winexe',
		'/optimize+',
		'/platform:anycpu',
		'/reference:System.Drawing.dll',
		'/reference:System.Windows.Forms.dll',
		"/out:$launcherPath"
	) + $nativeReferences + @(
		$updateCoreSource,
		$deepLinkCoreSource,
		$launchOptionsCoreSource,
		$launcherSource
	)

	if (Test-Path $iconPath -PathType Leaf)
	{
		$cscArguments += "/win32icon:$iconPath"
	}

	Write-Host 'Creating HapticScape.exe...'
	& $cscPath @cscArguments
	if ($LASTEXITCODE -ne 0)
	{
		throw "The Windows launcher compiler failed with exit code $LASTEXITCODE."
	}

	$zipPath = Join-Path $distributionDirectory "HapticScape-Windows-$architecture-$Version.zip"
	$checksumPath = "$zipPath.sha256"
	if (Test-Path $zipPath)
	{
		Remove-Item -Force $zipPath
	}
	if (Test-Path $checksumPath)
	{
		Remove-Item -Force $checksumPath
	}

	Write-Host 'Compressing the distributable bundle...'
	Compress-Archive -Path $appDirectory -DestinationPath $zipPath -CompressionLevel Optimal
	$hash = (Get-FileHash -Algorithm SHA256 $zipPath).Hash.ToLowerInvariant()
	[System.IO.File]::WriteAllText(
		$checksumPath,
		"$hash *$(Split-Path -Leaf $zipPath)`r`n",
		[System.Text.Encoding]::ASCII
	)

	Write-Host ''
	Write-Host 'Package created successfully:' -ForegroundColor Green
	Write-Host $zipPath
	Write-Host $checksumPath
	Write-Host ''
	Write-Host 'Test build\windows-package\HapticScape\HapticScape.exe before sharing the ZIP.'
}
finally
{
	Pop-Location
}
