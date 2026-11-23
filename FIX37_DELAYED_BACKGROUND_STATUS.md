# FIX37 — Tiếng gốc trễ theo hình khi trang chặn WebAudio (v1.2.16, code 21)

Ngày: 2026-11-23. Nền: FIX36 (v1.2.15). User báo: hình trễ nhưng âm
thanh gốc không trễ theo hình; tắt tiếng gốc về 0 thì mất luôn giọng
dịch; và tắt tiếng gốc thì nhạc nền + tạp âm cũng mất.

## Nguyên nhân thật sự (đã xác nhận bằng code)
- Trang web chặn WebAudio (CORS/DRM hoặc trình phát đã nối sẵn): hàm
  `attachAudio` trong JS thất bại, bộ trễ DelayNode không nối được —
  tiếng gốc phát thẳng, không trễ.
- Nhưng bản dịch vẫn chạy vì service tự chuyển sang thu tiếng hệ thống
  (`useSystemCapture = externalAppMode || !BrowserAudioBridge.isActive`)
  để lấy tiếng cho Gemini. Trễ hình vẫn chạy vì canvas lấy độ trễ từ
  Kotlin (EMA đo trễ dịch), độc lập với đường tiếng. Nên mới có cảnh:
  bản dịch có, hình trễ, tiếng gốc chạy thẳng.
- Giọng dịch và tiếng gốc chung một kênh (USAGE_ASSISTANT về chung
  STREAM_MUSIC trên máy user) nên vặn tiếng gốc về 0 là mất luôn giọng
  dịch — user đã thử và xác nhận.

## Cách sửa
Chế độ mới: trình duyệt + tap WebAudio hỏng lúc bắt đầu phiên.
1. Giọng dịch + tiếng gốc cùng chuyển sang kênh báo thức (như FIX29),
   tiếng thẳng của WebView bị tắt (STREAM_MUSIC về 0). Giờ vặn tiếng
   gốc không ảnh hưởng giọng dịch.
2. Tiếng gốc lấy từ thu hệ thống (stereo), chạy qua
   `BackgroundAudioPlayer` với độ trễ bằng đúng độ trễ hình — nhạc nền,
   hiệu ứng, tạp âm đều khớp hình trở lại. Độ trễ bám theo trễ tự động
   (collector theo `selectedMediaDelayMs`).
3. Bộ lọc giọng gốc theo đúng công tắc của user: tắt (mặc định) thì
   phát nguyên tiếng phim (cả thoại Trung + nền); bật thì chỉ giữ nền
   như FIX29.
4. Mới `DelayedAudioQueue`: hàng chờ gắn giờ tới, khối nào đủ trễ mới
   phát; trễ đọc trực tiếp nên đổi trễ có hiệu lực ngay; khối quá hạn
   >500ms thì bỏ để bắt kịp khi trễ giảm; trần 2600 khối (26 giây).
   Vòng phát chạy riêng, kiểm tra mỗi 5ms.
5. Đường stereo cho trình phát nền vẫn cấp tiếp kể cả khi tap WebAudio
   sống lại sau đó (vì WebView đã tắt tiếng, trình phát nền là nguồn
   tiếng gốc duy nhất). Đường mono cho Gemini vẫn dừng như cũ để khỏi
   trùng tiếng.
6. Chế độ ngoài app (FIX29) giữ nguyên: lọc + không trễ.

## Giới hạn thật
- Tiếng gốc qua đường này là tiếng thu hệ thống (48kHz stereo), không
  phải tiếng gốc trực tiếp từ trình phát — chất lượng đủ nghe nhạc nền
  và hiệu ứng, không phải âm thanh vòm.
- Chế độ quyết định một lần lúc bắt đầu phiên: nếu tap hỏng lúc bấm
  bắt đầu thì dùng chế độ này cả phiên.
- Nút âm lượng khi xem chỉnh âm lượng báo thức (giống FIX29).

## Kiểm chứng
- Test `DelayedAudioQueue` (trích đúng code, kotlinc): 17/17 — đủ trễ
  mới phát, đúng thứ tự, trễ 0 phát ngay, giảm trễ bỏ khối cũ để bắt
  kịp, quá hạn dưới 500ms vẫn phát, trần 2600 bỏ khối cũ nhất, tăng
  trễ có hiệu lực ngay, clear.
- 3 file sửa đều cân ngoặc; diff với FIX36 đúng 4 chỗ (hàng chờ trễ
  mới + trình phát nền + service + version).
- CHƯA build APK thật, chưa chạy trên máy user.

## Cần user kiểm chứng
Mở phim rồi bấm bắt đầu lồng tiếng như bình thường: nhạc nền + tiếng
động có khớp với hình không (không còn chạy trước hình), giọng dịch
vẫn nghe rõ không, vặn thanh trượt tiếng gốc có chỉnh được nhạc nền
mà giọng dịch không đổi không.
