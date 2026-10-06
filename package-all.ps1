param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern("^[0-9]+(?:\.[0-9]+){0,3}(?:-[0-9A-Za-z]+(?:\.[0-9A-Za-z]+)*)?$")]
    [string]$Version,

    [string]$RuneLiteVersion,

    [switch]$UnifiedLauncher
)

$ErrorActionPreference = 'Stop'

if ($env:OS -ne 'Windows_NT')
{
    throw 'This packaging script must be run on Windows.'
}

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$desktopPackager = Join-Path $projectRoot 'package-windows.ps1'
$bridgePackager = Join-Path $projectRoot 'package-bridge-client.ps1'

# Public release packages must come from the exact source commit named by
# their tag. CI's disposable 0.0.0-ci build deliberately has no release tag.
if ($Version -ne '0.0.0-ci')
{
    if (-not [string]::IsNullOrWhiteSpace($RuneLiteVersion))
    {
        throw 'Release packages use the RuneLite version pinned in RUNTIME.properties. Do not override it.'
    }

    $tag = "v$Version"
    $tagCommit = & git -C $projectRoot rev-parse --verify "refs/tags/$tag^{commit}" 2>$null
    if ($LASTEXITCODE -ne 0 -or -not $tagCommit)
    {
        throw "Tag $tag was not found. Create the release tag before packaging."
    }

    $headCommit = & git -C $projectRoot rev-parse HEAD
    if ($LASTEXITCODE -ne 0 -or -not $headCommit)
    {
        throw 'Could not identify the source commit for this package.'
    }
    if ($headCommit.Trim() -ne $tagCommit.Trim())
    {
        throw "The checkout is at $($headCommit.Trim()), but $tag points to $($tagCommit.Trim()). Check out $tag before packaging."
    }

    $changes = & git -C $projectRoot status --porcelain
    if ($LASTEXITCODE -ne 0 -or $changes)
    {
        throw 'The release checkout has uncommitted or untracked changes. Package from a clean tag checkout.'
    }

    Write-Host "Packaging $tag from $($tagCommit.Trim())."
}

if (-not (Test-Path $desktopPackager -PathType Leaf))
{
    throw "Desktop packaging script was not found: $desktopPackager"
}
if (-not (Test-Path $bridgePackager -PathType Leaf))
{
    throw "Bridge packaging script was not found: $bridgePackager"
}

switch ($env:PROCESSOR_ARCHITECTURE)
{
    'ARM64' { $architecture = 'arm64' }
    'AMD64' { $architecture = 'x64' }
    default { $architecture = ([string] $env:PROCESSOR_ARCHITECTURE).ToLowerInvariant() }
}

$distributionDirectory = Join-Path $projectRoot 'build\distribution'
$desktopZip = Join-Path $distributionDirectory "HapticScape-Windows-$architecture-$Version.zip"
$bridgeZip = Join-Path $distributionDirectory "LumBridge-Windows-$architecture-$Version.zip"

Push-Location $projectRoot
try
{
    Write-Host ''
    Write-Host '=== Packaging standalone HapticScape ===' -ForegroundColor Cyan
    # Run this first: it performs a root Gradle clean, so doing it second would
    # delete bridge packaging output produced earlier in the same run.
    & $desktopPackager -Version $Version

    Write-Host ''
    Write-Host '=== Packaging LumBridge ===' -ForegroundColor Cyan
    if ([string]::IsNullOrWhiteSpace($RuneLiteVersion))
    {
        & $bridgePackager -Version $Version
    }
    else
    {
        & $bridgePackager -Version $Version -RuneLiteVersion $RuneLiteVersion
    }

    if ($UnifiedLauncher) { & (Join-Path $projectRoot 'package-unified-launcher.ps1') -Version $Version -Architecture $architecture }

    if (-not (Test-Path $desktopZip -PathType Leaf))
    {
        throw "Standalone HapticScape package was not created: $desktopZip"
    }
    if (-not (Test-Path $bridgeZip -PathType Leaf))
    {
        throw "LumBridge package was not created: $bridgeZip"
    }

    Write-Host ''
    Write-Host 'Both packages created successfully:' -ForegroundColor Green
    Write-Host "  $desktopZip"
    Write-Host "  $desktopZip.sha256"
    Write-Host "  $bridgeZip"
    Write-Host "  $bridgeZip.sha256"
}
finally
{
    Pop-Location
}
