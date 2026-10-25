$ErrorActionPreference = 'Stop'

$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
. (Join-Path $PSScriptRoot 'load-local-env.ps1')
if (-not $env:DB_URL) {
    $env:DB_URL = 'jdbc:mysql://localhost:3306/scribble_game?createDatabaseIfNotExist=true&useSSL=false&serverTimezone=Asia/Ho_Chi_Minh&characterEncoding=UTF-8&allowPublicKeyRetrieval=true'
}
if (-not $env:DB_USER) { $env:DB_USER = 'root' }
if (-not $env:DB_PASSWORD) { throw 'Đặt DB_PASSWORD trong .env hoặc biến môi trường trước khi khởi động.' }
if (-not $env:SERVER_ADDRESS) { $env:SERVER_ADDRESS = '127.0.0.1' }
if (-not $env:SERVER_PORT) { $env:SERVER_PORT = '8080' }
if (-not $env:GAME_DEMO_SEED_ENABLED) { $env:GAME_DEMO_SEED_ENABLED = 'false' }
if (-not (Get-Command mvn.cmd -ErrorAction SilentlyContinue)) {
    throw 'Chưa tìm thấy Maven. Cài Maven và thêm thư mục bin vào PATH.'
}
if (Get-NetTCPConnection -LocalPort ([int]$env:SERVER_PORT) -State Listen -ErrorAction SilentlyContinue) {
    throw "Cổng $($env:SERVER_PORT) đang được sử dụng. Hãy dừng Backend cũ hoặc chọn SERVER_PORT khác."
}

Set-Location -LiteralPath $projectRoot
Write-Host "Database local: MySQL/$($env:DB_USER)@$($env:DB_URL)"
Write-Host "Backend: http://$($env:SERVER_ADDRESS):$($env:SERVER_PORT)"
& mvn.cmd -B -DskipTests install
if ($LASTEXITCODE -ne 0) { throw "Build thất bại (Maven exit code $LASTEXITCODE)." }
& mvn.cmd -pl server spring-boot:run
if ($LASTEXITCODE -ne 0) { throw "Backend dừng với Maven exit code $LASTEXITCODE." }
