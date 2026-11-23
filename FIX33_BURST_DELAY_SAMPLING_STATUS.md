# FIX33 — Lấy mẫu trễ theo đợt giọng nói, không chờ tín hiệu kết thúc lượt (v1.2.12, code 17)

Ngày: 2026-11-23. Nền: FIX32 (v1.2.11). User báo (kèm ảnh chụp, phim
"Thành Phố Dục Vọng" motchillme.com, đang xem tới 24:21): trễ 12 giây
901 ms giữ mãi, không tự điều chỉnh theo câu chữ.

## Nguyên nhân đã xác nhận
Trễ 12.901s là đúng giá trị khởi điểm của FIX32 (kẹp đo lần đầu 18s đã
chạy — không còn kẹt 10s nữa), nhưng đứng yên suốt 24 phút nghĩa là
phần cập nhật theo từng câu CHƯA HỀ CHẠY. FIX32 lấy mẫu trễ khi đoạn
giọng dịch đầu tiên của mỗi lượt nói tới, và chỉ biết "lượt mới" nhờ
tín hiệu kết thúc lượt (turnComplete) từ Gemini. Nhưng bản dịch
(gemini-3.5-live-translate-preview) KHÔNG gửi tín hiệu đó — cờ
`turnFirstAudioSeen` không bao giờ được đặt lại nên không có mẫu nào
được lấy. Đã loại trừ khả năng do đổi key 5 phút/lần: cùng một
WebSocket manager dùng lại sau mỗi lần nối lại, callback không mất.
(Dấu hiệu phụ: bộ lọc tiếng kêu lúc kết thúc lượt của FIX21 cũng phụ
thuộc tín hiệu này, nên nó cũng gần như không chạy — khớp với việc user
báo tạp âm không được lọc trước khi có FIX30.)

## Cách sửa
- MỚI `core/audio/AudioBurstDetector.kt`: tách dòng giọng dịch thành
  từng đợt — đoạn giọng tới sau ≥1s im lặng thì bắt đầu đợt mới (đoạn
  đầu tiên tính là đợt mới). Không cần tín hiệu nào từ máy chủ: model
  đọc liên tục khi dịch một câu và ngừng giữa các câu, nên đợt giọng là
  ranh giới lượt nói mà app tự thấy được.
- Service: lấy mẫu trễ ở đầu mỗi đợt giọng thay vì đầu mỗi lượt nói.
  EMA, biên 2s, kẹp 3–20s, deadband 500ms, bỏ mẫu cũ hơn 20s giữ nguyên
  như FIX32; bỏ cờ `turnFirstAudioSeen` và dòng đặt lại nó trong
  onTurnComplete. Khối đo lần đầu (seed + giữ giọng) không đổi.

## Kiểm chứng
- Test mới `BurstCheckMain.kt`: **11/11** (đoạn đầu = đợt mới, 100/200/
  999ms cùng đợt, 1000ms/5000ms tách đợt, dòng liên tục 10 đoạn = 1 đợt,
  2 câu cách nhau 1,5s = 2 đợt, reset, ngưỡng tùy chỉnh).
- Test FIX32 biên dịch lại: **19/19**; test lọc tạp âm FIX30: **20/20**
  (không hồi quy).
- 2 file cân ngoặc; diff với FIX32 chỉ đúng 3 chỗ đổi.
- CHƯA build APK thật, chưa chạy trên máy user.

## Giới hạn thật
- Nếu mạng giật lag hơn 1s giữa chừng một câu, app có thể tưởng là đợt
  mới và lấy một mẫu trễ sai — EMA + deadband 500ms hạn chế ảnh hưởng.
- Nói liền một mạch không ngừng thì không có đợt mới để lấy mẫu → trễ
  giữ nguyên tới khi có khoảng ngừng.

## Cần user kiểm chứng
Mở video ở chế độ TỰ ĐỘNG, xem 2–3 phút: dòng trễ phải tự nhích khỏi
giá trị khởi điểm (thường giảm dần về khoảng 5–8 giây với câu thoại
ngắn) thay vì đứng yên. Nếu vẫn đứng yên, chụp lại màn hình kèm thời
gian đã xem để đối chiếu.
