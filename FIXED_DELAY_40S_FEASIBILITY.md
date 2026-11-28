# DubLive — Trễ cố định 40 s: đọc code khả thi kỹ thuật (28/11/2026, CHỈ ĐỌC — không sửa code, không build)

Bối cảnh: v1.0.22, giọng Việt dài hơn gốc 1,42–1,51×, stretch ghim 1,50× gây méo giọng (user 12:11),
trần 1,25× trượt sim-gate (mất chữ, sim1027), D kẹt dưới lag thật, ~1800 ms trôi chưa giải thích được.
Chỉ đạo user 12:40 28/11 (thay thế "lấy mức nhỏ nhất"): **"Lấy thừa cũng được để xem mọi phim đều
có thể môi khớp với giọng" — ưu tiên đồng bộ trên mọi phim, lấy mức hào phóng, đầu trên 30–40 s
hoặc mức sim chứng minh đủ cho trường hợp xấu nhất.**

## 0. Kết luận trước (đọc nhanh)

- **Mức trễ đề xuất: D cố định = 40 s** (đầu trên khoảng user đề xuất). Lý do ở §6: D bị chi phối bởi
  độ trễ dịch của đầu burst (lag0, quan sát tối đa ~12 s), KHÔNG bị chi phối bởi độ dài burst;
  40 s = 3,3× worst-case đã quan sát, 2× trần hợp lệ của mẫu (20 s). 30 s cũng đủ cho số đã quan
  sát, nhưng theo chỉ đạo "lấy thừa" và vì chi phí 30→40 s là nhỏ khi đã nén khung hình, lấy 40.
- **Canvas ring buffer HIỆN TẠI KHÔNG kham nổi 40 s ở dạng thô: ~1,29 GB RAM live** (1400 khung
  × 0,92 MB). Trên WebView renderer của máy 12 GB RAM, đây là nguy cơ OOM/crash thực sự, không
  phải chỉ "nặng". Xem §1–2.
- **Phương án chính đề xuất: giữ nguyên kiến trúc canvas + trễ cố định 40 s, nhưng lưu khung hình
  nén JPEG thay vì canvas sống** — RAM ~25–40 MB, không hạ độ phân giải (vẫn 640 px như hiện tại),
  chỉ giảm fps snapshot 30→15. Xem §7.
- **Dự phòng: second-decoder lookahead** (video element thứ hai / ExoPlayer chạy trễ 40 s sau
  element chính) — 0 MB RAM khung hình, tốn 2× băng thông + 2× giải mã, CHỈ chạy khi URL video
  là file trực tiếp (mp4/m3u8 lấy được); chết trên YouTube/MSE/blob:. Chưa xác minh được URL
  thật của 18dj.tv bằng đọc code — phải đo trên máy (§8).
- **Trễ cố định 40 s GIẾT LUÔN vấn đề méo giọng:** khóa stretch ở 1,0× (không time-stretch nữa),
  bỏ vòng D thích ứng, bỏ van xả đuôi. Giọng phát đúng tốc độ gốc, không méo do stretch.
- **Giá phải trả (nói thẳng với user): chờ khởi động 40 s** — 40 s đầu sau khi bấm "Bắt đầu lồng
  tiếng" chỉ thấy màn hình chờ (video vẫn chạy ngầm để dịch), sau đó xem đồng bộ. Không mất nội
  dung nào (phim chỉ bị dời 40 s), nhưng mỗi lần bấm bắt đầu / đổi tập / seek đều chờ lại 40 s
  (trừ khi giữ buffer qua seek — không, xem §1: seek xóa buffer).

---

## 1. Cơ chế trễ hình HIỆN TẠI cho video web — chính xác theo code

**File: `app/src/main/java/com/dublive/live/DelayJs.kt` (toàn bộ INJECT, dòng ~40–268).**
KHÔNG phải video element thứ hai, KHÔNG phải ExoPlayer. Cơ chế duy nhất:

1. Một `<canvas>` cố định phủ toàn viewport (`position:fixed; z-index:2147483000`, dòng tạo canvas
   + `ctx2d = getContext('2d', {alpha:false})`), hiển thị khung hình đã trễ; video gốc vẫn phát
   bình thường bên dưới, bị ẩn bằng `opacity 0.01` (KHÔNG phải 0 — comment dòng ~"0.01, not 0":
   video opacity 0 có thể khiến Chromium ngừng cấp frame mới cho drawImage).
2. Snapshot: mỗi rAF tick, nếu `now - lastSnapshot >= 30` ms thì `drawImage(video)` vào một
   `<canvas>` MỚI tạo riêng cho khung đó (`document.createElement('canvas')`), push
   `{t: now, c: f}` vào mảng `frames`. Tức **~33 khung/s, mỗi khung là một canvas element sống
   với backing store riêng** — không tái sử dụng bitmap, không nén.
3. Kích thước khung: `w = min(640, videoWidth)`, `h = w * videoHeight/videoWidth` — tối đa
   **640 px rộng** (phim 16:9 → 640×360). Không hạ phân giải theo D.
4. Trim theo THỜI GIAN (sửa v1.0.13): giữ mọi khung trong cửa sổ `DubLiveDelayMs + 2000` ms
   (`while (frames[0].t < now - keep) frames.shift()`). Không trim theo số lượng.
5. Hiển thị: chọn khung mới nhất có `t <= now - effDelay` (`wanted = now - effDelay`, duyệt ngược),
   `drawImage` lên canvas phủ, căn giữa theo rect của video. Nếu chưa có khung đủ cũ (buffer đang
   nạp, hoặc sau seek) → `hideCanvas()` và **hiện video gốc trực tiếp (live, không trễ)** — không
   bao giờ hiện khung đen/đứng hình.
6. Sau seek/loadstart/emptied: `clearFrames()` + `seekAt = performance.now()`; `effDelay` ramp
   `0 → D` theo `min(D, now - seekAt)` — không phát lại khung trước seek.
7. Chỉ video CHÍNH được trễ (chấm điểm theo diện tích hiển thị, ×10 khi đang phát, min 100×60,
   quét document + shadowRoot + iframe cùng origin — giống `TapJs.kt`); quảng cáo góc không bị trễ.
8. `drawImage` throw (trang chặn lấy khung) → `captureFailed = true`, tắt trễ vĩnh viễn trong phiên
   + báo `DubLiveAudio.onDelayFailed`. Lưu ý: drawImage từ video cross-origin KHÔNG throw (chỉ
   đọc pixel mới throw) — canvas trễ hình không bị chặn bởi CORS; thứ bị CORS chặn là WebAudio
   tap (xem §3a).
9. Site fullscreen: canvas KHÔNG theo được (video được đưa lên surface riêng) → `tick()` tự
   `hideCanvas()` khi `document.fullscreenElement`; MainActivity tắt luôn trễ âm thanh/giọng
   (`setServiceDelayEnabled(false)` + `DubLiveTapSetDelay(0)`, MainActivity.kt:630–650). Chế độ
   fullscreen đồng bộ duy nhất là "Phóng to" của app.

**D hiện tại (~3,3–5,3 s) áp vào hình bằng cách nào:** D là biến thích ứng do `DubDelayTracker`
tính (EMA mẫu trễ + margin, kẹp trần 8 s — `MAX_DELAY_CAP_MS = 8000`, DubDelayTracker.kt:328,
`capDelayMs` dòng 1005–1011), service broadcast `TYPE_HUD` → MainActivity.kt:320–345 forward vào
WebView khi D đổi >200 ms: `window.DubLiveSetDelay($ms)` (canvas) + `DubLiveTapSetDelay(ms/1000)`
(DelayNode âm thanh gốc). Seed lúc mở trang/bấm bắt đầu: `Prefs.baseDelayMs` mặc định **5 s**
(3–20 s, Prefs.kt:87–100), `lastAppliedDelayMs = 5000` (MainActivity.kt:99–101, 801–806).
Giọng dịch áp cùng D bằng hold: `hold = D − latency của burst` (DubbingService.kt:1195–1212,
`voicePlayer.setVoiceHoldMs(holdMs)`; chunk phát tại arrival + hold).

**Đường local-file (không phải web):** `LocalPlayerActivity.kt` — ExoPlayer render vào TextureView,
snapshot `textureView.bitmap` mỗi 66 ms (~15 fps) vào `frameQueue` (Bitmap ARGB_8888, rộng tối đa
960 px theo TextureView), `cap = currentDelayMs/66 + 20` khung, ImageView phủ hiện khung đủ cũ
(`onSurfaceTextureUpdated` + `pumpFrame`). Cùng họ "frame queue", khác backend.

## 2. Chi phí kéo trễ hình lên 30–40 s BẰNG CÙNG CƠ CHẾ (canvas thô)

Số liệu tính từ hằng số code (§1): khung 640×360×4 byte = **921.600 B ≈ 0,88 MiB/khung**;
snapshot 30 ms → 33,3 khung/s; cửa sổ giữ = D + 2 s.

| D | Số khung giữ (D+2 s)×33,3 | RAM live khung hình | So với hiện tại |
|---|---|---|---|
| 5 s (seed) | ~233 | ~215 MB | — |
| 8 s (trần tracker) | ~333 | ~307 MB | — |
| 30 s | ~1067 | ~983 MB | 4,6× |
| **40 s** | **~1400** | **~1.290 MB (~1,26 GiB)** | 6× |

Chưa kể: canvas hiển thị (0,92 MB), video decode + WebView + app + hệ thống. Tốc độ cấp phát là
**~30,7 MB/s liên tục** (0,92 MB mỗi 30 ms) ở mọi D — GC phải thu hồi chừng đó; live set mới là
thứ tăng theo D. `frames.shift()` mỗi snapshot trên mảng 1400 phần tử là rẻ, không phải nút cổ chai.

- **CPU:** số lần drawImage/giây KHÔNG đổi theo D (snapshot 33/s + vẽ hiển thị mỗi rAF: 60 Hz,
  144 Hz trên iQOO Z9 Turbo theo comment DelayJs). Chi phí tăng thêm chủ yếu là GC quét 1400
  canvas sống + áp lực bộ nhớ. Pin: rAF + decode đã chạy liên tục ở D hiện tại; D=40 không thêm
  nhiều CPU thuần, nhưng GC nặng + nguy cơ kill sẽ làm pin/trải nghiệm tệ hơn.
- **Kết luận khả thi: KHÔNG KHAM NỔI ở dạng thô.** ~1,3 GB live heap trong renderer process của
  WebView trên máy RAM 12 GB (Android 16) là vùng nguy hiểm OOM — renderer bị kill → WebView trắng/
  crash, hoặc GC giật đến mức rAF rớt và canvas đứng hình (đúng lỗi v1.0.13 từng gặp ở quy mô nhỏ).
  Chưa có số đo thực tế (cần đo trên máy, §8), nhưng theo code thì đây là verdict "không", không
  phải "có thể thử".
- **Video dọc tệ hơn nhiều:** `h = 640 × (videoHeight/videoWidth)` — video 9:16 → khung 640×1138
  = 2,9 MB/khung → 40 s ≈ **4,1 GB**. Code không giới hạn chiều cao. Phim 18dj.tv là phim ngang
  16:9 nên con số 1,29 GB là trường hợp đang xét; video dọc/shorts sẽ chết chắc.
- **Chất lượng hình nếu cố giữ cơ chế thô:** muốn đủ RAM thì phải hạ phân giải/fps, ví dụ:
  480×270 @15 fps → khung 0,52 MB × 630 khung ≈ **327 MB** (vẫn nặng, hình mờ hơn ~25%);
  320×180 @12 fps → 0,23 MB × 504 ≈ **115 MB** (đủ RAM nhưng hình upscale 3,4× trên màn 1080p —
  mờ rõ, chuyển động 12 fps giật). Hạ xuống mức này là tụt chất lượng thấy được, trái với chỉ đạo
  "lấy thừa để môi khớp trên mọi phim" nếu tính cả chất lượng hình.
- **LocalPlayerActivity cũng chết tương tự:** Bitmap 960×540×4 = 2,07 MB/khung; cap = 40.000/66+20
  ≈ 626 khung → **~1,30 GB**. Cùng một bức tường, cùng một kết luận.

## 3. Các phương án khác (đánh giá theo code)

### (a) Video element thứ hai seek về — KHÔNG KHUYẾN NGHỊ cho web, chỉ khả thi có điều kiện

- **Code hiện tại:** không có bất kỳ lệnh seek nào lên `<video>` (grep `.pause()/.play()/currentTime =`
  trong `live/*.kt`: zero — chỉ có `player.play()` của ExoPlayer và `AudioTrack.play()`). Mọi chỗ
  đều dùng chung một element tốt nhất (`findBestVideo`/`__dubLiveTapBest`).
- **CORS/taint:** canvas `drawImage` không bị chặn bởi taint (chỉ `getImageData` mới throw) — nhưng
  phương án này không dùng canvas nên điểm đó không liên quan. Vấn đề thật là **nguồn video**:
  `TapJs.kt` đã ghi rõ — video cross-origin không có `crossOrigin` thì `createMediaElementSource`
  cho ra im lặng vĩnh viễn; video MSE/blob: (`blob:` URL của hls.js/MediaSource) **không thể gắn
  vào element thứ hai** (một MediaSource chỉ gắn một element; blob URL không tải lại được như file).
  YouTube (README.md:9 — app hỗ trợ youtube.com trong cùng WebView) dùng MSE/DASH + URL ký theo
  phiên (googlevideo, signature hết hạn, ràng buộc IP) → element thứ hai gần như chắc chắn chết.
- **Băng thông:** element thứ hai tải lại luồng từ đầu (hoặc từ vị trí seek): 2× băng thông, trừ khi
  browser cache phục vụ lại range request (không đảm bảo, không có code nào kiểm soát cache).
  Với phim 18dj.tv (VOD), 2× một luồng ~1–3 Mbps là chấp nhận được trên Wi-Fi, nhưng là chi phí thật.
- **Seek:** element thứ hai không seek từng khung — nó chỉ `play()` từ vị trí bắt đầu trễ D (seek một
  lần lúc khởi động, rồi phát liên tục). Seek của `<video>` là frame-accurate (giải mã từ keyframe
  gần nhất tới đích), sai số đồng bộ không đến từ GOP; cái tốn là thời gian seek khởi động.
  Nhưng phải gắn MediaElementSource/tap cho element thứ hai nữa nếu muốn lấy tiếng từ nó — và tap
  hiện tại gắn vào element CHÍNH đang phát live (tiếng lấy live mới dịch được). Hai element = hai
  graph, hai lần xử lý.
- **Verdict (a):** chỉ khả thi khi `video.currentSrc` là URL trực tiếp (mp4 tiến triển hoặc m3u8
  lấy được). Trên YouTube/MSE/blob: chết. Không phải phương án chính cho "mọi phim".

### (b) Bóc URL phát bằng ExoPlayer với buffer 30–40 s — CHỈ cho local file / VOD trực tiếp, mất trang web

- **Code hiện tại:** ExoPlayer CHỈ tồn tại trong `LocalPlayerActivity.kt:279`
  (`ExoPlayer.Builder(this)` trần — **không cấu hình LoadControl**, không `setPlaybackParameters`,
  không seek-delay nào; `MediaItem.fromUri(uri)`, `prepare()`, `play()` dòng 306–309). WebView
  không có ExoPlayer nào. `LocalTapProcessor.kt` là tap audio trong pipeline ExoPlayer (copy PCM
  48 kHz stereo trước khi qua volume) — chứng minh app đã biết cách lấy tiếng từ ExoPlayer.
- **Buffer 30–40 s trong ExoPlayer:** không cần frame queue — ExoPlayer đã đệm media theo
  `LoadControl` (mặc định media3 khi không cấu hình: code không ghi số, xem §8). Trễ hình kiểu
  ExoPlayer không phải "giữ 40 s khung hình" mà là **chạy hai player lệch nhau 40 s** (player A
  chạy live, tắt tiếng, tap audio → dịch; player B chạy video, bắt đầu phát từ vị trí 0 khi A đã
  tới 40 s) — hoặc một player phát chậm... Không: một player không thể vừa ở vị trí live (lấy
  tiếng) vừa ở vị trí trễ (hiện hình). Bắt buộc 2 decoder → 2× CPU giải mã + 2× I/O, 0 MB RAM
  khung hình. Với **file local** điều này khả thi ngay (URI file mở được hai lần, không ký phiên).
- **Mất gì:** toàn bộ UI/phụ đề/tập tiếp theo của trang web (18dj.tv: chọn tập, phụ đề Trung cháy
  cứng vào hình thì còn, nhưng phụ đề rời/subtitle track của trang thì mất; quảng cáo/xác minh của
  trang mất theo). **DRM:** video có Widevine trong WebView không bóc được license session sang
  ExoPlayer (CDM session gắn với page) — phim DRM chết. Video ký theo phiên (URL có token/signature
  hết hạn sau phút) có thể chết giữa chừng khi player B xin segment mới.
- **Verdict (b):** phương án dự phòng tốt cho **file local + VOD mp4/m3u8 trực tiếp không DRM**;
  không phải phương án chính cho web (18dj.tv/YouTube) vì mất trang và chưa chắc bóc được URL.

### (c) VOD pre-roll / lookahead — PHƯƠNG ÁN ĐÚNG VỀ BẢN CHẤT, và nó chính là trễ cố định

- **18dj.tv là VOD, không có live edge** — không có "đầu trực tiếp" nào phải đuổi theo. "Trễ 40 s"
  thực chất là: video cứ phát live (tiếng đi vào Gemini Live để dịch), hình hiện ra trễ 40 s.
  Không cần "dịch trước rồi mới phát" theo nghĩa tạm dừng — xem ngay dưới.
- **Luồng tap audio hiện tại lấy tiếng từ đâu:** `TapJs.kt` — `MediaElementSource` gắn vào video
  element của trang → `ScriptProcessor(2048)` resample 16 kHz mono → `DubLiveAudio.onPcm(base64)`
  vào app, **LIVE, không qua DelayNode** (nhánh tap rẽ trước DelayNode: `source.connect(tap)`;
  nhánh nghe mới qua `DelayNode(22 s) → Gain → destination`). Fallback khi tap chết/CORS:
  system capture (`AudioPlaybackCapture`, DubbingService) hoặc mic. Tức tiếng dịch luôn là tiếng
  live — đây là điều kiện tiên quyết để lookahead hoạt động.
- **Vì sao "tạm dừng video L giây trong khi tap chạy trước" là SAI về cơ chế:** không có code nào
  pause video (grep §3a), và pause sẽ giết chính nguồn tiếng — video pause thì MediaElementSource
  ra im lặng, ScriptProcessor không có PCM, Gemini không có gì để dịch. **Lookahead chỉ tồn tại khi
  video VẪN PHÁT.** Cái "chờ" duy nhất đúng là: video phát live (ẩn sau overlay chờ), tiếng dịch
  chạy, hình hiện ra sau khi buffer đủ D — tức đúng cơ chế canvas hiện tại, chỉ thay đổi hành vi
  trong lúc nạp buffer (xem §6).
- **Biến thể pre-roll thật sự khác:** ghi lại giọng dịch (PCM 24 kHz mono = 48 KB/s → 40 s chỉ
  **1,9 MB**) trong lúc video phát ẩn, rồi seek video về đầu và phát lại từ đầu cùng giọng đã ghi
  — nhưng sau khi phát lại, các câu tiếp theo vẫn cần dịch live với trễ D của chúng, nên biến thể
  này chỉ giải quyết **khởi động lạnh** (40 s đầu), không thay thế trễ steady-state. Không đề xuất
  làm phương án chính; nó là tối ưu phụ nếu 40 s chờ khởi động bị coi là quá dài.
- **Verdict (c):** pre-roll/lookahead = trễ cố định 40 s với canvas (hoặc second-decoder). Không có
  phép màu thứ ba: muốn hình trễ 40 s thì hoặc trữ 40 s khung hình (nén lại), hoặc chạy decoder
  thứ hai trễ 40 s.

## 4. Trễ âm thanh gốc hiện tại + với trễ 30–40 s

Hai đường, tùy chế độ âm thanh:

- **Tap mode (web + local-tap, chế độ đang dùng trên 18dj.tv):** `TapJs.kt` — WebAudio
  `ctx.createDelay(22.0)` (max 22 s theo spec Web Audio), `delay.delayTime.value =
  DubLiveTapDelaySec`; `DubLiveTapSetDelay(sec)` kẹp `0..20` (`Math.max(0, Math.min(20, …))`),
  đổi bằng `setTargetAtTime(n, currentTime, 0.3)` (glide ~1 s, v1.0.17). **Trần cứng 20 s (kẹp
  code) / 22 s (DelayNode) → 30–40 s KHÔNG làm được bằng một DelayNode.** Cách sửa đã thấy trước:
  chuỗi 2 DelayNode (22+22=44 s) hoặc chuyển âm thanh gốc sang `DelayedBackgroundPlayer`.
- **Capture/mic mode:** `DelayedBackgroundPlayer.kt` — `DelayedAudioQueue` live-delay,
  `setDelayMs` kẹp `0..25000` ms (dòng `targetDelayMs = ms.coerceIn(0L, 25000L)`), glide hiệu dụng
  tối đa 10 ms/10 ms (tức tốc độ 1× thời gian thực — đổi 0→40 s mất 40 s mới ổn định; lúc khởi
  động phải set thẳng giá trị hiệu dụng hoặc set target trước khi feed). Queue
  `DelayedAudioQueue(maxBlocks=2600, staleMs=500)`: mỗi block 10 ms (CHUNK_BYTES=1920, stereo
  48 kHz) → **tối đa 26,0 s** — 40 s cần `maxBlocks ≥ 4000` (+ dự phòng → 4500).
- **Giọng dịch:** `VoicePlayer.kt` — cùng `DelayedAudioQueue` nhưng chế độ fixed-hold
  (`add(arrival, data, holdMs)`, hold = D − latency, `staleMs=700`). Queue này chứa chunk PCM
  theo chunk Gemini (không phải block 10 ms cố định) nên sức chứa theo thời gian phụ thuộc độ dài
  chunk — cần kiểm tra, nhưng với hold 40 s thì queue phải chứa ~40 s giọng (xem byte bên dưới).
- **Byte âm thanh 40 s — nhỏ, không đáng lo (như brief dự đoán):** gốc stereo 48 kHz 16-bit =
  192.000 B/s → 40 s = **7,68 MB**; giọng mono 24 kHz 16-bit = 48.000 B/s → 40 s = **1,92 MB**.
  Tổng <10 MB. Âm thanh KHÔNG phải nút cổ chai của trễ 40 s; nút cổ chai duy nhất là hình.
- **Cần đổi:** `TapJs` (chuỗi DelayNode hoặc bỏ DelayNode, dùng background player),
  `DelayedBackgroundPlayer.setDelayMs` (25 s → ≥45 s), `DelayedAudioQueue.maxBlocks` (2600 → ≥4500),
  `Prefs` (xem §7), glide khởi động.

## 5. Thiết bị đo tốc độ stretch THẬT — điểm code (chưa làm, chỉ chỉ rõ)

Vấn đề: HUD hiện "tốc độ 1,50×" nhưng đó là **target của tracker, không phải tốc độ áp dụng thật**.
`DubDelayTracker.kt:634` `targetSpeed` (mặc định 1.0), `stretchUpdate` (1929–1977) + `ratioFloorMs`
(1979–...) nâng target theo tỉ lệ giọng/gốc; HUD in `targetSpeed` (diagnostics ~3595–3597).
`DubbingService` gọi `voicePlayer.setPlaybackSpeed(target)` (đường gọi qua tracker
`currentStretchSpeed`, DubDelayTracker.kt:1844).

**`VoicePlayer.kt` — chuỗi áp dụng thật:**

- `setPlaybackSpeed(speed)` (dòng ~841–844): **chỉ ghi `pendingSpeed = speed.coerceIn(1.0, 1.5)`**;
  nếu `!stretchSupported` thì return im lặng, không báo ai.
- `applyPendingSpeed()` (dòng ~856–885, chạy mỗi vòng lặp player thread, gọi ở ~435–438):
  `if (want == currentSpeed) return`; rồi `track.playbackParams =
  PlaybackParams().setSpeed(want.toFloat())`; `currentSpeed = want` + log "playback speed -> X".
  **Sau lệnh này KHÔNG BAO GIỜ đọc lại track** — không có `track.playbackParams.speed` nào trong
  file.
- **Lỗi throw một lần → kẹt 1,0× vĩnh viễn, không ai biết:** trong `catch` (dòng ~868–876):
  `stretchSupported = false; pendingSpeed = 1.0; currentSpeed = 1.0`, thử `setSpeed(1.0f)` lại
  (cũng có thể throw, đã nuốt). `stretchSupported` là `@Volatile private var` (dòng 269), **không
  chỗ nào set lại true** (kể cả `releaseTrack()` — dòng ~736–738 chỉ reset `currentSpeed = 1.0` để
  track mới áp lại pending). Từ đó mọi `setPlaybackSpeed()` đều return sớm ở dòng 842–843.
  Tracker vẫn nâng `targetSpeed` lên 1,5 (floor tỉ lệ), service vẫn gọi set, HUD vẫn in 1,50× —
  trong khi track thật chạy 1,0×. Đây là ứng viên số 1 cho "ghim 1,50×" trên ảnh 12:00 có thể là
  số giả.
- `playbackSpeed` getter (dòng 847) trả `currentSpeed` — **biến nhớ, không phải giá trị đọc từ track**.
  `isStretchSupported` (850) có sẵn nhưng không thấy HUD nào dùng.

**`confirmPlayStart()` (dòng ~677–...) — vì sao số đo play cũng sai theo khi stretch bật:**

- Nếu `currentSpeed != 1.0` (dòng 687): **bỏ qua hoàn toàn poll playhead**, báo ngay `playWall`
  DỰ BÁO từ `beginPlayMeasurement` (dòng ~621–660), `estimated=true`. Dự báo đó tính
  `tsWall + backlog/(24000 × currentSpeed)` — tức **chia backlog cho tốc độ TARGET, giả định target
  là thật**. Nếu Sonic thật chạy 1,0× trong khi target 1,5×, mỗi dự báo play sớm hơn thật
  `backlog × (1 − 1/1,5) = 33% backlog` (backlog ~600 ms → sai ~200 ms/burst), và sai số này đi
  thẳng vào pipeline EMA → D.
- `onBurstPlayed` trong tracker (DubDelayTracker.kt ~1768–1838): `playSliceWall = playWall +
  (dubCum − startCum)/playSpeed` với `playSpeed = targetSpeed` (dòng 1777) — **lại dùng target**.
  Nếu tốc độ thật là 1,0× mà chia cho 1,5×, độ trôi cuối burst bị ĐO THIẾU
  `(1 − 1/1,5) = 33%` độ dài dub trong burst. Con số: **1800 ms ≈ 33% × ~5,4 s dub** — đúng một
  burst giọng ~5,4 s (tương đương câu gốc ~3,7 s ở tỉ lệ 1,47×, tức một câu thoại bình thường trong
  thế giới sim: line 1,5–3,0 s + khoảng nối). Đây là lời giải định lượng khớp nhất cho số hạng
  ~1800 ms chưa giải thích được — cần thiết bị đo xác nhận, không kết luận vội.
- Poll playhead thật chỉ chạy khi `currentSpeed == 1.0` (dòng 699–...): `readPlayhead()` (596–...)
  dùng `AudioTrack.getTimestamp()` + `System.nanoTime()` (đã sửa clock v1.0.20, kiểm tra tươi 60 s).

**Danh sách điểm code cần sửa cho thiết bị đo (KHÔNG sửa trong task này):**

1. `VoicePlayer.applyPendingSpeed()` (~856–885): sau `setSpeed` thành công, đọc lại
   `track.playbackParams.speed`, lưu `appliedSpeedReadback: Double`, log
   `"playback speed -> target %.2f (readback %.2f)"`; nếu readback ≠ target quá 0,02 thì log cảnh
   báo + expose cho HUD. Sau `buildTrack()`/rebuild cũng đọc lại một lần (track mới về 1,0× rồi
   mới áp pending — `releaseTrack()` đã reset `currentSpeed=1.0`).
2. `VoicePlayer` player loop (~435–570): trong lúc một burst đang phát, lấy mẫu `readPlayhead()`
   mỗi vòng lặp, lưu cặp (wall, framePosition) đầu–cuối burst; tốc độ thật =
   `(dFrames/dWall)/24000` (framePosition đếm frame ĐẦU RA sau stretch nên độ dốc chính là tốc độ
   thật — không cần ánh xạ input/output). Ghi vào log/HUD mỗi burst: `trueSpeed`.
   (Phương án phụ: sửa `confirmPlayStart()` để poll cả khi speed≠1.0 với ngưỡng
   `firstFrame × currentSpeed` thay vì `firstFrame` — cần kiểm chứng công thức trên máy, không
   đoán; phương pháp độ dốc ở trên là chính vì không phụ thuộc công thức ánh xạ.)
3. `VoicePlayer.setPlaybackSpeed()` (~841–844) + `stretchSupported`: khi `!stretchSupported`,
   log + báo lên HUD/log Live một lần ("Stretch không hỗ trợ trên máy này — đang chạy 1,0×")
   thay vì return im lặng; thêm cơ chế thử lại sau rebuild (hiện tại false là vĩnh viễn).
4. `DubDelayTracker` diagnostics (~3595–3597) + `DubbingService` HUD: hiện thêm
   `tốc độ thật (readback/đo) vs target`, và cờ `estimated` của `onBurstPlayed`; khi stretch bật,
   mọi số cuối-burst phải gắn cờ "đo bằng tốc độ giả định" cho tới khi thiết bị đo xác nhận.
5. `DubDelayTracker.onBurstPlayed` (~1768–1777): `playSpeed` dùng cho `playSliceWall` phải lấy
   tốc độ thật đã đo của burst đó (snapshot từ VoicePlayer), không lấy `targetSpeed`.

## 6. Mức trễ 40 s suy ra từ trường hợp xấu nhất (không lấy trung bình)

**Số liệu đầu vào (đọc được từ code + BUILD_NOTES):**

- Độ trễ dịch đầu burst (lag0): D quan sát 3,3–5,3 s; mẫu slice cuối 4,5 s, **tối đa 12,0 s**
  (ảnh 09:21); ASR 518–536 ms (n=25/3); pipeline TB 106–374 ms. Tracker coi mẫu >20 s là không
  hợp lệ (`MAX_SAMPLE_MS = 20000`, DubDelayTracker.kt:246–254).
- Tỉ lệ giọng/gốc: **1,42–1,51×** (đo Rf1 floor 1,32–1,45; BUILD_NOTES).
- Câu thoại (line) trong thế giới sim hiệu chuẩn theo phim thật: **1,5–3,0 s**, khoảng lặng
  100–400 ms (sim1018/sim1022). Nhưng **VAD turn gộp**: turn kết thúc sau 700 ms im lặng
  (VadDetector.kt:85, `silenceFramesToEnd = 35` × 20 ms) hoặc 300 ms dưới ngưỡng bắt đầu
  (dòng 107), mà khoảng lặng phim chỉ 100–400 ms → turn gộp nhiều line: **trung vị ~53 s**
  (sim1022 header), ảnh 09:21: 1 lượt/60 s chứa **53,8 s lời nói** → giọng dub của một turn gộp
  dài tới ~53 × 1,47 ≈ **79 s**. Burst giọng tách theo `BURST_GAP_MS = 300 ms`
  (DubDelayTracker.kt:303) / `AudioBurstDetector(gapMs=500)` — thoại dày thì burst cũng dài hàng
  chục giây.
- **Tiêu chí đúng cho D cố định (suy ra, cần sim xác nhận):** chunk giọng ở vị trí gốc p trong
  burst đến hạn phát lúc `D + 1,47·p` (phát 1,0×, không stretch), tới nơi lúc `p + lag(p)`.
  Cần `lag(p) ≤ D + 0,47·p` với mọi p. Viết `lag(p) = lag0 + g·p` → cần
  `D ≥ lag0 + (g − 0,47)·p`. Tốc độ tích lũy lag quan sát trong chế độ thoại dày:
  backlog tăng ~5,8 s/phút → **g ≈ 0,097/s ≪ 0,47** (BUILD_NOTES phân tích ảnh 09:21). Hệ quả:
  **độ dài burst KHÔNG lái D** — hệ số 0,47·p (giọng dài hơn gốc tạo ra slack) lớn hơn tốc độ
  tích lũy lag; D bị lái bởi **lag0** (độ trễ dịch của đầu burst). Kể cả turn gộp 54 s:
  nhu cầu thêm chỉ là `lag0 + (0,097−0,47)×54 < lag0`.
- **Vậy D = 40 s bao phủ:** lag0 tối đa đã quan sát 12 s → dư 28 s (3,3×); trần mẫu hợp lệ 20 s
  → dư 2×; ngay cả khi lag0 vọt gấp đôi kỷ lục (24 s) vẫn còn dư 16 s. 30 s cũng bao phủ 12 s
  (2,5×), nhưng theo chỉ đạo 12:40 ("lấy thừa", "mọi phim đều môi khớp") và vì chi phí tăng thêm
  30→40 s sau khi nén khung là nhỏ (§7), **lấy 40 s, không lấy 30 s**.
- **Cảnh báo honesty:** bất đẳng thức trên dùng g đo được trong chế độ stretch/playout-backlog cũ,
  không phải g của riêng pipeline dịch dưới trễ cố định — **bắt buộc chạy sim mô hình trễ cố định**
  (adapt sim1025: D cố định 40, speed 1,0, đo end-burst lateness + stale-drop trên 4 thế giới +
  sim1018) trước khi build. Nếu sim thấy g > 0,47 ở chế độ thoại dày 100% duty + tỉ lệ 1,51 thì
  không có D hữu hạn nào cứu được — lúc đó mới cần van xả/stretch nhát ngắn. Sim1027 gate FAIL
  **không áp dụng** cho thiết kế này (nó gate thiết kế trần 1,25× + van, không phải trễ cố định).

## 7. Phương án ĐỀ XUẤT (chính + dự phòng) + hành vi khởi động + điểm code cần đổi

### Phương án chính: trễ cố định 40 s + khung hình nén JPEG (giữ kiến trúc canvas)

- Giữ nguyên `DelayJs.kt` (canvas phủ, video gốc phát live bên dưới, chọn video chính, ramp sau
  seek, tắt ở site fullscreen) và `TapJs.kt` (tap tiếng live). Chỉ thay **nội dung buffer**:
  snapshot → `canvas.toBlob('image/jpeg', 0.7)` (async) thay vì giữ canvas sống; hiển thị giải mã
  blob → `createImageBitmap` / `Image` rồi `drawImage`. Độ phân giải giữ **640 px** (không tụt),
  fps snapshot **30→15/s** (mỗi 66 ms) để giảm CPU nén.
- RAM: 15 fps × 42 s = 630 khung × ~40–60 KB (JPEG 640×360 q0,7) ≈ **25–38 MB** (so với 1.290 MB
  thô). Ở 30 fps: ~1.260 khung ≈ 50–75 MB — vẫn được, tùy CPU nén đo được trên máy.
- CPU: nén JPEG 15/s + giải mã khi hiển thị (mỗi khung hiển thị giải mã 1 lần, vẽ lại mỗi rAF từ
  bitmap đã giải mã). Chi phí chưa đo được bằng đọc code — **cần đo trên máy**: thời gian
  `toBlob` mỗi khung trên iQOO Z9 Turbo, có rớt rAF không. Nếu nén quá nặng: giảm xuống 12 fps
  hoặc q0,6, hoặc nén trên worker (OffscreenCanvas — cần kiểm tra WebView hỗ trợ).
- Chất lượng: JPEG q0,7 @640 px trên màn điện thoại ~1080p: mềm nhẹ, không mờ nát; 15 fps: chuyển
  động nhanh hơi giật so với 30 fps hiện tại — chấp nhận được cho phim thoại (môi khớp quan trọng
  hơn mượt chuyển động nền). Ghi rõ trade-off này cho user.
- Trễ cố định nghĩa là **tắt vòng thích ứng D**: `DubDelayTracker` không điều chỉnh D nữa (D = 40 s
  hằng số; tracker chỉ còn đo lag/latency để tính hold và chẩn đoán). Khóa stretch 1,0×
  (`targetSpeed` cố định 1,0; bỏ `stretchUpdate` floor tỉ lệ) → **hết méo giọng do stretch**.
  Van xả đuôi + stale-drop 700 ms gần như không nổ (chỉ nổ khi latency nhảy >40,7 s).
- **Điểm code cần đổi (liệt kê, không sửa):**
  - `Prefs.kt:47,77` (`DEFAULT_MAX_DELAY_SEC=20`, `maxDelayMs` kẹp 3..20) và `Prefs.kt:87–100`
    (`baseDelayMs` kẹp 3..20) → nới lên 45 (hoặc bỏ kẹp trên cho trễ cố định); `SettingsActivity.kt`
    SeekBar `progress 0..17 +3` (dòng 105–126, 217–218) → cập nhật thang.
  - `DubDelayTracker.kt:328` `MAX_DELAY_CAP_MS=8000` + `capDelayMs` (1005–1011) → D cố định 40 s
    (hoặc bypass cap khi ở chế độ fixed-D); `MIN_DELAY_MS=3000` không ảnh hưởng.
  - `MainActivity.kt:99–101` seed 5000, `applyBaseDelayToWebView()` (801–815), `adjustDelay()`
    kẹp `coerceIn(3,20)` (dòng ~840) → seed 40 s; nút ±1 s giữ nguyên (nudge quanh 40 s).
  - `DelayJs.kt`: `DubLiveDelayMs=5000` → 40000; `keep = delay+2000` giữ nguyên (42 s);
    thay snapshot canvas sống bằng toBlob JPEG; `DubLiveSetEnabled` ramp giữ nguyên.
  - `TapJs.kt`: `createDelay(22.0)` + `DubLiveTapSetDelay` kẹp 0..20 → **chuỗi 2 DelayNode
    (22+22=44 s)** hoặc chuyển âm thanh gốc sang `DelayedBackgroundPlayer` (§4).
  - `DelayedBackgroundPlayer.kt`: `setDelayMs` kẹp 0..25000 → 0..45000; glide 10 ms/10 ms →
    khi khởi động set thẳng hiệu dụng (hoặc slew nhanh) để không mất 40 s ổn định thêm;
    `DelayedAudioQueue(maxBlocks=2600)` → **≥4500** (45 s @10 ms/block).
  - `VoicePlayer.kt`: hold queue `DelayedAudioQueue(staleMs=700)` mặc định maxBlocks 2600 — kiểm
    tra sức chứa theo chunk Gemini (chunk ≠ 10 ms) rồi nới tương ứng với hold 40 s.
  - `DubbingService.kt:974–985` (callback `onDelayChanged` → `backgroundPlayer.setDelayMs` +
    broadcast HUD) và `:686` — giữ, nhưng D giờ là hằng số.
  - `LocalPlayerActivity.kt`: frame queue Bitmap → cùng bài toán: hoặc nén JPEG, hoặc dùng
    second-player (file local mở được 2 lần — xem dự phòng).

### Phương án dự phòng: second-decoder lookahead (0 MB RAM khung hình)

- Web: `<video>` thứ hai cùng `currentSrc`, `play()` khi element chính tới 40 s (seek 1 lần về 0),
  hiện hình element thứ hai, tắt tiếng nó (tiếng lấy từ element chính qua tap). Hoặc ExoPlayer
  với URL bóc được (HLS: media3 hỗ trợ HLS native — khả thi nếu URL là m3u8 trực tiếp).
- Điều kiện tiên quyết (chưa xác minh, §8): `currentSrc` là URL trực tiếp (mp4/m3u8), không phải
  `blob:`, không DRM, không ký phiên ngắn. Chết trên YouTube/MSE. Tốn 2× băng thông + 2× giải mã.
- File local: hai ExoPlayer cùng URI — khả thi ngay, là dự phòng tốt nhất cho LocalPlayerActivity.

### Hành vi khởi động đề xuất (pre-roll 40 s)

- **Hiện tại:** bấm "Bắt đầu lồng tiếng" (`MainActivity.startTapDub` ~961, `startDub` ~940) **KHÔNG
  pause/play video** — video đang phát cứ phát; app chỉ start service + đẩy seed D vào canvas+tap
  (`applyBaseDelayToWebView`). Trong D giây đầu, canvas chưa có khung đủ cũ → hiện **video live**
  (không trễ), giọng chưa có (hold ~D), âm thanh gốc qua DelayNode/background queue đang nạp →
  (gần như) im lặng. Với D=5 s điều này chỉ kéo dài 5 s, ít ai để ý; với D=40 s nó thành 40 s xem
  live không tiếng + không giọng — tệ.
- **Đề xuất:** trong lúc nạp buffer (effDelay < D), **che video live bằng overlay chờ**
  ("Đang chuẩn bị lồng tiếng… 0:12 / 0:40", tiến độ = effDelay/D) — video vẫn phát ngầm bên dưới
  (tiếng tap live → Gemini dịch, buffer canvas nạp, DelayNode nạp). Hết 40 s, overlay biến mất,
  canvas + giọng + tiếng gốc trễ cùng hiện, đồng bộ từ giây đầu tiên user thấy. **Không mất nội
  dung nào** — phim chỉ bị dời 40 s; user bấm bắt đầu rồi chờ 40 s (uống nước) thay vì xem live
  lệch. Đổi tập/seek: buffer xóa (DelayJs `clearFrames`) → chờ lại 40 s (overlay hiện lại) —
  đây là giá của trễ cố định, nói rõ với user.
- **KHÔNG dùng phương án "pause video L giây":** pause giết nguồn tiếng (§3c); không có code pause
  nào trong repo. Nếu muốn rút ngắn chờ khởi động: pre-roll chỉ cho 40 s đầu bằng giọng ghi sẵn +
  seek về đầu (§3c) — tối ưu phụ, không làm ở v1.0.23.
- Site fullscreen vẫn tắt trễ (giữ nguyên giới hạn; dùng "Phóng to" của app).

## 8. Cái gì CHƯA xác minh được bằng đọc code (cần đo trên máy thật)

1. **RAM/CPU thật của canvas thô ở D lớn và của JPEG:** chưa có số đo WebView heap/GC/rAF trên
   iQOO Z9 Turbo ở D=40 (thô hay JPEG, 15 hay 30 fps, `toBlob` mất bao nhiêu ms/khung).
   Số 1,29 GB là tính từ hằng số code (640×360×4), chưa phải đo thực tế — Chromium có thể nén
   backing store nội bộ (không có bằng chứng trong code; đừng giả định).
2. **`video.currentSrc` thật trên 18dj.tv/YouTube:** `blob:` (MSE/hls.js) hay URL trực tiếp
   (mp4/m3u8)? Quyết định phương án dự phòng (a)/(b) sống hay chết. Đo bằng JS trên trang thật.
   TapJs comment nói hls.js dùng blob: nhưng trang 18dj.tv cụ thể chưa ai ghi lại currentSrc.
3. **Tốc độ áp dụng thật của stretch (Sonic):** HUD 1,50× có thể là số giả (§5). Cần thiết bị đo
   §5 chạy trên máy thật mới đóng được số hạng ~1800 ms.
4. **Hành vi DelayNode khi nạp:** 40 s đầu âm thanh gốc có im lặng hoàn toàn không (lý thuyết Web
   Audio: delay line rỗng → ra 0), hay Chromium có hành vi khác. Đo bằng tai/log.
5. **Tốc độ tích lũy lag g dưới trễ cố định:** bất đẳng thức §6 dùng g≈0,097/s đo trong chế độ cũ;
   cần sim mô hình fixed-D (adapt sim1025) + đo máy: lag(p) tối đa trong turn gộp 54 s, stale-drop,
   end-burst lateness ở D=40/speed=1,0.
6. **Giới hạn heap của WebView renderer trên Android 16/OriginOS** cho máy user: không có trong code;
   chỉ đo thực tế mới biết 1,29 GB có crash không (dự đoán: có).
7. **Chunk Gemini Live dài bao nhiêu ms:** quyết định sức chứa thật của hold queue `VoicePlayer`
   (maxBlocks=2600 block) ở hold 40 s — chưa đọc được từ code (chunk size do server quyết định).

## Phụ lục — vị trí code chính (file:dòng, đã đọc)

- Trễ hình web: `DelayJs.kt` toàn bộ (canvas phủ, snapshot 30 ms, 640 px, trim D+2 s, effDelay ramp,
  hideCanvas khi chưa đủ khung, tắt ở fullscreen).
- Tap tiếng + DelayNode: `TapJs.kt` (`createDelay(22.0)`, `DubLiveTapSetDelay` kẹp 0..20, tap live
  rẽ trước DelayNode, CORS/taint, probe im lặng 2,2 s, fallback capture sau 8 s không video).
- Âm thanh gốc trễ (capture): `DelayedBackgroundPlayer.kt` (`setDelayMs` kẹp 0..25000, glide 10 ms/
  10 ms, `CHUNK_BYTES=1920` = 10 ms stereo 48 kHz, queue `DelayedAudioQueue`).
- Hàng đợi âm thanh: `DelayedAudioQueue.kt` (`maxBlocks=2600`, `staleMs=500` mặc định / 700 giọng).
- Giọng dịch + stretch: `VoicePlayer.kt` (`setPlaybackSpeed` ~841, `applyPendingSpeed` ~856–885,
  `confirmPlayStart` ~677, `beginPlayMeasurement` ~621, `readPlayhead` ~596, `playbackSpeed` getter
  ~847, `stretchSupported` ~269, buffer `max(minBuf, min(minBuf*2,12000))`).
- Tracker/D/stretch target/HUD: `DubDelayTracker.kt` (`MAX_DELAY_CAP_MS=8000` :328, `targetSpeed`
  :634, `stretchUpdate` :1929, `ratioFloorMs` :1979, `MAX_SLICES=120` :242, `BURST_GAP_MS=300` :303,
  `TX_GAP_MS=800` :395, diagnostics tốc độ ~3595, `onBurstPlayed` playSliceWall ~1768–1777).
- Service khởi động + hold: `DubbingService.kt` (`onStartCommand` :582, tracker tạo :968–985,
  hold `setVoiceHoldMs` :1195–1212, background player :2370–2390).
- Activity: `MainActivity.kt` (HUD forward D :320–345, `applyBaseDelayToWebView` :801–815,
  `adjustDelay` kẹp 3..20 ~:840, fullscreen tắt trễ :630–650, `startTapDub` ~:961 không pause video).
- Local player: `LocalPlayerActivity.kt` (ExoPlayer.Builder trần :279, frame queue Bitmap 66 ms,
  cap delay/66+20, 960 px).
- VAD/turn: `VadDetector.kt` (kết turn sau 700 ms im lặng :85 / 300 ms dưới ngưỡng :107, pre-roll
  500 ms :140); `AudioBurstDetector.kt` (gap 500 ms).
- Prefs/giới hạn: `Prefs.kt` (`DEFAULT_MAX_DELAY_SEC=20` :47, `maxDelayMs` kẹp 3..20 :77,
  `baseDelayMs` :87–100); `SettingsActivity.kt` (SeekBar 3..20 s :105–126, 217–218).
- Số liệu máy/sim: `BUILD_NOTES.md` (ảnh 12:00 v1.0.22, ảnh 09:10/09:21 v1.0.21, sim1022–1027,
  v1.0.23 SIM GATE FAIL — gate cho thiết kế trần stretch, không áp dụng cho trễ cố định).
