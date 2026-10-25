# Quy ước cập nhật luật chơi

- `game_rules_nhom8.md` là tài liệu luật chơi chính của dự án.
- Mỗi khi người dùng yêu cầu sửa luật/rule, phải cập nhật `game_rules_nhom8.md` trong cùng lần sửa code. Đồng bộ các thông báo giao diện và tài liệu hướng dẫn liên quan.
- Quy mô phòng hiện tại: tối thiểu 3 người đang kết nối và tất cả thành viên sẵn sàng để bắt đầu; tối đa 8 người trong một phòng. Trận đang chơi tiếp tục khi còn ít nhất 2 thành viên hợp lệ.
- Mất kết nối được giữ chỗ 30 giây theo hạn server; hết hạn mới rời trận và áp dụng điểm phạt/thưởng một lần. Xem mục 3 trong `game_rules_nhom8.md`.
- Không tự thay đổi luật khi chưa có yêu cầu của người dùng.
- Điểm trận mới dùng luật phiên bản 4 theo thứ tự người đoán đúng và số người có quyền đoán khi tranh bắt đầu; điểm người vẽ theo tỷ lệ người đoán đúng trên số người đủ điều kiện đoán. Lượt đoán cố định 30000 ms, ngưỡng cơ hội đoán mặc định 10000 ms. Công thức/ví dụ và tương thích lịch sử ở mục 4 `game_rules_nhom8.md` là nguồn chính.
