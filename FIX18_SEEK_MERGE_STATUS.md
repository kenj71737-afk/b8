# FIX18 — Gộp tính năng tua video (FIX16) vào bản sửa dịch tiếng Việt (20/11/2026)

Bản FIX17 sửa lỗi không dịch ra tiếng Việt nhưng dựng trên source FIX14,
chưa có tính năng tua video của FIX16. Bản này gộp tính năng tua vào, giữ
nguyên toàn bộ sửa chữa của FIX17.

## Tính năng tua (lấy từ FIX16 ở repo ALDA-v10)

- **Thanh tua ở cả 2 chỗ:** thanh điều khiển thường (dưới thanh trễ lồng
  tiếng) và HUD khi xem toàn màn hình — nút **−10s / +10s**, thanh trượt
  tiến trình, đồng hồ `phút:giây / tổng thời lượng`.
- Thanh trượt tự cập nhật mỗi 0,5 giây; kéo thả để tua tới đúng chỗ muốn
  xem. Nút tua mờ đi khi chưa có video nào đang phát.
- Tìm video thông minh hơn: ưu tiên video đang phát, nếu không có thì lấy
  video đã tải lớn nhất (video đang tạm dừng vẫn tua được và vẫn lên
  fullscreen được).
- **Đồng bộ lại 2 tầng sau khi tua** (điểm quan trọng nhất của FIX16):
  1. **Trong trang web (JS):** `__aladSeekAt` đánh dấu thời điểm tua —
     khung hình và audio trễ của cảnh cũ bị bỏ, độ trễ dựng lại từ 0 rồi
     tăng dần về mức đã chọn, nên hình + tiếng không lệch nhau sau tua.
  2. **Trong app (Kotlin):** `BrowserActivity` gửi `ACTION_RESYNC` cho
     service — service bỏ hết audio dịch đang chờ phát của cảnh cũ và xóa
     phụ đề, để không đọc lời cảnh cũ đè lên hình cảnh mới.
- Fullscreen cải thiện kèm theo: khóa cuộn trang khi fullscreen, ép các
  khung bọc video lấp đầy màn hình, khung hình trễ giữ đúng tỉ lệ thật
  của video (không bị kéo dãn khi tỉ lệ màn hình khác tỉ lệ video).

## Điều chỉnh khi gộp vào FIX17

FIX16 viết cho đường TTS cũ (giọng đọc Android + `TvbVoiceDirector` +
`DubSyncTracker`), còn FIX17 đã chuyển sang giọng dịch của Gemini. Những
phần sau được điều chỉnh cho khớp:

- `ACTION_RESYNC` chỉ còn: bỏ audio dịch đang chờ (`clearPendingAudio()`)
  + xóa phụ đề. Không còn `TvbVoiceDirector.stop()` /
  `DubSyncTracker.reset()` vì 2 thứ đó không còn dùng ở FIX17.
- `AudioPlayerManager.clearPendingAudio()` giữ nguyên — ở FIX17 nó càng
  quan trọng vì audio Gemini chính là giọng lồng tiếng.
- **Không gộp** các phần chẩn đoán TVB của FIX15 (số câu bị bỏ, số ms lố
  hình ở `DubbingSessionRepository` / `MainScreen` / `MainViewModel`):
  chúng chỉ đo đường TTS cũ, ở FIX17 luôn bằng 0 nên không gộp để khỏi
  thừa code. Nhãn độ trễ giữ nguyên bản FIX14.

## File thay đổi so với FIX17

| File | Thay đổi |
|---|---|
| `BrowserActivity.kt` | Toàn bộ tính năng tua FIX16 (UI tua 2 nơi, JS `__aladFindVideo` / `__aladSeekTo` / `__aladSeekBy` / `__aladVideoProgress`, `__aladSeekAt` + ramp độ trễ, cải thiện fullscreen) |
| `core/audio/AudioPlayerManager.kt` | Thêm `clearPendingAudio()` |
| `core/service/AudioDubbingForegroundService.kt` | Thêm `ACTION_RESYNC` (bản rút gọn cho đường Gemini-audio) |
| `app/build.gradle.kts` | `versionCode` 5 → 6, `versionName` 1.2.1 → 1.2.2 |

Mọi sửa chữa của FIX17 (modality AUDIO, mặc định tiếng Việt, phương ngữ
Bắc/Trung/Nam, Gradle 8.7) giữ nguyên — xem
`FIX17_VIETNAMESE_TRANSLATION_FIX_STATUS.md`.

## Build trên GitHub

Như FIX17: giải nén, copy toàn bộ vào gốc repo, push lên `main`, vào tab
**Actions** tải `alad-apks`, cài file **app-debug.apk**. Muốn tạo Release:
push tag `v1.2.2`.
