# Đánh giá chuẩn hóa 1NF/2NF/3NF

Tiêu chí 3NF: với mỗi phụ thuộc hàm không tầm thường `X → A`, `X` phải là siêu khóa hoặc `A` thuộc một khóa ứng viên. Chỉ các phụ thuộc được chứng minh bằng khóa/ràng buộc hoặc quy tắc nghiệp vụ rõ ràng mới được coi là VERIFIED. Các quan sát metadata H2 cũ chỉ là tư liệu lịch sử; schema chạy hiện tại là MySQL local.

| Bảng | Primary key | Candidate keys | Functional dependencies | 1NF | 2NF | 3NF | Issues | Recommended action |
|---|---|---|---|---|---|---|---|---|
| `users` | user_id | user_id, username, display_name | mỗi khóa → các thuộc tính tài khoản | VERIFIED | VERIFIED | VERIFIED theo phụ thuộc nội bảng đã quan sát | `ranking_score` là tổng tích lũy có thể khác tổng lịch sử vì thưởng/phạt/hủy trận; đây là rủi ro nhất quán, không tự nó chứng minh vi phạm 3NF | giữ giá trị vận hành, đối soát và ghi rõ quy tắc cập nhật; không tính lại dữ liệu cũ |
| `friendships` | friendship_id | friendship_id, cặp sender/receiver có hướng | cặp có hướng → status, created_at | VERIFIED | VERIFIED | VERIFIED theo phụ thuộc đã xác nhận | hai hàng A→B và B→A vẫn lọt; self-link chưa có CHECK | đề xuất chuẩn hóa cặp vô hướng sau rà soát dữ liệu |
| `chat_messages` | message_id | message_id | message_id → sender, receiver, content, is_read, created_at | VERIFIED | VERIFIED | VERIFIED theo phụ thuộc đã xác nhận | thiếu chỉ mục hỗ trợ hội thoại/thời gian; `is_read` là trạng thái mỗi message | cân nhắc index, giữ nội dung riêng từng message |
| `matches` | match_id | match_id | match_id → room_id, times, scoring_version | VERIFIED | VERIFIED | VERIFIED theo mô hình hiện tại | room_id không FK do phòng chỉ ở RAM | ghi rõ vòng đời phòng; không tự tạo FK lịch sử |
| `match_results` | result_id | chỉ result_id được DB cưỡng chế; `(match_id,user_id)` là khóa nghiệp vụ dự kiến | result_id → match_id, user_id, points; dự kiến cặp → points | VERIFIED | VERIFIED theo khóa hiện có | UNVERIFIED cho khóa nghiệp vụ | thiếu UNIQUE cặp, cho phép nhiều kết quả cùng người/trận; điểm tích lũy là aggregate | rà trùng rồi mới thêm UNIQUE; giữ lịch sử |
| `paintings` | painting_id | painting_id | painting_id → match, painter, topic, strokes | VERIFIED | VERIFIED | VERIFIED theo phụ thuộc đã xác nhận | painter không được DB kiểm tra có trong match_results; payload nét vẽ là một giá trị | xác minh nghiệp vụ trước khi thêm ràng buộc |
| `topic_entries` | id | id | id → word, active | VERIFIED | VERIFIED | VERIFIED theo khóa chính | từ khóa do admin quản lý; từ đã xóa được giữ inactive để bảo toàn liên kết lịch sử | giữ nguyên, không xóa vật lý từ đã gắn với tranh |

Các bảng có PK đơn nên không có phụ thuộc bộ phận vào một *khóa ghép đã được cưỡng chế*. Điều này không tự động chứng minh 3NF: cặp `(match_id,user_id)` là khóa nghiệp vụ chưa cưỡng chế, và tổng điểm người dùng là dữ liệu tổng hợp có thể mất đồng bộ. `avatar` dạng mã hoặc data URI và `stroke_data` dạng chuỗi nét vẽ là payload nguyên tử theo cách truy cập hiện tại; không kết luận vi phạm 1NF chỉ vì nội dung có cấu trúc. Không tồn tại các bảng riêng cho room, guess attempts, reconnect session hay score events.

**Kết luận toàn database: UNVERIFIED — chưa đủ bằng chứng về tất cả phụ thuộc nghiệp vụ và schema live/MySQL.** Chưa chứng minh được một vi phạm 3NF hình thức; thiếu unique và điểm cache là vấn đề ràng buộc/nhất quán riêng. Các bảng topic, message, match và painting phù hợp 3NF với tập phụ thuộc hiện quan sát. Cần đối soát dữ liệu và quy tắc điểm trước khi nâng các nhãn UNVERIFIED lên VERIFIED.

Môi trường chạy local dùng MySQL `scribble_game`; H2 chỉ tạo database tạm cho kiểm thử. Việc này không tự chứng minh 3NF; kết luận vẫn UNVERIFIED cho toàn schema. Quy trình kiểm tra và cập nhật schema: [LOCAL_MIGRATIONS.md](LOCAL_MIGRATIONS.md).
