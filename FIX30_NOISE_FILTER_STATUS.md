# FIX30 — Lọc tạp âm: Gemini không bịa câu dịch cho nhạc/tiếng ồn (v1.2.9, code 14)

Ngày: 2026-11-23. Nền: FIX29 (v1.2.8). User báo: "Dịch Gemini có nhiều câu
không có nghĩa, không loại bỏ tạp âm được."

## Nguyên nhân đã xác nhận (đọc code, không đoán)

1. **Nhạc nền / tiếng động / tiếng ồn được gửi lên Gemini.** Trước đây chỉ
   khung im lặng hẳn mới không gửi — nhạc, tiếng đập cửa, tiếng ồn đều tới
   model, và model dịch bịa ra câu tiếng Việt cho chúng. Đây là nguyên nhân
   chính của "nhiều câu không có nghĩa".
2. **Bộ lọc lượt nói của script bắt buộc CẢ câu gốc lẫn câu dịch đều vô
   nghĩa mới lọc (AND).** Phụ đề gốc của tiếng kêu thường trống (Gemini
   không phiên âm tiếng kêu) nên bộ lọc gần như không bao giờ chạy. App
   dùng OR từ đầu — script lệch với app.
3. **Ở app, chế độ "Xem trên app khác" phát giọng ngay khi về tới**, nên
   bộ lọc ở cuối lượt nói (FIX21) tới quá muộn — giọng kêu đã phát rồi.
   Chỉ còn lời dặn trong system instruction đỡ, mà lời dặn không phải
   công tắc cứng.

## Cách sửa (3 lớp, cả app lẫn script web v1.7.0)

### Lớp 1 — Cổng gửi tiếng theo VAD (mới, chặn từ gốc)
- MỚI `core/audio/SpeechSendGate.kt`: chỉ gửi tiếng lên Gemini khi VAD đã
  thấy giọng nói thật đủ 150ms trên ngưỡng nói (600/32768, cùng ngưỡng với
  đồng hồ đồng bộ). Đo bằng khoảng nói (lastSpeech − utteranceStart) chứ
  không phải tuổi câu — tiếng động ngắn (đập cửa, 1–2 khung) không bao giờ
  đủ 150ms. Đầu câu giữ trong pre-roll (~500ms) rồi xả ra khi đủ 150ms,
  nên đầu câu không bị cắt; câu đóng mà chưa đủ 150ms thì pre-roll bị bỏ.
- `ALADWebSocketManager.sendAudioData`: mọi tiếng (WebAudio tap, system
  capture thường, system capture stereo FIX29) đều đi qua cổng này — một
  chỗ duy nhất. `reset()` khi connect/disconnect.
- Script web Gemini v1.7.0 có cổng tương tự (`gateSendFrame`, cùng 150ms
  và pre-roll), đặt trong `tap.onaudioprocess`.

### Lớp 2 — Lọc giọng theo phụ đề đang dịch (mới, chạy được ở chế độ app khác)
- `AudioDubbingForegroundService.onBinaryMessageReceived`: trước khi phát
  mỗi đoạn giọng dịch, kiểm tra phụ đề dịch của lượt nói TỚI LÚC ĐÓ — nếu
  đã có chữ mà toàn là tiếng kêu (không có từ thật) thì bỏ đoạn giọng đó.
  Câu thật không bao giờ bị bỏ: từ thật đầu tiên mở cổng, và chưa có phụ
  đề (chưa có bằng chứng) thì đoạn giọng vẫn phát. Không thêm trễ — không
  giữ giọng chờ như phương án 600ms.
- Script web không cần lớp này: giọng dịch đã đợi tới lúc hình trễ tới câu
  đó mới phát, nên bộ lọc cuối lượt nói vẫn kịp bỏ giọng chờ.

### Lớp 3 — Lời dặn mạnh hơn + sửa bộ lọc script AND → OR
- System instruction (app + script) thêm: chỉ nghe thấy nhạc, tiếng ồn,
  hiệu ứng, nói lí nhí hoặc tiếng không nghe rõ thành chữ thì im hẳn —
  không được bịa chữ, không được đoán.
- Script `onTurnComplete`: đổi thành OR như app — chỉ cần câu gốc HOẶC câu
  dịch toàn tiếng kêu thì bỏ giọng chờ + xóa phụ đề.

## Kiểm chứng
- Test mới `NoiseFilterCheckMain.kt` (harness `~/workspace/alda-build-check/`):
  **20/20** — tiếng động ngắn không gửi, câu thật giữ đủ khung đầu, nhạc
  nền / im lặng không gửi, câu sau xác nhận lại từ đầu, tạm dừng giữa câu
  rồi nói tiếp vẫn xả đủ, reset() bỏ pre-roll, 8 trường hợp quy tắc lọc
  giọng theo phụ đề.
- Bộ test FIX29 cũ vẫn **51/51** (không hồi quy).
- 3 file sửa + 1 file mới cân ngoặc; diff với FIX29 chỉ đúng 4 chỗ đổi
  (version, SpeechSendGate mới, WS manager, service).
- CHƯA build APK thật, chưa chạy trên máy user.

## Giới hạn thật (nói thẳng với user)
- Nhạc nền TO hơn ngưỡng nói (đoạn nhạc cao trào) vẫn lọt qua cổng VAD —
  cổng chỉ phân biệt được bằng độ to, không phân biệt được nhạc với giọng.
- Tiếng kêu mà Gemini "diễn giải" thành câu có từ thật (ví dụ tiếng rên →
  "đau quá!") thì bộ lọc chữ không bắt được — chỉ lời dặn (lớp 3) đỡ.
- Đoạn giọng dịch đầu tiên của tiếng kêu có thể lọt ~0,1s nếu phụ đề dịch
  về chậm hơn giọng — lớp 2 chỉ chặn được từ đoạn thứ hai trở đi.

## Cần user kiểm chứng
Xem 1 tập phim có nhạc nền + cảnh đánh nhau/kêu đau: còn câu dịch vô nghĩa
cho nhạc không? Tiếng kêu còn đọc thành lời không? Câu thoại thật có bị
mất đầu câu không?
