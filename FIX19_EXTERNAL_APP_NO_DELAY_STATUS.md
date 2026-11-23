# FIX19 — Chế độ xem trên app khác, lồng tiếng không trễ (20/11/2026)

## Yêu cầu

Xem video trực tiếp trên app khác của điện thoại (YouTube, TikTok,
Netflix...) thay vì trong trình duyệt của ALAD, và giọng dịch phải khớp
với video gốc, không bị trễ.

## Vì sao trước đây bị trễ khi xem app khác

Thiết kế gốc của app là **trễ hình để đợi giọng dịch**: video trong trình
duyệt ALAD bị giữ lại 3–10 giây (mặc định 5 giây), còn giọng dịch cũng
được **giữ lại** một khoảng tương ứng (`initialVoiceDelayMs` = độ trễ đã
chọn − thời gian dịch) để khớp với hình đã trễ đó. Khi xem trên app khác,
video không bị trễ — nhưng giọng dịch vẫn bị giữ lại như cũ, nên giọng
dịch chậm hơn hình tới ~5 giây.

## Đã sửa: chế độ "Xem trên app khác"

Bật/tắt bằng thẻ **"Xem trên app khác"** ngay dưới thẻ Trình duyệt ALAD
trên màn hình chính. Khi bật:

| Thay đổi | Ở đâu |
|---|---|
| **Không giữ giọng dịch nữa**: `initialVoiceDelayMs = 0` — giọng dịch phát ngay khi Gemini dịch xong từng câu | `AudioDubbingForegroundService` (khối đo latency lần đầu + quan sát trực tiếp mục 7) |
| **Không đo/chỉnh độ trễ hình** nữa (không còn video nào để trễ) | cùng chỗ trên |
| **Luôn thu tiếng hệ thống** (`AudioPlaybackCapture`, thu được tiếng của mọi app khác) thay vì đường WebAudio của trình duyệt trong app | `AudioDubbingForegroundService` (bỏ qua `BrowserAudioBridge`, ép `useSystemCapture = true`) |
| **Tự bật Bong bóng nổi** (phụ đề + nút điều khiển nổi trên app khác) khi đã cấp quyền hiển thị trên app khác | `AudioDubbingForegroundService` sau khi kết nối Gemini |
| Cài đặt mới `external_app_mode` (mặc định tắt), lưu bền trong DataStore | `UserPreferencesRepository`, `MainViewModel`, thẻ bật/tắt ở `MainScreen` |

Khi tắt chế độ này, app chạy đúng như FIX18 (trình duyệt trong app + trễ
hình để khớp giọng dịch). Version lên 1.2.3 (code 7).

## Cách dùng

1. Mở ALAD, bật **"Xem trên app khác"**.
2. Bấm **Bắt đầu lồng tiếng**, cho phép thu màn hình khi máy hỏi.
3. Chuyển sang YouTube / TikTok / app xem video khác, mở video xem bình
   thường. Giọng Việt sẽ đọc chồng lên, phụ đề hiện trong bong bóng nổi
   (kéo thả được; bấm nút vuông trên bong bóng để dừng).
4. Muốn dừng hẳn: mở lại ALAD bấm Dừng, hoặc bấm nút vuông trên bong bóng.

## Giới hạn thật — nói rõ để khỏi hiểu lầm

- **Không thể trễ 0 giây.** Gemini cần nghe được một cụm từ rồi mới dịch
  và đọc lại được — thời gian đó khoảng **1–2 giây** và không app nào bỏ
  được. Chế độ này chỉ bỏ phần trễ **do app tự thêm vào** (~3–5 giây);
  giọng dịch sẽ chậm hơn hình khoảng 1–2 giây. Muốn khớp hình tuyệt đối
  thì phải xem trong trình duyệt ALAD (chế độ cũ, hình trễ 5 giây để đợi
  giọng).
- **Một số app chặn thu tiếng.** Android không cho thu tiếng của nội dung
  DRM (ví dụ Netflix bản tải về, một số app xem phim bản quyền) — những
  app đó sẽ không lồng tiếng được, đây là giới hạn của Android, không
  phải lỗi của app. YouTube, TikTok, Facebook thường thu được bình
  thường.
- Lần đầu bật, máy sẽ hỏi quyền **"Hiển thị trên các ứng dụng khác"** cho
  bong bóng phụ đề — cấp quyền này thì phụ đề mới hiện trên app khác.
  (Bấm vào thẻ Bong bóng nổi ở màn hình chính để mở cài đặt quyền.)

## Build trên GitHub

Như FIX17/FIX18: giải nén, copy toàn bộ vào gốc repo, push lên `main`,
vào tab **Actions** tải `alad-apks`, cài file **app-debug.apk**. Muốn tạo
Release: push tag `v1.2.3`.
