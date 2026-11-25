# DubLive v1.0.0 — BUILD NOTES

Build xong 25/11/2026 ~10:41 VN. APK Debug, cài được.
- File: `~/workspace/goals/alda-v10-tvb-style-dubbing-sync/files/DubLive-v1.0.0.apk`
  (symlink `~/workspace/your_files/DubLive-v1.0.0.apk`)
- Kích thước: 9.421.430 byte (~9,0 MB) — nhỏ hơn ALDA (57 MB) vì không mang
  theo các thư viện nặng của ALDA; chỉ có ExoPlayer + OkHttp + Material.
- aapt2: package `com.dublive.live`, versionCode 1, versionName 1.0.0,
  minSdk 26, targetSdk 34, launcher `MainActivity`, label `DubLive`.
- Source: `~/workspace/dub-live-moi/` (viết mới hoàn toàn, không copy file
  .kt/.js nào từ ALDA/ALAD).

## Build từ đâu (quan trọng — PAT thiếu quyền workflow)

- Repo mới `kenj71737-afk/dublive-live` (private) ĐÃ tạo được và chứa toàn bộ
  source DubLive, NHƯNG PAT GitHub của user chỉ có scope `repo`, không có
  scope `workflow` nên GitHub trả 404 cho mọi cách tạo file
  `.github/workflows/*` qua API (đã thử cả Trees API lẫn Contents API).
  Repo này hiện CHƯA có workflow nên không tự build được.
- Cách build thực tế: nhánh `dublive-v1` trên repo `kenj71737-afk/b8`
  (tạo từ main, chỉ giữ lại workflow có sẵn của b8, còn lại thay toàn bộ
  bằng code DubLive), mở PR #1 `dublive-v1` → `main` để kích hoạt workflow
  (trigger `pull_request`). **PR #1 KHÔNG merge — main của b8 vẫn nguyên
  vẹn là ALDA FIX43 (commit 54544ae6).** Muốn build lại sau này thì cứ đẩy
  commit mới lên nhánh `dublive-v1` (PR tự chạy lại).
- Actions run thành công: **45596735110**, head `064d2619`
  (commit nhánh dublive-v1), artifact `alad-apks` (id 15013547903).
- Muốn repo dublive-live tự build được: user cần cấp lại PAT có thêm scope
  `workflow`, hoặc tạo file `.github/workflows/build.yml` trong repo đó bằng
  tay trên web GitHub (copy file `build.yml` trong source). Khi đó đổi tên
  artifact `alad-apks` thành `dub-apks` cho gọn.

## 3 lần build lỗi trước khi thành công (đã sửa hết)

1. Run 45594785319 — `gradlew` dùng `java -jar gradle-wrapper.jar` nhưng jar
   wrapper của Gradle 8.5 không có `Main-Class` trong manifest. Sửa: chạy
   `java -cp gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain`.
2. Run 45595041529 — thiếu icon launcher (`mipmap/ic_launcher` không tìm
   thấy). Sửa: thêm adaptive icon `mipmap-anydpi-v26/ic_launcher[.xml]` +
   `ic_launcher_round.xml` + vector foreground.
3. Run 45595844019 — lỗi biên dịch Kotlin do Python khi ghi file đã nuốt
   mất ký tự escape: `SpeechFilter.kt` (`\\-`, `\\s` thành `\-`, `\s`) và
   `GroqFallback.kt` (`\n` thành ký tự xuống dòng thật trong chuỗi). Sửa:
   escape đúng trong 2 file; đã quét lại toàn bộ 12 file .kt, không còn lỗi
   tương tự (trình biên dịch chỉ báo đúng 2 file này).

## Kiến trúc (mới hoàn toàn)

- `MainActivity` — thanh địa chỉ + WebView (web phim + YouTube), phụ đề
  overlay, HUD trễ hình 1 dòng, fullscreen (WebChromeClient custom view).
- `LocalPlayerActivity` — mở file video trên máy bằng ExoPlayer
  (TextureView); trễ hình bằng hàng đợi khung hình snapshot ~15 fps,
  hiện qua ImageView phủ lên.
- `DelayJs` — trễ hình cho WebView: mỗi animation frame chụp frame video
  vào canvas off-screen, xếp hàng theo timestamp, canvas hiển thị vẽ frame
  đã tới hạn (now - delayMs). Viết mới, không lấy từ userscript web-dub.
- `DubbingService` (foreground service) — thu tiếng hệ thống bằng
  AudioPlaybackCapture (MediaProjection, Android 10+), fallback mic + báo
  rõ trong app. Không dùng WebAudio tap (lỗi cũ: video cross-origin không
  CORS làm Web Audio im lặng). Giọng lồng tiếng phát qua
  USAGE_VOICE_COMMUNICATION nên bộ capture (chỉ USAGE_MEDIA) không thu
  lại giọng của chính app (không vòng lặp echo).
- `GeminiLiveClient` — WebSocket Gemini Live speech-to-speech, model mặc
  định `gemini-2.0-flash-live-001`, giọng Aoede/Puck/Charon.
- Watchdog + xoay key (bài học FIX28): key chết IM LẶNG sau ~5 phút — nếu
  60 s không có đầu ra dịch sau khi đã có bản dịch thì tự nối lại (tối đa
  5 lần); xoay sang key còn lại mỗi 5 phút. Chỉ tính đầu ra dịch làm dấu
  hiệu sống.
- `GroqFallback` — khi Gemini Live lỗi/quota: Groq Whisper nghe →
  Gemini text API dịch → TTS đọc. Cần key Groq trong Settings (không bắt
  buộc).
- `DubDelayTracker` — đo trễ thật + LẤY MẪU ĐỊNH KỲ mỗi 2 s khi đang có
  bản dịch dở (sửa lỗi FIX43: trễ đứng yên ở 5s238ms vì trước đây chỉ lấy
  mẫu ở đầu đợt giọng sau ≥1 s im lặng, thoại Quảng Đông nhanh không có
  đầu đợt). EMA 30%, kẹp 3–20 s (mặc định), hiện số trễ trên HUD.
- `VadDetector` — VAD năng lượng: chỉ gửi khi có giọng thật ≥150 ms,
  giữ pre-roll ~500 ms đầu câu, kết thúc câu sau ~700 ms im lặng.
- `SpeechFilter` — bỏ câu chỉ toàn tiếng kêu/rên/cười/ho/nhạc; nghe không
  rõ thì im hẳn, không bịa.
- `VoicePlayer` — phát audio Gemini (24 kHz PCM) hoặc TTS tiếng Việt;
  tốc độ đọc tự đuổi kịp: trễ >2 s thì 1.2x, >4 s thì 1.35x.
- `Prefs` / `SettingsActivity` — nhập Key Gemini 1/2 (lưu local, không
  hardcode, không commit key), key Groq, ô key ElevenLabs (đang tắt — chỗ
  để sẵn, chưa dùng vì user chưa có tài khoản/thẻ Visa), chọn ngôn ngữ
  nguồn (Tự động/Trung giản thể/Quảng Đông/Anh/Hàn/Nhật), chế độ dịch
  nguyên câu (mặc định TẮT — dịch theo cụm, nhanh hơn cho thoại Quảng
  Đông), chọn giọng, thanh trượt trễ tối đa, nút kiểm tra key.

## Giới hạn còn lại (nói thẳng)

- Giọng dịch không nhanh hơn được: Gemini cần 1–2 s nghe + dịch, giọng
  luôn chậm hơn hình 2–5 s nên phải trễ hình đợi giọng — trễ hình là
  đánh đổi bắt buộc, không phải lỗi.
- Tiếng gốc khi lồng tiếng chỉ để ở mức 15% và vẫn chạy theo giờ thực
  (đi trước hình đã trễ đúng bằng độ trễ); chỉ giọng Việt là khớp với
  hình đã trễ. Đây là giới hạn của cách trễ hình bằng canvas (chỉ trễ
  được hình, không trễ được tiếng gốc trong WebView khi video cross-origin).
- Phim mono (1 kênh tiếng chung) không tách được giọng nhân vật khỏi nhạc.
- Video DRM (Netflix, v.v.) và video chặn hoàn toàn việc vẽ lên canvas sẽ
  không trễ hình được — app vẫn dịch + đọc giọng, nhưng hình không trễ.
- File local trễ hình bằng snapshot bitmap 15 fps: tốn CPU, hình có thể
  hơi giật trên máy yếu; tắt trễ hình thì xem mượt như thường.
- Chưa chạy kiểm chứng trên máy thật — các tính năng trên mới được kiểm
  chứng ở mức biên dịch + đóng gói thành công.
- Model Gemini Live mặc định `gemini-2.0-flash-live-001`: nếu Google đổi
  tên model, app sẽ báo lỗi qua fallback Groq; khi đó sửa tên model trong
  `GeminiLiveClient.kt` (`DEFAULT_MODEL`).

## Cách user kiểm chứng (3 bước)

1. Cài `DubLive-v1.0.0.apk` lên điện thoại, mở app → Cài đặt → nhập
   Key Gemini 1 (và Key 2 nếu có) → bấm "Kiểm tra key Gemini" phải báo
   "Key dùng được" → Lưu.
2. Mở phim: nhập URL phim (ví dụ `https://cat3film.com/watch/pretty-woman?sv=1&part=1`
   hoặc link phim ngắn tiếng Trung/Quảng Đông) → bấm Mở → bấm "Bắt đầu
   lồng tiếng" → cho phép ghi màn hình khi hệ thống hỏi → bấm Play trên
   video. Xem vài phút: phụ đề Việt có hiện cùng lúc giọng đọc không,
   và số "Trễ hình: X.X s" trên HUD có nhích dần (không đứng yên ở
   5.2 s như bản ALDA cũ) không.
3. Thử 2 nguồn còn lại: mở `https://www.youtube.com` trong app rồi mở 1
   video tiếng Trung/Quảng Đông và bấm lồng tiếng; rồi bấm "Mở file video
   trên máy", chọn 1 file video có tiếng nói và bấm lồng tiếng. Báo lại
   3 việc: (a) trễ hình có nhích không, (b) giọng Việt có khớp miệng
   không, (c) nguồn nào (web / YouTube / file) chạy tốt nhất.
