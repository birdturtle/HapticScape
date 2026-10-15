param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern("^[0-9]+(?:\.[0-9]+){0,3}(?:-[0-9A-Za-z]+(?:\.[0-9A-Za-z]+)*)?$")]
    [string]$Version,

    [string]$RuneLiteVersion,
    [switch]$SkipArchive
)

$ErrorActionPreference = 'Stop'

if ($env:OS -ne 'Windows_NT')
{
    throw 'This packaging script must be run on Windows.'
}

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$gradleWrapper = Join-Path $projectRoot 'gradlew.bat'
$moduleRoot = Join-Path $projectRoot 'runelite-bridge-client'

if (-not $RuneLiteVersion)
{
    $runtimePropertiesPath = Join-Path $moduleRoot 'RUNTIME.properties'
    $runtimeLine = Get-Content $runtimePropertiesPath | Where-Object { $_ -match '^runeLiteVersion=' } | Select-Object -First 1
    if (-not $runtimeLine)
    {
        throw 'RUNTIME.properties does not contain runeLiteVersion.'
    }
    $RuneLiteVersion = ($runtimeLine -split '=', 2)[1].Trim()
}
if ($RuneLiteVersion -notmatch '^[0-9]+(?:\.[0-9]+){2,3}$')
{
    throw "Invalid RuneLite version: $RuneLiteVersion"
}

$cscCandidates = @(
    (Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'),
    (Join-Path $env:WINDIR 'Microsoft.NET\Framework\v4.0.30319\csc.exe')
)
$cscPath = $cscCandidates | Where-Object { Test-Path $_ -PathType Leaf } | Select-Object -First 1
if (-not $cscPath)
{
    throw 'The Windows .NET Framework C# compiler was not found. Enable .NET Framework 4.x in Windows Features and try again.'
}

$provenancePath = Join-Path $moduleRoot 'BRIDGE-SOURCE.properties'
$provenance = @{}
Get-Content $provenancePath | ForEach-Object {
    if ($_ -match '^([^#=]+)=(.*)$')
    {
        $provenance[$matches[1]] = $matches[2]
    }
}
$bridgeCommit = $provenance['sourceCommit']
if (-not $bridgeCommit)
{
    throw 'BRIDGE-SOURCE.properties does not contain sourceCommit.'
}

Push-Location $projectRoot
try
{
    Write-Host 'Running bridge tests and building the packaged RuneLite client...'
    $gradleArguments = @(
        "-PappVersion=$Version",
        "-PruneLiteVersion=$RuneLiteVersion",
        ':runelite-bridge-client:clean',
        ':runelite-bridge-client:test',
        ':runelite-bridge-client:verifyBridgeClientJar',
        ':runelite-bridge-client:collectRuntimeLicenses'
    )
    & $gradleWrapper @gradleArguments
    if ($LASTEXITCODE -ne 0)
    {
        throw "Gradle failed with exit code $LASTEXITCODE."
    }

    $jarPath = Join-Path $moduleRoot 'build\libs\lumbridge.jar'
    if (-not (Test-Path $jarPath -PathType Leaf))
    {
        throw "The expected LumBridge JAR was not created: $jarPath"
    }

    $packageRoot = Join-Path $projectRoot 'build\bridge-windows-package'
    $appDirectory = Join-Path $packageRoot 'LumBridge'
    $appFilesDirectory = Join-Path $appDirectory 'app'
    $licensesDirectory = Join-Path $appDirectory 'licenses'
    $distributionDirectory = Join-Path $projectRoot 'build\distribution'

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

    Copy-Item $jarPath (Join-Path $appFilesDirectory 'lumbridge.jar')
    Copy-Item $provenancePath (Join-Path $appFilesDirectory 'BRIDGE-SOURCE.properties')
    Copy-Item (Join-Path $moduleRoot 'README-FIRST.md') (Join-Path $appDirectory 'README-FIRST.md')
    Copy-Item (Join-Path $projectRoot 'LICENSE') (Join-Path $licensesDirectory 'HapticScape.txt')
    Copy-Item (Join-Path $moduleRoot 'upstream\LOCAL-EVENT-BRIDGE-LICENSE.txt') (Join-Path $licensesDirectory 'Local-Event-Bridge.txt')
    Copy-Item (Join-Path $projectRoot 'licenses\*') $licensesDirectory -Recurse
    Copy-Item (Join-Path $moduleRoot 'build\generated\runtime-licenses') (Join-Path $licensesDirectory 'resolved-artifacts') -Recurse

    $releaseManifest = @{
        version = $Version
        architecture = $architecture
        repository = 'ashy0019/HapticScape'
        artifact = 'lumbridge'
        runeLiteVersion = $RuneLiteVersion
        bridgeSourceRepository = 'ashy0019/runelite-local-event-bridge'
        bridgeSourceCommit = $bridgeCommit
    } | ConvertTo-Json -Compress
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText(
        (Join-Path $appFilesDirectory 'release.json'),
        $releaseManifest,
        $utf8NoBom
    )

    $launcherSource = Join-Path $projectRoot 'bridge-launcher\LumBridgeLauncher.cs'
    $launcherPath = Join-Path $appDirectory 'LumBridge.exe'
    $cscArguments = @(
        '/nologo',
        '/target:winexe',
        '/optimize+',
        '/platform:anycpu',
        '/reference:System.dll',
        '/reference:System.Windows.Forms.dll',
        "/out:$launcherPath",
        $launcherSource
    )

    $iconPath = Join-Path $projectRoot 'lumbridge.ico'
    if (-not (Test-Path $iconPath -PathType Leaf))
    {
        throw "LumBridge icon was not found: $iconPath"
    }
    $cscArguments += "/win32icon:$iconPath"

    Write-Host 'Creating LumBridge.exe...'
    & $cscPath @cscArguments
    if ($LASTEXITCODE -ne 0)
    {
        throw "The Windows launcher compiler failed with exit code $LASTEXITCODE."
    }

    if (!$SkipArchive) {
        $zipPath = Join-Path $distributionDirectory "LumBridge-Windows-$architecture-$Version.zip"
        $checksumPath = "$zipPath.sha256"
        if (Test-Path $zipPath)
        {
            Remove-Item -Force $zipPath
        }
        if (Test-Path $checksumPath)
        {
            Remove-Item -Force $checksumPath
        }

        Write-Host 'Compressing the LumBridge bundle...'
        Compress-Archive -Path $appDirectory -DestinationPath $zipPath -CompressionLevel Optimal
        $hash = (Get-FileHash -Algorithm SHA256 $zipPath).Hash.ToLowerInvariant()
        [System.IO.File]::WriteAllText(
            $checksumPath,
            "$hash *$(Split-Path -Leaf $zipPath)`r`n",
            [System.Text.Encoding]::ASCII
        )

        Write-Host ''
        Write-Host 'LumBridge package created successfully:' -ForegroundColor Green
        Write-Host $zipPath
        Write-Host $checksumPath
        Write-Host ''
        Write-Host 'Test build\bridge-windows-package\LumBridge\LumBridge.exe before sharing the ZIP.'
    }

}
finally
{
    Pop-Location
}
