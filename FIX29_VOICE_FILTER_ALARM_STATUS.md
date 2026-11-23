# FIX29 — Lọc giọng gốc, chỉ giữ tiếng nền (cách B) — v1.2.8 / versionCode 13

Ngày: 2026-11-22. Nền: FIX28 (FIX21 nguyên mẫu + 2 key + xoay key 5 phút).
Zip: `ALDA-v10-FIX29-Voice-Filter-Alarm.zip` (full source, build như mọi lần qua GitHub Actions).

## User yêu cầu (22/11 21:46)

"Xem trên ứng dụng khác vẫn trễ. Có cách nào bỏ âm tiếng gốc, chỉ giữ âm
nền. Xong lồng giọng dịch vào âm nền gốc"

User chọn **cách B** (22/11 22:22): tắt hẳn tiếng app kia, phát qua kênh
báo thức.

## Cách làm

**Lọc giọng (DSP, không cần model/AI):** tiếng phim stereo thì lời thoại
thường nằm giữa 2 loa (giống nhau ở 2 kênh), nhạc + hiệu ứng nằm 2 bên.
Lấy hiệu 2 kênh (trái − phải) thì lời thoại mất, tiếng nền còn lại — kỹ
thuật karaoke / OOPS cổ điển, chạy ngay trong lúc xem.

**Đường tiếng ở chế độ "Xem trên app khác" + bật lọc giọng:**
1. Thu tiếng app kia ở dạng **stereo 48 kHz** (trước đây mono 16 kHz —
   mono thì không tách được giọng).
2. Mỗi đoạn stereo đi 2 nhánh: (a) trộn xuống mono 16 kHz cho Gemini
   dịch (đường dịch không đổi); (b) qua bộ lọc giọng rồi phát lại làm
   tiếng nền.
3. **STREAM_MUSIC về 0** — tắt hẳn tiếng app kia (kể cả lời thoại gốc).
4. Tiếng nền đã lọc + giọng dịch phát qua **kênh báo thức**
   (USAGE_ALARM, âm lượng riêng). Âm lượng báo thức được đặt theo đúng
   độ to nhạc lúc đó của user, và cả 2 âm lượng đều được trả lại như cũ
   khi dừng lồng tiếng.
5. Không xin audio focus nữa (không cần duck app kia, và tránh trường
   hợp app video dừng khi mất focus).

## File thay đổi

- MỚI `core/audio/VoiceFilterDsp.kt` — thuần Kotlin, không phụ thuộc
  Android: `stereo48kToMono16k` (trộn + lọc box 3:1 chống alias) và
  `VocalRemover` (hiệu 2 kênh × 0.7, ra stereo (L−R, R−L)). Có bảo vệ
  mono: nội dung mono/gần mono thì cho tiếng gốc đi qua ở 0.6 (thà nghe
  tiếng gốc còn hơn im lặng), có hysteresis (vào dưới 0.03, ra trên 0.08)
  và crossfade 5 ms khi đổi chế độ để không kêu "tách".
- MỚI `core/audio/BackgroundAudioPlayer.kt` — AudioTrack 48 kHz stereo,
  USAGE_ALARM, chơi tiếng nền đã lọc; `feedPcm` chạy trên luồng thu tiếng
  (write chặn = tự điều tiết); thanh trượt "tiếng gốc" giờ chỉnh độ to
  tiếng nền này; báo `onPassthroughChanged` khi gặp phim mono.
- `core/audio/AudioCaptureManager.kt` — thêm `startCaptureStereo`
  (48 kHz stereo, 2 callback: mono 16 kHz + stereo gốc). `startCapture`
  cũ giữ nguyên y hệt. Capture KHÔNG match USAGE_ALARM nên tiếng nền
  phát ra không bị thu lại (không feedback).
- `core/audio/AudioPlayerManager.kt` — `start(useAlarmStream)`:
  usage ALARM, tắt MUSIC, đặt ALARM theo độ to nhạc cũ, bỏ qua focus;
  `setOriginalMediaAudio` không làm gì ở chế độ báo thức; `stop()` trả
  lại cả 2 âm lượng.
- `core/service/AudioDubbingForegroundService.kt` — đọc pref lọc giọng,
  `alarmMode = app khác + lọc giọng + không phải chế độ chỉ-phụ-đề`;
  khởi động/tắt background player; thanh trượt tiếng gốc chuyển sang
  background player; capture stereo khi alarmMode; dừng: tắt background
  trước rồi mới trả âm lượng.
- `data/repository/UserPreferencesRepository.kt` — key
  `filter_original_voice` + flow + updater.
- `presentation/main/MainViewModel.kt` + `MainScreen.kt` — công tắc
  "Lọc giọng gốc, chỉ giữ tiếng nền" nằm trong thẻ "Xem trên app khác",
  chỉ hiện khi đã bật xem app khác. Ghi rõ: có hiệu lực khi bật lồng
  tiếng lần sau.
- `app/build.gradle.kts` — v1.2.8 / versionCode 13.

## Kiểm chứng (kotlinc 1.9.24 + stub, không build APK thật được)

Test `VoiceFilterCheckMain.kt`: **51/51**.
- Downmix: 1920 byte stereo → 320 byte mono; hằng số, âm, box filter
  3 khung, input lẻ đều đúng.
- VocalRemover: giọng ở giữa bị xóa (hệ số ~0), nhạc stereo còn 0.7;
  panned cứng còn 0.7 + mirror; mono → passthrough 0.6 kèm ramp 5 ms
  (khung 100 đúng điểm giữa ramp, khung 300 đủ 0.6); hysteresis giữ
  chế độ trong dải 0.03–0.08; anti-phase cực đoan kẹp đúng ±32768;
  im lặng giữ chế độ.
- AudioPlayerManager chế độ báo thức: MUSIC → 0, ALARM 10/15 → 4/7,
  đúng thứ tự; track USAGE_ALARM; không xin focus; setOriginalMediaAudio
  không tác dụng; giọng dịch vẫn tới track; stop trả lại cả 2 âm lượng.
  Chế độ mặc định không đổi (USAGE_ASSISTANT, có focus, slider cũ chạy).
- BackgroundAudioPlayer: track USAGE_ALARM 48 kHz stereo; giọng bị xóa
  khỏi byte ghi ra, nhạc còn 0.7 × 1.35; âm lượng 0.5 → ~0.25×; mute →
  im lặng; callback passthrough 2 chiều; chunk lẻ 1000 byte chạy được.
- Stereo capture: callback stereo nhận đúng chunk gốc, callback mono
  nhận 320 byte toàn 2000 (=(3000+1000)/2); dừng thì hết callback.
- 8/8 file cân ngoặc. Test cũ FIX25 vẫn 52/52 (stub mới không phá gì).

## Giới hạn thật (đã nói với user)

- **Không làm giọng dịch nhanh hơn.** Miệng vẫn cử động trước giọng dịch
  1–2 giây (thời gian Gemini dịch). Lọc giọng chỉ bỏ tiếng thoại gốc để
  2 giọng không chồng nhau.
- Lọc giọng cũng xóa cả nhạc cụ nằm giữa (giọng hát chính, bass, trống).
  Tiếng nền còn lại là phần stereo của bản nhạc.
- Phim tiếng mono thì không lọc được — app tự cho tiếng gốc đi qua và
  báo trên dòng trạng thái ("Phim này tiếng mono — không lọc được giọng
  gốc...").
- Tiếng nền phát lại trễ hơn hình ~0,1–0,3 giây (độ trễ thu + xử lý) —
  với nhạc/nền thì khó nhận ra.
- Chế độ này dùng kênh báo thức: nút âm lượng khi đang xem sẽ chỉnh âm
  lượng báo thức (đó chính là độ to giọng dịch + tiếng nền). Báo thức
  thật của máy (nếu có đặt) vẫn kêu bình thường. Khi dừng lồng tiếng,
  âm lượng nhạc + báo thức đều về như cũ.
- Công tắc chỉ có hiệu lực khi bật lồng tiếng lần sau (tắt/bật lại lồng
  tiếng để áp dụng). Chế độ chỉ-phụ-đề thì lọc giọng không bật (không có
  giọng dịch để lồng).

## Cần user kiểm chứng

1. Bật "Xem trên app khác" + "Lọc giọng gốc...", tắt/bật lại lồng tiếng,
   xem 1 tập: còn nghe tiếng thoại gốc không? Nhạc nền còn không?
2. Dòng trạng thái có hiện "Đang lọc giọng gốc — tiếng nền + giọng dịch
   phát qua kênh báo thức" không?
3. Dừng lồng tiếng: âm lượng nhạc + báo thức có về như cũ không?
4. Nút âm lượng khi đang xem có chỉnh được độ to giọng dịch không?
