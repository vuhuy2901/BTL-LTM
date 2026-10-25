# TECH_STACK_nhom8.md — Công nghệ & quy ước kỹ thuật (Game "Vẽ Hình Đoán Ý")

> Tài liệu dành cho AI agent. Phải tuân thủ đúng phiên bản, cấu trúc và quy tắc dưới đây. Không tự ý đổi công nghệ, không sửa luật chơi.

## 1. Tổng quan
- Mô hình: **Client – Server**, giao tiếp real-time hai chiều bằng **WebSocket** (Request-Response + Push/Broadcast).
- Server: **Spring Boot** (WebSocket + JPA/Hibernate + MySQL).
- Client: **JavaFX** desktop (Canvas vẽ tranh).
- Kiến trúc: **MVC** (Client: FXML = View, `*Controller` = Controller, DTO = Model).
- Ghi chú báo cáo (phải nêu rõ là nâng cấp so với giáo trình): Spring Boot, Spring Data JPA/Hibernate, WebSocket (giáo trình chương 9 dùng JSR 356 `@ServerEndpoint`; dự án dùng `WebSocketHandler` của Spring), JavaFX thay Swing, JSON thay `ObjectStream`.

## 2. Phiên bản bắt buộc
| Hạng mục | Công nghệ | Phiên bản |
|---|---|---|
| JDK | Temurin/OpenJDK | **21 (LTS)** |
| Build | Maven | 3.9+ (multi-module) |
| Server | Spring Boot | **3.3.x** |
| WebSocket server | `spring-boot-starter-websocket` | theo Boot |
| ORM | `spring-boot-starter-data-jpa` (Hibernate 6) | theo Boot |
| CSDL | MySQL | **8.x** |
| JDBC driver | `com.mysql:mysql-connector-j`, class `com.mysql.cj.jdbc.Driver` | theo Boot |
| JSON | Jackson (`jackson-databind`) | theo Boot (client dùng cùng bản) |
| Băm mật khẩu | `spring-security-crypto` (**BCryptPasswordEncoder**) | theo Boot |
| UI client | JavaFX (`javafx-controls`, `javafx-fxml`) | **21.0.x** |
| WebSocket client | `java.net.http.WebSocket` (có sẵn JDK) | — |
| Tiện ích | Lombok (tùy chọn), SLF4J/Logback (có sẵn) | — |
| Test | JUnit 5, Spring Boot Test | theo Boot |
| Email khôi phục mật khẩu (tùy chọn) | `spring-boot-starter-mail` | theo Boot |

## 3. Cấu trúc project (Maven multi-module)
```
nhom8-ve-hinh-doan-y/
├── pom.xml                (parent)
├── common/                (DTO + enum + message dùng chung, KHÔNG có JPA/Spring)
│   └── com.nhom8.common
│       ├── message/       Envelope, MessageType, payload records
│       └── dto/           PlayerDTO, RoomDTO, StrokeDTO, ScoreDTO...
├── server/                (Spring Boot)
│   └── com.nhom8.server
│       ├── config/        WsConfig, JpaConfig
│       ├── ws/            GameWebSocketHandler, SessionRegistry, MessageRouter
│       ├── module/        auth, player, social, chat, room, game, drawing, guessing, scoring, ranking
│       ├── entity/        JPA entity (User, Friendship, ChatMessage, Topic, Match, MatchPlayer, Drawing, Guess)
│       ├── repository/    JpaRepository
│       └── service/
└── client/                (JavaFX)
    └── com.nhom8.client
        ├── MainApp.java   (extends Application)
        ├── Launcher.java  (main() riêng, KHÔNG extends Application)
        ├── net/           ServerConnection (WebSocket), MessageDispatcher
        ├── controller/    LoginController, LobbyController, RoomController, TopicController, DrawController, GuessController, ResultController, RankingController
        ├── model/         ClientState (player, room, match hiện tại)
        └── resources/view/*.fxml, css/
```
Quy ước: Entity (JPA) ≠ DTO. Chỉ gửi DTO qua mạng, không gửi entity.

## 4. Cấu hình server (`application.properties`)
```properties
server.port=8080
spring.datasource.url=jdbc:mysql://localhost:3306/ve_hinh_doan_y?useSSL=false&serverTimezone=Asia/Ho_Chi_Minh&characterEncoding=UTF-8
spring.datasource.username=root
spring.datasource.password=CHANGE_ME
spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver
spring.jpa.hibernate.ddl-auto=update
spring.jpa.open-in-view=false
spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect
spring.jpa.show-sql=false
```
- Endpoint WebSocket: `ws://<host>:8080/ws/game`.
- CSDL dùng `utf8mb4` (có tiếng Việt, thành ngữ, tục ngữ).

## 5. Giao thức thông điệp (JSON)
Mọi tin nhắn dùng một phong bì:
```json
{ "type": "TOPIC_SELECT", "requestId": "uuid-tùy-chọn", "payload": { "topicId": 3 } }
```
Danh sách `type` tối thiểu:
- **Auth:** `REGISTER`, `LOGIN`, `LOGIN_RESULT`, `LOGOUT`, `RESET_PASSWORD`
- **Player:** `ONLINE_LIST` (push), `STATUS_UPDATE` (rỗi / trong phòng / đang đấu)
- **Social:** `FRIEND_REQUEST`, `FRIEND_RESPONSE`, `FRIEND_LIST`, `SEARCH_PLAYER`, `PROFILE`, `MATCH_HISTORY`
- **Chat:** `CHAT_PRIVATE`, `CHAT_ROOM`, `CHAT_HISTORY`
- **Room:** `ROOM_CREATE`, `ROOM_LIST`, `ROOM_JOIN`, `ROOM_LEAVE`, `ROOM_UPDATE` (broadcast), `READY`, `HOST_CHANGED`, `ROOM_DISBANDED`
- **Game:** `MATCH_START`, `TOPIC_OPTIONS` (S→C, `TopicOptionsMessage`), `TOPIC_SELECT` (C→S, `TopicSelectMessage`), `DRAW_PHASE_START`, `DRAW_SUBMIT`, `GUESS_PHASE_START`, `GUESS_SUBMIT`, `GUESS_RESULT`, `GUESS_PHASE_END`, `MATCH_SUMMARY`
- **Drawing:** `STROKE_BATCH` (client gửi liên tục lên server khi vẽ)
- **Ranking:** `LEADERBOARD`
- **Lỗi:** `ERROR` (`code`, `message`)

Quy ước: tên enum `MessageType` ở `common`, server và client dùng chung. Mỗi `type` có một record payload riêng.

## 6. Dữ liệu hình vẽ
- Canvas JavaFX (`Canvas` + `GraphicsContext`). Công cụ: bút, tẩy, chọn màu, kích thước nét, xóa tất cả.
- Mô hình nét: `StrokeDTO { tool, color, width, List<Point> points }`.
- Client gửi `STROKE_BATCH` theo lô nhỏ (gộp nhiều điểm) trong lúc vẽ để **server luôn giữ bản vẽ mới nhất**, phục vụ luật "hết 60s tự động nộp canvas hiện tại".
- Khi nộp (`DRAW_SUBMIT` hoặc hết giờ) server chốt danh sách nét làm bản chính thức, lưu CSDL dạng JSON/LONGTEXT (hoặc PNG Base64 nếu cần xem lại).
- Server **không** nhận diện chữ viết tay (luật fair-play).

## 7. Luật chơi cố định (KHÔNG được sửa)
- Phòng: **3–8 người**. Bắt đầu khi có ít nhất 3 người và **100% thành viên bấm Sẵn sàng**. Phòng chờ có thể có 1–2 người; phòng đủ 8 người không nhận thêm thành viên.
- Chọn từ khóa: server chọn trước **3 × số người chơi** từ không trùng; mỗi người nhận **3 lựa chọn riêng**, **10 giây** chọn, quá giờ server tự chọn. Một kho từ chung (đồ vật thường ngày, thành ngữ/tục ngữ tiếng Việt).
- Vẽ: **60 giây**, đồng thời tất cả người chơi. Không viết chữ/số lên tranh. Hết giờ: chỉ tự nộp bản nháp của người đang kết nối; bỏ qua tranh chưa nộp của người đã mất kết nối. Tranh đã được xác nhận nộp vẫn hợp lệ.
- Đoán: lần lượt từng bức tranh, **30 giây/bức**, gợi ý dạng gạch dưới (`___ ____` cho "con mèo"). Được gửi đáp án **nhiều lần**. Tác giả **không** được đoán tranh của mình. Chỉ có đúng hoặc sai, **không có mức "gần đúng"**.
- Điểm đoán trận mới: `round(100 × (n − r + 1) / n)` với `n` là số người có quyền đoán khi tranh bắt đầu, `r` là thứ tự đúng; người đầu luôn 100. Chỉ nhận đáp án trong 30000 ms. Sai hoặc trễ hạn nhận 0.
- Điểm vẽ trận mới: `round(100 × k / m)`, với `k` là số người đúng và `m` là số người đủ điều kiện đoán; `m=0` thì 0, `m>0,k=0` thì −20. Người không đúng cần có ít nhất 10000 ms kết nối cộng dồn mới được tính vào `m`. Xem mục 4 `game_rules_nhom8.md`.
- Rời phòng: giữ chỗ mất kết nối **30 giây**; chỉ xử lý rời khi hết hạn hoặc người chơi xác nhận rời. Trận đang chơi còn **< 2 người** thì giải tán và hủy kết quả thông thường, giữ điểm phạt/thưởng rời trận. Chủ phòng chỉ chuyển quyền sau khi thực sự rời phòng.
- Kết thúc: bảng tổng kết (tranh + chủ nhân + ai đoán đúng/sai/timeout), cập nhật điểm tích lũy vào CSDL, bảng xếp hạng toàn hệ thống.

## 8. Quy tắc lập trình bắt buộc
**Client (JavaFX)**
1. Mọi cập nhật giao diện từ luồng mạng phải qua `Platform.runLater(...)`.
2. Lớp `Launcher` (có `main`) tách khỏi `MainApp extends Application`.
3. Đồng hồ đếm ngược trên UI chỉ để hiển thị (`Timeline`); **server là nguồn thời gian chính thức**, client dùng mốc thời gian/thời lượng server gửi.
4. Nhận WebSocket bằng `WebSocket.Listener`; phải xử lý tin nhắn bị chia mảnh (`last == false`) bằng `StringBuilder` rồi mới parse JSON; gọi `ws.request(1)` sau mỗi lần nhận.
5. Không đặt logic nghiệp vụ trong FXML hoặc View.

**Server (Spring Boot)**
1. Dùng `TextWebSocketHandler` (`afterConnectionEstablished`, `handleTextMessage`, `afterConnectionClosed`) tương ứng `@OnOpen/@OnMessage/@OnClose` trong giáo trình.
2. `WebSocketSession` không thread-safe khi gửi: bọc bằng `ConcurrentWebSocketSessionDecorator` hoặc `synchronized (session)`.
3. Lưu trạng thái trực tuyến bằng `ConcurrentHashMap` (session, phòng, trận). Mỗi phòng/trận có cơ chế khóa riêng.
4. Bộ đếm thời gian dùng `ScheduledExecutorService`/`TaskScheduler`; khi hết giờ xử lý tự động (tự chọn từ, tự nộp tranh, đóng lượt đoán).
5. Mất kết nối (`afterConnectionClosed`, lỗi transport hoặc heartbeat quá hạn) chuyển sang RECONNECTING trong 30 giây; không xử lý như rời phòng ngay. Khôi phục phải xác thực và nhận snapshot từ Server. Quy tắc chi tiết xem mục 3 trong `game_rules_nhom8.md`.
6. Mật khẩu băm **BCrypt**, không lưu rõ. Truy vấn qua `JpaRepository` (không nối chuỗi SQL, tránh SQL injection như ví dụ mẫu của giáo trình).
7. Kiểm tra hợp lệ ở server: đúng pha, đúng người, đúng thời gian trước khi nhận `DRAW_SUBMIT`/`GUESS_SUBMIT`.
8. Phân tầng: `ws` (nhận/gửi) → `service` (nghiệp vụ) → `repository` (dữ liệu). Mô hình ba tầng như chương 10.

## 9. Bảng CSDL gợi ý (MySQL)
`users`, `friendships`, `chat_messages`, `topics`, `matches`, `match_players`, `drawings`, `guesses`.
- `users`: id, username (unique), password_hash, email (nullable), total_score, created_at.
- `topics`: id, content, category, word_count, char_count.
- `matches`: id, room_name, started_at, ended_at, status (VALID/CANCELLED).
- `match_players`: match_id, user_id, score_gained.
- `drawings`: id, match_id, author_id, topic_id, stroke_data (LONGTEXT/JSON), submitted_at.
- `guesses`: id, drawing_id, guesser_id, answer, is_correct, rank_no, score, sent_at.
- `friendships`: id, user_id, friend_id, status (PENDING/ACCEPTED).
- `chat_messages`: id, sender_id, receiver_id, content, sent_at (chỉ chat riêng được lưu lịch sử).

## 10. Lệnh chạy
```bash
# Build toàn bộ
mvn clean install

# Chạy server
mvn -pl server spring-boot:run

# Chạy client
mvn -pl client javafx:run
```

## 11. Bốn luồng công việc (workstream) cố định
1. **Server core** (WebSocket handler, session registry, router)
2. **Client core** (ServerConnection, dispatcher, các màn hình)
3. **Message protocol** (module `common`, envelope + payload)
4. **Room/State management** (phòng, trận, bộ đếm, tính điểm)

## 12. Cấm
- Không đổi sang Swing, TCP thuần, hoặc bỏ JPA/Hibernate nếu chưa có yêu cầu của nhóm.
- Không đổi công thức điểm, thời gian (10s/60s/30s), quy mô phòng, hay thêm mức "gần đúng".
- Không cập nhật UI ngoài luồng JavaFX; không gửi entity JPA qua mạng.
