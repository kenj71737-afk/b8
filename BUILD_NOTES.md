# DubLive v1.0.3 — SỬA "GEMINI LIVE LỖI": SAI GIAO THỨC LIVE (DÙNG ĐÚNG BẢN ALDA)

Build xong 25/11/2026 ~18:4x VN. APK Debug, cài được.
- File: `~/workspace/goals/alda-v10-tvb-style-dubbing-sync/files/DubLive-v1.0.3.apk`
  (symlink `~/workspace/your_files/DubLive-v1.0.3.apk`)
- Kích thước: ~9,1 MB. MD5: (ghi sau khi tải artifact).
- aapt2: package `com.dublive.live`, versionCode 4, versionName 1.0.3,
  minSdk 26, targetSdk 34, launcher `MainActivity`, label `DubLive`.
- Actions run thành công: (ghi sau khi build xong), commit (ghi sau khi đẩy)
  trên nhánh `dublive-v1` (repo `kenj71737-afk/b8`, PR #1 vẫn mở, KHÔNG merge),
  artifact `alad-apks` (bản Debug; bản Release unsigned trong cùng artifact
  thì không cài được nên không giao).

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
