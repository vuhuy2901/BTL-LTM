# Lược đồ dữ liệu đã quan sát

Nguồn: entity và schema MySQL local `scribble_game`; các bản sao H2 trong báo cáo cũ chỉ là lịch sử kiểm tra, không còn là database chạy thật.

| Bảng | PK | Cột dữ liệu chính | FK thực tế | Unique |
|---|---|---|---|---|
| `users` | `user_id` | `username`, `password`, `display_name`, `ranking_score`, `role`, `avatar`, `created_at` | — | `username`, `display_name` |
| `friendships` | `friendship_id` | `sender_id`, `receiver_id`, `status`, `created_at` | `sender_id`, `receiver_id` → users | cặp có hướng `(sender_id,receiver_id)` |
| `chat_messages` | `message_id` | `sender_id`, `receiver_id`, `content`, `is_read`, `created_at` | cả hai → users | — |
| `matches` | `match_id` | `room_id`, `started_at`, `ended_at`, `scoring_version`* | — | — |
| `match_results` | `result_id` | `match_id`, `user_id`, `points_drawn`, `points_guess`, `points_adjustment`* | matches, users | thiếu unique `(match_id,user_id)` |
| `paintings` | `painting_id` | `match_id`, `painter_id`, `topic_entry_id`, `topic`, `stroke_data`, `round_number`, `turn_number`, `result_data` | matches, users, topic_entries | — |
| `topic_entries` | `id` | `word`, `active` | paintings | — |

`*` Hai cột mới có giá trị mặc định tương ứng 1 và 0. Các FK có chỉ mục tự động. `room_id` là mã phòng đã tồn tại trong bộ nhớ lúc chơi, không có bảng rooms. Kết nối và bản nháp chỉ ở bộ nhớ; kết quả và log đoán của lượt hoàn tất được lưu dạng JSON trong `paintings.result_data`. `stroke_data` là một payload tranh. `round_number`, `turn_number` và `result_data` có thể null ở tranh cũ. `topic_entries` là kho từ khóa do admin quản lý; `paintings.topic_entry_id` nối tranh với từ khóa, còn `paintings.topic` giữ bản chụp chữ để tương thích lịch sử. Từ khóa đã dùng được giữ inactive khi admin xóa để không mất lịch sử.

Schema được tạo/thêm bởi Hibernate `spring.jpa.hibernate.ddl-auto=update`, không có migration được đánh số. MySQL local `scribble_game` là database chạy mặc định. H2 chỉ còn được dùng trong bộ kiểm thử cô lập.
