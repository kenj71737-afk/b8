# DubLive v1.0.12 — SỬA CRASH FGS mediaProjection KHI BẤM "Bắt đầu lồng tiếng" (TAP + MIC)

Build xong 26/11/2026 (03:43 user báo crash v1.0.11 trên NetShort tập 4, Mức tiếng 0).
APK Debug, cài được.
- File: `~/workspace/goals/alda-v10-tvb-style-dubbing-sync/files/DubLive-v1.0.12.apk`
  (symlink `~/workspace/your_files/DubLive-v1.0.12.apk`)
- Kích thước: 9.519.130 bytes (9,1 MB). MD5: 5a2cb4a60222618f7a9c3b12b56ee044.
- aapt2: package `com.dublive.live`, versionCode 13, versionName 1.0.12,
  minSdk 26, targetSdk 34, launcher `MainActivity`, label `DubLive`.
  Dex có đủ: `foregroundTypeForMode`, `enterForeground`,
  `upgradeForegroundToProjection`, chuỗi `media_volume_hint`, và hotfix
  v1.0.11 còn nguyên (field `deferredTooSmall`, chuỗi chẩn đoán
  "bỏ %d lát/%d mẫu (nhỏ %d hoãn/lớn %d)").
- Actions run thành công: **45698132626** (completed/success), commit
  `fcdd0ece8562155f2ae0579ac683cf6933850842` trên nhánh `dublive-v1`
  (repo `kenj71737-afk/b8`, PR #1 vẫn mở, KHÔNG merge), artifact
  `alad-apks` (id 15061184659, bản Debug). Build 1 lần là đậu, không tốn lượt retry.

## Nguyên nhân (ảnh user 03:42 26/11)

"Lỗi: Không mở được dịch vụ nền: Starting FGS with type mediaProjection
... targetSDK=34 requires permissions: all of the permissions allOf=true
[android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION] any of the
permissions allOf=false [android.permission.CAPTURE_VIDEO_OUTPUT,
android:project_media]". Manifest đã đúng (FOREGROUND_SERVICE_MEDIA_PROJECTION
+ service foregroundServiceType="mediaProjection|microphone"). Lỗi là code:
`DubbingService.startDub()` gọi `startForeground(..., MEDIA_PROJECTION |
MICROPHONE)` VÔ ĐIỀU KIỆN cho MỌI mode. Từ Android 14, FGS kiểu
mediaProjection chỉ được phép khi app đang giữ token MediaProjection
(android:project_media — chỉ có sau khi user bấm cho phép ở hộp thoại ghi
màn hình). Tap mode (không mở hộp thoại theo thiết kế) và mic mode đều
không có token nên hệ thống từ chối ngay, service chết, Mức tiếng 0.
V1.0.10 không lỗi vì đường chính luôn qua hộp thoại ghi màn hình trước.

## Cách sửa (chỉ 2 file service + activity, giữ nguyên toàn bộ v1.0.11)

1. `DubbingService.kt` — chọn kiểu FGS theo audioMode: chỉ CAPTURE (khởi
   động từ kết quả hộp thoại, token trong tay, `resultData != null`) mới
   dùng MEDIA_PROJECTION|MICROPHONE, và vẫn startForeground TRƯỚC
   getMediaProjection() như cũ; TAP_WEB/TAP_LOCAL/MIC chỉ dùng
   FOREGROUND_SERVICE_TYPE_MICROPHONE (RECORD_AUDIO đã xin trước,
   manifest đã khai microphone). Hàm mới: `foregroundTypeForMode()`,
   `enterForeground()`. Tuyệt đối không truyền MEDIA_PROJECTION khi chưa có token.
2. `DubbingService.kt` — đường fallback tap→capture (`transitionToCapture`,
   service đã foreground kiểu MICROPHONE): gọi lại `startForeground` với
   MEDIA_PROJECTION|MICROPHONE (`upgradeForegroundToProjection()`) NGAY
   KHI nhận token và TRƯỚC getMediaProjection(); nếu nâng kiểu bị từ chối
   thì fallback sang mic thay vì chết. Đường LocalPlayerActivity (local tap)
   đi qua cùng logic này (ACTION_START_LOCAL_TAP → TAP_LOCAL, fallback
   qua ACTION_START → transitionToCapture).
3. Dòng chú thích âm lượng dưới màn hình (MainActivity): trước đây là text
   tĩnh "Giọng dịch dùng âm lượng cuộc gọi..." kể cả khi đang tap (media).
   Giờ động theo route thật (`updateVolumeHint()`): tap → chuỗi mới
   `media_volume_hint` (nút âm lượng chỉnh chung cả hai, kéo thanh trượt
   trong Cài đặt để đổi tỉ lệ); capture/mic → `call_volume_hint` như cũ.
   Cập nhật cả khi bấm Bắt đầu (tap/media, mic/call), khi service báo
   đổi route (TYPE_AUDIO_ROUTE), khi dừng (về mặc định).
4. Không đổi gì khác: tap WebAudio + fallback, hotfix đo lát cắt, không
   đường báo thức, slice measurement giữ nguyên.

## Checklist thử lại (đưa user)

Bấm "Bắt đầu lồng tiếng" trên NetShort tập 4 phải chạy ngay không lỗi,
Mức tiếng > 0, trạng thái hiện đúng route ("Âm thanh: phương tiện" nếu
tap sống, "Âm thanh: cuộc gọi (trang chặn lấy tiếng)" nếu fallback);
thử cả nút mic (cũng từng crash y hệt); xem 2–3 phút rồi chụp dòng chẩn
đoán: "N mẫu/60 s" tăng, D đổi mỗi ~4 s.

# DubLive v1.0.11 — GIỌNG DỊCH + TIẾNG GỐC QUA ÂM THANH PHƯƠNG TIỆN (TAP TRONG TRANG)

Build xong 25/11/2026 (rebuild cùng version sau hotfix đo lát cắt).
APK Debug, cài được.
- File: `~/workspace/goals/alda-v10-tvb-style-dubbing-sync/files/DubLive-v1.0.11.apk`
  (symlink `~/workspace/your_files/DubLive-v1.0.11.apk`)
- Kích thước: 9.517.706 bytes (9,1 MB). MD5: a0586b00d6ba39a9c9bd84f044190ccb.
- aapt2: package `com.dublive.live`, versionCode 12, versionName 1.0.11,
  minSdk 26, targetSdk 34, launcher `MainActivity`, label `DubLive`.
  Các class mới có trong dex: TapJs, TapAudioQueue, LocalTapProcessor,
  TapRenderersFactory; chuỗi `settings_dub_volume_note` mới đã vào APK;
  1 tham chiếu `USAGE_ALARM` còn lại trong dex thuộc thư viện media3,
  không phải code DubLive (bản v1.0.11 không dùng báo thức).
  Hotfix đã vào dex: field `deferredTooSmall` có mặt, field cũ
  `droppedTooSmall` đã mất, chuỗi chẩn đoán mới
  "bỏ %d lát/%d mẫu (nhỏ %d hoãn/lớn %d)" có trong classes3.dex,
  chuỗi cũ đã mất.
- Actions run thành công: **45650625648** (completed/success), commit
  `d37b8187b703220e13f0379d610f00dcd6b33de9` trên nhánh `dublive-v1`
  (repo `kenj71737-afk/b8`, PR #1 vẫn mở, KHÔNG merge), artifact
  `alad-apks` (id 15039618742, bản Debug). Build đầu cùng version
  (run 45646438468, commit 53568b30, MD5 a77771424f74e67d1bfb5b91c575f78f)
  đã bị thay bằng bản rebuild này — cùng versionCode 12 /
  versionName 1.0.11, KHÔNG tách bản riêng.

## Hotfix trong cùng bản 1.0.11 — sửa đo lát cắt (ảnh 22:35)

User xem NetShort "hồng diện sát" tập 4 trên v1.0.10, Live nối tốt,
phụ đề Việt vẫn hiện, giọng dịch vẫn chạy, nhưng dòng chẩn đoán:
"D 4,3 s · mẫu cuối 1,8 s (67 s trước) · 0 mẫu/60 s · chờ 0 lát ·
hold 2,0 s · VAD: đang nói · bỏ 0 lát/133 mẫu". App CÓ đo (133 mẫu
trong ~67 s, đúng nhịp ~2/s) nhưng TẤT CẢ bị loại, D kẹt ở 4,3 s.

Đã kiểm tra bằng cách đọc code, KHÔNG đoán mò — 2 nghi vấn chính đều
BỊ LOẠI: (a) khác đồng hồ/đơn vị: KHÔNG — hai bên đều dùng
System.currentTimeMillis() (ms), thoại ms = samples/16 (16 kHz mono),
giọng ms = bytes/48 (24 kHz mono); (b) ghép sai đầu (LIFO): KHÔNG —
ghép lấy lát CŨ NHẤT (FIFO).

Lỗi thật: hàng đợi lát là một bộ tích phân mong manh, độ sâu =
(thoại đếm được − giọng đếm được)/500 + backlog trễ ban đầu, mà hai
bên KHÔNG BAO GIỜ đếm cùng một lượng tiếng — nên nó trôi về 0 hoặc
về trần, theo cả hai hướng:
- TRÔI XUỐNG (cạn → đúng ảnh 22:35): đầu câu 160 ms gửi cho Live
  không được đếm; giọng cứ lấy lát qua khoảng lặng nguồn trong khi
  thoại ngừng đẩy; bản dịch dài hơn câu gốc thì giọng lấy NHIỀU hơn
  thoại đẩy. Về 0 rồi, code cũ vẫn lấy lát vừa đẩy, được mẫu <500 ms,
  loại nó VÀ nuốt luôn lát đó — độ sâu không bao giờ hồi được: xoắn
  chết, D đóng băng. Xoay key mỗi 5 phút xóa hàng đợi nên cũng rơi
  vào trạng thái này.
- TRÔI LÊN (phình → D chạy mất): v1.0.10 đếm MỌI frame trong lúc nói,
  kể cả đuôi im lặng 700 ms của VAD sau mỗi câu — frame dưới ngưỡng
  END, KHÔNG BAO GIỜ gửi cho Live, KHÔNG có giọng dịch. Đếm thừa
  ~700 ms/câu nên với bản dịch dài bằng câu gốc, hàng đợi phình,
  mẫu phình, D chạy mất (mô phỏng: D 5,0 → 11,2 s).

Đã sửa (3 chỗ, cùng bản 1.0.11):
1. `DubDelayTracker.kt` — NHÌN TRƯỚC KHI LẤY (peek-before-pop):
   lát cũ nhất còn quá mới (<500 ms) thì ĐỂ LẠI trong hàng đợi, giữ
   nguyên credit giọng, không lấy — nó già đi rồi ghép sau, hàng đợi
   hồi lại; chỉ lát quá cũ (>20 s) mới lấy-bỏ. Lần ghép quá mới là
   HOÃN (deferral), không phải hủy: đếm riêng bằng `deferredTooSmall`,
   KHÔNG tính vào `droppedSamples` (đếm hoãn thành "bỏ" làm bản đang
   hồi phục báo "bỏ 900 mẫu" trong khi mẫu hợp lệ vẫn chảy).
2. `DubbingService.kt` `onSpeechStart` — đếm đầu câu 160 ms vào
   tracker (8 frame kích hoạt VAD, đã gửi cho Live, có giọng dịch;
   pre-roll im lặng trước đó không đếm vì không có giọng).
3. `DubbingService.kt` `onSpeechChunk` — CỔNG ĐUÔI (tail gate): chỉ
   đếm frame nào có rms >= vadEndThreshold — ĐÚNG cái gửi cho Live
   (cùng một cổng, tính rms một lần dùng chung cho cả hai). Âm tiết
   nhỏ giữa ngưỡng END và START vẫn gửi và vẫn đếm (v1.0.8); đuôi im
   lặng 700 ms không gửi, không đếm. Cùng với (2), thoại đếm được =
   đầu câu + frame to = đúng thứ Live nghe mà ra giọng dịch.
4. Dòng chẩn đoán thêm lý do loại mẫu: "bỏ %d lát/%d mẫu
   (nhỏ %d hoãn/lớn %d)" — "nhỏ … hoãn" = lần ghép hoãn vì lát quá
   mới (lát còn sống, ghép sau — KHÔNG nằm trong số "bỏ … mẫu");
   "lớn" = mẫu HỦY vì lát quá cũ (>20 s, ĐÚNG bằng số "bỏ … mẫu").
   Ảnh sau: 0 mẫu hợp lệ + "nhỏ" tăng = ghép đói (lỗi 22:35);
   "lớn" tăng = hàng đợi cũ / giọng ngắn hơn câu gốc.

Kiểm chứng bằng mô phỏng (`/tmp/dublive_1011_slice_sim.py` v2 — port
trung thực DubDelayTracker + LatencyEma + AudioBurstDetector, mô hình
VAD đúng: gộp khoảng đang-nói, 1 đầu câu/utterance, frame to/im,
cổng đuôi; câu 4 s, nghỉ 1 s, trễ thật L = 2,0 s, D hạt giống 5,0 s):
- A. Code CŨ, giọng dài gấp 1,3 lần gốc: TÁI HIỆN ĐÚNG ảnh user —
  0 mẫu/60 s, chờ 0 lát, VAD đang nói, bỏ 0 lát/196 mẫu (user: 133),
  mẫu cuối 0,5 s cách đây 67 s, D kẹt 3,0 s.
- B. Code MỚI, cùng trường hợp: 96 mẫu/60 s, mẫu cuối 0 s trước,
  D sống, bỏ 0 lát/0 mẫu (nhỏ 708 hoãn — hoãn, không phải hủy).
- C. Code MỚI, thoại liên tục không nghỉ, giọng = gốc (đúng tiêu chí
  nghiệm thu của task): D 3,9 s (≈ L+2 s), mẫu 1,9 s (≈ L), 120 mẫu/
  60 s (tối đa 2/s), hàng đợi ổn định 4 lát, bỏ 0, hoãn 0.
- D. Code MỚI, giọng = gốc, có nghỉ (trường hợp trôi lên): D 3,5 s
  (code CŨ là 11,3 s), mẫu 1,9 s, bỏ 0 — cổng đuôi sửa được hướng
  phình.
- E. Code MỚI + xoay key (xóa hàng đợi) ở 60 s: hồi phục, mẫu vẫn
  chảy, D nhích 3,0 → 3,2 s.
- F. Code CŨ, giọng = gốc (đối chứng): D chạy 3,0 → 11,3 s, mẫu
  phình tới 15,6 s, hàng đợi 27 lát.

Giới hạn còn biết: hướng cạn (bản dịch dài hơn gốc), mẫu lát kẹt ở
mức sàn 500 ms và D ngồi ở mức tối thiểu 3,0 s — vẫn ≥ L nên hình +
giọng vẫn khớp (cả hai đều theo D), và mẫu burst-start neo D nếu L
tăng. Bản dịch ngắn hơn gốc hẳn một cách ổn định vẫn làm hàng đợi
phình chậm — đã chặn bởi MAX_SLICES (40) + bộ đếm "lớn" sẽ hiện rõ
trên ảnh chẩn đoán nếu xảy ra.

## Bối cảnh

User yêu cầu 21:55–21:56 sau khi thử v1.0.10: "Qua âm thanh máy không
được à" → "Âm thanh phương tiện". Đường cuộc gọi (VOICE_COMMUNICATION)
quá nhỏ dù đã có LoudnessEnhancer +8 dB. Đã hứa với user: trang nào
cho phép thì dùng media, trang chặn (NetShort) thì quay về đường cuộc
gọi.

## Vì sao không đổi thẳng sang MEDIA được

App lấy tiếng phim bằng system capture (AudioPlaybackCapture khớp
USAGE_MEDIA/GAME). Nếu giọng dịch cũng phát qua USAGE_MEDIA thì
capture sẽ thu lại chính giọng dịch rồi dịch tiếp thành tiếng vọng.
Cách ALDA giải quyết (đã port đúng): lấy tiếng THẲNG TỪ TRONG TRANG
(WebAudio tap) thay vì thu hệ thống — không chạy capture nữa thì giọng
dịch phát media thoải mái, không vọng.

## Đã làm — chế độ TAP làm đường chính, capture chỉ là fallback

- `TapJs.kt` (mới): inject vào trang khi bắt đầu lồng tiếng. Tìm thẻ
  video đang phát, tạo AudioContext + MediaElementSource, rẽ 2 nhánh:
  (a) DelayNode(delay = D hiện tại, tối đa 22 s) → destination: tiếng
  gốc trễ đúng D, phát qua đường media; (b) ScriptProcessorNode
  (2048,1,1) → gain im → destination: PCM tap, resample trong JS
  xuống 16 kHz mono PCM16, gửi base64 về service qua
  JavascriptInterface `DubLiveAudio.onPcm` làm ĐẦU VÀO dịch (live,
  không trễ, không qua capture). Chỉ gửi block không im (RMS ≥
  0.00008, cùng ngưỡng ALDA). Duck điều khiển gain SAU DelayNode qua
  `DubLiveTapSetDuck(vol)` (giữ nhịp duck cũ: hạ khi giọng nói, trả
  100% sau 1,5 s giọng im). `DubLiveTapSetDelay(giây)` chỉnh DelayNode
  live — CHÚ Ý: nhận GIÂY, không phải mili-giây (canvas
  `DubLiveSetDelay` nhận ms; đã sửa 2 lần nhầm đơn vị trong lúc code).
  `DubLiveTapStop()` bypass delay (source → destination trực tiếp),
  giữ AudioContext sống (đóng nó thì đường tiếng của thẻ không chắc);
  `DubLiveTapResume()` nối lại graph.
- CORS/DRM (ALDA FIX42): video cross-origin mà thẻ KHÔNG có thuộc
  tính `crossOrigin` thì bị taint — tạo graph sẽ làm nó im vĩnh viễn.
  Video như vậy BỎ QUA HẲN (không tạo graph), `onTapBlocked` bắn,
  service fallback sang capture. Cùng-origin và
  `crossOrigin='anonymous'` gắn bình thường.
- Probe tap chết: 2,2 s sau khi AudioContext RUNNING mà 0 block không
  im trong khi video đang chạy → `onTapFailed` → fallback. Probe không
  bao giờ chạy khi context suspended (autoplay không gesture sẽ thành
  dương tính giả); resume khi play/playing/click/touchstart. Video mới
  được gắn mới + probe mới qua MutationObserver + quét 1,2 s.
- `TapAudioQueue.kt` (mới): hàng đợi PCM trong tiến trình, 2 loại
  MONO16K (tap web) / STEREO48K (tap local), tối đa 64 chunk, offer
  không chặn (JS bridge và audio thread ExoPlayer không bao giờ đợi
  service); đầy thì bỏ chunk cũ nhất.
- `DubbingService.kt`: chế độ âm thanh AudioMode TAP_WEB / TAP_LOCAL /
  CAPTURE / MIC; `createVad()` tách ra khỏi `startCapture()` (VAD +
  listener dùng chung cho capture thread VÀ tap feeder thread);
  `setupMediaProjection()` tách ra (dùng chung cho start thường và
  transition fallback); tap feeder thread rút `TapAudioQueue` vào VAD
  (mono thẳng vào VAD; stereo qua background player trước rồi downmix
  mono 16 kHz cho VAD/Live); `startDelayedBackgroundLocal()` (MEDIA,
  không enhancer, KHÔNG mute STREAM_MUSIC); `transitionToCapture()`
  chuyển tại chỗ khi có token fallback (giữ Live + D, chỉ đổi đường
  vào/ra); `fallbackToMic()` khi user từ chối dialog hoặc hết 90 s
  không có token; capture builders nhận `gameOnly` (local fallback chỉ
  khớp GAME); watchdog: local đang phát mà 10 s không có PCM → fallback.
  Route broadcast TYPE_AUDIO_ROUTE ("Âm thanh: phương tiện" /
  "Âm thanh: cuộc gọi (trang chặn lấy tiếng)" / "Âm thanh: micro").
- `MainActivity.kt`: `DubLiveAudioBridge` (JavascriptInterface:
  onPcm/onTapAlive/onTapFailed/onTapBlocked/onTapStatus); start
  tap-first (ACTION_START_TAP, KHÔNG hiện dialog ghi màn hình);
  `pendingTapStart` → `DubLiveTapResume()` khi service báo running;
  TYPE_NEED_PROJECTION → `showShareTipThenProjection()` (fallback);
  dialog OK → ACTION_START + EXTRA_FALLBACK + EXTRA_SOURCE=web +
  `DubLiveTapStop()`; từ chối → ACTION_FALLBACK_MIC; TYPE_HUD đẩy D
  sang cả canvas VÀ tap; `setOriginalVolume()` đi tap gain khi tap
  mode, canvas khi capture; toggleDelay / fullscreen trang web cũng
  chỉnh tap delay 0 / khôi phục D; onPageFinished inject TapJs + resume
  tap nếu đang lồng tiếng tap mode (user mở URL SAU khi bấm lồng tiếng
  trên trang trắng); stop → `DubLiveTapStop()` + xóa tapMode; trạng
  thái hiện kèm nhãn đường âm thanh.
- `LocalPlayerActivity.kt` + `LocalTapProcessor.kt` (mới): ExoPlayer
  build với `DefaultRenderersFactory.setAudioProcessors([tapProcessor])`
  + audio attributes USAGE_GAME/CONTENT_TYPE_MOVIE; processor chuẩn
  hóa về 48 kHz stereo PCM16, copy vào queue khi enabled, pass-through
  cho non-PCM16; start tap-first (ACTION_START_LOCAL_TAP, KHÔNG
  dialog), `player.volume = 0f` (im loa), `onPcm` nối vào
  `TapAudioQueue.offerStereo48k`; Player.Listener gửi
  ACTION_LOCAL_PLAYING (watchdog 10 s); fallback: dialog OK → capture
  GAME-only (player.volume = 1.0f TRƯỚC khi capture chạy, không thì
  capture thu được im — bài học v1.0.2), từ chối → mic; TYPE_AUDIO_ROUTE
  "call"/"mic" khôi phục volume + tắt processor.
- `SettingsActivity.kt`: thanh trượt giọng dịch gửi
  ACTION_APPLY_DUB_VOLUME cho service thay vì gọi
  `VoicePlayer.applyCallVolume` trực tiếp (kéo giữa phiên chạm đúng
  đường đang phát); cập nhật ghi chú `settings_dub_volume_note` (giọng
  + tiếng gốc cùng 1 đường: media khi tap được, cuộc gọi khi fallback).
- `VoicePlayer.kt`: `initialUsage` (MEDIA ở tap mode, call ở
  capture/mic), `setOutputUsage()` build lại AudioTrack live giữ
  nguyên hold queue, LoudnessEnhancer chỉ ở đường cuộc gọi,
  `applyMediaVolume()` (STREAM_MUSIC) bên cạnh `applyCallVolume()`.
- `DelayedBackgroundPlayer.kt`: `start(usage, useEnhancer)` — enhancer
  chỉ khi `useEnhancer` (MEDIA không cần).

## LỆCH KHỎI BRIEF — file local (ghi rõ lý do)

Brief nói: ExoPlayer USAGE_GAME + capture chỉ khớp GAME + giọng dịch
+ tiếng gốc trễ qua USAGE_MEDIA. Viết như vậy KHÔNG chạy được: mute
STREAM_MUSIC để im tiếng trực tiếp của player cũng im luôn giọng dịch
+ tiếng gốc trễ trên MEDIA (USAGE_GAME map về STREAM_MUSIC), và
`player.volume = 0` áp dụng ở AudioTrack TRƯỚC điểm capture tap nên
capture sẽ im (bài học v1.0.2). Nên file local KHÔNG dùng capture:
`LocalTapProcessor` tap PCM ra khỏi pipeline của player TRƯỚC track
volume, trong khi `player.volume = 0` im loa. Pipeline nhận PCM đủ
âm lượng, loa im, giọng dịch phát MEDIA, không gì bị thu lại. Khớp
GAME-only vẫn được implement (cờ `gameOnly` ở capture builders) và
dùng ở đường fallback capture của local nếu tap processor chết.

## Giữ nguyên từ v1.0.10

Đo trễ lát cắt 500 ms + trung vị mỗi cửa sổ 4 s + dòng chẩn đoán HUD,
hold giọng tới (câu+D), nút ±1s, VAD hysteresis + cổng phổ,
LoudnessEnhancer ở đường cuộc gọi, KHÔNG dùng báo thức ở bất cứ đâu.
D hiện tại vừa chỉnh DelayNode (tap) vừa chỉnh DelayedBackgroundPlayer
(fallback).

## Kiểm tra logic trước khi build (đã rà tay, chưa có máy thật)

- Build lần 1 (run 45645599090, commit 94fd3102) LỖI BIÊN DỊCH — 3 lỗi,
  đã sửa và đẩy lại:
  * `createVad()` trích từ `startCapture()` bị mất dấu xuống dòng
    giữa `})` và `vad = detector` (script nối chuỗi không chèn `\n`),
    làm 4 lỗi ở DubbingService.
  * media3 1.3.1 KHÔNG có `DefaultRenderersFactory.setAudioProcessors`
    (có từ media3 mới hơn) và KHÔNG có top-level
    `androidx.media3.common.audio.AudioFormat` (có từ 1.4.0) — đã xác
    minh bằng sources jar trên maven.google.com. Sửa: `LocalTapProcessor`
    dùng `AudioProcessor.AudioFormat` lồng trong (constructor
    `(48000, 2, ENCODING_PCM_16BIT)`, không Builder); processor được
    gắn qua `TapRenderersFactory` override `buildAudioSink()` +
    `DefaultAudioSink.Builder.setAudioProcessors()` (1.3.1 có).

- Tap sống → không có tiến trình capture nào chạy (không vọng), giọng
  media to rõ, bấm nút âm lượng hiện đúng "Phương tiện"; D đẩy sang
  DelayNode theo GIÂY (đã sửa lỗi nhầm ms→20 s); `onPcm` của local đã
  nối vào queue (lỗi bỏ sót đã sửa trước build).
- Tap chết (NetShort CORS/DRM) → tự fallback: trạng thái đổi chữ,
  giọng vẫn dịch qua đường cuộc gọi; user từ chối dialog → mic.
- File local → media, không vọng; tắt lồng tiếng thì tiếng video trực
  tiếp trở lại bình thường (player.volume = 1.0f, tap bypass).

## Thử lại trên máy thật (checklist cho user)

- Xem YouTube / web phim cho phép: dòng trạng thái hiện "Âm thanh:
  phương tiện", bấm nút âm lượng thân máy hiện đúng đường media, giọng
  dịch to rõ, không vọng (không nghe lại giọng dịch sau 1 câu).
- Xem NetShort (trang chặn lấy tiếng): tự hiện dialog ghi màn hình
  rồi fallback "Âm thanh: cuộc gọi (trang chặn lấy tiếng)", giọng vẫn
  dịch bình thường; từ chối dialog → micro.
- Mở file local: media, không vọng; tiếng gốc trễ khớp hình.
- Tắt lồng tiếng: tiếng video trực tiếp trở lại bình thường ngay.

---

# DubLive v1.0.10 — ĐO TRỄ LIÊN TỤC THEO ĐOẠN ÂM THANH GỐC + BỎ HẲN ĐƯỜNG BÁO THỨC

Build xong 25/11/2026 22:03 VN. APK Debug, cài được.
- File: `~/workspace/goals/alda-v10-tvb-style-dubbing-sync/files/DubLive-v1.0.10.apk`
  (symlink `~/workspace/your_files/DubLive-v1.0.10.apk`)
- Kích thước: 9.498.826 bytes (9,1 MB). MD5: 27de25eaa809dcb5ea63dca002084024.
- aapt2: package `com.dublive.live`, versionCode 11, versionName 1.0.10,
  minSdk 26, targetSdk 34, launcher `MainActivity`, label `DubLive`.
  Resource `id/diagView` có trong APK; chuỗi "Giọng dịch phát qua" /
  `dub_output` đã biến mất; 2 tham chiếu `USAGE_ALARM` còn lại trong
  dex đều thuộc thư viện media3/ExoPlayer, không phải code DubLive.
- Actions run thành công: **45639798218** (completed/success, đậu ngay lần
  đầu), commit `a2b89e2cf534b7245bd2777a49996e48f40dcd66` trên nhánh
  `dublive-v1` (repo `kenj71737-afk/b8`, PR #1 vẫn mở, KHÔNG merge),
  artifact `alad-apks` (id 15034828711, bản Debug).

## Bối cảnh

User báo 21:51 sau khi thử v1.0.9: "Chưa khớp với hình. Độ trễ hình
không tự động cập nhật theo đoạn âm thanh gốc". Nguyên nhân đã xác
định: đo trễ kiểu ALDA ở v1.0.9 (burst-start sampling) cần 2 điều kiện
— (a) giọng dịch có khoảng im ≥500 ms để mở burst mới, (b) bắt đầu
câu gốc còn mới (<20 s). Thoại Quảng Đông liên tục + giọng dịch
stream liên tục thì không có khoảng im nào và một lượt nói VAD kéo
dài nhiều phút, nên không có mẫu hợp lệ nào → D đứng yên. Chính ghi
chú FIX39/FIX43 của ALDA cũng thừa nhận cái bẫy này (delay đóng
băng ở seed khi không có burst). Đo theo sự kiện (burst/câu) không
bao giờ ổn với thoại liên tục — phải đo LIÊN TỤC theo từng đoạn âm
thanh gốc, không chờ ranh giới.

User yêu cầu thêm 21:54: "Không dùng qua báo thức nhé" — bỏ hẳn mọi
dùng đường báo thức (USAGE_ALARM / STREAM_ALARM) trong app.

## Đã sửa — đo trễ liên tục (giữ nguyên máy đồng bộ v1.0.9)

- `DubDelayTracker` viết lại (thay burst-start-only sampling, giữ
  LatencyEma + AudioBurstDetector làm nguồn phụ):
  * Phía thoại: mỗi 500 ms audio trong lượt nói (VAD onSpeechChunk)
    đẩy mốc thời gian vào hàng đợi FIFO, tối đa 40 mốc (20 s); đầy
    thì bỏ mốc cũ nhất và đếm (KHÔNG xóa sạch hàng đợi như
    v1.0.7/v1.0.8 — xóa sạch làm mất ghép cặp mỗi lần có khoảng nhạc).
  * Phía giọng: mỗi 500 ms giọng dịch TỚI (arrival, KHÔNG phải lúc
    phát — đo lúc phát thì hold làm mẫu tự khẳng định) lấy mốc CŨ
    NHẤT, mẫu = lúc tới − mốc thoại. Mẫu trong [500 ms, 20000 ms]
    mới nhận; mẫu ngoài khoảng BỎ ĐÚNG MẪU ĐÓ và tiếp tục. Giọng tới
    mà hàng đợi thoại rỗng thì không sinh mẫu sai.
  * Tổng hợp theo ĐOẠN ÂM THANH GỐC: gom mẫu trong cửa sổ 4 s theo
    đồng hồ tường (không chờ ranh giới câu), hết 4 s lấy TRUNG VỊ →
    LatencyEma (EMA 0.3 + 2000 ms biên, kẹp 3–20 s, deadband 500 ms)
    → D. D cập nhật tối đa mỗi 4 s, đúng 1 lần mỗi đoạn 4 s của âm
    thanh gốc.
  * Mẫu burst-start kiểu ALDA giữ làm nguồn phụ: burst start nào có
    bắt đầu câu gốc còn mới (<20 s) thì vào EMA ngay, không chờ cửa
    sổ 4 s.
  * Ngừng đo khi không có lát thoại mới >10 s (im lặng/nhạc): xóa
    hàng đợi + cửa sổ, không phát mẫu cũ; đo lại khi thoại trở lại.
- Giữ NGUYÊN từ v1.0.9: giọng dịch giữ lại tới (câu gốc + D) theo
  burst qua DelayedAudioQueue, tiếng gốc trễ theo hình qua
  DelayedBackgroundPlayer, mute STREAM_MUSIC hệ thống (khôi phục khi
  dừng), phụ đề hiện lúc giọng bắt đầu phát, nút ±1s, thanh trượt
  trễ cơ bản, VAD hysteresis, cổng phổ, LoudnessEnhancer.
- CHẨN ĐOÁN TRÊN MÀN HÌNH (mới): dòng nhỏ dưới số "Trễ hình" (cùng
  nội dung trong dialog "Xem lỗi Live"), cập nhật mỗi giây: D hiện
  tại, mẫu trễ cuối + cách đây bao nhiêu giây, số mẫu trong 60 s
  qua, số lát thoại đang chờ, hold giọng hiện tại, trạng thái VAD
  (đang nói / nghỉ), số lát/mẫu đã bỏ (nếu có).

## Đã sửa — bỏ hẳn đường báo thức (user 21:54)

- `DelayedBackgroundPlayer`: USAGE_ALARM → USAGE_VOICE_COMMUNICATION
  (cùng đường với giọng dịch). Capture chỉ bắt USAGE_MEDIA/GAME nên
  tiếng phát qua VOICE_COMMUNICATION vẫn KHÔNG bị thu lại gây vọng.
  Gắn LoudnessEnhancer +8 dB (800→500→0, không crash) vào AudioTrack
  của player này như giọng dịch (đường cuộc gọi vốn nhỏ tiếng);
  thanh trượt "tiếng gốc" vẫn điều khiển gain của track này.
- XÓA hẳn lựa chọn "Giọng dịch phát qua: Âm lượng báo thức" trong
  Cài đặt (spinner + chuỗi + `Prefs.KEY_DUB_OUTPUT` / `DUB_OUTPUTS` /
  `dubOutputAlarm()` / `dubOutputLabel()`); giọng dịch chỉ còn đường
  cuộc gọi duy nhất. Xóa mọi `setStreamVolume(STREAM_ALARM)` và
  lưu/khôi phục âm lượng báo thức (`initialAlarmVolume`).
- Hệ quả (ghi trong Cài đặt, chuỗi `settings_dub_volume_note`): cả
  giọng dịch lẫn tiếng gốc giờ cùng đi đường cuộc gọi — nút âm lượng
  trên thân máy lúc đang xem chỉnh chung cả hai; hai thanh trượt
  trong app chỉnh tỉ lệ giữa hai tiếng. `startDelayedBackground`
  đặt STREAM_VOICE_CALL bằng max(thanh trượt giọng, thanh trượt
  tiếng gốc), không hiện UI âm lượng giữa phiên. Giữ nguyên mute
  STREAM_MUSIC ở mức hệ thống và khôi phục âm lượng media khi dừng.

## Kiểm tra logic trước khi build

- Thoại + giọng đều liên tục → mỗi 500 ms một mẫu, D thay đổi mỗi
  4 s (trung vị cửa sổ → EMA, deadband 500 ms).
- Thoại im 10 s → không có mẫu mới (hàng đợi + cửa sổ đã xóa).
- Giọng tới nhưng thoại đã im (dịch câu dài) → hàng đợi thoại cạn,
  không sinh mẫu sai (chỉ đếm emptyPairings).
- Không còn `USAGE_ALARM` / `STREAM_ALARM` / `dubOutputAlarm` /
  `KEY_DUB_OUTPUT` nào trong code (chỉ còn ghi chú v1.0.10 trong
  comment và chuỗi ghi chú Cài đặt).

---

# DubLive v1.0.9 — ĐỒNG BỘ HÌNH + TIẾNG GỐC + GIỌNG DỊCH ĐÚNG NHƯ ALDA

Build xong 25/11/2026 21:25 VN. APK Debug, cài được.
- File: `~/workspace/goals/alda-v10-tvb-style-dubbing-sync/files/DubLive-v1.0.9.apk`
  (symlink `~/workspace/your_files/DubLive-v1.0.9.apk`)
- Kích thước: 9.497.530 bytes (9,1 MB). MD5: e519e0983f9c24304ef67212420661ea.
- aapt2: package `com.dublive.live`, versionCode 10, versionName 1.0.9,
  minSdk 26, targetSdk 34, launcher `MainActivity`, label `DubLive`.
- Actions run thành công: **45633169640** (completed/success, đậu ngay lần
  đầu), commit `ac1e6948b0d5cf0daa42da9af36fab5b5ea525f9` trên nhánh
  `dublive-v1` (repo `kenj71737-afk/b8`, PR #1 vẫn mở, KHÔNG merge),
  artifact `alad-apks` (id 15031378443, bản Debug).

## Bối cảnh

User báo 21:08 sau khi thử v1.0.8: "Video gốc và âm thanh gốc không khớp
với giọng dịch. Độ trễ hình làm giống alad là được". Đúng: DubLive tới
v1.0.8 chỉ có 1/3 mảnh của cơ chế ALDA — trễ HÌNH. Giọng dịch phát ngay
khi tới (chênh lệch 2–8 s, thay đổi từng câu), tiếng gốc phát trực tiếp
(không trễ). Ba thứ chạy ở ba thời điểm khác nhau nên không bao giờ khớp.

## Cơ chế ALDA (đã đọc lại trong source FIX43 trước khi code)

- `LatencyEma`: độ trễ D = EMA 0.3 của mẫu (giọng tới − bắt đầu câu gốc)
  + 2000 ms biên an toàn, kẹp 3–20 s, deadband 500 ms.
- `DelayedAudioQueue`: hàng đợi theo mốc thời gian — block chỉ phát khi
  (now − arrival) >= delay hiện tại (đọc live mỗi lần poll); block quá
  hạn > 500 ms thì bỏ; giới hạn 2600 block.
- Giọng dịch GIỮ LẠI: hold = selectedDelay − latency của lượt đó; giọng
  chỉ bắt đầu phát khi (bắt đầu câu gốc + D) tới. Chế độ xem app khác
  thì hold = 0.
- Tiếng gốc TRỄ THEO HÌNH (FIX37/FIX41): khi WebAudio không trễ được
  tiếng gốc, ALDA TẮT tiếng trực tiếp rồi phát lại tiếng hệ thống đã thu
  (capture → delay D → USAGE_ALARM). Capture chỉ bắt USAGE_MEDIA/GAME
  nên tiếng phát qua ALARM không bị thu lại gây vọng.
- Đo trễ (FIX32/FIX33/FIX39/FIX43): mẫu lấy lúc BẮT ĐẦU lượt giọng dịch
  mới (chunk giọng tới sau >= 500 ms im — AudioBurstDetector 500 ms),
  mẫu = now − bắt đầu câu gốc; bỏ mẫu khi bắt đầu câu cũ hơn 20 s.

## 1. Port LatencyEma + AudioBurstDetector + DelayedAudioQueue

- 3 file mới `LatencyEma.kt`, `AudioBurstDetector.kt`,
  `DelayedAudioQueue.kt` (package com.dublive.live, viết lại sạch, giữ
  nguyên hằng số: EMA 0.3, margin 2000 ms, kẹp 3000–20000 ms, deadband
  500 ms, stale 500 ms, gap burst 500 ms).
- `DelayedAudioQueue` thêm một chế độ ngoài bản ALDA: block có thể mang
  due time cố định (`add(arrival, data, holdMs)`) — dùng cho giọng dịch
  (mục 2). Chế độ trễ live của ALDA giữ nguyên cho tiếng gốc (mục 3).
- `DubDelayTracker` VIẾT LẠI, thay thế slice FIFO + trung vị theo câu
  của v1.0.8: `onAudioChunkArrival(now, utteranceStart, firstSentAt)`
  — seed ALDA ở audio đầu tiên (D = latency đầu + 2000 ms), mẫu ở mỗi
  burst start (bỏ khi utterance start cũ hơn 20 s), trả về hold của
  burst đó = max(0, D − latency). Mẫu PHẢI lấy lúc giọng TỚI (burst
  start arrival), không phải lúc phát — khi có hold thì đo bằng lúc
  phát sẽ tự khẳng định (giọng phát lúc câu + D theo đúng thiết kế).
  Giữ: nút "Trễ −1s"/"+1s" (`adjustManual`, EMA tiếp tục từ giá trị
  tay), thanh trượt "Trễ hình cơ bản" làm seed ban đầu, `resetForReconnect`
  (burst detector reset, EMA và D giữ nguyên).

## 2. Giọng dịch GIỮ LẠI theo từng burst

- `VoicePlayer`: chunk Live không còn đi qua `LinkedBlockingQueue`
  mà qua `DelayedAudioQueue` với due time cố định = arrival + hold
  hiện tại (`setVoiceHoldMs`, service tính lại mỗi burst start).
  Vòng lặp phát poll mỗi 5 ms, chỉ ghi chunk khi đã tới hạn. Hết hold
  thì phát liên tục như bình thường; burst sau tính hold mới. Chunk đã
  vào hàng đợi giữ nguyên hold cũ dù burst sau đổi hold.
- `onSpeechStarted` (phụ đề/lịch duck) vẫn bắn khi chunk ĐẦU TIÊN ĐƯỢC
  LẤY RA khỏi hàng đợi — tức lúc giọng bắt đầu phát thật, không phải
  lúc chunk tới.
- Đường TTS (Groq + text-only của Live): giữ cả câu rồi phát khi
  (bắt đầu câu gốc + D) tới (quá rồi thì phát ngay, lagMs cho tốc độ
  đọc đuổi kịp 1.2x/1.35x). Phụ đề đi cùng thời điểm với giọng đọc.
- Phụ đề Live: `showSubtitle` trễ hiển thị bằng hold hiện tại của
  burst (mỗi fragment post runnable riêng, không hủy lẫn nhau — nếu
  hủy thì thoại liên tục sẽ không bao giờ hiện phụ đề). Phụ đề hiện
  cùng lúc giọng BẮT ĐẦU PHÁT, không phải lúc giọng tới.

## 3. Tiếng gốc TRỄ THEO HÌNH (DelayedBackgroundPlayer)

- File mới `DelayedBackgroundPlayer.kt` — port `BackgroundAudioPlayer`
  của ALDA, KHÔNG có VocalRemover (tiếng gốc giữ nguyên cả thoại lẫn
  nhạc, filterEnabled=false vĩnh viễn): AudioTrack USAGE_ALARM,
  stereo 48 kHz, `feedPcm` nhận tiếng thô (trước VAD), hàng đợi
  `DelayedAudioQueue` chế độ trễ live (`setDelayMs` theo D hiện tại,
  đọc live mỗi lần poll). Vòng lặp phát bằng Thread thường (không thêm
  dependency kotlinx-coroutines).
- Capture NÂNG LÊN stereo 48 kHz (`buildPlaybackRecordStereo`, thử
  trước; thất bại thì rơi về mono 16 kHz cũ — khi đó KHÔNG có tiếng
  gốc trễ, giữ hành vi cũ): mỗi lần đọc 100 ms stereo → đưa thẳng
  vào player, đồng thời downmix (trung bình L+R, lấy 1 khung trong 3)
  thành mono 16 kHz cho VAD/Live như cũ. Tiếng gốc trễ không bị rè/
  mỏng hơn tiếng phim gốc.
- Duck: `applyDuck` trong service — chế độ trễ thì duck áp lên
  `DelayedBackgroundPlayer.setVolume` (hậu trễ, đúng nhịp giọng nói),
  chế độ mic/mono thì vẫn broadcast TYPE_DUCK như cũ.

## 4. TẮT TIẾNG TRỰC TIẾP — LỆCH KHỎI BRIEF, THEO ĐÚNG ALDA

- Brief gốc dặn "WebView — JS mute mọi thẻ video/audio +
  MutationObserver". ĐÃ KHÔNG LÀM theo cách đó, vì đọc code ALDA và
  code DubLive cho thấy JS mute sẽ GIẾT LUÔN tiếng đưa vào player
  trễ: `v.volume = 0` áp ở mức AudioTrack của WebView, capture
  (AudioPlaybackCapture) đọc sau mức đó nên chỉ thu được im lặng —
  player trễ không có gì để phát, Live cũng không nghe được gì
  (đúng bài học v1.0.2: duck qua JS làm VAD đói).
- Cách làm (đúng ALDA FIX29/FIX37): mute ở mức HỆ THỐNG —
  `AudioManager.setStreamVolume(STREAM_MUSIC, 0, 0)`. Capture đọc
  track TRƯỚC khi áp stream volume hệ thống, nên tiếng vẫn vào
  capture đầy đủ trong khi loa không phát tiếng trực tiếp. Lưu âm
  lượng STREAM_MUSIC + STREAM_ALARM lúc bật, khôi phục lúc dừng
  (`stopDelayedBackground`, cả khi chuyển sang mic giữa phiên và khi
  `stopDub`). STREAM_ALARM đặt bằng max(thanh trượt giọng dịch, thanh
  trượt tiếng gốc) để cả giọng dịch lẫn tiếng gốc trễ đều nghe được.
- File local ExoPlayer: cùng một lần mute hệ thống là đủ (ExoPlayer
  cũng phát qua USAGE_MEDIA) — không cần `player.volume = 0` (cách
  đó cũng giết capture như JS mute). `player.volume` giữ 1.0 trong
  chế độ trễ.
- `DelayJs`: `DubLiveOriginalVolume` mặc định đổi 0.15 → 1.0 (phần tử
  video mới xuất hiện sau khi bắt đầu lồng tiếng trước đây bị đặt
  0.15, làm capture nhỏ tiếng). Duck qua JS chỉ còn dùng ở chế độ mic.
- Tắt/bật trễ hình: thêm `ACTION_SET_DELAY_ENABLED` — nút "Tắt trễ
  hình", vào toàn màn hình của trang web (video live) thì service đặt
  player delay = 0 và hold giọng = 0; thoát toàn màn hình / bật lại
  thì khôi phục theo lựa chọn của user. "Phóng to" của app không đổi
  (canvas vẫn chạy).

## 5. D thay đổi thì cả 3 cùng đổi

- EMA hoặc nút ±1s đổi D → `DubDelayTracker.onDelayChanged`:
  canvas JS (qua TYPE_HUD → MainActivity → `DubLiveSetDelay`),
  `DelayedBackgroundPlayer.setDelayMs` (hàng đợi đọc live nên khối
  đã thu cũng theo ngay), hold của burst giọng kế tiếp (tính lại ở
  burst start).

## 6. Giữ nguyên từ v1.0.7/v1.0.8

- VAD hysteresis 2 ngưỡng, cổng phổ tần số, LoudnessEnhancer, lựa chọn
  phát giọng qua cuộc gọi/báo thức, thanh trượt âm lượng, watchdog +
  xoay key, Groq fallback.

## Kiểm tra bằng logic (chưa có máy thật)

- Câu gốc bắt đầu lúc U, giọng tới lúc U+L (L = latency). D = EMA(L)
  + 2000 ms. Hold = D − L → giọng phát lúc U+L+(D−L) = U+D, đúng lúc
  canvas hiện hình của câu đó (hình lúc U cũng hiện lúc U+D). Tiếng
  gốc thu lúc U phát lại lúc U+D. Cả ba gặp nhau ở U+D.
- D đổi giữa chừng: khối tiếng gốc đã thu theo D mới ngay (trễ live);
  chunk giọng đã vào hàng đợi giữ hold cũ (đúng: nó thuộc burst cũ);
  burst sau tính hold theo D mới.
- Tắt lồng tiếng: STREAM_MUSIC + STREAM_ALARM khôi phục đúng mức cũ;
  không còn tiếng vang 2 lớp.
- Chế độ mic: không có player trễ, không mute hệ thống — tiếng trực
  tiếp vẫn nghe như cũ, giọng vẫn hold theo D để khớp hình trễ.
- Mono fallback (máy không cho capture stereo): như chế độ mic.

## Checklist thử lại trên máy user (gửi kèm khi giao)

- Xem 2–3 phút: môi + giọng dịch khớp nhau; tiếng gốc (nhạc + thoại
  gốc) khớp hình, không còn tiếng vang 2 lớp (trước đây nghe cả tiếng
  trực tiếp lẫn giọng dịch lệch nhau).
- Số trễ thay đổi theo từng câu (EMA + deadband 500 ms).
- Bấm "Trễ −1s"/"+1s": tác dụng ngay (cả hình, tiếng gốc, giọng).
- Tắt lồng tiếng thì tiếng video trực tiếp trở lại bình thường (âm
  lượng hệ thống được khôi phục).
- Bật "Tắt trễ hình" rồi bật lại: tiếng + giọng theo kịp hình live
  rồi trễ lại bình thường.

---



Build xong 25/11/2026 20:52 VN. APK Debug, cài được.
- File: `~/workspace/goals/alda-v10-tvb-style-dubbing-sync/files/DubLive-v1.0.8.apk`
  (symlink `~/workspace/your_files/DubLive-v1.0.8.apk`)
- Kích thước: 9.489.130 bytes (9,1 MB). MD5: 058e0ccccafe7defd0c2c06340cd9d44.
- aapt2: package `com.dublive.live`, versionCode 9, versionName 1.0.8,
  minSdk 26, targetSdk 34, launcher `MainActivity`, label `DubLive`.
- Actions run thành công: **45627266073** (completed/success, đậu ngay lần
  đầu), commit `4ac674a85c4d1cd5003772bb01b97deea3a99a1c` trên nhánh
  `dublive-v1` (repo `kenj71737-afk/b8`, PR #1 vẫn mở, KHÔNG merge),
  artifact `alad-apks` (id 15030195101, bản Debug).
- Build lần đầu đậu ngay, không dùng tới giới hạn 3 lần thử lại. Lưu ý:
  run này chạy ~11 phút mới xong (in_progress từ ~20:40 tới ~20:51),
  chậm hơn hẳn các run v1.0.7 (~3–5 phút) — runner GitHub chậm, không phải
  lỗi code.

## Bối cảnh

User thử v1.0.7 báo 20:37 "Chỉnh độ nhạy nghe tiếng thì giọng dịch lạ bị
nghẹn, nói k liền câu. Độ trễ không cập nhật tự động theo đoạn câu" và
20:40 "Kéo xuống thấp thì không loại bỏ hết tạp âm khi dịch" + "Đo bằng
câu của âm gốc". Cả 3 đều là lỗi thật, cùng một gốc: ngưỡng năng lượng
đơn + ghép cặp chờ khoảng im. Sửa hết trong v1.0.8.

## 1. Giọng dịch nghẹn khi kéo ngưỡng lên cao — VAD 1 ngưỡng

- Nguyên nhân: `VadDetector` dùng MỘT ngưỡng duy nhất cho cả bắt đầu lẫn
  kết thúc lượt nói. Kéo ngưỡng lên 800–1000 thì các âm tiết nhỏ trong câu
  rơi dưới ngưỡng → VAD tưởng hết nói, hết 700 ms là cắt lượt; lúc chưa
  vào lượt nói thì audio chỉ vào pre-roll 500 ms và KHÔNG được gửi lên
  Live. Kết quả: audio gửi lên Live bị thủng lỗ chỗ, model dịch ngắt
  quãng, giọng dịch nghẹn.
- Sửa — HYSTERESIS 2 ngưỡng (`VadDetector.kt` viết lại):
  - Ngưỡng BẮT ĐẦU = cài đặt của user (mặc định 600); ngưỡng KẾT THÚC =
    40% ngưỡng bắt đầu (600 → 240). Đang nói thì chỉ khung nào dưới ngưỡng
    THẤP mới tính là im; khung dưới ngưỡng bắt đầu nhưng trên ngưỡng kết
    thúc vẫn là đang nói và vẫn gửi lên Live (cả qua VAD lẫn qua cổng gửi
    `onSpeechChunk` trong service — cổng này đổi sang dùng ngưỡng THẤP
    `vadEndThreshold`).
  - `energyThreshold` thành `var startThreshold` + `updateThreshold()`;
    watchdog của service đọc lại `Prefs.vadThreshold` mỗi 10 s, đổi thì
    gọi `updateThreshold()` — kéo thanh trượt trong Cài đặt có tác dụng
    ngay giữa phiên, không cần Dừng/Bắt đầu lại.
  - Thanh trượt "Độ nhạy nghe tiếng" thu hẹp 250–900 (bỏ 1000–1200 vì mức
    đó chắc chắn nghẹn), mặc định 600, ghi chú: "Kéo cao quá giọng dịch sẽ
    bị nghẹn, nói ngắt quãng — nếu bị nghẹn thì kéo xuống 400–600".
  - KHÔNG gửi toàn bộ audio lên Live (vẫn qua VAD) — gửi hết sẽ dịch cả nhạc.

## 2. Kéo ngưỡng xuống thấp thì nhạc lọt qua — cổng phổ tần số

- Nguyên nhân: ngưỡng năng lượng KHÔNG phân biệt được nhạc to với tiếng
  nói to — chỉ chỉnh ngưỡng thì không bao giờ xử lý được cả hai đầu.
- Sửa — SPECTRAL GATE lúc BẮT ĐẦU lượt nói (lớp mới `SpectralGate.kt`):
  - Mỗi khung 20 ms (320 mẫu, 16 kHz) chạy FFT 512 điểm, tính (a) tỉ lệ
    năng lượng trong dải tiếng nói 300–3400 Hz so với tổng năng lượng,
    (b) spectral flatness (trung bình nhân / trung bình cộng của phổ).
    Điều kiện bắt đầu lượt nói: vượt ngưỡng năng lượng BẮT ĐẦU VÀ tỉ lệ
    dải tiếng nói ≥ 0.55 VÀ flatness < 0.5. Chỉ chạy FFT trên khung đã
    vượt ngưỡng năng lượng nên chi phí không đáng kể.
  - Một khi đã vào lượt nói thì KHÔNG kiểm tra phổ nữa (chỉ dùng ngưỡng
    kết thúc hysteresis thấp) để câu nói không bị cắt giữa chừng khi có
    nhạc nền đệm dưới lời thoại.
  - Công tắc trong Cài đặt: "Lọc nhạc nền bằng phổ tần số (thử nghiệm)" —
    mặc định BẬT; tắt thì VAD về đúng như v1.0.8 không có cổng phổ (để user
    tự so sánh nếu cổng phổ làm nghẹn giọng). Ghi chú: "Nếu giọng bị nghẹn
    thì tắt thử, hoặc kéo ngưỡng bắt đầu xuống 400–600".
  - Hạn chế đã biết: nhạc cụ đơn âm nằm gọn trong dải 300–3400 Hz vẫn có
    thể qua cổng; đường Live (lời dặn im lặng với nhạc) + SpeechFilter
    là tuyến phòng thủ cuối.
  - Đường Groq (dự phòng): bỏ kết quả Whisper chứa "[Music]", "[Nhạc]",
    "[Âm nhạc]", hoặc toàn bộ là 1–2 từ lặp lại ("la la la") trước khi
    đưa sang bước dịch — Whisper hay "nghe" nhạc thành lời.

## 3. Trễ không cập nhật theo đoạn câu — ghép lát cắt + trung vị theo câu

- Nguyên nhân: ghép cặp FIFO ở v1.0.7 chờ "đoạn giọng dịch mới bắt đầu"
  (chunk giọng tới sau ≥800 ms im) mới ghép một mẫu. Thoại liên tục thì
  giọng dịch cũng nói liên tục, không có khoảng im 800 ms nào → không bao
  giờ có sự kiện ghép → trễ vẫn đứng yên. Lỗi cấu trúc giống hệt bản cũ,
  chỉ chuyển từ phía thoại sang phía giọng.
- Sửa — GHÉP LÁT CẮT 500 ms + TRUNG VỊ THEO CÂU (`DubDelayTracker` viết lại):
  - Phía thoại: service đẩy mốc `pushSpeechSlice(now)` mỗi 500 ms audio
    đang trong lượt nói (tích lũy từ `onSpeechChunk`, khung 20 ms = 320
    mẫu, 8000 mẫu = 500 ms) + 1 mốc lúc `onSpeechStart`. Hàng đợi tối đa
    40 mốc (20 s), cũ nhất bị đẩy ra khi đầy.
  - Phía giọng: trong `onAudioChunk` (Live), tích lũy thời lượng giọng đã
    phát (audio Live là PCM 24 kHz 16-bit mono — 48.000 byte/s nên
    ms = bytes/48, KHÔNG phải bytes/32 như brief ban đầu ghi nhầm cho
    16 kHz) và mỗi 500 ms gọi `onDubSlice(now)` — lấy mốc CŨ NHẤT, mẫu =
    now − mốc; mẫu trong [500 ms, 20000 ms] thì THU LẠI chờ ranh giới câu,
    mẫu ngoài khoảng hoặc hàng đợi rỗng thì bỏ qua, mẫu ngoài khoảng thì
    XÓA SẠCH cả 2 hàng đợi để resync.
  - Cập nhật theo CÂU GỐC (ý user 20:40 "đo bằng câu của âm gốc"): mẫu
    không áp dụng ngay mà thu vào `pendingSamples` tới ranh giới câu —
    VAD `onSpeechStart` (câu mới bắt đầu), VAD `onSpeechEnd`, hoặc ép cắt
    4 s của `segmentCutter` (giữ lại từ v1.0.7, đổi từ đẩy mốc sang gọi
    `noteSentenceBoundary()`) — rồi lấy TRUNG VỊ của các mẫu trong câu vừa
    kết thúc, EMA alpha 0.5 cập nhật trễ và broadcast TYPE_HUD MỘT LẦN.
    Câu nào không có mẫu thì giữ nguyên trễ cũ. Kết quả: số "Trễ hình"
    đổi đúng 1 lần mỗi câu gốc — ổn định theo câu, không đứng yên như
    v1.0.6, không nhảy loạn mỗi nửa giây.
  - Đường TTS (Groq) giữ một mẫu mỗi lượt đọc: `VoicePlayer` tách 2
    callback — `onSpeechStarted` (chunk Live đầu tiên của lượt) gọi
    `noteDubSegmentStart()` (ghép như lát giọng), `onTtsStarted` (TTS
    onStart) gọi `noteTtsSegmentStart()` (ghép mốc cũ nhất rồi xóa nốt
    các lát còn lại của câu đó để lượt đọc sau ghép với lát mới).
  - Giữ: kẹp [3 s, maxDelay], seed từ "Trễ hình cơ bản", nút "Trễ −1s"/"+1s"
    và `adjustManual` (tác dụng ngay như cũ), mọi hàm tracker @Synchronized.
    `clearSlices()` khi `onConnected` để lát cũ trước lúc nối không làm
    bẩn mẫu đầu tiên.

## Kiểm tra bằng logic (chưa có máy thật)

- Ngưỡng 800, phim thoại bình thường: khung âm tiết nhỏ (RMS 240–800)
  vẫn tính là đang nói → giọng dịch liền câu, không nghẹn; nhạc to không
  lời (phổ lan rộng, flatness cao) không mở lượt nói mới.
- Thoại + giọng đều liên tục: mỗi 500 ms giọng sinh 1 mẫu, số "Trễ hình"
  đổi 1 lần mỗi câu (tối đa mỗi 4 s nhờ ép cắt) — không đứng yên, không
  nhảy mỗi nửa giây. Giọng im → không sinh mẫu. Thoại im mà giọng vẫn đọc
  (dịch câu dài) → hàng đợi thoại cạn, không sinh mẫu sai.
- Nút "Trễ −1s"/"+1s" vẫn tác dụng ngay; trung vị câu sau EMA từ giá trị
  đã chỉnh tay, không kéo ngược lại.

## Checklist thử lại trên máy user (gửi kèm khi giao)

- Ngưỡng 800, xem phim thoại: giọng dịch liền câu, không nghẹn.
- Xem 2–3 phút: số "Trễ hình" đổi đúng 1 lần mỗi câu thoại (không đứng yên
  như v1.0.6, không nhảy loạn mỗi nửa giây), hình khớp giọng dần sau vài câu.
- Bấm "Trễ −1s"/"+1s": tác dụng ngay.
- Đoạn nhạc dạo đầu / khoảng nghỉ giữa thoại: không còn câu dịch nào đọc nhạc.
- Thoại bình thường: giọng vẫn liền câu, không nghẹn.
- Nếu giọng nghẹn: tắt công tắc lọc phổ thử, hoặc kéo ngưỡng xuống 400–600.

---

# DubLive v1.0.7 — TRỄ HÌNH TỰ ĐỔI THEO TỪNG CÂU + GIỌNG DỊCH TO HƠN

Build xong 25/11/2026 20:27 VN. APK Debug, cài được.
- File: `~/workspace/goals/alda-v10-tvb-style-dubbing-sync/files/DubLive-v1.0.7.apk`
  (symlink `~/workspace/your_files/DubLive-v1.0.7.apk`)
- Kích thước: 9.1 MB. MD5: 2b12823fb71d6e856431f8a6b8aa141a.
- aapt2: package `com.dublive.live`, versionCode 8, versionName 1.0.7,
  minSdk 26, targetSdk 34, launcher `MainActivity`, label `DubLive`.
- Actions run thành công: **45623109794** (completed/success, đậu ngay lần
  đầu), commit cuối `4af5126cc62995df4bb2cd8725081c7b2b863e08` trên nhánh
  `dublive-v1` (repo `kenj71737-afk/b8`, PR #1 vẫn mở, KHÔNG merge),
  artifact `alad-apks` (id 15027665648, bản Debug).
- 2 commit trong bản này (cùng version 1.0.7): `dac726ca` (ghép cặp FIFO +
  nút ±1s, run 45622288780 success) và `4af5126c` (giọng to hơn:
  LoudnessEnhancer + chế độ báo thức, run 45623109794 success — đây là
  commit giao). Lần build nào cũng đậu ngay lần đầu, không dùng tới giới
  hạn 3 lần thử lại.

## Bối cảnh

User thử v1.0.6 báo 20:12 "Độ trễ hình đứng nguyên từ lúc vào. Không thấy
đổi để khớp với giọng dịch" và 20:19 "Dùng âm lượng của cuộc gọi điện thoại
nên nhỏ tiếng quá". Cả 2 đều là lỗi thật, khác nhau, sửa hết trong v1.0.7.

## 1. Trễ hình đóng băng — đo đúng 1 mẫu rồi đứng yên cả phiên

- Nguyên nhân: `DubDelayTracker` v1.0.5/v1.0.6 chỉ lấy ĐÚNG 1 mẫu mỗi lượt
  nói (cờ `dubSpokeThisTurn`, chỉ reset khi VAD thấy 700 ms im lặng).
  Thoại Quảng Đông nói liên tục không có khoảng lặng 700 ms nên thực tế
  chỉ có MỘT lượt nói kéo dài nhiều phút → đúng 1 mẫu từ đầu phiên rồi
  trễ đứng yên mãi. Đường forward delay vào JS (nhánh `TYPE_HUD`,
  MainActivity dòng 170) đã có và đúng — tracker không sinh mẫu mới,
  không phải đường forward hỏng. Bản v1.0.5 bỏ tick lấy mẫu định kỳ đã
  chữa phình (19,4 s) nhưng làm trễ đóng băng luôn. Hai cực đối nhau:
  v1.0.4 cộng mẫu `(now - utteranceStart)` mỗi 2 s → đo độ DÀI lượt nói,
  phình tới trần; v1.0.5/1.0.6 một mẫu/lượt nói → đóng băng.
- Sửa — GHÉP CẶP FIFO (`DubDelayTracker` viết lại):
  - Service đẩy mốc bắt đầu lượt nói vào hàng đợi FIFO mỗi khi VAD
    `onSpeechStart`, VÀ timer `segmentCutter` (2 s) ép cắt đoạn mỗi 4 s
    khi đang nói liên tục — chỉ đẩy mốc cho tracker, KHÔNG gọi
    `onSpeechEnd`, không cắt `utteranceBytes` của Groq, không gửi
    `endTurn` (Live stream liên tục). Một đoạn thoại dài 3 phút thành
    ~45 đoạn, không còn là 1 lượt nói.
  - Mỗi khi một đoạn giọng dịch mới bắt đầu (chunk giọng tới sau ≥800 ms
    im — theo dõi trong `DubbingService.onAudioChunk` và callback
    `VoicePlayer` cho TTS), lấy mốc CŨ NHẤT chưa ghép trong hàng đợi,
    mẫu = dubStart − burstStart. Mẫu hợp lệ trong [500 ms, 20000 ms]
    thì EMA alpha 0.5 cập nhật trễ và broadcast `TYPE_HUD` (đường
    forward JS giữ nguyên); mẫu ngoài khoảng thì XÓA SẠCH hàng đợi để
    đồng bộ lại (một đoạn bị lọc/mất sẽ làm lệch cặp, phải resync chứ
    không ghép sai tiếp). Bỏ hẳn `startUtterance` / `onDubSpeechStarted` /
    `endUtterance` và cờ `dubSpokeThisTurn`; giữ kẹp [3 s, maxDelay].
  - Các hàm tracker đều `@Synchronized` (mốc từ thread capture/VAD,
    chunk giọng từ thread socket Live và listener TTS).
- NÚT CHỈNH TAY (bắt buộc — user cần chỉnh bằng mắt ngay khi đang xem):
  2 nút "Trễ −1s" / "Trễ +1s" trên màn hình chính cạnh HUD "Trễ hình"
  (nằm trong `hudRow` nên khi vào fullscreen của trang web cả cụm chuyển
  sang decorView cùng nhau) và 2 nút tương tự trong thanh toàn màn hình
  của app (Phóng to). Bấm nút gửi intent `ACTION_ADJUST_DELAY` tới
  service → `tracker.adjustManual(±1000 ms)` (kẹp [3 s, maxDelay]) và
  forward ngay vào JS qua `TYPE_HUD`; mẫu tự động sau đó EMA tiếp từ
  giá trị tay vừa đặt (không kéo ngược lại). Khi chưa lồng tiếng, bấm
  nút chỉnh luôn seed "Trễ hình cơ bản" và áp vào canvas ngay.
- Thanh trượt "Trễ hình cơ bản" 3–20 s (mặc định 5 s) trong Cài đặt
  (`Prefs.KEY_BASE_DELAY_SEC`) làm seed ban đầu, thay cho 5238 ms cố
  định trong tracker và 5000 ms hardcode trong `MainActivity`
  (`onPageFinished` và lúc bắt đầu lồng tiếng đều đẩy seed này vào JS).

## 2. Giọng dịch nhỏ vì chạy trên stream cuộc gọi

- Nguyên nhân: giọng dịch phát qua `USAGE_VOICE_COMMUNICATION` (cố ý để
  `AudioPlaybackCapture` chỉ bắt `USAGE_MEDIA` nên không thu lại giọng
  dịch gây vọng), mà stream cuộc gọi trên nhiều máy — kể cả khi chỉ số
  đã tối đa — vẫn nhỏ hơn hẳn stream media, và `AudioTrack.setVolume(1.0f)`
  ở v1.0.6 đã là trần của gain thường.
- Sửa (KHÔNG đổi `USAGE_VOICE_COMMUNICATION` mặc định):
  - `LoudnessEnhancer` gắn vào AudioTrack của giọng dịch ngay khi tạo
    track (`audioSessionId` của track): `setTargetGain(800)` (+8 dB),
    nếu ném ngoại lệ thì thử 500 rồi 0, không bao giờ crash; release
    trong `stop()`. TTS không lấy được audioSessionId công khai nên
    không gắn (TTS chỉ là đường dự phòng). Đây là cách tăng to vượt trần
    gain 1.0 mà vẫn giữ stream cuộc gọi.
  - Lựa chọn mới trong Cài đặt — "Giọng dịch phát qua":
    [Âm lượng cuộc gọi (mặc định, không vọng)] / [Âm lượng báo thức
    (to hơn, không vọng)]. Khi chọn báo thức, `VoicePlayer` dùng
    `USAGE_ALARM` cho cả AudioTrack lẫn TTS (stream báo thức to hơn
    hẳn, và capture chỉ bắt `USAGE_MEDIA` + `USAGE_GAME` — đã kiểm tra
    lại `buildPlaybackRecord` — nên giọng báo thức KHÔNG bị thu lại gây
    vọng). Lưu vào `Prefs.KEY_DUB_OUTPUT`, áp dụng khi bắt đầu lồng
    tiếng lần sau (ghi chú trong Cài đặt: "Đổi xong thì Dừng rồi Bắt đầu
    lại"). Mặc định vẫn là cuộc gọi.
  - Thanh trượt giọng dịch v1.0.6 vẫn chỉnh đúng stream đang dùng:
    `applyCallVolume` giờ chọn `STREAM_ALARM` khi ở chế độ báo thức,
    `STREAM_VOICE_CALL` khi ở chế độ cuộc gọi.

## Checklist thử lại cho user

1. Gỡ bản cũ, cài v1.0.7 (cài đè thì key còn nguyên).
2. Xem 2–3 phút ở chế độ thường: số "Trễ hình" phải TỰ THAY ĐỔI theo
   từng câu nói (không đứng yên như v1.0.6); hình với giọng khớp dần
   sau vài câu.
3. Bấm "Trễ +1s" thì hình chờ thêm 1 s ngay (giọng đi trước hình thì
   bấm +1s), bấm "Trễ −1s" thì hình nhanh lên 1 s ngay; bấm vài lần
   tới khi miệng và giọng khớp hẳn.
4. Ở chế độ mặc định (cuộc gọi): kéo giọng dịch lên 100% — giọng phải
   to rõ hơn hẳn bản 1.0.6 (nhờ LoudnessEnhancer +8 dB).
5. Vào Cài đặt → "Giọng dịch phát qua" → chọn "Âm lượng báo thức",
   Lưu, Dừng lồng tiếng hẳn rồi Bắt đầu lại: giọng phải to hơn nữa và
   KHÔNG bị vọng (không có câu dịch lặp lại chính giọng Việt vừa đọc).
   Muốn về lại thì chọn lại "Âm lượng cuộc gọi", Dừng/Bắt đầu lại.
6. Báo lại: số trễ có tự đổi theo câu nói không, bấm ±1s có tác dụng
   ngay không, giọng ở chế độ cuộc gọi đủ to chưa, chế độ báo thức có
   to hơn và có bị vọng không.

## Giới hạn

- Chế độ báo thức chỉ đổi ở lần bắt đầu lồng tiếng sau (VoicePlayer
  đọc lựa chọn khi tạo track); đang lồng tiếng mà đổi thì phải Dừng
  rồi Bắt đầu lại — đã ghi chú trong Cài đặt.
- Chưa kiểm chứng trên máy thật (iQOO Z9 Turbo / OriginOS 6 /
  Android 16) — mới kiểm chứng ở mức biên dịch + đóng gói + đối chiếu
  code đã đẩy + chuỗi mới có trong APK; đang chờ user chạy checklist trên.

# DubLive v1.0.6 — TRỄ HÌNH KHỚP GIỌNG + XEM ĐƯỢC TOÀN MÀN HÌNH + ÂM LƯỢNG TO

Build xong 25/11/2026 20:05 VN. APK Debug, cài được.
- File: `~/workspace/goals/alda-v10-tvb-style-dubbing-sync/files/DubLive-v1.0.6.apk`
  (symlink `~/workspace/your_files/DubLive-v1.0.6.apk`)
- Kích thước: 9.1 MB. MD5: 2cba5872c81df0be765efdf6069ed711.
- aapt2: package `com.dublive.live`, versionCode 7, versionName 1.0.6,
  minSdk 26, targetSdk 34, launcher `MainActivity`, label `DubLive`.
- Actions run thành công: **45619702134** (completed/success, đậu ngay lần
  đầu), commit cuối `08ead9fd95f5b9ec8ce85eebdefdbd19bc4b4421` trên nhánh
  `dublive-v1` (repo `kenj71737-afk/b8`, PR #1 vẫn mở, KHÔNG merge),
  artifact `alad-apks` (id 15023683789, bản Debug).
- 3 commit trong bản này (cùng version 1.0.6): `f7d2a532` (trễ hình +
  fullscreen, run 45617450254 success), `ec523bac` (sửa đen màn hình
  fullscreen, run 45618223571 success), `c6995957` (Phóng to của app —
  build LỖI lần đầu: `MainActivity.kt:359 Unresolved reference:
  liveErrorsButton` do lúc thêm id errorsRow đã ăn mất thẻ `<Button`
  mở của nút Xem lỗi Live, xem log job 139221260463; sửa ở `4bcf60ed`,
  run 45619332843 success), `08ead9fd` (âm lượng, run 45619702134
  success — đây là commit giao). Lỗi 404 ở bước tải artifact là do
  gọi sai cú pháp `gh.py download-artifact` (cần 2 tham số vị trí
  `<artifact-id> <outfile>`, không phải cờ `--artifact-id/--dest`);
  log job Actions tải bằng cách bắt 302 Location (xem `/tmp/get_logs2.py`).

## Bối cảnh

User báo 19:46 "Trễ hình không khớp với video", 19:52 "Vẫn chưa xem
full màn hình được nha", 19:56 "Làm sao xem video full màn hình điện
thoại hình và giọng dịch khớp nhau", 20:01 "Âm lượng dịch với âm gốc
nhỏ quá". Cả 4 đều là lỗi thật, khác nhau, sửa hết trong v1.0.6.

## 1. Trễ hình là số ảo — video luôn chỉ trễ đúng 5 giây (lỗi chính)

- Nguyên nhân: `DubDelayTracker` tính trễ thật (EMA 5–19 s) và broadcast
  `TYPE_HUD`, nhưng `MainActivity` ở nhánh `TYPE_HUD` CHỈ cập nhật chữ
  "Trễ hình: X s" mà KHÔNG BAO GIỜ gọi `window.DubLiveSetDelay(ms)`.
  Lệnh `DubLiveSetDelay` duy nhất nằm ở `onPageFinished` và hardcode
  5000 ms. Kết quả: canvas trong WebView luôn trễ đúng 5,0 s dù HUD
  hiện 7 s, 12 s hay 19,4 s; giọng dịch tới sau lời thoại thật 7–15 s
  nên giọng luôn đi SAU hình, càng xem càng lệch. (`LocalPlayerActivity`
  đã dùng đúng `currentDelayMs` từ `TYPE_HUD` nên file trên máy không
  bị lỗi này.)
- Sửa: nhánh `TYPE_HUD` vừa cập nhật chữ HUD vừa gọi
  `window.DubLiveSetDelay(ms)` mỗi lần trễ đổi quá 200 ms (khỏi spam
  JS); `onPageFinished` giữ seed 5000 ms. Sau sửa, `MainActivity` có
  đúng 2 chỗ gọi `DubLiveSetDelay` (seed + HUD). `DubLiveSetDelay` chỉ
  đặt `window.DubLiveDelayMs`, vòng lặp tick đọc lại mỗi khung nên gọi
  giữa chừng an toàn.
- Tracker bám nhanh hơn: EMA alpha 0.3 → 0.5; bỏ qua mẫu > 15 s (lượt
  đầu sau khi nối chứa buffer cũ, không đại diện); giữ lấy mẫu đúng
  1 lần mỗi lượt nói (v1.0.5) và kẹp [3 s, maxDelay].
- KHÔNG đổi: không thử trễ tiếng gốc bằng Web Audio DelayNode — video
  cross-origin (NetShort CDN không CORS) sẽ làm MediaElementSource câm
  hẳn, đúng lỗi cũ của ALDA. Tiếng gốc vẫn phát real-time ở mức nhỏ
  như thiết kế; chỉ cần hình + giọng dịch khớp nhau là đúng mục tiêu.

## 2. Toàn màn hình của trang web chỉ thấy MÀN ĐEN

- Nguyên nhân: khi trễ hình bật, DelayJs đặt thẻ video thật
  `opacity = 0` và chỉ hiện canvas trễ đè lên TRONG TRANG. Bấm toàn
  màn hình của trang web (biểu tượng ⛶ trên video) thì trình duyệt đưa
  THẺ VIDEO (đang opacity 0) lên surface toàn màn hình, canvas ở lại
  trong trang — toàn màn hình chỉ thấy màn đen. Đó là lý do "không xem
  full màn hình được", không phải chỉ trễ không khớp.
- Sửa (`MainActivity.onShowCustomView`): TRƯỚC khi addView custom view
  thì gọi `window.DubLiveSetEnabled(false)` để tắt trễ hình (video trở
  lại opacity 1, canvas ẩn) rồi mới hiện toàn màn hình — video xem được
  bình thường, chỉ là lúc này giọng dịch đi sau hình. `onHideCustomView`
  gọi lại `DubLiveSetEnabled(delayOn)` để bật trễ hình trở lại nếu user
  chưa tự tắt bằng nút "Tắt trễ hình"; `delayOn` không bị toàn màn hình
  làm lệch, chữ nút đồng bộ lại bằng `updateDelayButtons()`. Giữ lại
  Toast cảnh báo + thêm câu hướng dẫn dùng nút Phóng to của app (mục 3).
- Phụ đề + HUD vẫn hiện trên toàn màn hình của trang: `bringToFront()`
  một mình không đủ (chỉ sắp xếp trong parent của chính nó, mà custom
  view nằm trên decorView) nên đã chuyển cả 2 overlay sang decorView
  lúc fullscreen (`moveOverlayToDecor`/`restoreOverlay`: phụ đề ở đáy,
  HUD ở đầu) và trả lại khi thoát.
- Sửa kèm trong DelayJs (`DubLiveSetEnabled`): khi bật lại trễ thì xóa
  các khung cũ trong bộ đệm (hình từ TRƯỚC lúc tắt trễ, tick sẽ ăn hết
  một lượt và canvas hiện hình cũ sai thời điểm) và giữ video opacity 1
  cho tới khi tick vẽ khung trễ đầu tiên (tick tự đặt opacity 0 lúc
  đó), nên thoát toàn màn hình không bị màn đen ~5 s chờ đệm đầy lại.

## 3. Toàn màn hình CỦA APP — cách duy nhất để hình khớp giọng ở màn hình lớn

- Giới hạn đã ghi rõ: nút toàn màn hình CỦA TRANG WEB đưa video lên
  một surface native riêng của WebView, canvas trễ nằm lại trong trang
  nên KHÔNG BAO GIỜ khớp được — đó là giới hạn của Android WebView,
  không sửa được bằng cách vẽ thêm. Cách làm được: toàn màn hình CỦA APP.
- Nút "Phóng to" mới cạnh nút "Tắt trễ hình" (`expandButton`,
  `R.string.expand_app`). Khi bấm: ẩn toàn bộ giao diện chrome của app
  (ô địa chỉ + nút Mở, hàng Bắt đầu/Dừng lồng tiếng, hàng Mở file/Cài
  đặt/Tắt trễ hình/Ẩn phụ đề, nút Lồng tiếng bằng mic, nút Xem lỗi Live,
  dòng Trạng thái, thanh Mức tiếng) — chỉ giữ lại WebView (chiếm toàn bộ
  màn hình), phụ đề Việt, HUD "Trễ hình", nút "Tắt trễ hình" (bản sao
  trong thanh fullscreen) và nút "Thu nhỏ" để thoát. Chế độ immersive
  (`IMMERSIVE_STICKY` + fullscreen + hide navigation) ẩn cả thanh trạng
  thái/điều hướng; `supportActionBar` ẩn/hiện theo. Vì canvas trễ nằm
  trong DOM của WebView nên trễ hình VẪN CHẠY và hình vẫn khớp giọng —
  đây là điểm mấu chốt.
- "Thu nhỏ" (`collapseButton` trong `fullscreenBar` ở đầu FrameLayout)
  hoặc nút Back đều thoát và hiện lại đầy đủ giao diện, trễ hình giữ
  nguyên (không bật/tắt lại). `onResume` áp lại cờ immersive nếu vẫn
  đang fullscreen (hệ thống reset cờ khi app pause). Nút "Tắt trễ hình"
  có 2 bản sao (thường + fullscreen) dùng chung `toggleDelay()` và
  `updateDelayButtons()` để chữ luôn đồng bộ. Vào Phóng to khi đang ở
  fullscreen của trang web thì bỏ qua (guard `customView != null`).
- Nút toàn màn hình của trang web vẫn để nguyên (không chặn), nhưng khi
  user bấm nó thì vẫn áp dụng xử lý mục 2 (tự tắt trễ hình để không đen
  màn hình + Toast cảnh báo + câu hướng dẫn dùng nút Phóng to của app).

## 4. Âm lượng giọng dịch + tiếng gốc đều nhỏ quá

- Nguyên nhân: giọng dịch phát qua `USAGE_VOICE_COMMUNICATION` (cố ý
  để `AudioPlaybackCapture` chỉ bắt `USAGE_MEDIA`, khỏi thu lại chính
  giọng dịch gây vọng), mà stream này dùng ÂM LƯỢNG CUỘC GỌI
  (`STREAM_VOICE_CALL`) riêng, khác hẳn âm lượng media — user vặn to
  âm lượng media bao nhiêu thì giọng dịch vẫn nhỏ, vì nút âm lượng chỉ
  chỉnh đúng stream đang phát.
- Sửa (`VoicePlayer`):
  - AudioTrack và TTS đặt ở mức tối đa (`setVolume(1.0f)`,
    `KEY_PARAM_VOLUME = 1.0f`); `setDubVolume()` giờ luôn ép 1.0f.
  - Thanh trượt "Âm lượng giọng dịch" trong Cài đặt (mặc định 90%) đổi
    thành điều khiển TRỰC TIẾP âm lượng hệ thống của `STREAM_VOICE_CALL`
    qua `AudioManager.setStreamVolume` (thang 0–100% map sang chỉ số
    stream 0..max, cờ `FLAG_SHOW_UI` để hiện bảng âm lượng hệ thống).
    `VoicePlayer.applyCallVolume(context, pct)` dùng chung cho cả
    Settings (kéo là áp dụng ngay, lưu pref ngay, không cần bắt đầu
    lại) và service (áp dụng giá trị đã lưu khi bắt đầu lồng tiếng).
    Quyền `MODIFY_AUDIO_SETTINGS` đã có sẵn trong AndroidManifest (quyền
    normal, không cần xin runtime).
  - KHÔNG đổi `USAGE_VOICE_COMMUNICATION` sang `USAGE_MEDIA` (đổi là
    capture sẽ thu lại giọng dịch gây vọng — đúng lý do ALDA dùng voice
    communication).
- Tiếng gốc: mặc định thanh trượt "Âm lượng tiếng gốc khi lồng tiếng"
  từ 60% lên 80% (`Prefs.originalVolumePct`); khi giọng dịch im thì trả
  về 100% như cũ (duck ticker v1.0.5). Đã kiểm tra `DubLiveSetVolume`
  nhận đúng thang 0..1 (`Prefs.originalVolume` trả pct/100).
- Gợi ý ngay dưới thanh Mức tiếng trên màn hình chính (nhỏ, không che
  video): "Giọng dịch dùng âm lượng cuộc gọi: lúc giọng đang nói, bấm
  nút tăng âm lượng trên điện thoại để tăng riêng giọng dịch"
  (`R.string.call_volume_hint`). Trong Cài đặt, ghi chú dưới thanh
  trượt giọng dịch: "Âm lượng này là âm lượng cuộc gọi, khác âm lượng
  media của video" (`R.string.settings_dub_volume_note`).

## Checklist thử lại cho user

1. Gỡ bản cũ, cài v1.0.6 (cài đè thì key còn nguyên).
2. Xem 2–3 phút ở chế độ thường: miệng nhân vật và giọng dịch phải
   khớp; số "Trễ hình" thay đổi theo câu nói thì hình cũng đổi theo.
3. Bấm nút toàn màn hình CỦA TRANG WEB: phải thấy video chạy bình
   thường (không đen), phụ đề vẫn hiện, Toast cảnh báo giọng sẽ đi sau
   hình + hướng dẫn dùng nút Phóng to của app; thoát ra thì trễ hình
   bật lại và hình khớp giọng trở lại (không đen ~5 s).
4. Bấm "Phóng to" của app, xem 2 phút: video lấp đầy màn hình (phim
   dọc thì lấp đầy chiều rộng), phụ đề vẫn hiện ở đáy, trễ hình vẫn
   chạy và giọng khớp hình; bấm "Thu nhỏ" hoặc Back thì giao diện trở
   lại đầy đủ.
5. Kéo thanh giọng dịch lên 100% thì giọng phải to rõ; bấm nút tăng âm
   lượng lúc giọng đang nói thì giọng to lên (âm lượng cuộc gọi); tiếng
   gốc khi giọng im phải nghe rõ (trả về 100%).
6. Báo lại: hình + giọng có khớp không (chế độ thường và Phóng to),
   toàn màn hình của trang có đen không, giọng dịch/tiếng gốc đủ to chưa.

## Giới hạn

- Toàn màn hình của trang web KHÔNG BAO GIỜ khớp hình + giọng được
  (surface native bỏ qua canvas) — chỉ có toàn màn hình của app
  (Phóng to) mới khớp. Đã nói rõ trong app bằng Toast + nút Phóng to.
- Chưa kiểm chứng trên máy thật (iQOO Z9 Turbo / OriginOS 6 /
  Android 16) — mới kiểm chứng ở mức biên dịch + đóng gói + đối chiếu
  code đã đẩy; đang chờ user chạy checklist trên.

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
