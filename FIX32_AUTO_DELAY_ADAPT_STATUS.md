# FIX32 — Trễ tự động đo liên tục theo từng câu, không còn kẹt 10s (v1.2.11, code 16)

Ngày: 2026-11-23. Nền: FIX31 (v1.2.10). User báo (kèm ảnh chụp): địa chỉ
nào nhập vào ALAD cũng ghi trễ 10 giây, dù đã để TỰ ĐỘNG.

## Nguyên nhân đã xác nhận (đọc code, không đoán)
1. **Độ trễ lần đầu bị kẹp 8 giây:** `elapsed = (...).coerceIn(0L, 8000L)`
   trong `AudioDubbingForegroundService`, rồi `adaptiveDelay = elapsed + 2000`.
   Lần dịch đầu tiên gần như luôn mất ≥8s (khởi động lạnh + câu đầu tiên +
   mạng) → trễ ra đúng 10 giây mọi lần, mọi địa chỉ. FIX31 nâng trần trễ
   lên 20s nhưng KHÔNG sửa chỗ kẹp 8s này nên vẫn kẹt 10s.
2. **"Tự động" chỉ đo đúng một lần:** `setEstimatedDubbingDelayMs` chỉ
   được gọi từ khối đo lần dịch đầu tiên (`firstTranslatedAudioMeasured`
   chặn mọi lần sau). Vòng lặp 500ms trong BrowserActivity chỉ copy giá trị
   đó ra giao diện — không có gì cập nhật nó theo từng câu nói. Script web
   thì có EMA cập nhật liên tục (DubSyncTracker port); app thì không.

## Cách sửa
1. **Kẹp đo lần đầu 8s → 18s** (để trễ khởi điểm lên được tới trần 20s),
   và dùng nó làm hạt giống (seed) cho EMA.
2. **Trễ tự động cập nhật theo từng lượt nói** (như script web):
   - MỚI `core/audio/LatencyEma.kt`: EMA 30%/mẫu + biên 2s, kẹp 3–20s,
     chỉ đổi trễ khi lệch ≥500ms (chống giật hình).
   - `SpeechSendGate` giữ `lastUtteranceStartMs` (giờ bắt đầu câu nói gần
     nhất, còn giữ sau khi câu đóng) để service đo trễ từng lượt;
     `ALADWebSocketManager.lastUtteranceStartMs()` công khai nó.
   - Service: đoạn giọng dịch đầu tiên của mỗi lượt nói lấy mẫu trễ
     (giờ hiện tại − giờ bắt đầu câu nói), bỏ mẫu nếu câu bắt đầu quá 20s
     trước (sai câu), cập nhật trễ ước lượng qua EMA. Không chạy ở chế độ
     xem app khác (không có trễ hình ở đó). Chế độ THỦ CÔNG không bị ảnh
     hưởng (repository chỉ lan trễ ước lượng sang trễ đã chọn khi đang
     bật tự động).

## Kiểm chứng
- Test mới `DelayAdaptCheckMain.kt`: **19/19** (giờ bắt đầu câu nói: chưa
  có / đang nói / giữ sau khi đóng / câu mới / reset; EMA: seed, kéo trễ
  xuống, deadband, kẹp trần 20s, kẹp sàn 3s, chưa seed, hội tụ 8000→5372
  sau 3 mẫu, mẫu chậm nâng trễ).
- Test lọc tạp âm FIX30 biên dịch lại với cổng VAD mới: **20/20** (không
  hồi quy).
- 4 file sửa + 1 file mới cân ngoặc; diff với FIX31 chỉ đúng 5 chỗ đổi.
- CHƯA build APK thật, chưa chạy trên máy user.

## Giới hạn thật
- Mẫu trễ lấy từ câu nói gần nhất: nếu diễn viên nói câu mới ngay khi giọng
  dịch của câu cũ còn đang về, mẫu có thể nhỏ hơn thật — EMA + deadband
  500ms hạn chế ảnh hưởng, giống hệt cách script web đã chạy.
- Nếu diễn viên nói liền một mạch không ngắt lượt, không có lượt mới để
  lấy mẫu → trễ giữ nguyên tới khi có lượt tiếp theo.

## Cần user kiểm chứng
Mở video bất kỳ ở chế độ TỰ ĐỘNG: dòng trễ có còn đúng 10 giây 0 ms nữa
không, và xem vài phút thì trễ có tự nhích theo tốc độ dịch thật không
(thường sẽ về khoảng 5–8 giây với câu thoại ngắn).
