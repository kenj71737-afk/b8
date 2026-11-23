# FIX38 — Đo trễ từ đầu câu khi dịch nguyên câu (v1.2.17, code 22)

Ngày: 2026-11-23. Nền: FIX37 (v1.2.16). User báo: tắt ô "dịch
nguyên câu hoàn chỉnh" thì hình khớp tiếng, bật lên thì hình trễ mà
tiếng gốc không trễ.

## Hai lỗi riêng, đều chỉ lộ ra ở chế độ dịch nguyên câu
### 1. Tiếng gốc không trễ — bộ trễ WebAudio bị ngắt (đã sửa ở FIX37)
- Trang web chặn WebAudio, HOẶC đầu video im lặng quá 2,2 giây: hàm
  kiểm tra trong JS tưởng trang chặn tiếng nên ngắt DelayNode, tiếng
  gốc phát thẳng, đặt trễ hình về 0.
- Ở chế độ dịch từng cụm, độ trễ ổn định (~5s) nên Kotlin không đẩy
  trễ mới sang JS — hình giữ ở 0 (phát thẳng), khớp với tiếng thẳng.
  Trông như "khớp", thực ra cả hai đều đang phát thẳng, không trễ.
- Ở chế độ dịch nguyên câu, độ trễ thay đổi liên tục (câu dài ngắn
  khác nhau) nên Kotlin đẩy trễ mới sang JS mỗi lần đổi — hình trễ
  theo (tới 15-20s) nhưng DelayNode đã ngắt nên tiếng vẫn phát thẳng.
  Lệch nhau tới cả chục giây, nhìn là thấy.
- FIX37 (đã có trong bản này) sửa đúng chỗ này: khi tap WebAudio
  hỏng, tiếng gốc đi qua trình phát nền trễ theo hình.

### 2. Giọng dịch tới sau hình — đo trễ từ nhầm chỗ (sửa ở FIX38)
- Độ trễ tự động đo bằng: lúc giọng dịch đầu tiên tới trừ lúc câu
  nói bắt đầu. Nhưng cổng lọc tiếng (SpeechSendGate) coi mỗi đoạn
  nói ngắt nhau quá 450ms là một câu nói mới. Câu dài trong phim
  thường có 2-3 nhịp ngắt (mỗi nhịp 500-800ms) nên một câu thật bị
  chẻ thành 2-3 "câu nói".
- Chế độ dịch nguyên câu chờ hết CẢ câu mới dịch, nhưng máy đo trễ
  từ đầu NHỊP CUỐI (không phải đầu câu). Ví dụ câu 8 giây, 3 nhịp:
  đo được 3,2 giây thay vì 9 giây — thiếu gần 6 giây. Hình chỉ trễ
  ~5 giây trong khi giọng tới sau 9 giây nên giọng trễ hơn hình ~4
  giây, giống như giọng bị mất.
- Chế độ dịch từng cụm không lỗi vì mỗi cụm là một câu nói riêng,
  đo từ đầu cụm là đúng.

## Cách sửa (FIX38)
- SpeechSendGate thêm `lastSentenceStartMs`: một câu mới chỉ bắt đầu
  sau khoảng im từ 1500ms trở lên; ngắt ngắn hơn là nhịp ngắt trong
  cùng một câu. Ngưỡng 1500ms: nhịp ngắt trong câu thường 200-800ms,
  ngắt giữa hai câu thường 1-3 giây.
- Service đo trễ theo đầu CÂU khi bật dịch nguyên câu, theo đầu câu
  nói (như cũ) khi tắt. Mốc hạt giống (lần dịch đầu) không đổi vì nó
  đo từ lúc gửi tiếng đầu tiên (gần đầu câu).
- Giới hạn: câu có nhịp ngắt kịch tính quá 1,5 giây giữa chừng vẫn
  bị chẻ làm hai (sai số nhỏ hơn nhiều so với trước).

## Kiểm chứng
- Test đầu câu (trích đúng code, kotlinc): 14/14 — ngắt 800ms vẫn
  cùng câu, ngắt 2000ms sang câu mới, ranh giới 1500ms đúng cả hai
  chiều, reset xóa cả hai mốc, câu 8 giây 3 nhịp đo đúng 9000ms
  (cách cũ chỉ 3190ms).
- Hồi quy cổng lọc FIX30: 20/20 (không đổi hành vi gửi tiếng).
- 3 file sửa đều cân ngoặc; diff với FIX37 đúng 4 chỗ (cổng lọc +
  quản lý socket + service + version).
- CHƯA build APK thật, chưa chạy trên máy user.

## Cần user kiểm chứng (bản này gộp cả FIX37 + FIX38)
Bật dịch nguyên câu, xem một đoạn thoại dài: (1) tiếng gốc (nhạc
nền, tiếng động) có khớp hình không; (2) giọng dịch có khớp miệng
không, còn tới sau hình vài giây không.
