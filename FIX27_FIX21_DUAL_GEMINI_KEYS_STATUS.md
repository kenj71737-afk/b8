# FIX27 — FIX21 nguyên mẫu + 2 key Gemini tự đổi (22/11/2026)

## Nguyên tắc

Bản này lấy **nguyên mẫu FIX21, chỉ thêm tính năng 2 key, không chỉnh
sửa gì khác**. So với FIX21, chỉ 6 file thay đổi (5 file Kotlin + số
phiên bản) và toàn bộ các dòng thay đổi đều thuộc tính năng 2 key.
Không có Groq, không gộp nhánh nào khác (bản gộp FIX26 đã gác lại theo
yêu cầu của user).

## Tính năng mới: 2 key Gemini, tự đổi khi hết hạn mức

- Cài đặt → thẻ Key Gemini có thêm ô **Key Gemini 2 (dự phòng)** + dòng
  "Đang dùng: Key X — hết hạn mức sẽ tự chuyển sang Key Y". Key cũ giữ
  nguyên thành Key 1; key 2 lấy từ tài khoản Google khác tại
  aistudio.google.com/apikey (miễn phí, mỗi key có hạn mức riêng).
- Key đang dùng được lưu bền: phiên sau mở app sẽ dùng tiếp key đang
  chạy tốt. Nếu key đang chọn bị xóa, app tự dùng key còn lại.
- Key 1 hết hạn mức (lỗi 429, quota, key sai — cả lỗi trong tin nhắn
  lẫn lỗi khi nối) → app tự đổi sang Key 2 và nối lại ngay, phim không
  dừng. Nối thành công thì lần lỗi sau lại được đổi tiếp — Key 2 hết
  thì chuyển lại Key 1.
- Cả 2 key đều hết: app báo rõ "Cả 2 key Gemini đều hết hạn mức — hạn
  mức miễn phí hồi lại theo ngày" thay vì lỗi chung chung.

## File thay đổi (so với FIX21)

| File | Thay đổi |
|---|---|
| `core/service/AudioDubbingForegroundService.kt` | 2 key + `switchGeminiKey`/`currentGeminiKey` + xử lý `onKeyError` (đổi key + nối lại) |
| `core/network/ALADWebSocketManager.kt` | Callback `onKeyError` + nhận biết lỗi 429/quota/key sai |
| `data/repository/UserPreferencesRepository.kt` | Lưu key 2 + key đang dùng |
| `presentation/settings/SettingsScreen.kt` | Ô Key Gemini 2 + nhãn key đang dùng |
| `presentation/settings/SettingsViewModel.kt` | Trạng thái key 2 + key đang dùng |
| `app/build.gradle.kts` | `versionCode` 11, `versionName` 1.2.6 |

(Mã phiên bản 10 là của bản gộp FIX26 đã gác lại, nên bản này dùng 11.)

## Build trên GitHub

Như các bản trước: giải nén, copy toàn bộ vào gốc repo, push lên `main`,
vào tab **Actions** tải `alad-apks`, cài file **app-debug.apk**. Muốn tạo
Release: push tag `v1.2.6`.

## Lưu ý khi dùng

- Tài khoản Google thứ 2 user tự tạo (cần số điện thoại xác minh) rồi
  lấy key tại aistudio.google.com/apikey, dán vào ô Key Gemini 2.
- Kiểm chứng trên máy: 25/25 file cân ngoặc; diff với FIX21 chỉ còn
  các dòng thuộc tính năng 2 key. CHƯA build APK thật (Gradle bị chặn
  trên VM) và chưa chạy trên máy user.
