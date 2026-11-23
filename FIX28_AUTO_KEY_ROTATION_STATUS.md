# FIX28 — Tự động xoay key 5 phút + chuyển key khi Gemini ngừng dịch (v1.2.7, 2026-11-22)

## Vấn đề user báo (tối 22/11, sau khi build FIX27)

Key Gemini miễn phí dùng khoảng 5 phút là **hết dịch được** — không báo lỗi gì,
phải tắt đi bật lại mới dịch tiếp. Tính năng tự chuyển key của FIX27 không kích
hoạt, vì nó chỉ phản ứng khi Gemini gửi lỗi rõ ràng (mã 429, quota...). Thực tế
key chết **im lặng**: không lỗi, không ngắt kết nối, chỉ đơn giản là ngừng trả
bản dịch. FIX28 sửa đúng điểm này: service tự theo dõi phiên dịch thay vì chờ
lỗi.

## Thay đổi (trên nền FIX27 = FIX21 + 2 key, không động vào phần khác)

### 1. Xoay key định kỳ 5 phút — `AudioDubbingForegroundService.kt`
- Vòng lặp watchdog kiểm tra mỗi 10 giây: phiên dịch đủ 5 phút thì tự chuyển
  sang key còn lại và nối lại. Mỗi lần nối lại (bất kỳ lý do gì) đều đặt lại
  đồng hồ 5 phút.
- Dòng trạng thái: "Xoay key 5 phút — đang dùng Key 2".
- Chỉ có 1 key: vẫn nối lại phiên mới mỗi 5 phút trên cùng key đó (phiên mới
  làm key sống lại).

### 2. Phát hiện Gemini ngừng dịch (stall) — cùng file
- Service ghi dấu thời gian mỗi khi có bản dịch ra (audio dịch, phụ đề dịch,
  turn complete). Chỉ tính **đầu ra dịch** — phụ đề tiếng gốc (chỉ chứng tỏ
  Gemini còn nghe) không tính.
- Đã từng có bản dịch mà im lặng quá 60 giây → chuyển key + nối lại.
- Chống báo nhầm cảnh phim im lặng dài: chỉ kích hoạt khi từ lúc nối lại tới
  giờ đã từng có bản dịch (`hadOutputSinceConnect`). Sau khi nối lại do stall,
  nếu vẫn chưa có bản dịch thì không nối lại liên tục nữa.
- Dòng trạng thái: "Gemini ngừng dịch — đã chuyển sang Key 2".

### 3. Tự nối lại khi mất kết nối — `ALADWebSocketManager.kt` + service
- WS manager thêm callback `onDisconnected` (bắn từ `onClosing` / `onFailure`
  khi không phải lỗi key). Lệnh ngắt chủ động `disconnect()` không bắn callback
  này (socket đã bị null trước, callback cũ bị chặn bởi identity guard).
- Service nối lại sau 2 giây, đổi key luân phiên. Sau 5 lần thất bại liên tiếp
  mà không có lần nối thành công nào ("Gemini Ready") thì dừng và báo:
  "Mất kết nối Gemini — kiểm tra mạng rồi bật lại" (tránh lặp vô hạn khi mất mạng).

### 4. Dọn dẹp chung
- Khối ngắt + nối lại dùng chung một hàm `reconnectNow()` (trước đây khối này
  lặp lại trong `onKeyError`); hàm này chạy single-flight (2 trigger cùng lúc
  chỉ thành 1 lần nối lại) và tự hủy nếu user đã tắt lồng tiếng.
- `onKeyError`: nếu đã có lần nối lại đang chạy thì không báo "Cả 2 key đều hết
  hạn mức" nữa (lỗi đó thuộc về socket cũ đang chết).
- "Gemini Ready" giờ còn xóa cả bộ đếm thất bại nối lại (ngoài việc xóa cờ
  failover như FIX27).
- `keyWatchdogJob` hủy trong `stopDubbing()` cùng các job khác.

## File thay đổi
- `app/src/main/kotlin/com/alad/app/core/service/AudioDubbingForegroundService.kt`
- `app/src/main/kotlin/com/alad/app/core/network/ALADWebSocketManager.kt`
- `app/build.gradle.kts` (v1.2.7, versionCode 12)

## Kiểm chứng trên VM (22/11)
- 25/25 file Kotlin cân ngoặc; các điểm nối FIX28 (watchdog, stall, rotation,
  onDisconnected, hủy job) kiểm tra chéo bằng grep.
- **Build lần đầu trên GitHub Actions (22/11 20:39) LỖI biên dịch
  `:app:compileDebugKotlin`**: handler `onDisconnected` dùng `return@onDisconnected`
  trong lambda gán cho property — Kotlin chỉ cho nhãn ngầm khi lambda là tham số
  của một hàm, gán cho property thì không có nhãn. Đã sửa thành if/else thường
  (không đổi tính năng) và đóng gói lại zip cùng version v1.2.7/code 12.
- CHƯA build APK thật thành công, CHƯA chạy trên máy user.
  Cần user kiểm chứng: xem phim liên tục trên 10 phút, dịch không bị ngắt; dòng
  trạng thái phải hiện "Xoay key 5 phút" sau mỗi 5 phút; khi key chết giữa chừng
  thì trong vòng ~1 phút phải tự chuyển key và dịch tiếp.
