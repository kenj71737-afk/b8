# FIX20 — Tự đuổi kịp giọng dịch khi xem trên app khác (20/11/2026)

## Vấn đề

Ở chế độ "Xem trên app khác" (FIX19), giọng dịch phát ngay khi Gemini
dịch xong, nhưng câu tiếng Việt thường dài hơn câu gốc nên đọc lâu hơn —
xem một lúc giọng dịch tụt dần lại sau hình, có thể tới vài giây. Trước
đây chỉ có cách chỉnh tay thanh "Tốc độ giọng dịch".

## Đã sửa: tự đuổi kịp (auto catch-up)

App tự đo giọng dịch đang tụt lại bao nhiêu, tự đọc nhanh lên để đuổi
kịp, bắt kịp rồi tự về tốc độ bình thường. Không cần bật/tắt gì thêm —
tính năng này tự chạy khi đang ở chế độ "Xem trên app khác".

### Cách đo độ tụt lại

`AudioPlayerManager` đếm số byte audio dịch đang chờ trong hàng đợi phát
(`pendingAudioBytes`: + khi nhận, − khi phát, reset về 0 mỗi khi hàng đợi
rỗng hẳn để không bị lệch số do cơ chế bỏ chunk cũ nhất) và đổi ra mili
giây qua `getPendingAudioMs()` (audio 24 kHz 16-bit mono = 48.000
byte/giây). Số này chính là độ tụt lại của giọng dịch so với hình — chưa
tính ~1–2 giây máy dịch (phần đó không đo được ở đây và cũng không bỏ
được).

### Luật tăng tốc (vòng kiểm tra mỗi 0,5 giây, chỉ ở chế độ app khác)

| Audio dịch đang chờ | Tốc độ |
|---|---|
| Trên 4 giây | 1.35× |
| Trên 2 giây | 1.20× |
| Dưới 0,8 giây | Tắt đuổi kịp, về tốc độ user đã chọn |
| 0,8–2 giây | Giữ nguyên tốc độ đang chạy (vùng đệm chống nhảy tốc độ liên tục) |

Tốc độ hiệu dụng = tốc độ nhanh hơn trong 2 tốc độ: tốc độ user chọn ở
thanh trượt và tốc độ đuổi kịp. Tức là nếu user đã chỉnh 1.25× thì đuổi
kịp chỉ đẩy lên khi cần nhanh hơn 1.25×; và khi đuổi kịp tắt thì tốc độ
về đúng mức user đã chọn (kể cả khi user chọn chậm hơn 1.00×). Cả thanh
trượt lẫn vòng đuổi kịp đều đi qua một hàm chung
`applyEffectivePlaybackSpeed()` nên không giành nhau điều khiển
AudioTrack, và AudioTrack chỉ bị chạm tới khi tốc độ thật sự đổi.

Màn hình chính hiện dòng **"Đang đuổi kịp giọng dịch — đọc nhanh
1.20×"** trong thẻ "Xem trên app khác" mỗi khi đuổi kịp đang chạy, để
biết app đang tự tăng tốc chứ không phải giọng đọc bị lỗi.

### Vì sao chỉ chạy ở chế độ app khác

Ở chế độ trình duyệt trong app, hình đã bị trễ 5 giây để đợi giọng — nếu
giọng đọc nhanh lên thì lại vượt trước hình đã trễ, phá vỡ đồng bộ. Nên
đuổi kịp chỉ bật khi xem trên app khác (hình chạy đúng giờ thực).

### Giới hạn

- Đuổi kịp chỉ xử lý phần tụt lại **do câu dịch dài hơn câu gốc**; ~1–2
  giây máy dịch vẫn còn nguyên, không cách nào bỏ.
- Tốc độ tối đa 1.35× (giới hạn sẵn của app): một đống chờ 4 giây cần
  khoảng 11 giây để đuổi hết ở 1.35×. Nếu thấy vẫn chậm, bước tiếp theo
  có thể thêm là bỏ bớt audio cũ nhất khi chờ quá lâu — nhưng làm vậy sẽ
  mất chữ, nên bản này chưa làm.
- Đuổi kịp không chạy ở chế độ chỉ phụ đề (không có giọng để tăng tốc).

## File thay đổi

| File | Thay đổi |
|---|---|
| `core/audio/AudioPlayerManager.kt` | Đếm `pendingAudioBytes` + `getPendingAudioMs()` |
| `core/service/AudioDubbingForegroundService.kt` | Vòng đuổi kịp `catchUpJob` (0,5s/lần) + hàm chung `applyEffectivePlaybackSpeed()` + hủy job khi dừng |
| `data/repository/DubbingSessionRepository.kt` | Trạng thái `catchUpSpeed` để UI hiển thị |
| `presentation/main/MainViewModel.kt` | Đưa `catchUpSpeed` ra UI |
| `presentation/main/MainScreen.kt` | Dòng "Đang đuổi kịp giọng dịch…" + cập nhật hướng dẫn trong thẻ app khác |
| `app/build.gradle.kts` | `versionCode` 7 → 8, `versionName` 1.2.3 → 1.2.4 |

## Build trên GitHub

Như các bản trước: giải nén, copy toàn bộ vào gốc repo, push lên `main`,
vào tab **Actions** tải `alad-apks`, cài file **app-debug.apk**. Muốn tạo
Release: push tag `v1.2.4`.
