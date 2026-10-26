# Bàn giao: mất kết nối và khôi phục phiên

## Luồng hoạt động

Server xác nhận mất kết nối qua đóng/lỗi WebSocket hoặc ping/pong, chuyển thành viên sang RECONNECTING và giữ vị trí 30 giây. Chủ phòng, điểm, bản nháp riêng và bài nộp được giữ; trận vẫn chạy. Không tính thành viên mất kết nối là sẵn sàng cho trận mới.

Client tự thử lại theo backoff, có nút thử thủ công và lớp phủ chặn thao tác. Đồng hồ giữ chỗ của chính client được ghi rõ là ước tính. Khi xác thực lại bằng mật khẩu và mã khôi phục, server kiểm tra hạn, thay socket cũ, vô hiệu tác vụ hết hạn cũ và gửi snapshot riêng. Client dựng màn hình theo pha hiện tại, không đặt lại đồng hồ.

Hết hạn: chuyển tư cách thành viên cũ sang LEFT, xóa khỏi phòng/trận, giải phóng vị trí và chuyển chủ phòng. Không khôi phục tư cách cũ; chỉ cho tự vào lại khi phòng WAITING, còn chỗ và đáp ứng mật khẩu nếu có. Socket cũ không được điều khiển phiên mới.

## Tranh, phòng và điểm

- Nét vẽ có mã tranh và phiên bản tăng dần; truyền các đoạn vector, không truyền ảnh toàn khung liên tục. Bản nháp chỉ trả về đúng tác giả. Client giữ bản cục bộ để bổ sung phần chưa đồng bộ sau reconnect.
- DRAFT: chưa nộp. SUBMITTED: server xác nhận đã nhận. SKIPPED: chưa nộp và còn mất kết nối khi hết giờ. PUBLISHED: đang/đã được chiếu.
- Tranh SKIPPED không vào hàng đợi đoán, không hiện tranh trắng và không chịu phạt riêng do không ai đoán đúng. Tranh đã nhận vẫn được chiếu dù tác giả mất kết nối. Nộp trễ hoặc sai mã tranh bị từ chối; gửi trùng bài đã nhận chỉ được xác nhận lại.
- Phòng tối đa 8 người, tối thiểu 3 người đang kết nối và tất cả sẵn sàng để bắt đầu. Trận đang chơi cần ít nhất 3 thành viên hợp lệ; vị trí trong thời hạn reconnect 30 giây vẫn được giữ. Nếu hết hạn mà chỉ còn 2 người, trận dừng và phòng giải tán.
- Rời trận hoặc hết hạn khi trận còn chạy: -20 cho người rời, +20 cho mỗi thành viên hợp lệ còn lại. Một guard cho mỗi người trong mỗi trận ngăn áp dụng trùng; cập nhật điểm của cả nhóm trong một truy vấn giao dịch nguyên tử.
- Không phạt mất kết nối tại phòng chờ, reconnect đúng hạn hoặc hết hạn sau khi trận đã kết thúc bình thường. Rời phòng chủ động cần xác nhận và không khởi tạo thời gian giữ chỗ khi server nhận yêu cầu.

## File backend và giao thức

- `common/src/main/java/com/nhom8/common/dto/ConnectionState.java`: trạng thái kết nối.
- `common/src/main/java/com/nhom8/common/message/MessageType.java`: sự kiện bản nháp, xác nhận nộp, snapshot, thay phiên.
- `common/src/main/java/com/nhom8/common/message/Envelope.java`: dữ liệu được chuyển thành JSON node ngay khi gán, thống nhất xử lý trực tiếp và qua mạng.
- `server/src/main/java/com/nhom8/server/ws/ClientSession.java`: trạng thái, hạn reconnect và heartbeat.
- `server/src/main/java/com/nhom8/server/ws/GameWebSocketHandler.java`: ping/pong, xử lý lỗi transport và chặn socket cũ.
- `server/src/main/java/com/nhom8/server/service/GameService.java`: xác thực recovery, chuyển phiên nguyên tử, deadline và xử lý hết hạn.
- `server/src/main/java/com/nhom8/server/game/Room.java`: vị trí dự trữ, điều kiện sẵn sàng và xóa thành viên idempotent.
- `server/src/main/java/com/nhom8/server/game/GameSession.java`: bản nháp riêng, mã/phiên bản tranh, hàng đợi bài hợp lệ, snapshot, timer và guard điểm rời trận.
- `server/src/main/java/com/nhom8/server/repository/UserRepository.java`: cập nhật điểm rời/thưởng nguyên tử.
- `server/src/main/resources/application.properties`: hạn 30000 ms, heartbeat mỗi 5000 ms, timeout 12000 ms.

## File frontend

- `fx-client/src/main/java/com/nhom8/client/ws/GameWebSocketClient.java`: kết nối có timeout, tránh kết nối/gửi trùng, chặn callback socket cũ, không phát lại hành động gameplay tồn đọng.
- `fx-client/src/main/java/com/nhom8/client/MainApplication.java`: tự reconnect, mã recovery, nhận snapshot, chuyển màn hình và xác nhận rời phòng.
- `fx-client/src/main/java/com/nhom8/client/ui/ReconnectOverlay.java`: lớp phủ, nút thử lại, trạng thái và đếm ngược ước tính.
- `fx-client/src/main/java/com/nhom8/client/ui/GameScreen.java`: mã tranh, xác nhận nộp, giữ/khôi phục bản nháp, đồng bộ pha và điểm.
- `fx-client/src/main/java/com/nhom8/client/ui/RoomScreen.java`: thành viên đang reconnect, làm mờ thẻ/avatar và cập nhật số giây còn lại.

## Kiểm thử và xác minh

- `SessionRecoveryTest`: 26 ca, gồm trước/sát/sau hạn, giải phóng chỗ, vào lại phòng, snapshot pha/timer/bản nháp, bỏ qua tranh, giữ bài đã nộp, phiên bản trễ, đoán đúng, chủ phòng, mất kết nối đồng thời, socket cũ, race với hết hạn, nộp/rời trùng, kết quả cuối trận, giả mạo recovery và đổi tài khoản trên socket.
- `AdminAndConcurrencyTest`: cập nhật cho ba người, reconnect bản nháp qua WebSocket thật và xác nhận chat phòng, chat riêng, bảng xếp hạng sau reconnect. Lượt kiểm tra hồi quy cuối đã qua cả hai ca.
- `GameIntegrationTest`: cập nhật mã tranh, vẫn chạy trận tự động tới kết quả cuối cùng.
- `LeaveScoringTest`: kiểm tra truy vấn phạt/thưởng trên H2 thực.
- `RoomPlayerLimitsTest`: xác nhận yêu cầu sẵn sàng theo kết nối.
- `RecoveryUiCheck`: chạy JavaFX, nạp snapshot qua MainApplication, khôi phục canvas, kiểm tra nút thử lại và gỡ lớp phủ; ảnh QA tại `fx-client/target/reconnect-preview.png`.

Toàn bộ 32 kiểm thử server đã qua ở lượt kiểm tra đầy đủ (`mvn test`); sau các sửa cuối, 26 ca SessionRecoveryTest đã chạy lại và qua. Kiểm tra JavaFX và build/install đều thành công. `git diff --check` không có lỗi whitespace.

## Dữ liệu và giới hạn

Không thay đổi entity hoặc schema cơ sở dữ liệu cho tính năng reconnect. Các bản ghi tài khoản/tranh/kết quả tiếp tục dùng cấu trúc hiện có.

Phiên reconnect, token và bản nháp nằm trong bộ nhớ server đơn lẻ; không khôi phục trận qua lần khởi động lại tiến trình và không triển khai spectator. Khi server không thể truy cập, client tiếp tục thử kết nối để nhận quyết định chính thức; mốc 30 giây vẫn được server xử lý độc lập.

Luật chính đã được cập nhật trong `game_rules_nhom8.md`, đồng bộ với `AGENTS.md`, `AGENT_GUIDE_nhom8.md`, `tech.md` và `HUONG_DAN_CHAY.md`.
