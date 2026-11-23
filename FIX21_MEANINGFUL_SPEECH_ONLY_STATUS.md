# FIX21 — Chỉ dịch câu có nghĩa, bỏ tiếng kêu/rên không lời (20/11/2026)

## Yêu cầu

Bỏ các âm thanh không cần thiết như tiếng kêu đau "á...", tiếng rên,
tiếng ậm ừ — chỉ dịch những câu nói có nghĩa.

## Đã sửa: 2 lớp lọc

### 1. Dặn Gemini im lặng với âm thanh không phải lời nói (lớp chính)

Thêm vào hướng dẫn hệ thống gửi kèm mỗi phiên dịch (áp dụng cho mọi ngôn
ngữ đích, không riêng tiếng Việt):

> Translate only meaningful speech. Completely ignore non-verbal sounds —
> cries of pain, groans, moans, gasps, sighs, laughter, coughing, sneezing,
> hiccups, and filler sounds such as uh, um, hmm, ah and oh — and stay
> silent for them: do not speak, do not turn them into words. A lone cry
> or groan is not a sentence and needs no translation.

Khi model nghe theo, nó không nói gì cả khi nhân vật chỉ kêu/rên — không
có giọng dịch, cũng không có phụ đề cho đoạn đó. Đây là lớp lọc duy nhất
có tác dụng ở chế độ xem trên app khác, vì ở đó giọng dịch phát ngay khi
vừa dịch xong, không còn cơ hội chặn sau.

### 2. Lưới an toàn khi một lượt nói kết thúc (lớp phụ)

File mới `core/util/UtteranceFilter.kt`: hàm `isMeaningless(text)` trả về
đúng chỉ khi CẢ lượt nói toàn là tiếng kêu/rên, không có một từ thật nào.
Luật cố tình chặt để không bao giờ nuốt mất câu thật:

- Mỗi từ (tách theo khoảng trắng) sau khi gộp ký tự lặp ("ááá" → "á",
  "啊啊啊" → "啊") phải nằm trong danh sách tiếng cảm thán đã biết
  (Việt: á/à/ơ/ừm/hự/ối/ui...; Anh: ow/ouch/ugh/ah/um/hmm...; Hàn:
  아야...), hoặc toàn bộ ký tự đều là chữ cảm thán Trung/Nhật/Hàn
  (啊/哎/哦/呜/あ/う/아/앗...), hoặc là tiếng cười (haha/hehe...).
- Cả cụm "ui da", "ây da", "trời ơi", "ối giời ơi" cũng tính là tiếng kêu.
- Chỉ toàn dấu câu ("...") cũng tính là không có nghĩa.
- **Không lọc** câu trả lời ngắn có nghĩa: "ừ", "ờ", "dạ", "yeah",
  "嗯" (ừm đồng ý), "oh no", "痛い" (đau — đây là từ có nghĩa), và bất
  kỳ câu nào có từ thật ("á, đau quá!" vẫn dịch bình thường vì có
  "đau quá").

Khi một lượt nói kết thúc (`onTurnComplete`), service kiểm tra cả câu
gốc lẫn câu dịch: nếu một trong hai là không có nghĩa thì bỏ audio dịch
đang chờ phát (`clearPendingAudio()`) và xóa phụ đề ngay thay vì để nó
hiện thêm 4,5 giây. Đã kiểm tra logic lọc bằng 52 trường hợp thử
(đau kêu, rên, cười, ậm ừ, câu thật, câu trả lời ngắn...) — đúng 52/52.

## Giới hạn — nói rõ

- Hướng dẫn là lời dặn model, không phải công tắc cứng: model thường
  nghe theo, nhưng thỉnh thoảng vẫn có thể bật ra một tiếng kêu. Lưới
  an toàn lớp 2 chỉ chặn được audio còn đang chờ phát — ở chế độ trình
  duyệt trong app (giọng chờ sau độ trễ hình) thì chặn kịp; ở chế độ xem
  trên app khác (giọng phát ngay) thì tiếng kêu đã phát xong trước khi
  lượt nói kết thúc, nên chỉ còn xóa được phụ đề.
- Phụ đề của tiếng kêu vẫn có thể hiện thoáng qua trong lúc lượt nói
  đang diễn ra (phụ đề hiện trực tiếp theo từng mảnh), rồi bị xóa khi
  lượt nói kết thúc.
- Nếu sau bản này vẫn còn nghe tiếng kêu nhiều, bước tiếp theo sẽ là giữ
  audio dịch lại một nhịp ngắn để kiểm tra câu gốc trước khi phát — nhưng
  làm vậy sẽ cộng thêm độ trễ, nên chưa làm vội.

## File thay đổi

| File | Thay đổi |
|---|---|
| `core/network/ALADWebSocketManager.kt` | Thêm quy tắc "chỉ dịch câu có nghĩa, im lặng với âm thanh không phải lời nói" vào hướng dẫn hệ thống |
| `core/util/UtteranceFilter.kt` | Mới: `isMeaningless(text)` — nhận biết lượt nói toàn tiếng kêu/rên |
| `core/service/AudioDubbingForegroundService.kt` | `onTurnComplete`: lượt nói không có nghĩa → bỏ audio chờ + xóa phụ đề ngay |
| `app/build.gradle.kts` | `versionCode` 8 → 9, `versionName` 1.2.4 → 1.2.5 |

## Build trên GitHub

Như các bản trước: giải nén, copy toàn bộ vào gốc repo, push lên `main`,
vào tab **Actions** tải `alad-apks`, cài file **app-debug.apk**. Muốn tạo
Release: push tag `v1.2.5`.
