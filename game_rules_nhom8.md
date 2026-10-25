Quy ước cập nhật: Đây là tài liệu luật chơi chính. Mọi thay đổi luật/rule theo yêu cầu người dùng phải được cập nhật vào file này cùng lúc với code và thông báo giao diện liên quan.

1. Mô tả Kiến trúc chung của hệ thống
1.1. Mô hình kiến trúc Client-Server
Hệ thống "Game Vẽ Hình Đoán Ý" được xây dựng theo mô hình kiến trúc Client-Server hiện đại, chia tách rõ ràng trách nhiệm giữa máy chủ trung tâm và các ứng dụng khách:
Server (Máy chủ trung tâm): Đóng vai trò trung tâm điều phối toàn bộ hệ thống. Server chịu trách nhiệm lưu trữ cơ sở dữ liệu, quản lý và xác thực tài khoản, xử lý toàn bộ logic nghiệp vụ, đồng bộ dữ liệu real-time giữa các client, điều phối phòng chơi, trận đấu, tính toán điểm số và xếp hạng.
Client (Máy khách): Là ứng dụng giao diện chạy trên thiết bị của người chơi. Client tương tác trực tiếp với người dùng, cung cấp đồ họa giao diện (UI) trực quan cho các chức năng: đăng nhập/đăng ký, sảnh chờ, kết bạn, nhắn tin, quản lý phòng, công cụ vẽ tranh canvas, gửi đáp án đoán và xem kết quả bảng xếp hạng.
1.2. Các thành phần chính của hệ thống



Thành phần
Vai trò
Mô tả chi tiết
Server
Trung tâm xử lý
Lưu trữ và xử lý thông tin người chơi, phòng chơi, kho từ khóa, hình vẽ và kết quả trận đấu. Quản lý trạng thái kết nối, logic trận đấu, tính điểm và cập nhật xếp hạng.
Client
Giao diện người dùng
Cung cấp giao diện trực quan cho người chơi: đăng nhập, xem danh sách online, kết bạn, nhắn tin, tạo/tham gia phòng, tương tác vẽ tranh trên canvas, đoán đáp án và theo dõi kết quả.
Cơ sở dữ liệu
Lưu trữ dữ liệu
Lưu trữ bền vững thông tin tài khoản, lịch sử trận đấu, bảng xếp hạng, kho từ khóa, danh sách bạn bè và lịch sử tin nhắn. Được quản lý và truy xuất thông qua Hibernate (JPA).
Giao thức truyền thông
Kết nối Mạng
Truyền dữ liệu real-time hai chiều giữa Server và Client (hình vẽ, đáp án, trạng thái phòng, tin nhắn). Hỗ trợ cả cơ chế Request-Response (Client → Server) và Push/Broadcast (Server → Client) để đồng bộ trạng thái phòng và chat.
Module tính điểm
Xử lý nghiệp vụ điểm
Điểm đoán dựa trên thứ tự đáp án đúng và số người có quyền đoán khi tranh bắt đầu: người đầu tiên luôn nhận 100 điểm, các vị trí sau chia đều theo quy mô phòng. Điểm người vẽ theo tỷ lệ người đúng trên số người đủ điều kiện đoán, đạt 100 điểm nếu tất cả đều đúng. Không có người đủ cơ hội đoán thì không phạt. Công thức, ví dụ và ngoại lệ tại mục 4.

1.3. Luồng dữ liệu chính trong hệ thống
Đăng nhập & Xác thực: Client gửi thông tin tài khoản → Server xác thực thông tin với Cơ sở dữ liệu → Server khởi tạo phiên làm việc, trả về kết quả đăng nhập kèm danh sách người chơi đang online.
Tương tác xã hội & Chat: Client gửi yêu cầu kết bạn hoặc tin nhắn cá nhân → Server xử lý, lưu vết vào CSDL và định tuyến (route) trực tiếp tới người nhận nếu đang online.
Tạo & Quản lý phòng: Client tạo hoặc xin vào phòng → Server khởi tạo/thêm người chơi vào danh sách phòng → Server phát broadcast cập nhật danh sách và trạng thái phòng tới tất cả Client liên quan.
Khởi tạo trận đấu: Tất cả thành viên trong phòng nhấn Sẵn sàng (đủ tối thiểu 3 người) → Server kích hoạt chuyển trạng thái phòng sang thi đấu
Phân phối từ khóa: Server ngẫu nhiên chọn trước bộ từ khóa không trùng lặp (3 × số người chơi) → Gửi 3 lựa chọn riêng cho từng người chơi → Người chơi có 10 giây để chọn (nếu quá giờ Server tự động chọn) → Bắt đầu pha vẽ (60 giây).
Vẽ & Tải tranh: Người chơi thực hiện nét vẽ trên canvas → Chọn Gửi (Send) hoặc hết giờ 60s → Client gửi dữ liệu hình vẽ hoàn thiện về Server.
Trình chiếu & Đoán đáp án: Server chiếu từng bức tranh kèm gợi ý số ký tự (dạng gạch dưới) tới các người đoán → Người chơi nhập đáp án trong 30 giây (gửi nhiều lần) → Server kiểm tra đáp án, ghi nhận thứ hạng thời gian và tính điểm.
Tổng kết & Cập nhật: Sau khi kết thúc tất cả lượt đoán, Server tổng kết điểm số từng bức tranh → Hiển thị bảng xếp hạng trận đấu → Cập nhật điểm tích lũy tổng vào CSDL.
2. Mô tả Thiết kế chung của hệ thống
2.1. Các module chức năng chính
Module Xác thực (Authentication): Quản lý toàn bộ quy trình đăng ký, đăng nhập, đăng xuất và khôi phục tài khoản. Đảm bảo an toàn thông tin và duy trì phiên làm việc cho người chơi.
Module Quản lý người chơi (Player Management): Theo dõi danh sách người chơi online/offline, cập nhật trạng thái (rỗi, trong phòng, đang đấu), quản lý hồ sơ cá nhân, cấp độ và điểm số tích lũy.
Module Xã hội (Social): Quản lý các mối quan hệ bạn bè, gửi/nhận lời mời kết bạn, tìm kiếm người chơi, xem profile chi tiết và lịch sử các trận đấu đã tham gia.
Lịch sử trận: Người đã tham gia trận được xem từng vòng, từng lượt vẽ, người vẽ, từ khóa, tranh và kết quả lượt đoán đã lưu. Tranh thuộc phiên bản cũ chưa có số vòng/lượt và log đoán thì hiển thị là dữ liệu cũ; không suy đoán lại thông tin thiếu.
Module Giao tiếp (Chat): Đảm nhận hai kênh giao tiếp riêng biệt: Kênh chat cá nhân (Private Chat 1-1 giữa bạn bè, có lưu lịch sử) và Kênh chat phòng (Room Chat dành cho thành viên phòng chờ).
Module Quản lý phòng chơi (Room Management): Xử lý việc tạo phòng, tìm phòng, vào/ra phòng. Kiểm tra điều kiện bắt đầu (3–8 người đang kết nối, 100% sẵn sàng). Phòng chờ có thể có 1–2 người nhưng chưa được bắt đầu trận đấu. Không nhận thêm người khi phòng đã đủ 8 người, kể cả các vị trí được giữ trong 30 giây mất kết nối. Trận đang chơi tiếp tục khi còn ít nhất 2 thành viên hợp lệ; chỉ chuyển quyền chủ phòng khi người đó xác nhận rời phòng hoặc hết hạn khôi phục.
Module Trận đấu (Game Engine): Đóng vai trò bộ máy điều phối luồng chơi: trích xuất bộ từ khóa ngẫu nhiên từ CSDL (tối thiểu 3 × số người chơi), quản lý bộ đếm thời gian chọn từ (10s), thời gian vẽ (60s) và thời gian đoán (30s/bức tranh), kiểm soát việc nộp tranh tự động khi hết giờ.
Module Vẽ tranh (Drawing): Cung cấp bộ công cụ vẽ phong phú trên canvas Client (bút vẽ, tẩy, chọn màu, kích thước nét vẽ, xóa tất cả) và đóng gói dữ liệu hình vẽ chuyển về Server.
Module Đoán đáp án (Guessing): Hiển thị tranh vẽ kèm gợi ý dạng gạch dưới độ dài đáp án. Xử lý nhận đáp án nhập từ người chơi, đối chiếu với đáp án gốc và phản hồi kết quả chính xác ngay lập tức.
Module Tính điểm (Scoring): Người đoán đúng nhận điểm theo thứ tự Server xử lý và số người có quyền đoán lúc bắt đầu tranh; người vẽ nhận điểm theo số người đoán trúng. Server theo dõi tổng thời gian kết nối của từng người trong mỗi lượt để xác định cơ hội đoán hợp lệ khi xét phạt. Xem mục 4.
Điểm phạt cho người vẽ: Chỉ phạt 20 điểm khi tranh được chiếu hợp lệ, có ít nhất một người đủ điều kiện đoán nhưng không ai đoán đúng; tranh SKIPPED và trường hợp không có người đủ cơ hội đoán không bị phạt.
Module Kết quả & Xếp hạng (Results & Ranking): Hiển thị bảng tổng kết chi tiết sau trận đấu và quản lý Bảng xếp hạng toàn hệ thống theo điểm tích lũy người chơi.
2.2. Thiết kế giao diện tổng quan
Màn hình Đăng nhập / Đăng ký: Giao diện cho phép nhập tài khoản, mật khẩu, chuyển đổi đăng ký tài khoản mới và khôi phục mật khẩu.
Màn hình Sảnh chính (Lobby): Hiển thị danh sách người chơi online, danh sách bạn bè, thông báo lời mời kết bạn, cửa sổ chat 1-1, danh sách các phòng hiện có, nút Tạo phòng, nút vào Bảng xếp hạng và Hồ sơ cá nhân.
Màn hình Phòng chờ: Hiển thị danh sách người chơi trong phòng kèm trạng thái Sẵn sàng, nút Sẵn sàng/Bắt đầu, nút Thoát phòng và khung Room Chat nội bộ.
Màn hình Chọn từ khóa: Giao diện hiển thị 3 lựa chọn từ khóa ngẫu nhiên kèm bộ đếm ngược 10 giây để người chơi chọn từ muốn vẽ.
Màn hình Vẽ tranh: Khu vực Canvas trung tâm, hiển thị từ khóa được giao, đồng hồ đếm ngược 60s, thanh công cụ vẽ (màu sắc, kích thước nét, xóa) và nút Gửi tranh.
Màn hình Đoán tranh: Trình chiếu bức tranh vẽ, gợi ý số ký tự (dạng gạch dưới "_ _ _"), khung nhập đáp án, bộ đếm 30s và lịch sử các câu đoán đã gửi.
Màn hình Kết quả trận đấu: Bảng vinh danh thứ hạng trận đấu, tổng điểm từng người chơi thu được và danh sách chi tiết các bức tranh trong lượt.
Màn hình Bảng xếp hạng: Bảng tổng hợp điểm tích lũy toàn hệ thống, xếp hạng danh hiệu người chơi.
2.3. Các quy tắc nghiệp vụ quan trọng
Quy mô phòng chơi: Tối thiểu 3 người đang kết nối để bắt đầu và tối đa 8 người chơi trong một phòng. Trận đang chơi được tiếp tục khi còn ít nhất 2 thành viên hợp lệ (bao gồm thành viên đang được giữ chỗ trong thời hạn khôi phục). Phòng chờ được phép có 1–2 người trong khi chờ đủ người; người thứ 9 không được tham gia phòng.
Điều kiện bắt đầu: Trận đấu chỉ được khởi tạo khi 100% thành viên trong phòng nhấn Sẵn sàng.
Lời mời vào phòng: Sau khi gửi thành công lời mời tới một người bạn, người gửi phải đợi 15 giây trước khi mời lại cùng người đó. Server kiểm tra thời gian chờ theo cặp người gửi–người nhận, kể cả khi đổi phòng hoặc kết nối lại; các yêu cầu lặp trong thời gian chờ không gửi thêm thông báo tới người nhận. Người đã ở trong phòng không thể được mời thêm.
Sau khi đủ ít nhất 3 người đang kết nối và tất cả đã Sẵn sàng, Server phát đếm ngược 3–2–1 trong phòng chờ rồi mới bắt đầu chọn chủ đề. Nếu điều kiện sẵn sàng thay đổi trong lúc đếm, Server hủy đếm ngược; khi đủ điều kiện trở lại, đếm lại từ 3.
Quy tắc chọn từ khóa: Mỗi người nhận 3 từ khóa gợi ý riêng không trùng lắp trong cùng phòng, có 10 giây để chọn (quá giờ Server tự chọn).
Pha vẽ tranh: Thời gian tối đa 60 giây. Không được dùng chữ hoặc số để trực tiếp ghi đáp án lên tranh vẽ.
Pha đoán tranh: Thời gian 30 giây cho mỗi bức tranh. Người đoán được phép nhập và gửi đáp án nhiều lần.
Quyền đoán: Tác giả của bức tranh không được tham gia đoán bức tranh do chính mình vẽ.
Gợi ý đáp án: Hệ thống hiển thị dạng gạch dưới tương ứng với độ dài và số lượng từ của đáp án (ví dụ: "___ ____" cho "con mèo").
Xử lý người chơi rời trận giữa chừng: Người xác nhận rời trận hoặc hết hạn mất kết nối 30 giây trong khi trận còn diễn ra bị trừ 20 điểm xếp hạng; mỗi thành viên hợp lệ còn lại trong phòng được cộng 20 điểm. Mỗi lần rời trận chỉ áp dụng điểm phạt/thưởng một lần. Nếu số người còn lại dưới 2 người, phòng đang chơi bị giải tán và trận đấu bị hủy phần kết quả thông thường; điểm phạt/thưởng rời trận vẫn được giữ. Không phạt khi trở lại đúng hạn hoặc mất kết nối ở phòng chờ; không phạt bỏ trận hồi tố nếu trận đã kết thúc bình thường trước lúc hết hạn.
Chuyển quyền chủ phòng: Chỉ chuyển khi chủ phòng xác nhận rời hoặc hết hạn khôi phục 30 giây; giữ nguyên quyền trong thời gian RECONNECTING.
Tự động nộp bài: Nếu hết thời gian vẽ mà người đang kết nối chưa chọn Gửi, Server tự động lấy bản nháp đã đồng bộ làm bài nộp. Nếu người chơi đã được xác nhận mất kết nối và chưa nộp, bản nháp bị đánh dấu SKIPPED, không được chiếu và không bị phạt riêng vì không ai đoán đúng. Tranh đã được Server xác nhận nộp vẫn hợp lệ dù tác giả mất kết nối sau đó.
Giới hạn kiểm duyệt nét vẽ: Quy định không viết chữ ghi đáp án là quy tắc fair-play, hệ thống không tự động nhận diện chữ viết tay trên canvas.
Lưu trữ kết quả: Kết quả và điểm số sau mỗi trận đấu hợp lệ được lưu lại CSDL để tính điểm xếp hạng toàn hệ thống.

3. Mất kết nối, kết nối lại và khôi phục phiên

- Trạng thái kết nối tách biệt trạng thái trận: CONNECTED (đang kết nối), RECONNECTING (được giữ chỗ), LEFT (đã rời phòng).
- Server xác nhận mất kết nối bằng đóng/lỗi WebSocket hoặc heartbeat. Mặc định gửi ping mỗi 5 giây, phát hiện sau khoảng 12 giây không nhận pong. Thời hạn giữ chỗ là 30 giây tính từ lúc Server xác nhận; Client chỉ hiển thị thời gian ước tính khi chưa có hạn chính xác.
- Trong 30 giây: giữ danh tính, điểm, vị trí phòng, chủ phòng và tranh đã nộp; không tính người mất kết nối là sẵn sàng để bắt đầu trận mới. Trận và các đồng hồ vẫn chạy.
- Kết nối lại phải xác thực tài khoản và mã khôi phục do Server cấp. Chỉ một socket có quyền điều khiển tài khoản. Socket cũ và tác vụ hết hạn cũ không được thay đổi phiên đã khôi phục.
- Server gửi snapshot riêng cho người quay lại: pha hiện tại, hạn kết thúc pha, vòng, điểm, người chơi, trạng thái đoán đúng và bản nháp riêng. Không gửi từ khóa/bản nháp của người khác. Không đặt lại đồng hồ.
- Nét vẽ được đồng bộ theo mã tranh và phiên bản tăng dần. Tranh có các trạng thái DRAFT, SUBMITTED, SKIPPED, PUBLISHED. Gửi lại bài nộp đã nhận không tạo tranh hoặc cộng điểm trùng.
- Nếu quay lại sau hạn vẽ, không được nộp tranh trễ; phải chuyển tới pha hiện tại. Nếu trận đã kết thúc trong lúc mất mạng, hiển thị kết quả gần nhất.
- Hết 30 giây: Server xóa tư cách thành viên phòng/trận, giải phóng vị trí, xử lý điểm rời trận nếu còn đang đấu, chuyển chủ phòng và cập nhật cho mọi người. Tác vụ hết hạn phải kiểm tra đúng phiên và hạn hiện hành.
- Sau hết hạn: không khôi phục tư cách thành viên cũ. Người chơi được tự vào lại nếu phòng còn tồn tại, ở WAITING và chưa đầy (vẫn phải đáp ứng mật khẩu phòng). Phòng đang chơi từ chối người đã hết hạn; phòng đã giải tán đưa người chơi về sảnh.
- Nút Rời phòng cần xác nhận; rời trận đang chơi thông báo phạt 20 điểm và xử lý ngay, không tạo thời gian giữ chỗ.
- Phiên khôi phục/bản nháp được giữ trong bộ nhớ của Server đơn lẻ; không cam kết khôi phục trận sau khi tiến trình Server khởi động lại.

## 4. Luật tính điểm theo thứ tự — phiên bản 4

Áp dụng cho các trận mới sau khi cập nhật. Giữ phòng 3–8 người, chọn từ 10 giây, thời gian vẽ theo cài đặt hiện có (mặc định 60 giây). Mỗi tranh có **30.000 ms để đoán**, không rút ngắn/kéo dài theo cài đặt thời gian vẽ. Lượt đoán có thể kết thúc sớm khi mọi người còn quyền/cơ hội đoán đều đã đúng.

### 4.1. Điểm người đoán

`Điểm đoán = round(100 × (n − r + 1) / n)`, với `n` là số người có quyền đoán khi tranh bắt đầu (số thành viên của lượt trừ người vẽ), `r` là thứ tự đáp án đúng đầu tiên được Server chấp nhận (`1 ≤ r ≤ n`). Làm tròn gần nhất, phần lẻ 0,5 làm tròn lên. Chốt `n` khi bắt đầu chiếu tranh; người mất kết nối hoặc rời phòng trong lượt không làm đổi điểm của những người còn lại.

- Chỉ chấp nhận khi `0 ≤ t < 30000`. Đáp án đến đúng hạn cuối hoặc muộn hơn bị từ chối, nhận 0 điểm.
- Phòng tối đa 8 người nên một tranh có tối đa 7 người có quyền đoán. Người đúng thứ nhất luôn nhận 100 điểm; người đúng cuối cùng nhận `round(100/n)` điểm nếu tất cả `n` người đều đúng. Thời gian đoán không làm thay đổi điểm khi đáp án còn trong hạn.
- Sai hoặc không có đáp án đúng trước hạn: 0 điểm; đoán sai không bị trừ điểm và vẫn được thử lại.
- Mỗi người chỉ được cộng điểm một lần cho mỗi mã tranh trong một trận. Gửi trùng đáp án đúng không cộng thêm.
- Người vẽ không được đoán tranh của mình. Socket cũ, người hết tư cách thành viên hoặc kết nối không hợp lệ không được tính điểm.
- Server kiểm soát hạn 30 giây bằng thời gian Server và xử lý đáp án theo thứ tự tuần tự. Nếu hai đáp án đến gần như cùng lúc, thứ tự Server chấp nhận quyết định điểm; bỏ qua thời gian do Client gửi.

| Số người trong phòng lúc bắt đầu tranh | Điểm theo thứ tự đúng |
|---|---|
| 3 (2 người đoán) | 100, 50 |
| 4 (3 người đoán) | 100, 67, 33 |
| 5 (4 người đoán) | 100, 75, 50, 25 |
| 6 (5 người đoán) | 100, 80, 60, 40, 20 |
| 7 (6 người đoán) | 100, 83, 67, 50, 33, 17 |
| 8 (7 người đoán) | 100, 86, 71, 57, 43, 29, 14 |

Ví dụ: phòng 4 người có 3 người đoán. Người đầu tiên đúng ở giây 20 nhận 100 điểm; người tiếp theo đúng ở giây 21 nhận 67 điểm. Nếu cả hai đúng ở cùng mili giây, thứ tự Server xử lý vẫn quyết định ai nhận 100 và 67. Người đoán sai, không đúng hoặc đến đúng hạn 30.000 ms trở đi nhận 0 điểm.

### 4.2. Ai được tính vào số người đoán đủ điều kiện?

Với từng bức tranh, Server lập danh sách ứng viên là các thành viên còn quyền tham gia khi lượt đoán bắt đầu, trừ tác giả. Người trong danh sách được tính nếu **đã đoán đúng**, hoặc **có tổng thời gian kết nối hợp lệ ít nhất 10.000 ms** trong lượt đoán đó.

- Thời gian kết nối được cộng dồn qua các lần mất/kết nối lại, chỉ trong cửa sổ của lượt đang xét. Ví dụ: kết nối 4 giây, mất mạng, quay lại thêm 6 giây → đủ 10 giây.
- Ngưỡng được cấu hình bởi `game.scoring.minimum-exposure-ms`, mặc định 10000. Thời gian lượt đoán vẫn là 30000 ms.
- Người đã đoán đúng luôn được giữ là người thành công, dù mất kết nối hoặc xác nhận rời sau đó. Quy tắc rời trận/loại tư cách thành viên vẫn áp dụng độc lập.
- Người rời vĩnh viễn trước khi lượt bắt đầu không phải ứng viên. Người không đúng và chưa đủ 10 giây cơ hội không làm giảm điểm người vẽ.
- Người mất mạng vẫn trong hạn giữ chỗ có thể quay lại và đoán; không kết thúc lượt sớm chỉ vì những người còn online đã đúng.

### 4.3. Điểm người vẽ

`Điểm vẽ = round(100 × k / m)` khi có ít nhất một người đoán đúng và ít nhất một người đủ điều kiện đoán. Làm tròn gần nhất, phần lẻ 0,5 làm tròn lên.

Trong đó `k` là số người đoán đúng. `m` là số người đoán đủ điều kiện tại lúc chốt lượt (không bao gồm tác giả), xác định theo mục 4.2. Người đã đúng vẫn được tính vào `m` khi mất kết nối hoặc rời sau đó.

- `m = 0`: 0 điểm, không phạt vì không ai có cơ hội hợp lệ.
- `m > 0, k = 0`: −20 điểm.
- `m > 0, k > 0`: tính theo tỷ lệ đúng; nếu tất cả `m` người đều đúng thì người vẽ nhận 100 điểm. Thứ tự và tốc độ của họ không làm thay đổi điểm người vẽ.
- Chỉ chốt và cộng điểm vẽ **một lần**, khi lượt đoán kết thúc. Không tính lại khi nhận thông điệp trùng hoặc reconnect.

Ví dụ với bốn người đủ điều kiện:

| Kết quả | Điểm vẽ |
|---|---:|
| 4/4 đúng | 100 |
| 3/4 đúng | 75 |
| 2/4 đúng | 50 |
| 1/4 đúng | 25 |
| 0/4 đúng | −20 |
| Không có người đủ điều kiện | 0 |

### 4.4. Tranh bị bỏ qua, reconnect và tổng điểm

- Tranh đã được Server xác nhận nộp vẫn hợp lệ khi tác giả mất mạng. Bản nháp chưa nộp của người đang mất kết nối lúc hết hạn vẽ là SKIPPED: không đưa vào hàng đợi đoán, không có điểm vẽ và không phạt riêng vì không ai đoán đúng.
- Reconnect không đặt lại cửa sổ đoán, thời gian đã tích lũy, điểm đã được cộng hoặc trạng thái đã đúng. Quay lại sau hạn không được tính điểm đoán hồi tố hay nộp tranh trễ.
- Thưởng/phạt rời trận (−20 người rời, +20 mỗi thành viên hợp lệ còn lại, đúng một lần) tách khỏi điểm vẽ và điểm đoán. Không cộng thưởng/phạt lần thứ hai khi tổng kết.
- Bảng điểm trận và tổng kết hiển thị `điểm đoán + điểm vẽ + thưởng/phạt`. Điểm âm được giữ nguyên và cập nhật đúng vào xếp hạng. Khi hủy trận, vẫn giữ chính sách hiện có: bỏ kết quả thông thường, giữ thưởng/phạt rời trận đã áp dụng.

### 4.5. Lịch sử và dữ liệu

Trận cũ giữ nguyên `scoring_version = 1`, `2` hoặc `3`; trận mới dùng `scoring_version = 4`. Không xóa lịch sử, không tính lại điểm cũ và không đặt lại điểm xếp hạng. Cột `points_adjustment` lưu riêng thưởng/phạt của kết quả mới, mặc định 0 cho dữ liệu cũ. Việc cộng điểm trùng được ngăn ở Server theo mã tranh, người chơi và vòng đời trận; trạng thái trận đang chạy vẫn chỉ nằm trong bộ nhớ như cơ chế reconnect hiện có.


