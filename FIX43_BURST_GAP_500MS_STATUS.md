# FIX43 — Độ trễ tự động đứng yên ở chế độ dịch theo cụm

**Ngày:** 24/11/2026. **Phiên bản:** v1.2.22 (code 27). **Nền:** FIX42.

## Triệu chứng (user báo 10:05)

- User tắt ô "Dịch nguyên câu hoàn chỉnh" (theo hướng dẫn FIX42, vì phim
  Quảng Đông thoại nhanh khiến model chờ hết câu mà không dịch).
- Ảnh chụp 10:05 (cat3film.com, "Pretty Woman", 19:55 / 1:32:58): chế độ
  TỰ ĐỘNG, **độ trễ đứng ở "5 giây 238 ms"** và không đổi dù đã xem lâu.
- 5238 ms = đúng giá trị seed (lần dịch đầu 3238 ms + biên 2000 ms) —
  không có mẫu trễ nào chạy sau seed.

## Nguyên nhân

Ở chế độ dịch theo cụm (whole-sentence TẮT), mẫu trễ chỉ chạy ở
**đầu đợt giọng** (FIX33): một chunk dịch tới sau ≥ 1000 ms im lặng mới
tính là đầu đợt mới. Thoại Quảng Đông nhanh, model dịch liên tục, khoảng
nghỉ giữa các cụm < 1 giây → không bao giờ có đầu đợt → không có mẫu →
độ trễ đứng yên ở seed. (FIX39 chỉ lấy mẫu theo thời lượng câu ở chế độ
dịch nguyên câu nên không giúp được ở đây.)

## Sửa (1 dòng + version)

- `AudioDubbingForegroundService.kt`: `AudioBurstDetector()` →
  `AudioBurstDetector(500L)` — rút ngưỡng đợt giọng từ 1000 ms xuống
  500 ms để bắt được khoảng nghỉ ngắn giữa các cụm trong thoại nhanh.
- Không đổi default của `AudioBurstDetector` (test cũ vẫn đúng; test đã
  có sẵn trường hợp gap 500 ms).
- EMA (30%/mẫu) + deadband 500 ms vẫn làm mịn mẫu nhiễu nếu model nghỉ
  giữa chừng một cụm.

## Kiểm chứng

- Service: braces 157/157, parens 502/502, brackets 6/6.
- Diff vs FIX42 = đúng 2 file (`build.gradle.kts`, service).
- Build thật qua GitHub Actions.

## Giới hạn

- Nếu khoảng nghỉ giữa các cụm còn ngắn hơn 500 ms (thoại cực nhanh),
  vẫn có thể ít mẫu. Bước tiếp theo nếu còn đứng yên: lấy mẫu định kỳ
  không phụ thuộc đợt giọng.
- Độ trễ đứng yên không hẳn là sai — nếu tốc độ dịch ổn định thì trễ
  đúng ra cũng ổn định. Dấu hiệu sửa chạy được: con số trễ nhích dần
  (thường giảm về ~4–6 giây) trong vài phút đầu xem.
