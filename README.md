# Dự án Vẽ Hình Đoán Ý (Nhóm 8)

Hệ thống trò chơi "Vẽ Hình Đoán Ý" nhiều người chơi thời gian thực (Pictionary Online) sử dụng kiến trúc WebSocket.

- **Backend**: Java 21 trở lên, Spring Boot 3.3.5, WebSocket, Hibernate/JPA, MySQL local, BCrypt.
- **Client**: JavaFX 21.0.5 Desktop. Build bằng Maven, không cần Node.js.

Luồng: JavaFX → WebSocket `/ws/game` → `GameWebSocketHandler` → `GameService`/`AuthService`/`FriendService` → `GameSession`/`Room` → Spring Data JPA → MySQL local `scribble_game`. `common` chứa message/DTO dùng chung; `server` chứa nghiệp vụ và DB; `fx-client` chứa giao diện desktop. Phòng và trận đang chơi lưu trong RAM, kết quả cuối lưu DB.

---

## 🚀 HƯỚNG DẪN KHỞI CHẠY (QUICK START)

Chi tiết đầy đủ xem tại: [HUONG_DAN_CHAY.md](HUONG_DAN_CHAY.md).

### 1. Bật Backend Server (Java Maven)
Mở Terminal tại thư mục gốc dự án:
```bash
mvn -B -DskipTests install
mvn -pl server spring-boot:run
```
*(Server chạy tại `ws://localhost:8080/ws/game`.)*

---

### 2. Bật Client JavaFX
Mở Terminal mới:
```bash
mvn -pl fx-client javafx:run
```
Mở thêm terminal và chạy lại lệnh để thử nhiều cửa sổ. Tài khoản `admin` mở màn **Quản trị** trong sảnh.

Máy mới cần JDK 21+, Maven 3.9+, Git và MySQL. Tạo database `scribble_game`, đặt thông tin đăng nhập MySQL rồi chạy backend.

---

## ⚙️ CẤU HÌNH DATABASE
Mặc định dự án dùng MySQL local `scribble_game` tại `localhost:3306`.

Hibernate `ddl-auto=update` tự tạo/cập nhật schema trong MySQL. Toàn bộ từ khóa đang dùng được lưu trong bảng `topic_entries` và được nối với tranh qua `paintings.topic_entry_id`. Trên máy mới mặc định **không tạo tài khoản demo**: đăng ký tài khoản qua client. Phòng có tối đa 8 người; chọn từ 10 giây, vẽ mặc định 60 giây, đoán 30 giây.

Xem [.env.example](.env.example) để biết các biến cấu hình. Script `start-local-server.ps1` và `run-game-with-3-clients.ps1` nạp `.env` local; khi chạy Maven thủ công, hãy đặt `DB_URL`, `DB_USER`, `DB_PASSWORD` trong PowerShell trước khi khởi động server. Client dùng ô địa chỉ server hoặc JVM `-Dscribble.server=host:port`.

Chạy kiểm thử: `mvn.cmd -B test` trong root. Có thể mở ba client bằng `mvn.cmd -pl fx-client javafx:run` ở ba terminal sau khi server sẵn sàng. Script `powershell -ExecutionPolicy Bypass -File .\scripts\run-game-with-3-clients.ps1` dùng khi **cổng 8080 chưa có server**; đuôi file là `.ps1`, không phải `.ps14`. Script này tự build và bật server. Nếu server đã chạy, chỉ mở client.

Chưa có Docker Compose; MySQL local phải được khởi động trước. Không có Maven Wrapper trong repo nên phải cài Maven. Bản demo đóng gói cho Windows nằm ở `demo/` và `scripts/build-demo.ps1`.

Lỗi thường gặp: port 8080 đã dùng thì tắt tiến trình cũ hoặc đặt `SERVER_PORT`; nếu không kết nối được, kiểm tra MySQL đang chạy và thông tin đăng nhập. Lỗi build thiếu common thì chạy `mvn.cmd -B -DskipTests install` từ root.

An toàn: tài khoản demo chỉ bật theo tùy chọn. Mật khẩu MySQL chỉ đặt trong `.env` local hoặc biến môi trường, không đưa vào Git. Nếu DB cũ có tài khoản demo, cần đổi mật khẩu trước khi mở ra mạng. Với triển khai ngoài LAN tin cậy, đặt `WS_ALLOWED_ORIGINS` cụ thể, dùng TLS và quản lý tài khoản admin riêng. Dữ liệu trận đang chạy không sống qua restart; migration hiện dựa vào Hibernate `update`, chưa có lịch sử migration đánh số.

Đóng góp: tạo nhánh riêng, chạy test, xem `git diff --check`, gửi PR; cập nhật `game_rules_nhom8.md` mỗi khi sửa luật. Các kết quả audit chi tiết ở [docs/audit/FULL_PROJECT_REPORT.md](docs/audit/FULL_PROJECT_REPORT.md) và [docs/database/DATABASE_AUDIT.md](docs/database/DATABASE_AUDIT.md).

## Phát triển độc lập trên máy cá nhân

Mỗi thành viên clone mã nguồn và chạy một Backend, một JavaFX Client, một database riêng **trên chính máy mình**. Không sao chép `*.mv.db`, tài khoản hoặc kết quả trận qua Git. Mặc định Backend chỉ chạy local trên máy mình tại `127.0.0.1:8080`; Client kết nối bằng `ws://localhost:8080/ws/game`. Để demo qua LAN, người chủ động đặt `SERVER_ADDRESS=0.0.0.0` và nhập IP máy chủ vào Client.

Trên Windows/VS Code, cài JDK 21 trở lên, Maven 3.9 trở lên, Git và MySQL. Không cần Node.js hoặc Docker. Trong PowerShell:

```powershell
java -version
mvn.cmd -version
git clone https://github.com/vuhuy2901/BTL-LTM.git
Set-Location BTL-LTM
powershell -ExecutionPolicy Bypass -File .\scripts\start-local-server.ps1
```

Script build ba module và kết nối MySQL local `scribble_game`. Hibernate tự tạo/cập nhật schema bằng `ddl-auto=update`; dữ liệu còn nguyên khi dừng/chạy lại Backend. Tạo `.env` từ `.env.example` và điền `DB_PASSWORD`, hoặc đặt biến môi trường trước khi chạy.

Mở terminal PowerShell thứ hai ở thư mục clone:

```powershell
mvn.cmd -pl fx-client javafx:run
```

Chọn địa chỉ mặc định `localhost:8080`, đăng ký tài khoản local, rồi đăng nhập. Nếu dùng cổng khác, sửa ô địa chỉ Server trong Client. Có thể kiểm tra Backend bằng `Test-NetConnection localhost -Port 8080`; WebSocket ở `/ws/game`. Mở 3 Client, tạo/cho vào cùng phòng, cả 3 sẵn sàng rồi bắt đầu game. Luật là 3–8 người, chọn từ 10 giây, vẽ mặc định 60 giây và đoán 30 giây.

MySQL là cấu hình mặc định: tạo `scribble_game`, đặt `$env:DB_URL`, `$env:DB_USER`, `$env:DB_PASSWORD`, rồi chạy `mvn.cmd -pl server spring-boot:run`.

Sau `git pull origin main`, đọc [quy trình migration](docs/database/LOCAL_MIGRATIONS.md), sao lưu DB của riêng mình và chạy `mvn.cmd -B verify`. Thay đổi source theo nhánh `git switch -c feature/ten-tinh-nang`, commit và push nhánh đó, tạo Pull Request; sau review/merge, thành viên khác pull `main`. Git chỉ đồng bộ source/config/test, không đồng bộ dữ liệu local. Không force-push.

Nếu cổng 8080 bị chiếm, chỉ để một Backend chạy hoặc đổi `SERVER_PORT` và ô địa chỉ Client. Nếu Maven báo thiếu `common`, chạy `mvn.cmd -B -DskipTests install` ở root. Nếu Client báo không kết nối được, bật Backend trước và kiểm tra `Test-NetConnection`. File `.env` được `.gitignore` loại khỏi Git.

## Ảnh đại diện

Trong **Trang cá nhân**, chọn **Tải ảnh từ máy…** (PNG/JPG, tối đa 10 MB).
Kéo ảnh trong khung tròn, dùng thanh trượt hoặc cuộn chuột để thu/phóng và xem trước.
Nhấn **Lưu ảnh đại diện** để lưu; **Hủy thay đổi** khôi phục ảnh trước đó.
Các biểu tượng có sẵn cũng chỉ được cập nhật sau khi nhấn Lưu. Ảnh được cắt thành
256 × 256 và lưu trong cơ sở dữ liệu, hiển thị ở sảnh, bạn bè, bảng xếp hạng và phòng chờ.

## Đóng gói và demo LAN

Tắt server đang chạy, rồi chạy `powershell -ExecutionPolicy Bypass -File .\scripts\build-demo.ps1`. Hai file ZIP được tạo trong `dist`: gói chạy Windows và gói mã nguồn. Hướng dẫn máy chủ, firewall và client nằm trong [demo/DEMO.md](demo/DEMO.md).
