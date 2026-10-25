# Kiểm toán database và kế hoạch cải thiện

Ghi chú lịch sử: phần số liệu ban đầu bên dưới đến từ bản sao H2 trước migration và không mô tả database hiện tại. Ứng dụng đã chuyển sang MySQL local `scribble_game`; các bảng từ khóa cũ đã được hợp nhất vào `topic_entries`, tranh được nối qua `topic_entry_id`. Không dùng số liệu H2 này để suy luận trạng thái MySQL hiện hành.

| Bảng | Vấn đề / phụ thuộc | Chuẩn / constraint | Sửa đề xuất | Tác động migration | Rủi ro | Tự sửa? |
|---|---|---|---|---|---|---|
| `match_results` | nghiệp vụ dự kiến `(match_id,user_id) → points_*` nhưng DB chỉ cưỡng chế `result_id` | thiếu unique, nguy cơ cộng điểm trùng; không đủ chứng cứ để tuyên bố lỗi 3NF hình thức | rà toàn bộ dữ liệu hiện hành, giải quyết trùng có chủ đích, thêm UNIQUE `(match_id,user_id)` bằng migration đánh số | có thể thất bại nếu có trùng | cao | chưa |
| `friendships` | quan hệ bạn bè không hướng, schema chỉ unique cặp có hướng | có thể tồn tại A→B và B→A; thiếu CHECK sender != receiver | chuẩn hóa cặp bằng lower/higher ID hoặc ứng dụng đặt khóa, sau đó thêm ràng buộc | hợp nhất trạng thái hai hướng cần quyết định nghiệp vụ | cao | chưa |
| `users` | `ranking_score` là tổng cộng dồn, không chỉ phụ thuộc vào `match_results` vì điểm rời/hủy | denormalization có chủ đích; thiếu sổ điểm đầy đủ để tính lại | đối soát tổng, thêm ledger bất biến trong phiên bản sau | không được viết lại lịch sử | cao | chưa |
| `matches` | `room_id` tham chiếu phòng trong RAM | không thể FK; lịch sử vẫn đọc được | giữ nguyên, tài liệu hóa | không | thấp | đã ghi tài liệu |
| `paintings` | `painter_id` không được DB buộc thuộc trận | toàn vẹn nghiệp vụ | validate tại dịch vụ, cân nhắc FK ghép khi có khóa ứng viên kết quả | cần dữ liệu đầy đủ | vừa | chưa |
| `chat_messages` | chỉ có index FK đơn lẻ | truy vấn hội thoại có thể chậm | index `(sender_id,receiver_id,created_at)` nếu đo tải xác nhận | index thêm, không sửa dữ liệu | thấp | sau đo tải |
| `topic_entries` | trước đây custom và built-in nằm ở hai bảng | `id`; painting trỏ bằng FK | giữ từ đã xóa ở trạng thái inactive để bảo toàn lịch sử | dữ liệu cũ đã hợp nhất, bỏ `source_word` | thấp | đã thực hiện |

**Schema drift:** bản sao cũ thiếu `scoring_version`, `points_adjustment`; metadata đó chỉ có giá trị lịch sử. Cơ chế Hibernate `update` không quản lý phiên bản migration hay rollback; trước triển khai thực cần snapshot và migration đánh số. H2 chỉ dùng cho kiểm thử.

Xem [SCHEMA.md](SCHEMA.md), [NORMALIZATION_3NF.md](NORMALIZATION_3NF.md), [ERD.md](ERD.md).
