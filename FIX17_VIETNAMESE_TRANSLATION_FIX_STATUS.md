# FIX17 — Sửa lỗi không dịch ra tiếng Việt (20/11/2026)

## Triệu chứng

App chạy, kết nối Gemini được, nhưng không có tiếng Việt: không nghe giọng
lồng tiếng Việt, cũng không thấy phụ đề dịch.

## Nguyên nhân (đã đối chiếu tài liệu chính thức của Google, cập nhật 20/11/2026)

### 1. `responseModalities: ["TEXT"]` — model này không trả TEXT

`gemini-3.5-live-translate-preview` là model **speech-to-speech**: nghe tiếng
nói rồi trả tiếng nói đã dịch. Tài liệu chính thức và wire format đã kiểm
chứng trên endpoint thật đều dùng `responseModalities: ["AUDIO"]`. Bản cũ
xin `TEXT` nên phiên dịch không trả về bản dịch nào cả.

### 2. Ngôn ngữ đích mặc định là tiếng Ba Tư (`fa`), không phải tiếng Việt

`UserPreferencesRepository` và `MainViewModel` đều mặc định `targetLang =
"fa"`, nên `translationConfig.targetLanguageCode` là `fa` nếu user chưa
đổi trong giao diện — app dịch sang tiếng Ba Tư. Trong khi đó system
instruction lại hardcode "Translate ... into Vietnamese": Google ghi rõ
instruction mâu thuẫn với `targetLanguageCode` sẽ bị bỏ qua hoặc làm hỏng
bản dịch.

### 3. Lệnh chèn marker `[[TVB:...]]` không được hỗ trợ — và nó nuốt luôn phụ đề

Ở chế độ dịch, Google chỉ cho system instruction hướng dẫn cách dịch;
yêu cầu thay đổi định dạng đầu ra (như chèn marker) là không hỗ trợ.
Model không bao giờ trả marker, nhưng `AudioDubbingForegroundService` lại
đưa mọi đoạn text qua `TvbVoiceDirector.consumeTextDelta`, chỉ hiện phụ đề
/ đọc TTS khi thấy marker — nên toàn bộ text dịch (kể cả phụ đề từ
`outputTranscription`) bị bỏ hết: không phụ đề, không giọng đọc.

## Đã sửa

| File | Thay đổi |
|---|---|
| `core/network/ALADWebSocketManager.kt` | `responseModalities` → `["AUDIO"]`; bỏ `speechConfig`/`voiceConfig` (chế độ dịch giữ giọng người nói gốc, không dùng voice preset); bỏ lệnh marker `[[TVB:...]]`; system instruction dựng động theo ngôn ngữ đích thật (không còn hardcode tiếng Việt nên không bao giờ mâu thuẫn với `targetLanguageCode`); giữ phong cách lồng tiếng TVB (ấm, truyền cảm, xưng hô tự nhiên, khớp thời lượng câu gốc) khi đích là tiếng Việt |
| `core/service/AudioDubbingForegroundService.kt` | Phụ đề lấy thẳng từ `outputTranscription`; giọng lồng tiếng là audio dịch của Gemini qua `AudioPlayerManager` (24 kHz, khớp đúng định dạng model trả); gỡ đường TTS cũ khỏi service (dùng nữa sẽ đọc chồng lên giọng Gemini) |
| `core/audio/TvbVoiceDirector.kt` | Giữ nguyên file làm fallback, thêm ghi chú: hiện không dùng vì model trả audio trực tiếp |
| `data/repository/UserPreferencesRepository.kt` | Ngôn ngữ đích mặc định `fa` → `vi` |
| `presentation/main/MainViewModel.kt` | Giá trị khởi đầu `fa` → `vi` |
| `presentation/main/Languages.kt` | Thêm phương ngữ tiếng Việt: Miền Bắc (Hà Nội), Miền Trung (Huế), Miền Nam (Sài Gòn) — chọn trong thẻ Target Language |
| `app/build.gradle.kts` | `versionCode` 4 → 5, `versionName` 1.2.0 → 1.2.1 |
| `gradle/wrapper/gradle-wrapper.properties` | Gradle 9.3.0 → **8.7** (bản AGP 8.5.0 yêu cầu theo tài liệu chính thức; AGP 8.5 ra trước Gradle 9 nên combo cũ không được kiểm chứng) |

## Hành vi sau sửa

- Mở video → Gemini dịch trực tiếp sang **tiếng Việt**, giọng dịch giữ
  giọng/điệu của nhân vật gốc (đây là cách model translate hoạt động).
- Phụ đề tiếng Việt hiện từ `outputTranscription`; phụ đề gốc vẫn hiện
  song song nếu bật Dual subtitles.
- Chọn giọng Puck/Charon/... vẫn có tác dụng: nó chỉnh màu giọng (DSP)
  trên audio dịch ở `AudioPlayerManager`, không còn gửi lên Gemini.
- Chế độ SUBTITLE_ONLY vẫn tắt tiếng lồng tiếng như cũ.

## Lưu ý

- Máy đã cài bản cũ: DataStore giữ ngôn ngữ đã lưu. Sau khi cài bản mới,
  kiểm tra thẻ Target Language hiện **🇻🇳 Vietnamese**; nếu vẫn là Persian
  thì chọn lại Vietnamese một lần.
- Bản này dựng trên source FIX14 trong file zip user gửi. Các thay đổi
  FIX15/FIX16 ở repo ALDA-v10 (tua ±10s, thanh trượt tiến trình...) **đã
  được gộp vào FIX18** — xem `FIX18_SEEK_MERGE_STATUS.md`.

## Build trên GitHub

1. Giải nén file zip này, copy **toàn bộ nội dung** vào thư mục gốc của
   repo (sao cho `gradlew`, `app/`, `.github/` nằm ngay ở root, không lồng
   thêm thư mục con).
2. Commit + push lên nhánh `main` (hoặc `master`).
3. Vào tab **Actions** → job **Android CI** chạy xong → mục **Artifacts**
   → tải `alad-apks`.
4. Cài file **`app-debug.apk`** (đã ký bằng debug key, cài trực tiếp được).
   File `app-release-unsigned.apk` chưa ký vì repo không có keystore — chỉ
   dùng khi đã thêm keystore riêng.
5. Muốn tạo Release: push tag `v1.2.1` (`git tag v1.2.1 && git push origin
   v1.2.1`), job release sẽ đính kèm APK vào trang Releases.

Workflow đã có sẵn ở `.github/workflows/build.yml` (JDK 17, Gradle qua
wrapper, không cần cài thêm gì).
