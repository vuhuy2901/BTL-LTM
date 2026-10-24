param([switch]$SkipBuild)

$ErrorActionPreference = 'Stop'
$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$dist = Join-Path $repoRoot 'dist'
$demoStage = Join-Path $dist 'demo-stage'
$sourceStage = Join-Path $dist 'source-stage'

function Remove-Stage([string]$path) {
    $full = [System.IO.Path]::GetFullPath($path)
    $allowed = [System.IO.Path]::GetFullPath($dist) + [System.IO.Path]::DirectorySeparatorChar
    if (-not $full.StartsWith($allowed, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Duong dan don dep nam ngoai dist: $full"
    }
    if (Test-Path -LiteralPath $full) { Remove-Item -LiteralPath $full -Recurse -Force }
}

function Write-Archive([string]$source, [string]$destination) {
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        try {
            Compress-Archive -Path (Join-Path $source '*') -DestinationPath $destination -Force -ErrorAction Stop
            return
        } catch {
            if ($attempt -eq 3) { throw }
            Start-Sleep -Seconds 2
        }
    }
}

Push-Location $repoRoot
try {
    if (-not $SkipBuild) {
        & mvn.cmd -q -DskipTests install
        if ($LASTEXITCODE -ne 0) { throw 'Maven install that bai. Hay tat server dang chay roi thu lai.' }
        & mvn.cmd -q -pl fx-client dependency:copy-dependencies '-DincludeScope=runtime'
        if ($LASTEXITCODE -ne 0) { throw 'Khong sao chep duoc thu vien client.' }
    }

    $serverJar = Join-Path $repoRoot 'server/target/server-1.0-SNAPSHOT.jar'
    $clientJar = Join-Path $repoRoot 'fx-client/target/fx-client-1.0-SNAPSHOT.jar'
    $dependencyDir = Join-Path $repoRoot 'fx-client/target/dependency'
    foreach ($item in @($serverJar, $clientJar, $dependencyDir)) {
        if (-not (Test-Path -LiteralPath $item)) { throw "Thieu file build: $item" }
    }

    New-Item -ItemType Directory -Path $dist -Force | Out-Null
    Remove-Stage $demoStage
    Remove-Stage $sourceStage
    $demoRoot = Join-Path $demoStage 've-hinh-demo'
    $sourceRoot = Join-Path $sourceStage 've-hinh-source'
    New-Item -ItemType Directory -Path (Join-Path $demoRoot 'server'), (Join-Path $demoRoot 'client/lib'), $sourceRoot -Force | Out-Null
    Copy-Item -LiteralPath $serverJar -Destination (Join-Path $demoRoot 'server/server.jar')
    Copy-Item -LiteralPath $clientJar -Destination (Join-Path $demoRoot 'client/client.jar')
    Copy-Item -Path (Join-Path $dependencyDir '*.jar') -Destination (Join-Path $demoRoot 'client/lib')
    Copy-Item -LiteralPath (Join-Path $repoRoot 'demo/start-server.cmd'), (Join-Path $repoRoot 'demo/start-client.cmd'), (Join-Path $repoRoot 'demo/allow-lan-firewall.ps1'), (Join-Path $repoRoot 'demo/DEMO.md') -Destination $demoRoot

    foreach ($file in @('pom.xml','README.md','HUONG_DAN_CHAY.md','AGENT_GUIDE_nhom8.md','game_rules_nhom8.md','tech.md')) {
        $path = Join-Path $repoRoot $file
        if (Test-Path -LiteralPath $path) { Copy-Item -LiteralPath $path -Destination $sourceRoot }
    }
    foreach ($module in @('common','server','fx-client')) {
        $dest = Join-Path $sourceRoot $module
        New-Item -ItemType Directory -Path $dest -Force | Out-Null
        Copy-Item -LiteralPath (Join-Path $repoRoot "$module/pom.xml") -Destination $dest
        Copy-Item -LiteralPath (Join-Path $repoRoot "$module/src") -Destination $dest -Recurse
    }
    Copy-Item -LiteralPath (Join-Path $repoRoot 'demo'), (Join-Path $repoRoot 'scripts') -Destination $sourceRoot -Recurse
    $demoZip = Join-Path $dist 've-hinh-demo-windows.zip'
    $sourceZip = Join-Path $dist 've-hinh-source.zip'
    Start-Sleep -Milliseconds 750
    Write-Archive $demoRoot $demoZip
    Write-Archive $sourceRoot $sourceZip
    Write-Host "Demo: $demoZip"
    Write-Host "Ma nguon: $sourceZip"
} finally {
    Pop-Location
    Remove-Stage $demoStage
    Remove-Stage $sourceStage
}
