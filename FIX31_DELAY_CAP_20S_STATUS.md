# FIX31 — Nâng trần trễ tự động 10s → 20s (v1.2.10, code 15)

Ngày: 2026-11-23. Nền: FIX30 (v1.2.9). User hỏi: trễ tự động khớp video
gốc trong ALAD tối đa 10s phải không? Nếu đúng thì sửa cho tự động khớp.

## Xác nhận: đúng là 10s, kẹt ở 6 chỗ
- `DubbingSessionRepository.setEstimatedDubbingDelayMs` + `setSelectedMediaDelayMs`:
  `coerceIn(3000, 10000)` — đường trễ tự động.
- `AudioDubbingForegroundService`: trễ thích ứng `(elapsed + 2000).coerceIn(3000, 10000)`.
- `AudioPlayerManager.setInitialVoiceDelayMs`: `coerceIn(0, 10000)`.
- `BrowserActivity`: thanh trượt `max = 700` (3000–10000ms), nút AUTO `coerceIn(0, 10000)`,
  nút −/+ thủ công `coerceIn(3000, 10000)`, nút chọn nhanh chỉ tới 10s.
- JS trong WebView: `createDelay(12.0)` — DelayNode âm thanh tối đa 12s.
  (Nâng trần app mà không nâng cái này thì tiếng gốc chỉ trễ tối đa 12s
  trong khi hình trễ tới 20s → lệch tiếng với hình.)

## Cách sửa
1. **Trần mới 20s** ở cả 6 chỗ Kotlin trên: Repository (2 chỗ), service,
   AudioPlayerManager, BrowserActivity (slider `max = 1700`, AUTO, −/+).
   Nút chọn nhanh thêm 12s / 15s / 20s.
2. **DelayNode 12s → 22s** (trễ tối đa 20s + 2s dự phòng).
3. **Thưa dần tốc độ chụp khung hình khi trễ dài (chống tràn RAM):** hình
   trễ chạy bằng bộ đệm canvas — mỗi khung là 1 canvas tới 640px rộng,
   chụp mỗi 30ms. Ở trễ 20s, chụp 30ms thì giữ ~730 canvas (~650MB với
   video ngang, hơn nữa với video dọc) → dễ sập WebView. Giờ khoảng cách
   chụp = `max(30, (trễ + 2) * 1000 / 400)` ms: dưới ~10s vẫn 30ms như cũ,
   ở 20s là 55ms (~18 khung/giây, vẫn xem được), bộ đệm luôn ~400 khung
   ở mọi độ trễ — RAM không tăng so với bản 10s.

## Vì sao trần là 20s, không phải không giới hạn
- Trễ thực tế = độ dài câu nói + thời gian dịch (~1–2s) + biên 2s. Câu thoại
  phim dài nhất cũng hiếm quá 15s → 20s bao hết trường hợp thật.
- Quá 20s thì bản thân độ trễ đã không xem được nữa (xem hình chậm hơn
  thực tế nửa phút), và bộ đệm khung hình dù đã thưa vẫn nặng thêm.

## Kiểm chứng
- 4 file Kotlin sửa cân ngoặc; không còn chuỗi "10000" nào trên đường trễ
  (chỉ còn STALL_CHECK_INTERVAL_MS 10s của watchdog key — không liên quan).
- Công thức chụp khung trích đúng từ file, chạy thử 11/11: 5s/10s → 30ms,
  12s → 35ms, 20s → 55ms; số khung giữ ≤ 400 ở mọi độ trễ 3–20s.
- CHƯA build APK thật, chưa chạy trên máy user.

## Chưa sửa: script web Gemini/Groq
Script web cũng kẹt 10s (`syncRecordLatency` kẹp 3000–10000, DelayNode
12.0). User mới hỏi ALAD nên chưa sửa script — sửa tiếp khi user đồng ý.

## Cần user kiểm chứng
Xem tập có câu thoại dài hoặc mạng chậm: dòng trễ tự động có vượt 10s
không, giọng còn khớp hình không, app có nặng/giật không khi trễ lên cao.
