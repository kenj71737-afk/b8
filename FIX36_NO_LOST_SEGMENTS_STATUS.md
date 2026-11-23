# FIX36 — Không mất đoạn khi đổi key (v1.2.15, code 20)

Ngày: 2026-11-23. Nền: FIX35 (v1.2.14). User báo bản 35 vẫn mất đoạn
khi đọc, và hỏi có phải do hình/video gốc chạy chậm nên giọng khựng lại
chờ hình không.

## Trả lời câu hỏi về hình chậm
Không — giọng dịch trong app KHÔNG có cơ chế chờ hình. Giọng chỉ giữ
một lần lúc mới bắt đầu (khoảng 2 giây), sau đó đọc liên tục từ hàng
chờ, không dừng lại chờ hình từng câu. Cái nghe như "khựng lại chờ"
là giọng hết đoạn dịch để đọc, xảy ra khi (a) video tự đứng hình chờ
tải (không có tiếng mới thì không có bản dịch mới — do mạng/trang web,
app không sửa được), hoặc (b) app làm mất tiếng gốc lúc đổi key — đó
mới là chỗ sửa ở bản này. Phân biệt: nếu video cũng xoay vòng chờ tải
đúng lúc giọng khựng thì là mạng; nếu video chạy mượt mà giọng vẫn mất
đoạn thì là lỗi app (bản này sửa).

## Nguyên nhân mất đoạn thật sự
- Mỗi 5–6 phút app đổi key Gemini một lần (key miễn phí chết lặng sau
  ~5 phút). Lúc nối lại, phiên mới cần 1–3 giây để thiết lập — trong
  khoảng đó code cũ chỉ giữ lại 2 gói tiếng (20 ms), còn lại bỏ hết.
  Câu nói đúng lúc đó không bao giờ được dịch → mất đoạn.
- FIX34 đã cố đổi key lúc yên lặng, nhưng phim thoại liên tục hiếm khi
  có 3 giây yên ắng (vừa không ai nói vừa không có bản dịch về), nên
  hầu hết lần đổi vẫn bị ép ở mốc 6 phút, giữa câu.

## Cách sửa
1. Mới `core/audio/PendingAudioQueue.kt`: hàng chờ tiếng gốc giữ tới
   600 gói (6 giây) thay vì 2 gói, xả theo đúng thứ tự, 2 gói mỗi lần
   có tiếng mới vào (nhanh gấp đôi thời gian thật nên 3 giây tiếng dồn
   xả hết trong 1,5 giây). Xả từ từ thay vì xả ồ ạt — xả ồ ạt sẽ đụng
   trần 64 KB của socket và vẫn mất tiếng. Mạng nghẽn thì dừng xả, giữ
   tiếng lại chờ lần sau thay vì bỏ.
2. `ALADWebSocketManager`: mọi tiếng qua cổng VAD đều vào hàng chờ rồi
   mới gửi; lúc phiên chưa thiết lập xong thì tiếng cứ dồn trong hàng
   chờ thay vì bị bỏ. Bỏ chỗ xả ồ ạt lúc thiết lập xong.
3. `disconnect(resetGate = false)` khi nối lại (đổi key, đổi ngôn ngữ):
   giữ nguyên trạng thái cổng VAD — đầu câu (tới 0,5 giây) nằm trong
   bộ đệm chờ của cổng không bị mất, mốc giờ câu nói cũng giữ lại cho
   bộ đo trễ. Chỉ khi dừng lồng tiếng hẳn mới đặt lại cổng.

## Kiểm chứng
- Test `PendingAudioQueue` (trích đúng code, biên dịch + chạy bằng
  kotlinc): 22/22 — đúng thứ tự, xả tối đa 2/lần, trần 600 gói bỏ gói
  cũ nhất, mạng nghẽn thì giữ lại, 300 gói (3 giây) xả hết trong 150
  lần, đúng thứ tự.
- 3 file sửa đều cân ngoặc; diff với FIX35 đúng 4 chỗ (file hàng chờ
  mới + WS manager + service + version).
- CHƯA build APK thật, chưa chạy trên máy user.

## Cần user kiểm chứng
Xem hết một tập: còn mất đoạn không, nhất là quanh các mốc 5/10/15
phút (lúc đổi key). Nếu vẫn mất, cho biết lúc đó phụ đề có mất đoạn đó
không (phụ đề cũng mất = mất ở tiếng đầu vào; phụ đề còn mà giọng mất
= mất ở đầu ra) và video có xoay vòng chờ tải không.
