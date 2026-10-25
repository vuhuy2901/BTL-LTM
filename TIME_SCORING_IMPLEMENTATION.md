# Bàn giao: hệ thống tính điểm theo thời gian, phiên bản 2

Tài liệu này ghi lại triển khai phiên bản 2 để đối chiếu lịch sử. Trận mới hiện dùng luật phiên bản 4 theo thứ tự đoán đúng và tỷ lệ người đoán đúng; xem mục 4 `game_rules_nhom8.md`.

## Kết quả

Đã thay công thức điểm theo thứ hạng bằng điểm theo thời gian do Server kiểm soát, tích hợp vào trận đấu, reconnect, bảng điểm trực tiếp, kết quả cuối trận và lịch sử. Server đã được build lại và khởi động với bản mới tại `ws://localhost:8080/ws/game`.

Luật chính thức và toàn bộ ví dụ nằm ở **mục 4 của `game_rules_nhom8.md`**. `AGENTS.md` tiếp tục yêu cầu cập nhật file luật mỗi khi sửa rule.

Khác biệt với mô tả đầu vào: dự án hiện cho phép **3–8 người**, không phải 3–6. Giữ nguyên giới hạn đã được người dùng yêu cầu; chọn từ 10 giây, vẽ theo cài đặt hiện có (mặc định 60 giây). Lượt đoán cố định 30.000 ms, không phụ thuộc thời gian vẽ. Trận đang chơi có thể tiếp tục với ít nhất 2 thành viên theo luật reconnect hiện có.

## Công thức và tính nhất quán

- Điểm đoán: `round(20 + 80 × (30000 − t) / 30000)`, chỉ khi `0 ≤ t < 30000`. `t` dùng mili giây từ đồng hồ đơn điệu của Server. Sai: 0, không trừ điểm. Người đầu tiên đúng ở giây 20 nhận 47, người tiếp theo ở giây 21 nhận 44; thứ tự không quyết định điểm.
- Điểm vẽ: `round(70 × k/m + 30 × S/(100 × m))`. `m = 0` nhận 0; `m > 0, k = 0` nhận −20. Ví dụ `m = 4`, điểm người đúng `100, 60, 20` cho người vẽ 66.
- Người đủ điều kiện: đoán đúng hoặc kết nối hợp lệ cộng dồn ít nhất 10.000 ms trong lượt đó. Loại tác giả, người đã rời trước lượt và người chưa có cơ hội đủ dài. Ngưỡng cấu hình: `game.scoring.minimum-exposure-ms=10000`.
- Server chống cộng điểm đoán trùng theo người/mã tranh, kiểm tra mã tranh hiện hành và dùng khóa đồng bộ khi xử lý. Điểm vẽ có bộ chốt một lần theo mã tranh trong trận. Không tin thời gian Client gửi.
- Thứ tự đoán chỉ còn là thông tin hiển thị. Lượt kết thúc sớm vẫn tính theo thời lượng gốc 30.000 ms.
- Thưởng/phạt rời trận tách riêng: −20 người rời, +20 mỗi thành viên hợp lệ còn lại, áp dụng đúng một lần. Cuối trận chỉ cộng thêm điểm chơi chưa cập nhật; không cộng lại khoản thưởng/phạt đã ghi.

## Backend và giao thức

| File | Thay đổi |
|---|---|
| `server/src/main/java/com/nhom8/server/service/ScoringService.java` | Công thức điểm thuần, hằng số thời lượng và phiên bản luật; thay công thức theo rank. |
| `server/src/main/java/com/nhom8/server/game/GuessingWindow.java` | Cửa sổ đoán, thời gian online cộng dồn, điểm đúng đầu tiên, tập người đủ điều kiện và điều kiện kết thúc sớm. |
| `server/src/main/java/com/nhom8/server/game/GameSession.java` | Tích hợp chấm điểm trực tiếp, chốt điểm vẽ một lần, mã tranh, điểm âm, tổng điểm, thưởng/phạt và snapshot khôi phục. |
| `server/src/main/java/com/nhom8/server/service/GameService.java` | Chuyển sự kiện kết nối vào bộ theo dõi cơ hội đoán, kiểm tra yêu cầu theo mã tranh và trả lịch sử có phiên bản/thưởng phạt. |
| `server/src/main/java/com/nhom8/server/model/Match.java` | Phiên bản tính điểm; trận mới dùng phiên bản 2. |
| `server/src/main/java/com/nhom8/server/model/MatchResult.java` | Lưu thưởng/phạt riêng, tổng điểm gồm điểm chơi và điều chỉnh. |
| `server/src/main/resources/application.properties` | Ngưỡng thời gian đủ điều kiện đoán mặc định 10.000 ms. |

Các sự kiện bắt đầu/kết quả và snapshot được bổ sung dữ liệu liên quan như `drawingId`, `scoringVersion`, `elapsedMs`, số người đủ điều kiện, hình phạt và điểm điều chỉnh. Client hiển thị điểm Server gửi, không tự quyết định điểm.

## Frontend

| File | Thay đổi |
|---|---|
| `fx-client/src/main/java/com/nhom8/client/ui/GameScreen.java` | Gửi mã tranh khi đoán; hiện điểm đúng và thời gian từ Server; kết quả lượt có số đúng/đủ điều kiện, điểm từng người và điểm vẽ/phạt. Tổng kết tách điểm vẽ, đoán và thưởng/phạt, hiển thị đúng số âm. |
| `fx-client/src/main/java/com/nhom8/client/ui/ProfileDialog.java` | Đọc và hiển thị điểm điều chỉnh trong lịch sử; dữ liệu cũ vẫn đọc được. |

Giữ phong cách JavaFX hiện có, không thiết kế lại màn hình không liên quan. Tranh SKIPPED có trạng thái riêng, không dựng điểm/phạt giả.

## Disconnect, reconnect và vòng đời tranh

- Giữ nguyên hạn khôi phục 30 giây; quay lại không đặt lại đồng hồ đoán hoặc điểm đã nhận. Socket cũ/bị thay thế vẫn bị chặn tại lớp kiểm tra phiên hiện có.
- Thời gian online được cộng dồn qua các lần reconnect; người đã đúng vẫn được tính vào thành công của tranh dù sau đó mất mạng hoặc rời hẳn.
- Tranh đã nộp hợp lệ vẫn tồn tại sau khi tác giả mất mạng. Bản nháp của người mất mạng lúc hết hạn vẽ được bỏ qua, không vào hàng đợi và không nhận thêm phạt −20 vì không ai đoán đúng.
- Không nhận đáp án sau hạn, không mở lại bản nháp hết hạn và không chấm lại khi nhận thông điệp trùng.

## Database và bảo toàn lịch sử

Dùng cơ chế migration hiện có của dự án: Hibernate `ddl-auto=update`, không thêm thư viện migration mới.

```sql
alter table match_results add column points_adjustment integer default 0 not null;
alter table matches add column scoring_version integer default 1 not null;
```

Đây là hai cột mới của tính năng. Bản ghi cũ giữ nguyên điểm và có phiên bản 1/điều chỉnh 0; trận mới tạo phiên bản 2. Không xóa lịch sử, không tính lại điểm lịch sử và không reset bảng xếp hạng. Log khởi động xác nhận đã thêm hai cột thành công trên database đang dùng.

Trước khi cập nhật, đã dừng tiến trình Server cũ và sao lưu database đã đóng tại `server/target/game_db.before-time-scoring-v2.mv.db`. Đây là bản sao trước migration; không chép đè trở lại khi Server đang chạy. Thư mục `target` có thể bị Maven clean xóa, nên chuyển bản sao này sang nơi lưu trữ lâu dài nếu cần giữ qua các lần clean.

## Kiểm thử và build

Toàn bộ bộ kiểm thử Maven: **62 test, 0 lỗi, 0 thất bại, 0 bỏ qua**.

| Bộ kiểm thử | Số test | Phạm vi |
|---|---:|---|
| `ScoringServiceTest` | 15 | Các mốc điểm, mili giây/làm tròn, hạn cuối, công thức vẽ, cơ hội cộng dồn, tác giả và người offline. |
| `TimeScoringGameTest` | 11 | Giây 20/21, bằng điểm, sai/trùng, hạn cuối, reconnect, rời trận, chốt điểm một lần, gửi đồng thời, ví dụ 5 người và tác giả mất mạng sau nộp. |
| `ScoringPersistenceTest` | 2 | Dữ liệu lịch sử trước schema mới, điểm âm, điều chỉnh và phiên bản mới. |
| `SessionRecoveryTest` | 26 | Khôi phục phiên, deadline, tranh bỏ qua/đã nộp, socket cũ, snapshot và các trường hợp vòng đời phòng. |
| Các bộ kiểm thử hiện có còn lại | 8 | Trận đấu qua WebSocket, admin/concurrency, avatar, giới hạn phòng và điểm rời trận. |

Đã chạy `TimeScoringUiCheck` bằng JavaFX thật: kiểm tra phản hồi +47, kết quả 3/4 người đúng với điểm vẽ 66 và phân tách tổng điểm có số âm/thưởng phạt. Ảnh kiểm tra: `fx-client/target/time-scoring-preview.png`.

Các lớp client kiểm tra thủ công có `main` không được tính là JUnit test; Maven báo 0 test cho các lớp đó. Kiểm tra JavaFX bên trên được chạy riêng, không tính vào 62 test.

- `mvn.cmd -B test` (kèm giảm log Spring/Hibernate): BUILD SUCCESS; log `target/time-scoring-validation.log`.
- `mvn.cmd -B -DskipTests install`: BUILD SUCCESS cho mọi module; log `target/time-scoring-install.log`.
- `git diff --check`: không có lỗi whitespace; có cảnh báo chuẩn hóa LF/CRLF của Git trên Windows.
- Dự án không cấu hình bước lint riêng; đã biên dịch, chạy kiểm thử và kiểm tra diff.
- Đã khởi động JAR mới, xác nhận cổng 8080 lắng nghe và bắt tay WebSocket trả HTTP 101. Log: `server/target/server-time-scoring.log` và `server/target/server-time-scoring-error.log`.

## Tài liệu đồng bộ và giới hạn

Đã cập nhật `game_rules_nhom8.md`, `AGENTS.md`, `AGENT_GUIDE_nhom8.md`, `tech.md` và `HUONG_DAN_CHAY.md`; loại bỏ công thức tính điểm theo rank khỏi hướng dẫn đang áp dụng.

Trạng thái lượt đang chạy, mã chống chấm trùng và phiên reconnect vẫn ở bộ nhớ Server đơn lẻ theo kiến trúc hiện có. Không hỗ trợ khôi phục trận sau khi tiến trình Server khởi động lại hoặc chia sẻ trận qua nhiều Server. Kết quả trận hoàn tất được lưu database; không bổ sung kho sự kiện từng đáp án để phát lại toàn bộ trận. Không có lỗi còn chặn build hay bộ kiểm thử đã chạy.
