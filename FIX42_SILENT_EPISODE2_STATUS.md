# FIX42 — Video im lặng hoàn toàn trên tập 2+ (trang chặn WebAudio)

**Ngày:** 24/11/2026. **Phiên bản:** v1.2.21 (code 26). **Nền:** FIX41.

## Triệu chứng (user báo 09:19–09:24)

Trên trang 91crdjai.com (phim "锁情咒"):
- Tập 1: không bị chặn WebAudio, lồng tiếng bình thường.
- Tập 2 trở đi: hiện toast "Trang web chặn âm thanh WebAudio (CORS/DRM)".
- **Tắt lồng tiếng cũng không nghe thấy gì; bật lồng tiếng thì phụ đề Việt không hiện.**
- Tức là video im lặng HOÀN TOÀN — không tiếng gốc, không bản dịch, điện thoại không phát ra gì.

## Nguyên nhân (đọc code, 2 cơ chế đều dẫn tới im lặng)

### 1. Video cross-origin không có CORS → "tainted" (nguyên nhân chính)

`attachAudio()` gọi `createMediaElementSource(v)`. Khi video nằm ở origin
khác mà thẻ `<video>` không có thuộc tính `crossOrigin`, media bị "tainted":
Web Audio API chỉ xuất ra im lặng (0) cho mọi node. Cả 2 đường đều chết:
- Đường tap: không nghe được gì → sau 2,2s fallback CORS kích hoạt.
- Đường fallback (`source → ctx.destination`): source đã tainted nên vẫn
  xuất im lặng — fallback KHÔNG cứu được tiếng.
- Hệ quả: video phát hình mà không có tiếng; system capture cũng chỉ thu
  được im lặng → Gemini không có gì để dịch → không phụ đề, không giọng dịch.

Tập 1 (cùng origin hoặc có CORS) thì sạch nên chạy tốt; tập 2+ (host trên
CDN khác, không CORS) thì tainted.

### 2. AudioContext bị suspended khi video tự phát (autoplay)

`new AudioContext()` luôn khởi tạo ở trạng thái `suspended` cho tới khi có
cử chỉ người dùng (tap/phím). Tập 1 user bấm play trực tiếp (có cử chỉ) nên
context chạy. Tập 2 tự phát sau khi chuyển tập (không có cử chỉ) → `resume()`
bị trình duyệt từ chối → context treo suspended → không tiếng nào chạy qua
graph → tap im lặng → fallback CORS kích hoạt NHẦM (không phải CORS, chỉ là
chưa có cử chỉ) → fallback cũng qua context suspended nên vẫn im lặng.

## Sửa (chỉ `BrowserActivity.kt` + version)

1. **Bỏ qua video cross-origin không có `crossOrigin`:** trước khi tạo
   AudioContext, kiểm tra `v.currentSrc || v.src` — nếu khác origin với trang
   và `v.crossOrigin` trống thì `return` sớm, KHÔNG gọi
   `createMediaElementSource`. Video phát qua đường thường (nghe được), service
   dùng system capture + chế độ trễ-tiếng-gốc (FIX37/FIX41) để lồng tiếng.
   Video cùng origin và video có `crossOrigin='anonymous'` vẫn gắn graph
   như cũ. Không đưa vào `audioStates` nên `scan()` kiểm tra lại rẻ mỗi 1,2s
   khi `src` đổi (sang tập khác).
2. **Hoãn probe 2,2s tới khi context chạy:** tách logic timer thành
   `startCheck()`; `resume()` chỉ gọi nó khi `ctx.state === 'running'`.
   Timer mà gặp context vẫn suspended thì re-arm (không kích hoạt fallback).
3. **Listener cử chỉ toàn trang:** `click` / `touchstart` / `keydown` →
   `resumeAllContexts()` — resume mọi AudioContext suspended trong
   `window.__aladAudioStates` rồi gọi `startCheck()` của chúng.
4. **Thông báo 1 lần:** khi hoãn probe do context suspended, báo
   "Video đang im lặng — chạm vào màn hình để bật tiếng."

## Kiểm chứng

- `BrowserActivity.kt`: braces 232/232, parens 740/740, brackets 19/19.
- Diff vs FIX41 = đúng 2 file (`build.gradle.kts`, `BrowserActivity.kt`).
- Build thật qua GitHub Actions (không build local được — Gradle bị sandbox
  chặn TCP).

## Giới hạn

- Video cross-origin không CORS: không có trễ hình/trễ tiếng qua WebAudio
  (graph không gắn được) — tiếng gốc trễ qua trình phát nền (FIX37/FIX41),
  hình trễ qua canvas vẫn chạy. Nếu server không gửi CORS và site cũng
  không đặt `crossOrigin`, đây là giới hạn của trình duyệt, không lách được.
- Nếu video tự phát và user không bao giờ chạm màn hình, tiếng vẫn im cho
  tới lần chạm đầu tiên (thông báo đã hướng dẫn).
