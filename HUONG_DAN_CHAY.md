# HƯỚNG DẪN KHỞI CHẠY DỰ ÁN VẼ HÌNH ĐOÁN Ý (NHÓM 8)

Dự án được xây dựng theo kiến trúc Client-Server hiện đại trên nền tảng **Java 21** với mô hình Multi-Module Maven:
- **`common`**: Chứa DTO, giao thức thông điệp Envelope và MessageType dùng chung.
- **`server`**: Backend Spring Boot 3.3, Spring WebSocket, Spring Data JPA / Hibernate, MySQL local.
- **`fx-client`**: Desktop Frontend JavaFX 21 phong cách nét vẽ tay Sketch (Doodle nghệ thuật), tương tác thời gian thực qua WebSocket.

---

## 1. Yêu cầu môi trường (Prerequisites)

1. **Java Development Kit (JDK)**: Phiên bản **Java 21 trở lên**.
   - Kiểm tra bằng lệnh: `java -version`
2. **Apache Maven**: Phiên bản **3.8+**.
   - Kiểm tra bằng lệnh: `mvn -v`

> [!IMPORTANT]
> Cần cài và chạy MySQL local. Database mặc định là `scribble_game` trên `localhost:3306`.

---

## 2. Bước 1: Khởi chạy Backend Server (Spring Boot)

### Cách 1: Chạy bằng dòng lệnh (Khuyến nghị - Cắm là chạy ngay 100%)
Mở một **Terminal / PowerShell** tại thư mục gốc dự án (`BTL LTM`) và chạy:

```bash
mvn -B -DskipTests install
mvn -pl server spring-boot:run
```
Chạy `install` lại sau mỗi lần cập nhật mã nguồn ở module `common` để server và client dùng cùng bản giao thức, sau đó đóng các tiến trình cũ rồi mở lại. Hệ thống sẽ kết nối MySQL `scribble_game` và lắng nghe WebSocket tại cổng 8080. Tài khoản mặc định là `root`; đặt `DB_PASSWORD` qua biến môi trường trước khi chạy Maven. Với script khởi động, có thể ghi mật khẩu vào file `.env` local (không đẩy lên Git).

---

### Cách 2: Chạy trực tiếp trong IDE (IntelliJ IDEA / VS Code / Eclipse)
1. Mở file: `server/src/main/java/com/nhom8/server/Application.java`
2. Nhấp chuột phải vào file $\rightarrow$ Chọn **Run 'Application.main()'** (hoặc bấm vào biểu tượng tam giác xanh ▶️ bên cạnh hàm `public static void main`).
   *(Lưu ý: Không gõ đường dẫn file trực tiếp vào terminal PowerShell).*

---

### Cách 3: Chuẩn bị MySQL local
Nếu bạn dùng MySQL Server cục bộ (XAMPP / Docker / MySQL Workbench):
1. Tạo Database:
   ```sql
   CREATE DATABASE IF NOT EXISTS scribble_game CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
   ```
2. Đặt thông tin đăng nhập trong PowerShell:
   ```powershell
   $env:DB_USER='root'
   $env:DB_PASSWORD='mat_khau_mysql'
   ```
3. Chạy Server với cấu hình mặc định:
   ```bash
   mvn -pl server spring-boot:run
   ```

---

Khi Server khởi động thành công, màn hình sẽ hiển thị:
```
[SERVER] ================================================
[SERVER]   Vẽ Hình Đoán Ý - Spring Boot Server (Nhóm 8)
[SERVER]   Đang lắng nghe tại cổng: 8080
[SERVER]   WebSocket endpoint: ws://localhost:8080/ws/game
[SERVER]   Chờ kết nối từ Client JavaFX...
[SERVER] ================================================
```

---

## 3. Bước 2: Khởi chạy Client Desktop (JavaFX)

Mở một **cửa sổ Terminal / PowerShell mới** tại thư mục gốc và chạy:

```bash
mvn -pl fx-client javafx:run
```

Nếu cửa sổ cũ không nhận được bản tin mới sau khi cập nhật mã nguồn, đóng server và mọi client đang chạy, chạy `mvn -B -DskipTests install` tại thư mục gốc rồi mở lại. Tiến trình Java đang chạy không tự nạp JAR mới.

*Hoặc trong IDE:*
- Nhấp chuột phải vào file `fx-client/src/main/java/com/nhom8/client/Launcher.java` $\rightarrow$ Chọn **Run 'Launcher.main()'**.

Cửa sổ giao diện đăng nhập với phong cách nét vẽ tay Doodle (sắc vàng `#F5C800`, nút bấm hình cây bút chì, font Amatic SC & Patrick Hand) sẽ xuất hiện.

### 👤 Tài khoản thử nghiệm

Trên database mới không có tài khoản tạo sẵn. Chọn **Đăng ký tài khoản** để tạo ít nhất ba người chơi. Seed demo chỉ dùng trong môi trường phát triển/kiểm thử khi đặt `GAME_DEMO_SEED_ENABLED=true`; không bật trên máy chủ công khai. Database cũ có thể vẫn còn tài khoản demo đã tạo trước đây; hãy đổi mật khẩu các tài khoản ấy. Tự đăng ký tên `admin` không được cấp quyền quản trị.

---

## 4. Hướng dẫn Test nhiều người chơi (Multi-player)

Luật chơi hỗ trợ từ **3 đến 8 người chơi** trong cùng một phòng. Phòng chờ có thể có 1–2 người, nhưng phải đủ ít nhất 3 người và tất cả sẵn sàng mới bắt đầu; phòng đủ 8 người sẽ không nhận thêm thành viên. Luật chi tiết được cập nhật tại [game_rules_nhom8.md](game_rules_nhom8.md).

Khi mời bạn bè vào phòng, mỗi lần gửi thành công phải đợi 15 giây mới mời lại cùng người đó. Nút mời hiển thị thời gian chờ; người đã vào phòng sẽ không còn được mời.

Các trận mới dùng điểm theo thứ tự đoán đúng (phiên bản 4), chia theo số người có quyền đoán khi tranh bắt đầu: phòng 3 người là 100/50 điểm, phòng 4 người là 100/67/33 điểm. Điểm người vẽ bằng tỷ lệ người đoán đúng trên số người đủ điều kiện đoán, tối đa 100 điểm khi tất cả đúng. Lượt đoán vẫn luôn 30 giây; người không đúng cần có tổng thời gian kết nối ít nhất 10 giây trong lượt mới tính vào mẫu số. Xem mục 4 trong file luật để biết bảng điểm phòng 3–8 người và ngoại lệ. Lịch sử cũ và điểm tích lũy được giữ nguyên.

Nếu mất mạng, client tự kết nối lại và hiển thị thời gian giữ chỗ ước tính. Server giữ vị trí trong **30 giây từ lúc xác nhận mất kết nối**; trận vẫn chạy trong thời gian giữ chỗ. Trở lại đúng hạn sẽ khôi phục pha hiện tại và bản nháp riêng. Hết hạn sẽ bị loại khỏi phòng; nếu trận chỉ còn 2 thành viên hợp lệ, trận dừng và phòng giải tán, mọi người còn lại trở về sảnh. Nếu phòng vẫn còn đủ ít nhất 3 người thì trận tiếp tục. Chỉ được tự vào lại khi phòng còn tồn tại, ở WAITING và còn chỗ. Xem mục 3 trong file luật để biết quy tắc tranh bị bỏ qua và điểm phạt.

Cấu hình server trong `server/src/main/resources/application.properties`: `game.reconnect-grace-ms=30000`, `game.heartbeat-interval-ms=5000`, `game.heartbeat-timeout-ms=12000`. Client có thể cấu hình JVM bằng `game.heartbeat.interval.ms` và `game.heartbeat.timeout.ms`; thời hạn giữ chỗ luôn do server quyết định. Reconnect/bản nháp đang được giữ trong bộ nhớ, không khôi phục trận qua lần khởi động lại server.

1. **Mở thêm màn hình người chơi:**
   - Mở thêm các cửa sổ Terminal mới và chạy lại lệnh:
     ```bash
     mvn -pl fx-client javafx:run
     ```
   - Mỗi cửa sổ hoạt động như một Client độc lập.

2. **Quy trình trải nghiệm:**
   - Đăng ký tài khoản (ví dụ: `player1`, `player2`, `player3`).
   - Người chơi 1 bấm **➕ Tạo phòng mới** (nhận mã phòng gồm 6 số, ví dụ `516979`).
   - Người chơi 2 và 3 bấm vào phòng trong sảnh chờ hoặc nhập mã phòng để vào.
   - Khi tất cả người chơi bấm **Sẵn sàng**, cả phòng thấy đếm ngược 3–2–1 kèm âm thanh, rồi trận đấu tự bắt đầu. Nếu một người hủy sẵn sàng hoặc rời phòng trong lúc đếm, đếm ngược sẽ hủy.

3. **Các giai đoạn trong trận đấu:**
   - **Chọn chủ đề (10s):** Người vẽ chọn 1 trong 3 từ khóa ngẫu nhiên.
   - **Vẽ tranh (60s):** Người vẽ vẽ trên canvas (bút vẽ, bảng màu, tẩy, hoàn tác, xóa bảng). Nét vẽ đồng bộ tức thì qua WebSocket tới mọi người đoán.
   - **Đoán từ & Tính điểm:** Người đoán nhập đáp án vào khung chat. Đoán đúng nhận điểm thưởng và chuyển lượt vẽ tiếp theo.

---

## 5. Chạy Bộ Kiểm Thử Tự Động Toàn Diện (Continuous Stress Test)

Để kiểm tra tự động toàn bộ 5 kịch bản (Xác thực tiếng Việt, Đa phòng đồng thời, Mô phỏng 3 Bot thi đấu, Chịu tải dồn dập, Chuyển quyền Host khi rời mạng):

```bash
mvn -pl fx-client exec:java "-Dexec.mainClass=com.nhom8.client.ContinuousStressTest" "-Dexec.classpathScope=test"
```
Kết quả đạt chuẩn: **5 / 5 Suites PASS (100%)**.

### Quản trị và công cụ vẽ

- Đăng nhập bằng tài khoản `admin`, chọn **Quản trị** tại sảnh. Các tab hiển thị số tài khoản, người trực tuyến, phòng đang mở, trận và tranh đã lưu; danh sách người chơi; phòng chơi; từ khóa tự thêm.
- Có thể thêm, sửa hoặc xóa từ khóa. Toàn bộ từ khóa đang dùng được lưu trong bảng `topic_entries`, được nối với tranh qua `paintings.topic_entry_id` và dùng từ trận tiếp theo. Có thể giải tán phòng theo mã phòng.
- Trước lần đầu chạy bản mới với MySQL `scribble_game` cũ, hãy dừng backend và sao lưu bằng `mysqldump`. Lần chạy đầu sẽ thêm bảng/cột và nhập từ khóa cũ; không cần xóa hai bảng từ khóa cũ.
- Ở **Trang cá nhân → Lịch sử**, chọn trận rồi bấm **Xem các vòng và tranh đã vẽ**. Chọn một lượt để xem từ khóa, tranh và chi tiết lượt đoán; bấm **Phát lại nét vẽ** để xem tranh được dựng lại. Tranh trước bản nâng cấp vẫn xem được nhưng không xác định được số vòng/lượt và chi tiết lượt đoán.
- Trong pha vẽ ở client JavaFX, nút **Tẩy** chuyển sang nét trắng với đầu tẩy lớn hơn. Bấm **Bút vẽ** để quay lại. Nút **Xóa bảng** vẫn xóa toàn bộ tranh.
- Kiểm thử server, gồm ba client chơi một trận và kiểm tra quyền admin: chạy `mvn test` tại thư mục gốc.

---

## 6. Trải nghiệm Hệ thống Bạn bè & Chat Phòng Cô Lập

### 👥 A. Nhắn tin riêng cho bạn bè (Private 1-1 Chat ở sảnh)
1. **Giao diện Cột Phải tại Sảnh Chờ (Lobby):**
   - **Tab 👥 Bạn bè:** Danh sách bạn bè kèm trạng thái Online 🟢 / Offline ⚫, nút **💬 Chat** mở cửa sổ nhắn tin riêng Doodle, nút **➕ Thêm bạn**, click chuột phải để Hủy kết bạn.
   - **Tab 🌐 Online:** Xem toàn bộ người chơi đang online trong sảnh, bấm **💬 Chat** ngay lập tức hoặc gửi lời mời kết bạn.
   - **Tab 🔔 Lời mời:** Xem danh sách lời mời kết bạn gửi đến, nút **✔ Đồng ý** hoặc **✖ Từ chối**.
2. **Cửa sổ Chat 1-1:**
   - Mở cửa sổ vẽ tay Doodle chuyên biệt, tự động nạp **lịch sử trò chuyện** từ cơ sở dữ liệu.
   - Gửi và nhận tin nhắn tức thì bằng WebSocket. Tự động bật lên khi bạn bè gửi tin nhắn đến.

### 🔒 B. Chat nội bộ phòng chơi (Room Chat Isolation)
1. **Cô lập hoàn toàn:**
   - Khung chat trong phòng (`RoomScreen` và `GameScreen`) mang nhãn `💬 CHAT NỘI BỘ PHÒNG #[mã_phòng]` kèm biểu tượng `🔒 Chỉ các thành viên trong phòng này mới nhận được tin nhắn`.
   - Mọi tin nhắn trong phòng chỉ được gửi (`broadcast`) tới những người chơi đang cùng ở trong phòng đó. Người ở ngoài sảnh chờ hoặc ở các phòng khác hoàn toàn không thể thấy nội dung này.
2. **Đoán từ trong trận:**
   - Trong lúc trận đấu diễn ra, khung chat kiêm luôn chức năng đoán từ. Người đoán đúng sẽ nhận điểm thưởng và hệ thống ghi nhận kết quả.

### 🧪 C. Lệnh kiểm thử tự động Chat & Bạn bè:
```bash
mvn -pl fx-client exec:java "-Dexec.mainClass=com.nhom8.client.SocialAndChatValidationTest" "-Dexec.classpathScope=test"
```
Kiểm tra tự động 3 hạng mục:
- `PASS [TEST 1]`: Gửi/nhận tin nhắn riêng 1-1 và tải lịch sử chat từ cơ sở dữ liệu.
- `PASS [TEST 2]`: Chat phòng chơi cách ly 100%, người ở sảnh không nhận được tin nhắn nội bộ phòng.
- `PASS [TEST 3]`: Đồng bộ danh sách bạn bè & xử lý lời mời kết bạn.
