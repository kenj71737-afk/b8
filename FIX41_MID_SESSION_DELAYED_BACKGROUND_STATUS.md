# FIX41 — Bật chế độ trễ tiếng gốc khi tap WebAudio chết giữa phiên

**Ngày:** 24/11/2026. **Phiên bản:** v1.2.20 (versionCode 25).
**Nền:** FIX40 (`~/workspace/alad-fix40-github/`).

## Vấn đề (user báo 24/11 08:51, kèm ảnh)

Trang `91crdjai.com` chặn WebAudio (CORS/DRM) — thông báo "Trang web chặn âm
thanh WebAudio (CORS/DRM). Đã trả về phát gốc". Tiếng gốc phát TRỰC TIẾP
(không trễ) trong khi hình trễ 7,5s và giọng dịch cũng trễ — ba thứ lệch nhau.

## Nguyên nhân (đã đọc code)

FIX37 chỉ quyết định chế độ trễ-tiếng-gốc MỘT LẦN lúc bắt đầu phiên
(`browserDelayedBackground`, theo `BrowserAudioBridge.isActive` lúc đó).
Nhưng cơ chế chặn CORS/DRM của trang chỉ kích hoạt ~2,2s SAU KHI video bắt đầu
phát (hàm `resume()` trong JS chờ đủ 2,2s không thấy tiếng thì mới ngắt tap).
Nếu user bật lồng tiếng trước khi video phát (hoặc trước khi tap chết), lúc
bắt đầu phiên tap vẫn còn sống → FIX37 không kích hoạt. Khi tap chết giữa
phiên, `onModeChanged(false)` chỉ nối lại thu tiếng hệ thống MONO cho Gemini
(giọng dịch vẫn chạy) — KHÔNG bật trình phát tiếng nền, KHÔNG trễ tiếng gốc,
KHÔNG chuyển giọng dịch sang kênh báo thức. Tiếng gốc cứ phát trực tiếp.

## Cách sửa

1. **`AudioPlayerManager.kt` — mới `switchToAlarmStream()`:** chuyển giọng
   dịch sang kênh báo thức GIỮA PHIÊN (dựng lại AudioTrack với USAGE_ALARM,
   tắt STREAM_MUSIC, đặt STREAM_ALARM theo độ to hiện tại). Vòng lặp phát
   tách thành `launchPlaybackLoop()` dùng chung cho `start()` và switch —
   hàng đợi giọng dịch còn nguyên, không áp lại độ giữ giọng đầu phiên.
   (Không thể `stop()` rồi `start()` lại trên cùng instance: `stop()` hủy
   `playerScope` vĩnh viễn.)
2. **`AudioDubbingForegroundService.kt`:**
   - Mới `delayedBackgroundActive` (`AtomicBoolean`, khởi đầu bằng quyết định
     lúc bắt đầu phiên) — cờ sống của chế độ trễ-tiếng-gốc.
   - Bộ theo dõi âm lượng gốc: chuyển sang trình phát nền khi `alarmMode`
     HOẶC `delayedBackgroundActive`.
   - Bộ cập nhật trễ nền: chạy luôn, kiểm tra cờ sống bên trong (trước đây
     chỉ khởi động khi cờ lúc bắt đầu phiên là true — chuyển giữa phiên thì
     không bao giờ chạy).
   - Mới `activateDelayedBackground(projection)` (hàm cục bộ trong phiên):
     chuyển giọng dịch sang báo thức → tạo + chạy `BackgroundAudioPlayer`
     với trễ hình hiện tại → thu stereo (mono cho Gemini, stereo cho trình
     phát nền). `compareAndSet` đảm bảo chỉ kích hoạt một lần.
   - `onModeChanged(false)`: ở chế độ trình duyệt + có giọng dịch + chưa ở
     chế độ trễ nền → gọi `activateDelayedBackground`; còn lại giữ nguyên
     fallback mono cũ (chế độ app khác / chỉ phụ đề).

## Giới hạn thật

- Nếu user chuyển sang video khác trên trang CHO PHÉP WebAudio mà không tắt
  lồng tiếng, tap sống lại → thu hệ thống dừng, trình phát nền hết tiếng,
  tiếng gốc im (STREAM_MUSIC vẫn 0) cho tới khi tắt/bật lại lồng tiếng.
  Hiếm gặp (thường xem hết phim trên cùng một trang chặn) — tắt/bật lại là
  xong, không cần sửa gấp.
- Lúc chuyển chế độ, giọng dịch có thể khựng rất ngắn (đổi AudioTrack).

## Kiểm chứng

- 2 file cân ngoặc; diff với FIX40 đúng 3 file
  (`AudioPlayerManager.kt`, `AudioDubbingForegroundService.kt`,
  `build.gradle.kts`).
- `delayedBackgroundActive` khai báo (dòng 233) trước mọi chỗ dùng;
  `activateDelayedBackground` (dòng 904) trước chỗ gọi (dòng 994).

## Build

Đẩy lên GitHub `kenj71737-afk/b8` (nhánh main) qua Git Data API, Actions
build, tải APK debug về giao user.
