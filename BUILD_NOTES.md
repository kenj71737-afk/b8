# DubLive v1.0.5 — SỬA 4 LỖI TRẢI NGHIỆM (PHỤ ĐỀ / TIẾNG GỐC / TRỄ / TẠP ÂM)

Build xong 25/11/2026 19:32 VN. APK Debug, cài được.
- File: `~/workspace/goals/alda-v10-tvb-style-dubbing-sync/files/DubLive-v1.0.5.apk`
  (symlink `~/workspace/your_files/DubLive-v1.0.5.apk`)
- Kích thước: 9.1 MB. MD5: fe66904b52d161ec74f6f9d9ed1e2b11.
- aapt2: package `com.dublive.live`, versionCode 6, versionName 1.0.5,
  minSdk 26, targetSdk 34, launcher `MainActivity`, label `DubLive`.
- Actions run thành công: **45614046606** (completed/success, đậu ngay lần
  đầu, 0 lần build lỗi), commit `d5ca22738e89681f1914e81780b609e534b5cf9b`
  trên nhánh `dublive-v1` (repo `kenj71737-afk/b8`, PR #1 vẫn mở, KHÔNG merge),
  artifact `alad-apks` (id 15020849337, bản Debug; bản Release unsigned trong
  cùng artifact thì không cài được nên không giao).
- Đẩy bằng script mới `~/workspace/dub-live-moi/.push_b8.py` (Git Data API
  cho nhánh b8/dublive-v1; `.push.py` cũ đẩy lên dublive-live/main).
  LƯU Ý khi đẩy lần sau: GitHub trả 404 cho mọi entry `.github/workflows/*`
  qua API vì PAT thiếu scope `workflow` — script đã bỏ qua file
  `.github/workflows/build.yml` (đã có sẵn trên nhánh, không đổi) và cả
  `gradle-wrapper.jar` (binary, không đổi); base_tree giữ nguyên 2 file đó.

## Bối cảnh — v1.0.4 ĐÃ NỐI ĐƯỢC Live (tiến bộ lớn)

User thử v1.0.4 lúc 19:21 trên video NetShort "hồng diện sát": Trạng thái
"Đang nghe câu nói…", Mức tiếng 823 (RMS 1632), phụ đề Việt hiện, nghe
được giọng dịch. Live đã nối được — nhưng user báo 4 lỗi trải nghiệm
(xem ảnh 19:21). v1.0.5 sửa cả 4, không sửa gì khác.

## 1. Phụ đề che hết video (nặng nhất)

- Nguyên nhân: `DubbingService.onTranslatedText` append mọi mảnh dịch vào
  `pendingText` và broadcast TOÀN BỘ, chỉ xóa khi `turnComplete`; model
  translate-preview stream liên tục, ít khi gửi `turnComplete`, nên
  `pendingText` phình vô hạn — ảnh 19:21 hiện một khối chữ khổng lồ che
  kín video (cả một đoạn thoại dài).
- Sửa phía service (`showSubtitle` + `trimSubtitle`): mỗi lần broadcast
  chỉ gửi 1–2 câu gần nhất — tách theo dấu kết thúc câu `. ? ! … 。 ！ ？`
  và xuống dòng, giữ tối đa 2 câu, tối đa ~120 ký tự (dài quá 200 ký tự
  thì giữ đuôi 120 ký tự, bỏ từ bị cắt dở). Phụ đề tự ẩn 6 s sau mảnh
  cuối cùng (hẹn giờ + generation counter, mảnh mới hủy hẹn giờ cũ);
  lượt không có nghĩa (`SpeechFilter`) thì ẩn ngay. Đường Groq cũng dùng
  chung `showSubtitle`.
- Sửa phía UI (`activity_main.xml` + `MainActivity`): `subtitleView`
  18sp → **15sp**, `maxLines=3`, `ellipsize=end`, nền đen mờ 60%
  (`#99000000`, màu mới `dub_black_60`), neo đáy video, margin 12dp —
  không bao giờ che quá 3 dòng / ~1/3 màn hình. Thêm nút **"Ẩn/Hiện phụ
  đề"** cạnh nút "Tắt trễ hình" (cờ `subtitlesVisible`; ẩn thì giữ
  `subtitleView` GONE dù service vẫn gửi).

## 2. Chỉ nghe giọng dịch, không nghe được tiếng gốc

- Nguyên nhân: `applyDuckOnce()` hạ tiếng gốc xuống mức
  `Prefs.originalVolume` NGAY khi giọng dịch đầu tiên phát và KHÔNG BAO
  GIỜ trả lại cho tới khi dừng hẳn; giọng dịch nói liên tục nên tiếng
  gốc chìm hẳn.
- Sửa: duck theo nhịp nói thật — `duckTicker` mỗi 500 ms đọc
  `VoicePlayer.isSpeaking()` (chunk Live mới lấy trong 1,5 s qua, hoặc
  TTS đang đọc); đang nói → duck xuống mức user chọn, im quá 1,5 s →
  trả tiếng gốc về **100%**. Chỉ gửi `TYPE_DUCK` khi trạng thái đổi, và
  `stopDub` cũng trả về 1.0. `VoicePlayer` thêm `setDubVolume()` (áp
  dụng ngay cho AudioTrack + tham số TTS) và `isSpeaking()`.
- Đã kiểm chứng: `Prefs.originalVolume()` trả về **0..1** (pct/100 — 50%
  → 0.5, KHÔNG phải 50), đúng thang `DelayJs.DubLiveSetVolume` nhận
  (`v.volume = vol`); `MainActivity` cũng đã gửi đúng thang đó.
- Cài đặt thêm 2 thanh trượt: **"Âm lượng tiếng gốc khi lồng tiếng"
  (mặc định 60%, 0–100%)** và **"Âm lượng giọng dịch" (mặc định 90%)**.
  Lưu ý: duck làm WebView nhỏ tiếng, và AudioPlaybackCapture thu tiếng
  SAU khi duck — nếu user kéo tiếng gốc về 0% thì VAD cũng đói tiếng
  (bài học v1.0.2); mức duck 60% thì RMS 1632 còn ~980, vẫn trên ngưỡng
  VAD 600 nên an toàn.

## 3. Trễ hình vẫn leo tới 19,4 s (v1.0.4 nói đã đóng băng ở 5,2 s)

- Nguyên nhân thật: v1.0.4 chỉ đóng băng khi CHƯA CÓ output nào; ở đây
  ĐÃ có output, và tick 2 s cứ cộng mẫu `(now - utteranceStart)` trong
  lúc giọng dịch đang phát. Phim nói liên tục, VAD không bao giờ thấy
  700 ms lặng nên lượt nói không bao giờ kết thúc, `utteranceStart` cũ
  mãi, mẫu cứ tăng tới trần 20 s → 19,4 s trong ảnh 19:21.
- Sửa `DubDelayTracker`: **lấy mẫu ĐÚNG 1 LẦN mỗi lượt nói** — tại
  `onDubSpeechStarted()`, mẫu = `now - utteranceStart` (chính là độ trễ
  thật từ lúc nhân vật bắt đầu nói tới lúc giọng dịch bắt đầu phát),
  rồi không lấy mẫu nữa cho tới lượt nói mới (cờ `dubSpokeThisTurn`).
  **Bỏ hẳn tick cộng mẫu định kỳ** — tick chỉ còn làm watchdog: lượt
  nói nào chạy quá 30 s mà chưa có giọng dịch thì reset `utteranceStart`
  về hiện tại (coi như lượt mới) để mẫu không phình. Vẫn kẹp
  [3 s, maxDelay], EMA alpha 0.3; chưa có output thì đóng băng ở seed
  5,2 s như v1.0.4.
- Kiểm chứng bằng logic: phim nói liên tục 5 phút, mỗi lượt nói lấy 1
  mẫu ≈ độ trễ nghe+dịch (~5 s) → trễ ổn định quanh 5–8 s, không leo
  dần tới 19–20 s.

## 4. Tạp âm vẫn bị dịch

- Nguyên nhân: ngưỡng VAD hard-code **250** quá thấp so với RMS thực tế
  **1632** (nhạc nền + tiếng ồn lúc nào cũng vượt ngưỡng) nên VAD luôn
  tưởng đang có lời nói, gửi cả nhạc/ồn lên Live và model dịch/bịa
  thành chữ.
- Sửa: ngưỡng VAD mặc định lên **600 (bằng ALDA)** — `VadDetector` nhận
  `energyThreshold` qua constructor, service đọc `Prefs.vadThreshold`
  mỗi lần bắt đầu lồng tiếng. Cài đặt thêm thanh trượt **"Độ nhạy nghe
  tiếng" (ngưỡng RMS 250–1200, mặc định 600)** — càng cao càng ít tạp
  âm, càng thấp càng bắt được tiếng nhỏ.
- Cổng ồn thứ hai ở service: trong `onSpeechChunk`, VAD còn chuyển tiếp
  cả frame lặng xen giữa các từ (cho tới khi đủ 700 ms lặng mới kết thúc
  lượt); chunk nào có RMS < ngưỡng thì **không gửi lên Live**
  (`rmsOf()`), chỉ giữ trong `utteranceBytes` cho đường Groq (Whisper
  chịu ồn tốt hơn Live nhiều). Giữ nguyên lời dặn "nghe không rõ thì im"
  và `SpeechFilter` làm lớp thứ hai.
- Giới hạn nói thẳng: nhạc nền TO (RMS vượt cả 600) vẫn có thể kích VAD
  thành một lượt nói — ngưỡng chỉ giảm chứ không triệt tiêu hoàn toàn;
  khi đó model + SpeechFilter là lớp chặn cuối (lượt chỉ toàn nhạc sẽ
  bị ẩn phụ đề ngay ở `turnComplete`).

## Checklist thử lại cho user

1. Gỡ v1.0.4, cài v1.0.5 (key Gemini/Groq lưu trong SharedPreferences sẽ
   mất khi gỡ app — nhập lại key trong Cài đặt nếu app hỏi; nếu chỉ cập
   nhật đè thì key còn nguyên).
2. Mở video NetShort "hồng diện sát", Play có tiếng, "Bắt đầu lồng
   tiếng" → Micro → "Chia sẻ toàn bộ màn hình".
3. **Phụ đề:** chỉ hiện tối đa 2 câu (~3 dòng) ở đáy video, câu mới thay
   câu cũ, không dồn đống; im 6 s thì phụ đề tự ẩn. Nút "Ẩn phụ đề" tắt
   hẳn phụ đề khi chỉ muốn nghe.
4. **Tiếng gốc:** khi nhân vật im (giọng dịch không nói) thì tiếng gốc
   phải nghe rõ 100%; khi giọng dịch nói thì tiếng gốc nhỏ xuống
   (mặc định 60%) nhưng vẫn nghe lờ mờ. Chỉnh 2 thanh trượt trong Cài
   đặt nếu muốn.
5. **Trễ hình:** xem liên tục 5 phút, trễ phải ổn định quanh 5–8 s,
   KHÔNG leo dần tới 19–20 s.
6. **Tạp âm:** đoạn chỉ có nhạc nền thì trạng thái không hiện "Đang
   dịch…" và không ra phụ đề (nếu nhạc quá to vẫn lọt thì tăng ngưỡng
   "Độ nhạy nghe tiếng" trong Cài đặt lên 800–1000 rồi thử lại).
7. Báo lại: phụ đề có gọn không, tiếng gốc có nghe được khi giọng dịch
   im không, trễ đứng ở bao nhiêu giây sau 5 phút, đoạn nhạc có ra phụ
   đề không.

## Giới hạn

- Chưa kiểm chứng trên máy thật (iQOO Z9 Turbo / OriginOS 6 / Android
  16) — mới kiểm chứng ở mức biên dịch + đóng gói + đối chiếu code đã
  đẩy; đang chờ user chạy checklist trên.
- LocalPlayerActivity (xem file trên máy) nhận phụ đề đã rút gọn từ
  service nên cũng gọn hơn, nhưng layout phụ đề của nó chưa sửa theo
  chuẩn 3 dòng như MainActivity (ngoài phạm vi 4 lỗi của bản này).

---
# DubLive v1.0.4 — SỬA RACE SOCKET CŨ PHÁ SOCKET MỚI + KHỚP ALDA TỪNG DÒNG

Build xong 25/11/2026 19:15 VN. APK Debug, cài được.
- File: `~/workspace/goals/alda-v10-tvb-style-dubbing-sync/files/DubLive-v1.0.4.apk`
  (symlink `~/workspace/your_files/DubLive-v1.0.4.apk`)
- Kích thước: 9.462.374 byte (~9,0 MB). MD5: 5502174d47fd50c37e4e98b2af5a7ae1.
- aapt2: package `com.dublive.live`, versionCode 5, versionName 1.0.4,
  minSdk 26, targetSdk 34, launcher `MainActivity`, label `DubLive`.
- Actions run thành công: **45611383761** (completed/success, build đậu ngay
  lần đầu, 0 lần build lỗi), commit `b6779d4df8696e00c21b4f74e9bb5e2fba651f3b`
  trên nhánh `dublive-v1` (repo `kenj71737-afk/b8`, PR #1 vẫn mở, KHÔNG merge),
  artifact `alad-apks` (id 15019878045, bản Debug; bản Release unsigned trong
  cùng artifact thì không cài được nên không giao).

## Phép thử đối chứng 25/11 19:06 — lỗi 100% phía DubLive

User mở ALDA, dùng ĐÚNG key Gemini đó, mở ĐÚNG video NetShort "hồng diện
sát", và ALDA "Vẫn dịch bình thường" — bong bóng ALAD SUBTITLES hiện cả câu
gốc tiếng Trung lẫn bản dịch tiếng Việt. Kết luận chắc chắn: key còn tốt,
hạn mức còn tốt, model gemini-3.5-live-translate-preview còn sống, server
Google còn tốt lúc 19:06. Lỗi là 100% ở phía client DubLive, không phải
key/model/server/quota. Vì vậy v1.0.4 soi từng dòng DubLive với ALDA FIX43
(`ALADWebSocketManager.kt`) và sửa mọi điểm khác còn sót, không chỉ
generation guard.

## Lỗi chính — callback của socket cũ phá socket mới (race)

- `reconnectLive()` làm: `closingForReconnect=true; liveClient?.close();
  closingForReconnect=false; connectLive()`. Nhưng `close()` của OkHttp là
  bất đồng bộ: `onClosed` của socket CŨ có thể tới SAU khi cờ đã reset về
  false, nên `onClosed` đó bị tưởng là "server tự ngắt" →
  `handleLiveError("Gemini Live tự ngắt kết nối")` → advance model/key +
  scheduleReconnect → đóng socket MỚI vừa nối được → `onClosed` của nó lại
  kích hoạt tiếp. Vòng lặp đốt hết 6 tổ hợp (model, key) trong vài giây và
  hiện đúng dòng lỗi trong ảnh v1.0.3 lúc 19:01: "Lỗi Live [2.5-native]:
  Gemini Live tự ngắt kết nối" (mức tiếng 1000, trễ hình 19,3 s, không phụ
  đề/giọng Việt).
- Sửa: mỗi lần `connectLive()` tăng một generation (AtomicInteger, lưu cả
  ở client `GeminiLiveClient.generation` lẫn service `liveGeneration`); mọi
  callback `onOpen/onMessage/onFailure/onClosed` của client kiểm tra
  `webSocket === socket` (đúng mẫu ALDA) và mọi callback của service kiểm
  tra generation có phải lần nối hiện tại không — callback socket cũ bỏ qua
  hoàn toàn. Bỏ hẳn cờ `closingForReconnect`.
- Khi đổi model/key thì RETRY cùng tổ hợp 1 lần trước khi advance (server
  hiccup một lần không đáng đổi model); chỉ advance sau 2 lần lỗi liên tiếp
  trên cùng tổ hợp. Đếm riêng từng tổ hợp (`comboFailures`, key
  "modelIndex:keyIndex"); `MAX_RECONNECTS` nâng 5 → 12 (= 6 tổ hợp x 2 lần)
  làm trần an toàn, không dùng chung một biến cho cả 6 tổ hợp. Nối lại
  thành công thì xóa số lỗi của tổ hợp đó.
- `onClosed` giờ truyền cả code + reason vào status: "Live tự ngắt (mã
  <code>: <reason>)" thay vì chỉ "tự ngắt kết nối"; `onFailure` ghi rõ mã
  HTTP + reason phrase khi có.

## So sánh với ALDA FIX43 — các điểm khác còn sót đã khớp

1. **OkHttpClient**: ALDA dùng `pingInterval(15s) + retryOnConnectionFailure(true)`
   với timeout mặc định; DubLive không có ping, lại `readTimeout(0)` (vô hạn).
   Đã đổi đúng cấu hình ALDA.
2. **Gửi audio — điểm khác lớn nhất, nghi là cò súng**: ALDA có
   `PendingAudioQueue` (tối đa ~6 s, xả 2 block/nhịp = 2x thời gian thực,
   oldest-first) + `sendAudioNow` bỏ block khi `socket.queueSize() > 64 KB`.
   DubLive `flushPendingAudio()` dồn cả ~10 s buffer gửi 1 lượt ngay khi
   `setupComplete` — đúng cái comment FIX36 của ALDA cảnh báo là flood socket
   queue past the 64 KB congestion drop. Một cú flood như vậy là nguyên nhân
   hợp lý khiến server ngắt session ngay sau khi nối ("tự ngắt kết nối").
   Sửa: client có hàng đợi riêng (`queueAudio`/`drainPending`, tối đa ~6 s
   = 192.000 byte, xả 2 block/nhịp, dừng xả khi socket nghẽn thay vì bỏ
   block), `sendAudio` giờ là queue + drain; `flushPendingAudio` chỉ enqueue
   rồi drain 2 block, phần còn lại xả dần theo các nhịp speech tiếp theo.
   Buffer pre-connect của service cũng hạ 10 s → 6 s (192.000 byte).
   Thêm cờ `isSetupComplete` (chỉ xả audio sau setupComplete, như ALDA),
   thay vì `connected` bật ngay từ `onOpen`.
3. **Setup JSON**: DubLive chèn câu hint ngôn ngữ nguồn ("The speech you
   hear is in ...") mà ALDA không bao giờ gửi (model tự nhận diện; hint sai
   khi cài đặt khác tiếng video thật thì chỉ có hại) — đã bỏ. Thứ tự các
   khối instruction và câu cuối "preserve meaning and speaker intent..."
   đã viết đúng y ALDA ở cả 2 chế độ (chế độ nguyên câu trước đây thiếu câu
   này). Thêm `isKeyError` (regex quota/429/invalid key như ALDA FIX27) để
   ghi chú "(lỗi key/hạn mức?)" vào lịch sử lỗi.
4. **Xử lý callback**: ALDA chấp nhận cả snake_case lẫn camelCase cho mọi
   field (`serverContent/server_content`, `outputTranscription/...`,
   `modelTurn/...`, `turnComplete/...`, `setupComplete/...`) + `onMessage`
   dạng bytes + `onClosing` (tín hiệu ngắt sớm nhất); DubLive chỉ làm
   camelCase text + `onClosed`. Đã thêm đủ 3 cái. Thứ tự xử lý message cũng
   đổi theo ALDA: serverContent → setupComplete → error.
5. **Reconnect**: ALDA chờ 2 s sau khi mất nối; DubLive chờ 1 s — đã đổi
   `scheduleReconnect(2000L)`. ALDA single-flight reconnect
   (`reconnectInFlight`) tương đương cờ `reconnectPending` sẵn có của
   DubLive — giữ nguyên.
- KHÔNG đổi theo ALDA: chính sách thử hết 6 tổ hợp (3 model x 2 key) rồi
  mới sang Groq — ALDA chỉ dùng 1 model nên không có fallback model; giữ
  thiết kế DubLive. Ngưỡng VAD 250 giữ nguyên (ALDA dùng 600 — chặt hơn,
  không phải nguyên nhân; DubLive nghe được ở RMS ~150–220 nên ngưỡng thấp
  hơn là đúng). VAD không reset mỗi lần nối lại (ALDA reset gate, nhưng
  `VadDetector` của DubLive không đồng bộ — reset giữa chừng từ main thread
  trong khi capture thread đang feed sẽ race).

## Lỗi phụ 1 — lịch sử lỗi bị ghi đè, chỉ thấy lỗi cuối

- Ảnh v1.0.3 chỉ thấy lỗi của model 2.5-native, không biết translate-preview
  (key 1, key 2) lỗi gì nên không chẩn đoán được.
- Sửa: service giữ 8 lỗi gần nhất (giờ, model, key số mấy, message ĐẦY ĐỦ
  không cắt 90 ký tự), đẩy qua broadcast `TYPE_LIVE_ERRORS` mỗi khi có lỗi
  mới; MainActivity thêm nút "Xem lỗi Live" mở hộp thoại TextView cuộn được
  hiện toàn bộ lịch sử. Dòng Trạng thái hiện tại giữ nguyên.

## Lỗi phụ 2 — trễ hình phình tới 19,3 s khi không có bản dịch nào

- `DubDelayTracker.startUtterance` bật sampling, tick mỗi 2 s cộng mẫu
  `(now - utteranceStart)` dù giọng dịch chưa bao giờ phát, nên trễ cứ tăng
  dần tới trần 20 s (ảnh v1.0.3: 19,3 s).
- Sửa: chỉ lấy mẫu khi giọng dịch đang phát (sau `onDubSpeechStarted` của
  đúng utterance đó) và đã có output (`lastOutputAtMs > 0`); khi chưa có
  output nào thì ĐÓNG BĂNG trễ ở seed 5,2 s, không lấy mẫu định kỳ;
  `endUtterance` mà không có giọng dịch thì dừng sampling luôn.
- Kiểm tra bằng logic: mở app, không dịch được, trễ phải đứng yên ~5,2 s
  chứ không leo lên 19 s.

## Lỗi phụ 3 — mức tiếng kịch trần 1000

- `level = (rms/2000)*1000` nên tiếng to là kịch 1000, không biết to bao
  nhiêu và VAD luôn tưởng đang nói (nhạc nền liên tục).
- Sửa: thang log `level = ln(1+rms)/ln(1+8000)*1000`, hiện thêm số RMS thô
  bên cạnh thanh mức tiếng (`"842 (RMS 1930)"`). Không đổi ngưỡng VAD.

## Checklist thử lại cho user (bản 1.0.4)

1. Gỡ v1.0.3, cài v1.0.4.
2. Cài đặt → bấm "Kiểm tra key Gemini" — phải báo "Live nối được
   (gemini-3.5-live-translate-preview)"; nếu báo lỗi khác thì chụp lại dòng đó.
3. Mở video NetShort "hồng diện sát", bấm Play cho chạy có tiếng.
4. Bấm "Bắt đầu lồng tiếng" → cấp quyền Micro → chọn "Chia sẻ toàn bộ
   màn hình".
5. Trạng thái phải chạy "Đang nối…" → "Đã nối, đang nghe…" → "Đang nghe câu
   nói…" → "Đang dịch…" rồi phụ đề + giọng Việt. Mức tiếng phải nhảy > 0
   (thang log, kèm số RMS). Trễ hình phải đứng yên ~5,2 s cho tới khi có
   bản dịch đầu tiên, rồi mới nhích theo độ trễ thật — KHÔNG leo lên 19 s.
6. Nếu vẫn lỗi: bấm nút "Xem lỗi Live" trên màn hình chính và CHỤP TOÀN BỘ
   lịch sử lỗi gửi lại — đó là thứ cần nhất để chẩn đoán (dòng đầu tiên
   trong lịch sử mới là lỗi gốc, thường là translate-preview key 1).
7. Báo lại: (a) Kiểm tra key báo gì, (b) trạng thái dừng ở đâu + ảnh lịch
   sử lỗi, (c) có phụ đề/giọng Việt không, (d) trễ hình đứng ở bao nhiêu
   giây khi chưa dịch được.

## Giới hạn còn lại

- Chưa chạy kiểm chứng trên máy thật (máy đích iQOO Z9 Turbo / OriginOS 6 /
  Android 16) — mới kiểm chứng ở mức biên dịch + đóng gói + đối chiếu code
  ALDA; đang chờ user chạy checklist trên.
- Nếu Google đổi tên cả model translate-preview lẫn 2 model native trong
  tương lai, app tự chuyển sang Groq (cần key Groq trong Cài đặt) và báo rõ
  trên dòng Trạng thái.

---

# DubLive v1.0.3 — SỬA "GEMINI LIVE LỖI": SAI GIAO THỨC LIVE (DÙNG ĐÚNG BẢN ALDA)

Build xong 25/11/2026 ~18:52 VN. APK Debug, cài được.
- File: `~/workspace/goals/alda-v10-tvb-style-dubbing-sync/files/DubLive-v1.0.3.apk`
  (symlink `~/workspace/your_files/DubLive-v1.0.3.apk`)
- Kích thước: 9.453.850 byte (~9,1 MB). MD5: 3bc98088b6732863d2c26addf717e3c7.
- aapt2: package `com.dublive.live`, versionCode 4, versionName 1.0.3,
  minSdk 26, targetSdk 34, launcher `MainActivity`, label `DubLive`.
- Actions run thành công: **45608009688** (completed/success, build đậu ngay
  lần đầu, 0 lần build lỗi), commit `8debbf93d1398275a63cd03d7f6bf65eff0c479c`
  trên nhánh `dublive-v1` (repo `kenj71737-afk/b8`, PR #1 vẫn mở, KHÔNG merge),
  artifact `alad-apks` (id 15017869642, bản Debug; bản Release unsigned trong
  cùng artifact thì không cài được nên không giao).

## Chẩn đoán user xác nhận trên v1.0.2 (25/11 18:40, ảnh chụp màn hình)

- Trạng thái "Đang nghe câu nói…", Mức tiếng 77, Trễ hình 5,6 s, video
  NetShort "hồng diện sát" đang phát 00:02/03:30, Toast "Gemini Live lỗi
  và chưa có key Groq dự phòng".
- User nhắn thêm: "Chưa nhập key grod" (chưa nhập key Groq).
- Kết luận: thu tiếng + VAD + trễ hình đều tốt, chỉ có Gemini Live lỗi.
  Groq fallback không có vì chưa có key Groq — v1.0.3 PHẢI chạy được
  bằng Live translate-preview, không trông chờ vào Groq.

## 3 điểm sai giao thức đã sửa (đối chiếu ALDA FIX43 + script web v1.9.1)

ALDA FIX43 (`ALADWebSocketManager.kt`) và script web v1.9.1 chạy được
bằng đúng key của user; DubLive v1.0.2 gọi sai cả 3 điểm so với bản đó:

1. **Model sai:** v1.0.2 dùng `gemini-2.5-flash-native-audio-latest`
   (chưa từng kiểm chứng với key này) → đổi model chính thành
   `gemini-3.5-live-translate-preview` (speech-to-speech, dịch trực tiếp).
   2 model native-audio giữ lại làm dự phòng CUỐI CÙNG, mỗi model có
   setup riêng của nó.
2. **Setup sai:** v1.0.2 gửi key snake_case (`generation_config`,
   `system_instruction`, ...) mà server lặng lẽ bỏ qua, gửi kèm
   `speech_config`/`voice_config` (model translate-preview từ chối setup
   có voiceConfig — ALDA FIX17) và KHÔNG có `translationConfig`.
   Sửa: key camelCase đúng như ALDA (`generationConfig`,
   `systemInstruction`, `inputAudioTranscription`,
   `outputAudioTranscription`); translate-preview gửi
   `translationConfig {targetLanguageCode: "vi", echoTargetLanguage: true}`
   + `sessionResumption {handle: null}` và KHÔNG gửi voice config;
   model native-audio gửi `speechConfig` + voice đã chọn và KHÔNG gửi
   translationConfig. Lời dặn hệ thống cho translate-preview lấy theo
   mẫu ALDA (phiên dịch đồng thời, chỉ dịch câu có nghĩa, tiếng kêu/
   nhạc/ồn thì im hẳn không bịa, giữ đúng thời lượng câu gốc, phong
   cách lồng tiếng TVB tiếng Việt).
3. **Gửi audio sai:** v1.0.2 gửi `realtime_input.media_chunks` →
   đổi sang `realtimeInput.audio {mimeType: "audio/pcm;rate=16000",
   data: base64}` đúng mẫu ALDA/script web. Bỏ gửi `audio_stream_end`
   sau mỗi câu (`endTurn()` giờ là no-op, ALDA stream liên tục, model
   tự ngắt câu).

## Thứ tự thử lại + kiểm tra key (v1.0.3)

- Thứ tự thử cố định: translate-preview key 1 → key 2 →
  native-audio-latest key 1 → key 2 → native-audio-preview key 1 → key 2.
  Mỗi lần thử lỗi đều ghi rõ model nào lỗi vào dòng Trạng thái
  (vd "Lỗi Live [3.5-translate]: …"). Hết mọi tổ hợp mới chuyển Groq.
- Nút "Kiểm tra key" thử cả 3 model theo đúng thứ tự trên, báo
  "Live nối được (model gemini-3.5-live-translate-preview)" khi
  translate-preview nối được; nếu cả 3 đều lỗi mới dùng models-list
  để phân biệt key hỏng với model chết.
- Settings ghi rõ: model dịch trực tiếp giữ giọng gốc của nhân vật;
  ô chọn giọng chỉ có tác dụng ở chế độ dự phòng Groq/TTS.
- Giữ nguyên toàn bộ sửa v1.0.2: đệm audio 10 s, watchdog chạy cả khi
  chưa có output, mọi lỗi đều retry/fallback + hiện trạng thái lâu,
  VAD 250, tiếng gốc 50%, dòng Trạng thái + thanh mức tiếng.

## Đường dự phòng Groq cũng sửa trong v1.0.3 (thêm sau build đầu)

- Ảnh mới của user: màn hình hiện "Lỗi: Dịch text HTTP 404", Mức tiếng
  111, Trễ hình 7,6 s, video NetShort đang phát. Phân tích: user đã Dừng
  rồi Bắt đầu lại nên key Groq mới đã có tác dụng, Whisper đã nghe +
  chuyển thành text thành công — lỗi KHÔNG ở Whisper, cũng không phải
  do key Groq cũ. Lỗi nằm ở bước DỊCH TEXT trong GroqFallback.translate:
  URL generateContent với model gemini-2.5-flash trả HTTP 404 — model
  này không tìm thấy với key này (giống kiểu model Live 2.0 chết 1008).
- Sửa bước dịch: dịch bằng CHÍNH GROQ trước (key Groq đã có, Whisper
  vừa chạy tốt): POST api.groq.com/openai/v1/chat/completions, thử lần
  lượt "openai/gpt-oss-120b" rồi "llama-3.3-70b-versatile", đọc
  choices[0].message.content. Chỉ khi cả 2 đều lỗi mới fallback sang
  Gemini text, thử lần lượt "gemini-2.5-flash", "gemini-2.0-flash",
  "gemini-1.5-flash" (mỗi model lỗi thì thử model sau, không dừng ở 404
  đầu tiên). Mọi lỗi từng bước đều broadcast lên dòng Trạng thái với
  tên bước + mã HTTP + model đã thử.
- Whisper: GIỮ "whisper-large-v3" làm chính (ảnh chứng minh bước này đã
  chạy qua được), thêm fallback "whisper-large-v3-turbo" nếu Whisper trả
  400/404 model. Turbo là model transcription Groq khuyên dùng hiện tại
  (docs Groq: production, đa ngôn ngữ).
- Key Groq cũ: GroqFallback từng được tạo một lần trong startDub với key
  lúc bắt đầu phiên — nhập key trong Cài đặt khi dịch vụ đang chạy thì
  object cũ vẫn giữ key rỗng. Sửa: switchToGroq() tạo lại GroqFallback
  với key TƯƠI, và trước mỗi lần transcribeAndTranslate service gọi
  updateKeys() đọc lại key hiện tại. Nhập key Groq xong vẫn nên Dừng
  hẳn rồi Bắt đầu lại cho chắc, nhưng bản này đã tự đọc key mới.
- Trạng thái Groq: khi ở chế độ Groq, dòng trạng thái hiện "Đang nghe
  bằng Groq…" / "Đang dịch bằng Groq…"; lỗi từng bước (Whisper HTTP /
  dịch HTTP / text rỗng / bị lọc) đều hiện lên dòng Trạng thái, không im.
- Lưu ý build: run 45607134772 cho commit 7e165f77 (chỉ có sửa Live)
  đã success, nhưng bản đó chưa có sửa Groq nên KHÔNG giao; APK giao
  là từ commit mới (có cả sửa Live + Groq) — xem run/commit ở đầu mục.

## Checklist thử lại cho user (v1.0.3)

1. Gỡ v1.0.2, cài v1.0.3.
2. Cài đặt → bấm "Kiểm tra key Gemini" — phải báo "Live nối được
   (model gemini-3.5-live-translate-preview)". Nếu báo lỗi khác thì
   chụp lại dòng đó.
3. Mở video (vd NetShort), bấm Play cho chạy có tiếng.
4. Bấm "Bắt đầu lồng tiếng" (hoặc "Lồng tiếng bằng mic") → cấp quyền
   Micro → chọn "Chia sẻ toàn bộ màn hình".
5. Trạng thái phải chạy "Đang nối…" → "Đã nối, đang nghe…" → "Đang nghe
   câu nói…" → "Đang dịch…" rồi phụ đề + giọng Việt. Mức tiếng phải
   nhảy > 0 khi video phát tiếng.
6. Báo lại: (a) Kiểm tra key báo gì, (b) trạng thái dừng ở đâu (nếu lỗi
   thì chụp dòng trạng thái — trong đó có tên model lỗi), (c) có phụ đề/
   giọng Việt không.
7. Nếu Live lỗi và app chuyển sang Groq: trạng thái phải hiện "Đang dịch
   bằng Groq…" rồi phụ đề Việt phải hiện. Nhập key Groq xong thì Dừng
   lồng tiếng hẳn rồi Bắt đầu lại (bản 1.0.3 đã tự đọc key mới, nhưng
   dừng/bắt đầu lại vẫn là cách chắc nhất).

---

# DubLive v1.0.2 — SỬA IM LẶNG HOÀN TOÀN: MODEL LIVE ĐÃ CHẾT (1008)

Build xong 25/11/2026 ~18:19 VN (giờ UTC 11:19). APK Debug, cài được.
- File: `~/workspace/goals/alda-v10-tvb-style-dubbing-sync/files/DubLive-v1.0.2.apk`
  (symlink `~/workspace/your_files/DubLive-v1.0.2.apk`)
- Kích thước: 9.448.138 byte (~9,1 MB). MD5: 7ae2f07227da0b3d9d4df47eebd9e963.
- aapt2: package `com.dublive.live`, versionCode 3, versionName 1.0.2,
  minSdk 26, targetSdk 34, launcher `MainActivity`, label `DubLive`.
- Actions run thành công: **45602896371** (completed/success, build đậu ngay
  lần đầu, 0 lần build lỗi), commit `7f563b1404de9b50e3322ead1d6af3af65096bd9`
  trên nhánh `dublive-v1` (repo `kenj71737-afk/b8`, PR #1 vẫn mở, KHÔNG merge),
  artifact `alad-apks` (id 15015747590, bản Debug; bản Release unsigned trong
  cùng artifact thì không cài được nên không giao).

## Chẩn đoán user xác nhận (25/11 18:12)

- Bấm CẢ 2 nút "Bắt đầu lồng tiếng" và "Lồng tiếng bằng mic" đều không có dịch.
- Video CÓ tiếng, HUD có hiện trễ hình.
- Bấm "Kiểm tra key" báo key còn dùng được.
- Kết luận: cả 2 đường thu tiếng đều không ra bản dịch, video có tiếng, HUD
  chạy, key text API còn tốt — đúng hướng model Live
  `gemini-2.0-flash-live-001` đã chết (1008 / not found). Không phải do thu
  tiếng hay do key. Vì vậy v1.0.2 tập trung sửa model + mọi đường im lặng.

## Nguyên nhân gốc + đã sửa (đủ 7 mục brief)

1. **Model Live đã chết** — `GeminiLiveClient` dùng `gemini-2.0-flash-live-001`
   trên endpoint v1alpha: server trả 1008 / "not found" với key AI Studio
   (xác nhận bằng tìm kiếm web 25/11/2026). Sửa: DEFAULT_MODEL =
   `gemini-2.5-flash-native-audio-latest`, endpoint v1beta
   (`.../v1beta.GenerativeService.BidiGenerateContent`), model dự phòng thứ 2
   `gemini-2.5-flash-native-audio-preview-12-2025`: nếu setup model 1 bị từ
   chối (1008/404/not found) thì tự thử model 2 trước khi báo lỗi.
   `GroqFallback` dịch text đổi `gemini-2.0-flash` → `gemini-2.5-flash`.
2. **Nút "Kiểm tra key" nói dối** — trước đây chỉ GET v1beta/models, key đúng
   là qua dù model Live đã chết. Sửa: nút này giờ mở WebSocket Live thật
   với model mới, chờ setupComplete trong 10s, báo "Live nối được (model …)"
   hoặc báo rõ lỗi (HTTP / 1008 / model not found); nếu model 1 bị từ chối
   thì thử model dự phòng 1 lần; chỉ khi socket không mở được mới dùng
   models-list làm bước phụ để phân biệt key hỏng (401) với model chết.
3. **Audio bị vứt khi chưa nối** — VAD chỉ gửi audio `if (liveConnected)`,
   nên mọi câu nói trong lúc Live đang nối/đổi key đều mất. Sửa: đệm tối đa
   ~10 giây audio (hàng đợi chunk, bỏ phần cũ nhất khi đầy) và gửi hết ngay
   khi onConnected.
4. **Watchdog không bao giờ chạy khi chưa có output** — điều kiện
   `lastOutputAtMs > 0` nên phiên chưa từng dịch được thì im mãi. Sửa:
   watchdog chạy cả khi chưa có output: sau 25s từ lần nối mà liveConnected
   vẫn false hoặc chưa có output thì nối lại (đổi model/key), tối đa 5 lần,
   hết 5 lần thì tự chuyển sang Groq fallback (kể cả chưa có key Groq thì
   cũng broadcast lỗi rõ ràng, không im).
5. **onError chỉ xử lý 401/403/quota** — mọi lỗi khác (1008, model not
   found, WebSocket failure, EOF, 404) chỉ Toast một lần rồi im. Sửa: BẤT KỲ
   lỗi Live nào cũng kích hoạt nối lại (đổi key/model, chống lặp bằng cờ
   reconnectPending) rồi fallback, và ghi lỗi vào dòng Trạng thái hiển thị
   lâu trên màn hình chính, không chỉ Toast.
6. **Tiếng gốc 15% làm VAD điếc** — MainActivity giảm tiếng gốc xuống 15%
   ngay khi bắt đầu, mà AudioPlaybackCapture thu tiếng SAU khi giảm âm lượng
   nên RMS tụt dưới ngưỡng VAD 420 và VAD không bao giờ kích hoạt. Sửa:
   mặc định tiếng gốc 50% (Prefs), hạ ngưỡng VAD 420 → 250, và chỉ giảm
   tiếng gốc xuống mức user chọn SAU khi giọng dịch đầu tiên đã phát
   (applyDuckOnce; cả 2 activity đều giữ tiếng gốc 100% lúc bắt đầu).
7. **Không có cách nào biết app có nghe thấy tiếng không** — thêm chẩn đoán
   trong app: (a) dòng Trạng thái trên màn hình chính, cập nhật liên tục qua
   broadcast TYPE_STATUS ("Đang nối Gemini Live…", "Đã nối, đang nghe…",
   "Đang nghe câu nói…", "Đang dịch…", "Lỗi Live: …", "Đang dùng dự phòng
   Groq", "Không nghe thấy tiếng — kiểm tra video có phát tiếng không");
   (b) thanh mức tiếng: service gửi RMS mỗi 500ms (TYPE_LEVEL 0–1000),
   MainActivity hiện thanh + số RMS; (c) lỗi cuối cùng ở lại trên màn hình
   (TextView), không chỉ Toast.
8. (Thêm khi sửa) **Xử lý message server còn thiếu** — `handleServerMessage`
   giờ đọc `error` trong message (gọi onError với code/message), đọc
   `inputTranscription` (log + callback debug), không crash khi JSON thiếu
   trường; `onFailure` kèm mã HTTP. Sửa luôn một lỗi suýt gây lỗi biên dịch:
   lúc thay block field trong DubbingService đã làm mất khai báo
   `utteranceBytes`/`pendingText`/`audioPlayedThisTurn` — đã thêm lại và
   kiểm tra tĩnh toàn bộ 12 file .kt cân ngoặc, đủ R.id/R.string, XML hợp lệ.

## Hướng dẫn user thử lại (đúng thứ tự)

1. Gỡ v1.0.1 rồi cài `DubLive-v1.0.2.apk` (cài đè cũng được, gỡ sạch rồi cài
   mới là chắc nhất).
2. Mở app → Cài đặt → bấm **"Kiểm tra key Gemini"** — lần này phải báo
   **"Live nối được (model gemini-2.5-flash-native-audio-latest)"**. Nếu báo
   lỗi khác thì chụp lại dòng lỗi đó gửi mình (đó chính là lý do im lặng
   trước đây). Bấm Lưu.
3. Mở video (web phim / YouTube / file) → bấm Play cho video chạy có tiếng.
4. Bấm "Bắt đầu lồng tiếng" (hoặc "Lồng tiếng bằng mic") → cấp quyền Micro
   → chọn "Chia sẻ toàn bộ màn hình" như trước.
5. Nhìn dòng **Trạng thái** và **thanh Mức tiếng** trên màn hình chính:
   - Mức tiếng phải nhảy > 0 khi video đang phát tiếng. Nếu mức tiếng = 0
     thì app chưa thu được tiếng video (video chưa phát tiếng / chọn sai
     phạm vi chia sẻ).
   - Trạng thái phải chạy: "Đang nối…" → "Đã nối, đang nghe…" → "Đang nghe
     câu nói…" → "Đang dịch…" rồi phụ đề Việt hiện + giọng Việt đọc.
   - Nếu Trạng thái dừng ở "Lỗi Live: …" thì chụp lại dòng đó gửi mình.
6. Báo lại 3 việc: (a) Kiểm tra key Live báo gì, (b) mức tiếng có nhảy
   không (số lớn nhất thấy được), (c) có phụ đề/giọng Việt không.

## Giới hạn còn lại

- Chưa chạy kiểm chứng trên máy thật (máy test đích: iQOO Z9 Turbo /
  OriginOS 6 / Android 16 của user) — các sửa trên mới được kiểm chứng ở
  mức biên dịch + đóng gói thành công.
- Nếu cả 2 model Live đều bị Google đổi tên trong tương lai, app sẽ tự
  chuyển sang Groq (cần key Groq trong Cài đặt) và báo rõ trên dòng
  Trạng thái; khi đó sửa tên model trong `GeminiLiveClient.kt`
  (`DEFAULT_MODEL` / `FALLBACK_MODEL`).
- Chế độ mic nghe tiếng loa ngoài nên chất lượng phụ thuộc loa + tiếng ồn
  xung quanh; tiếng gốc 50% mặc định vẫn chạy theo giờ thực.

---
# DubLive v1.0.1 — SỬA CRASH KHI CẤP QUYỀN GHI MÀN HÌNH (Android 16)

Build xong 25/11/2026 ~18:03 VN (giờ UTC 11:03). APK Debug, cài được.
- File: `~/workspace/goals/alda-v10-tvb-style-dubbing-sync/files/DubLive-v1.0.1.apk`
  (symlink `~/workspace/your_files/DubLive-v1.0.1.apk`)
- Kích thước: 9.437.426 byte (~9,1 MB). MD5: 46442a4ade70146091019f82676a4208.
- aapt2: package `com.dublive.live`, versionCode 2, versionName 1.0.1,
  minSdk 26, targetSdk 34, launcher `MainActivity`, label `DubLive`.
- Actions run thành công: **45600269889** (completed/success, lần build đầu
  tiên đã đậu, không cần build lại), commit `007a71f9a56854c53db66819e3bee186385bb636`
  trên nhánh `dublive-v1` (repo `kenj71737-afk/b8`, PR #1 vẫn mở, KHÔNG merge),
  artifact `alad-apks` (id 15014364330, bản Debug; bản Release unsigned trong
  cùng artifact thì không cài được nên không giao).

## Máy đích (đính chính: Android 16, không phải 15)

- **iQOO Z9 Turbo (bản pin dài), OriginOS 6, Android 16** (ảnh thứ 2 user gửi
  ghi rõ "Android 16"), Snapdragon 8s Gen 3, RAM 12 GB + 12 GB, bộ nhớ
  256 GB, pin 6400 mAh. Máy mạnh — crash không phải do máy yếu.
- Android 16 siết foreground service / MediaProjection và partial screen
  sharing chặt hơn cả Android 15. Code v1.0.1 kiểm chứng theo đúng luật
  Android 16 (xin quyền trước, startForegroundService bọc try/catch +
  retry, service startForeground rồi mới getMediaProjection, fallback mic,
  dặn chọn toàn màn hình).
- Lưu ý: app vẫn giữ targetSdk 34 / compileSdk 34 (không nâng lên 36, vì
  cần compileSdk 36 + AGP mới, rủi ro build). Trên Android 16 app chạy ở
  chế độ tương thích, nhưng mọi điểm siết của Android 16 nêu trên đều đã
  xử lý trong code.

## Crash v1.0.0 — diễn biến từ video user gửi (12,6 s, 17:53)

Màn hình chính DubLive (chưa mở video nào) → bấm "Bắt đầu lồng tiếng" →
hộp hệ thống "Ghi hoặc chia sẻ màn hình với DubLive", phạm vi mặc định
"Chia sẻ một ứng dụng" → bấm Tiếp tục → bảng chọn app → user chọn chính
DubLive → màn hình về Home, app biến mất, không có thông báo lỗi.

## Nguyên nhân đã sửa (đủ 6 mục, cộng thêm 2 mục phát hiện khi sửa)

1. **RECORD_AUDIO chưa bao giờ được xin runtime** — v1.0.0 chỉ khai báo
   trong Manifest, không hề `requestPermissions`. AudioPlaybackCapture bắt
   buộc phải có RECORD_AUDIO đã cấp, nếu không `AudioRecord.build` /
   `startRecording` ném `SecurityException`. Sửa: `MainActivity` và
   `LocalPlayerActivity` xin RECORD_AUDIO (+ POST_NOTIFICATIONS trên
   Android 13+) TRƯỚC khi mở hộp ghi màn hình, chỉ khi đã cấp mới mở hộp.
   Nếu user từ chối: Toast báo rõ + tự thử chế độ mic (service kiểm tra
   lại quyền, báo thiếu quyền rồi dừng sạch, không crash).
2. **`startForegroundService` không có try/catch** — trong callback của
   hộp hệ thống, Android 16 có thể chạy callback lúc activity chưa resume
   hẳn nên lệnh này ném `ForegroundServiceStartNotAllowedException` /
   `SecurityException` và crash toàn app. Sửa: bọc try/catch + **thử lại
   1 lần sau 600 ms** (lúc đó activity đã resume), vẫn lỗi thì Toast +
   chuyển sang chế độ mic. Áp dụng cho cả `MainActivity` và
   `LocalPlayerActivity`.
3. **Service bọc từng bước khởi động riêng** (`DubbingService.startDub` /
   `startCapture`): `startForeground`, `getMediaProjection`,
   `AudioPlaybackCaptureConfiguration`, `AudioRecord.Builder.build`,
   `record.startRecording` — mỗi bước một try/catch riêng. Bước nào lỗi
   cũng không để service chết: playback lỗi → fallback MIC (báo
   `TYPE_FALLBACK`), mic cũng lỗi → broadcast lỗi + `stopDub()` sạch.
   `getMediaProjection` trả null hoặc ném `SecurityException` với token
   single-app đều xử lý. Thêm `MediaProjection.Callback`: hệ thống/
   OriginOS dừng chia sẻ giữa chừng → tự chuyển sang mic thay vì chết
   theo token. Thêm cờ `stopped` để luồng chuyển mic không khởi động lại
   capture sau khi service đã dừng.
4. **Bẫy "Chia sẻ một ứng dụng"** — Android 16 mặc định phạm vi này, user
   chọn chính DubLive thì projection chỉ bắt DubLive, không có tiếng phim
   (và đó đúng là thao tác trong video crash). Không có API ép toàn màn
   hình nên thêm hộp `AlertDialog` TRƯỚC khi mở hộp hệ thống: "Ở màn hình
   tiếp theo, bấm Tiếp tục rồi chọn 'Chia sẻ toàn bộ màn hình'. Đừng chọn
   'Chia sẻ một ứng dụng'". Chỉ hiện 1 lần, ghi nhớ bằng
   `Prefs.shareTipShown`.
5. **Chế độ mic dự phòng** — nút mới "Lồng tiếng bằng mic (không cần ghi
   màn hình)" trên màn hình chính và trên màn hình file local, chạy
   `DubbingService` với `ACTION_START_MIC` (không cần token
   MediaProjection, capture MIC trực tiếp). Ghi rõ trong app: chế độ mic
   nghe tiếng loa ngoài, để loa đủ to, tránh chỗ ồn.
6. **Các chỗ khác dễ crash khi service khởi động** — `VoicePlayer` init
   (`TextToSpeech`, `AudioTrack`) và `GeminiLiveClient.connect` /
   `sendAudio` / `endTurn` đều bọc try/catch, lỗi thì broadcast lỗi chứ
   không chết service. `getParcelableExtra` dùng bản type-safe trên
   Android 13+. `Prefs.geminiKeys` và quyền RECORD_AUDIO được kiểm tra
   trong service sau `startForeground` (bắt buộc phải startForeground
   trước, nếu không hệ thống kill app vì `startForegroundService` không
   kèm `startForeground`); thiếu key/quyền thì broadcast cả `TYPE_ERROR`
   lẫn `STATE_STOPPED` rồi `stopSelf()` sạch (phát hiện thêm khi sửa:
   không broadcast `STATE_STOPPED` thì nút Bắt đầu trên MainActivity bị
   kẹt ở `dubbing=true`).
7. (Thêm) **`LocalPlayerActivity` sửa y hệt MainActivity** — file này có
   cùng đường crash (projection callback không bọc, không xin quyền).
8. (Thêm) **`stopDub()` / `onDestroy()` bọc try/catch toàn bộ** —
   `stopForeground` / `join` / `unregisterCallback` đều có thể ném khi
   service chưa foreground hẳn trên Android 16.

## Hướng dẫn user thử lại (đúng thứ tự)

1. Gỡ bản v1.0.0 rồi cài `DubLive-v1.0.1.apk` (cùng package nên cài đè
   cũng được, nhưng gỡ sạch rồi cài mới là chắc nhất).
2. Mở app → Cài đặt → kiểm tra key Gemini còn đó không (nếu mất thì nhập
   lại) → quay ra màn hình chính.
3. Bấm "Bắt đầu lồng tiếng" → **cấp quyền Micro** (và Thông báo nếu máy
   hỏi) khi hộp quyền hiện.
4. Hộp hướng dẫn hiện: đọc rồi bấm "Đã hiểu, tiếp tục".
5. Hộp hệ thống "Ghi hoặc chia sẻ màn hình": bấm vào "Phạm vi ghi hoặc
   chia sẻ" → chọn **"Chia sẻ toàn bộ màn hình"** (KHÔNG chọn "Chia sẻ
   một ứng dụng", KHÔNG chọn app DubLive) → bấm Tiếp tục.
6. App phải ở lại màn hình DubLive, HUD hiện "Trễ hình: …", không thoát
   về Home. Mở video rồi bấm Play, xem phụ đề/giọng có chạy không.
7. Nếu bước 5 vẫn thoát app: mở lại app, bấm **"Lồng tiếng bằng mic
   (không cần ghi màn hình)"** thay cho "Bắt đầu lồng tiếng", để loa đủ
   to — đây là đường thoát khi OriginOS chặn ghi màn hình. Báo lại kết
   quả từng bước (nhất là bước 3 và 5) để sửa tiếp nếu cần.

## Giới hạn còn lại

- Chưa chạy kiểm chứng trên máy thật (máy test đích là iQOO Z9 Turbo /
  OriginOS 6 / Android 16 của user) — các sửa trên mới được kiểm chứng ở
  mức biên dịch + đóng gói thành công.
- Chế độ mic nghe tiếng loa ngoài nên chất lượng phụ thuộc loa + tiếng ồn
  xung quanh; tiếng gốc 15% vẫn chạy theo giờ thực.
- Lỗi tải artifact lúc đầu: dùng nhầm lệnh `gh.py download` (không auth,
  401) — lệnh đúng là `gh.py download-artifact <id> <file>`.

---

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
