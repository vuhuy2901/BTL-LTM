$ErrorActionPreference = "Stop"

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
. (Join-Path $PSScriptRoot 'load-local-env.ps1')
if (-not $env:DB_PASSWORD) { throw 'Đặt DB_PASSWORD trong .env hoặc biến môi trường trước khi khởi động.' }
Set-Location -LiteralPath $projectRoot

if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    throw "Không tìm thấy Maven (mvn). Hãy cài Maven và thêm vào PATH trước."
}

$portCheck = Test-NetConnection -ComputerName "localhost" -Port 8080 -WarningAction SilentlyContinue
if ($portCheck.TcpTestSucceeded) {
    throw "Cổng 8080 đang được sử dụng. Hãy tắt server cũ rồi chạy lại lệnh này."
}

Write-Host "Đang biên dịch và cài các module common, server, fx-client..."
& mvn -DskipTests install
if ($LASTEXITCODE -ne 0) {
    throw "Build thất bại (mvn exit code $LASTEXITCODE)."
}

Write-Host "Đang mở server trong cửa sổ PowerShell riêng..."
$server = Start-Process -FilePath "powershell.exe" `
    -ArgumentList @("-NoExit", "-Command", "mvn -pl server spring-boot:run") `
    -WorkingDirectory $projectRoot -PassThru

Write-Host "Đang chờ server mở cổng 8080..."
$serverReady = $false
for ($attempt = 0; $attempt -lt 120; $attempt++) {
    Start-Sleep -Seconds 1
    $check = Test-NetConnection -ComputerName "localhost" -Port 8080 -WarningAction SilentlyContinue
    if ($check.TcpTestSucceeded) {
        $serverReady = $true
        break
    }
    if ($server.HasExited) {
        throw "Server đã dừng trước khi mở cổng 8080. Xem cửa sổ server để biết lỗi."
    }
}

if (-not $serverReady) {
    throw "Đợi server quá 120 giây nhưng cổng 8080 chưa sẵn sàng. Xem cửa sổ server để biết trạng thái."
}

Write-Host "Server đã sẵn sàng. Đang mở 3 client..."
for ($clientNumber = 1; $clientNumber -le 3; $clientNumber++) {
    Start-Process -FilePath "powershell.exe" `
        -ArgumentList @("-NoProfile", "-Command", "mvn -pl fx-client javafx:run") `
        -WorkingDirectory $projectRoot -WindowStyle Hidden
    Start-Sleep -Milliseconds 700
}

Write-Host "Đã mở server và 3 client. Đăng nhập bằng 3 tài khoản khác nhau trên các client."
