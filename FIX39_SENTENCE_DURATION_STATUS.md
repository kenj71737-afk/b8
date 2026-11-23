# FIX39 — Trễ tự động chạy lại ở chế độ dịch nguyên câu (v1.2.18, code 23)

Ngày: 2026-11-24. Nền: FIX38 (v1.2.17). User báo (kèm ảnh 06:02):
độ trễ đứng yên ở 16 giây 134 ms từ lúc vào, sau đó không thay đổi.

## Nguyên nhân
- 16 giây 134 ms chính là giá trị hạt giống (lần dịch đầu 14,1 giây
  + biên 2 giây). Từ đó không mẫu trễ nào chạy nữa.
- FIX33 lấy mẫu trễ ở đầu mỗi "đợt giọng" — đoạn giọng tới sau
  khoảng im từ 1 giây trở lên. Ở chế độ dịch từng cụm thì có nhịp
  ngắt giữa các cụm nên đợt giọng xuất hiện đều. Nhưng ở chế độ dịch
  nguyên câu, máy dịch nói liên tục câu này nối câu kia, không có
  khoảng im nào đủ 1 giây — chỉ có đúng một đợt giọng từ đầu tới cuối
  phiên, nên không mẫu nào chạy sau mẫu đầu tiên.
- Cách đo bằng đầu câu (FIX38) không cứu được vì nó vẫn chờ đợt giọng
  mới để lấy mẫu — không có đợt giọng thì không có mẫu, dù mốc câu
  đã đúng.

## Cách sửa
- Mới `SentenceDurationTracker`: canh câu gốc từ phụ đề tiếng gốc
  trực tiếp (input transcription, về dần theo lời nói). Mỗi câu gốc
  kết thúc bằng dấu câu (。！？.!?…) thì tính thời lượng câu đó.
- Ở chế độ dịch nguyên câu, máy chờ hết câu mới dịch nên độ trễ thật
  bằng thời lượng câu gốc + thời gian dịch (~1,5 giây). Mỗi câu gốc
  xong là một mẫu trễ cho EMA (giữ nguyên biên 2 giây, kẹp 3-20 giây,
  vùng chết 500ms như cũ).
- Câu đo dưới 1 giây bị bỏ (phụ đề gửi theo lô, không phải câu thật);
  câu quá 20 giây bị bỏ (lạc dấu câu). Chế độ dịch từng cụm giữ nguyên
  cách lấy mẫu theo đợt giọng (vẫn chạy tốt ở đó).
- `latencyEma` / `burstDetector` dời lên trước các callback nhận chữ
  để callback phụ đề gốc dùng chung được.

## Giới hạn thật
- Ước lượng 1,5 giây cho thời gian dịch là số trung bình (thực tế
  1-2 giây); sai số ±0,5 giây nằm trong biên 2 giây của EMA.
- Nếu phụ đề gốc về theo lô (cả câu tới cùng lúc) thì mẫu bị bỏ và
  trễ giữ nguyên — không sai, chỉ không cập nhật được.
- Phụ đề gốc không có dấu câu (hiếm) thì câu đó không cho mẫu.

## Kiểm chứng
- Test `SentenceDurationTracker` (trích đúng code, kotlinc): 17/17 —
  câu 5 giây đo đúng 5 giây, câu tiếp nối đo đúng, 2 câu trong 1
  chunk chỉ lấy câu đầu, câu 500ms bị bỏ, câu 25 giây bị bỏ, dấu câu
  Tây + dấu …, reset, chuỗi rỗng.
- `latencyEma` chỉ còn 1 chỗ khai báo (đã dời, không trùng); 2 file
  sửa đều cân ngoặc; diff với FIX38 đúng 3 chỗ (bộ đếm câu mới +
  service + version).
- CHƯA build APK thật, chưa chạy trên máy user.

## Cần user kiểm chứng
Bật dịch nguyên câu, xem 2-3 phút ở chế độ TỰ ĐỘNG: con số độ trễ
có nhích khỏi 16 giây 134 ms không (thường sẽ dao động theo độ dài
câu thoại, lên xuống vài giây), và giọng dịch còn khớp miệng không.
