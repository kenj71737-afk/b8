# FIX34 — Không mất câu khi đổi key / nối lại (v1.2.13, code 18)

Ngày: 2026-11-23. Nền: FIX33 (v1.2.12). User báo: giọng dịch bị mất
câu, giống như bị nghẹn lại.

## Nguyên nhân đã xác nhận (đọc code)
1. **Đổi key 5 phút cắt ngang câu (nặng nhất):** watchdog FIX28 đổi key
   ngay khi đủ 5 phút, bất kể model đang dịch dở câu nào. Mỗi lần nối
   lại, tiếng gốc trong 1–3 giây chờ thiết lập phiên mới bị bỏ gần hết
   (chỉ giữ 2 gói cuối trong `pendingAudio`) nên câu đó không bao giờ
   được dịch; câu đang dịch dở cũng bị cắt. Một tập 1 giờ mất chừng
   12 câu theo chu kỳ 5 phút — đúng cảm giác "nghẹn" định kỳ.
2. **Báo kẹt giả ở cảnh yên lặng:** kiểm tra kẹt cũ chỉ cần "đã từng có
   bản dịch mà 60 giây không có bản dịch mới" — cảnh nhạc/không thoại
   dài hơn 60 giây cũng bị hiểu là key chết rồi nối lại vô ích, mỗi lần
   lại mất câu khi thoại quay lại. Cờ `hadOutputSinceConnect` không bảo
   vệ được trường hợp này như chú thích cũ tưởng (nó chỉ chặn lúc chưa
   từng có bản dịch nào).
3. **Cổng lọc FIX30 dùng phụ đề cũ:** cổng lọc tiếng kêu soi phụ đề
   đang hiện, mà phụ đề này tích lũy qua nhiều lượt (tín hiệu kết thúc
   lượt không về nên không bao giờ được xóa) — sau cảnh toàn tiếng kêu,
   chữ kêu cũ còn đọng làm cổng lọc mất đoạn đầu câu thật tiếp theo.

## Cách sửa
- MỚI `core/audio/RotationPolicy.kt`: (a) chỉ đổi key khi đang yên lặng
  — không có tiếng nói và không có bản dịch trong 3 giây — để lần nối
  lại rơi vào giữa hai câu; ép đổi ở phút thứ 6 nếu thoại không ngừng
  (không chờ mãi); (b) chỉ báo kẹt khi tiếng nói vẫn đang chảy mà 60
  giây không có bản dịch — cảnh yên lặng không bị nối lại nữa. Cách này
  còn bắt được key chết ngay từ đầu (có tiếng nói, không bản dịch nào),
  cái mà cờ cũ không bắt được.
- `SpeechSendGate` giữ `lastSpeechAtMs` (giờ khung tiếng nói gần nhất,
  còn giữ sau khi câu đóng); `ALADWebSocketManager.lastSpeechAtMs()`
  công khai nó cho watchdog.
- `lastOutputAtMs` khởi tạo bằng giờ nối máy (trước đây là 0) để đo
  "không có bản dịch từ lúc nối" đúng; bỏ cờ `hadOutputSinceConnect`
  (không còn ai đọc).
- Cổng lọc FIX30 soi chữ của **đợt giọng hiện tại** (`burstTranslatedText`,
  xóa ở đầu mỗi đợt giọng theo bộ tách đợt FIX33) thay vì phụ đề tích lũy
  — mỗi đợt chỉ bị xét bằng đúng chữ của nó.

## Đã kiểm tra và KHÔNG phải nguyên nhân
- Hàng đợi phát giọng (128 đoạn, đầy thì bỏ đoạn cũ nhất): model dịch
  với tốc độ ~1x, tốc độ phát cũng 1x nên hàng đợi không đầy được trong
  lúc xem bình thường; chỉ đầy khi AudioTrack bị kẹt (ví dụ loa
  Bluetooth chập chờn) — không phải nguyên nhân chung.
- `clearPendingAudio` chỉ chạy khi user tua video (FIX18) và khi lọc
  lượt vô nghĩa lúc kết thúc lượt (FIX21, tín hiệu đó không về nên cũng
  không chạy).

## Kiểm chứng
- Test mới `RotationPolicyCheckMain.kt`: **21/21** (đổi key: chưa đủ 5
  phút, đủ 5 phút + yên lặng, còn đang đọc/nói thì chờ, đúng ranh 3
  giây, chưa từng có tiếng nói, ép đổi ở 6 phút, 5:59 vẫn chờ; báo kẹt:
  có tiếng mà 61 giây không bản dịch, cảnh yên lặng, chưa có tiếng,
  bản dịch đang chảy, tiếng 30 giây trước + không bản dịch 61 giây,
  chưa đủ 60 giây, chết ngay từ đầu; giờ tiếng nói cuối: chưa có / có /
  cập nhật / giữ sau khi câu đóng / reset).
- Test cũ biên dịch lại với cổng mới: lọc tạp âm **20/20**, trễ tự động
  **19/19**, đợt giọng **11/11** (không hồi quy).
- 4 file sửa + 1 file mới cân ngoặc; diff với FIX33 chỉ đúng 5 chỗ đổi.
- CHƯA build APK thật, chưa chạy trên máy user.

## Giới hạn thật
- Đổi key vào lúc yên lặng vẫn mất tiếng gốc nếu đúng lúc đó nhân vật
  bắt đầu nói lại trong 1–3 giây chờ nối máy — hiếm hơn nhiều so với
  trước, nhưng chưa triệt tiêu hẳn (muốn hết hẳn phải nối song song 2
  phiên rồi mới chuyển, là việc lớn hơn).
- Câu bắt đầu bằng tiếng kêu rồi mới thành câu ("á, đau quá!") có thể
  mất ~0,1–0,2 giây đầu vì cổng lọc chưa biết trước chữ sau sẽ có nghĩa.

## Cần user kiểm chứng
Xem một tập liền: còn câu nào tự dưng biến mất không, nhất là quanh
các mốc 5/10/15 phút (lúc đổi key) và sau các cảnh nhạc dài không thoại.
