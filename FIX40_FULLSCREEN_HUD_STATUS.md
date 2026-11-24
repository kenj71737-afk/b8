# FIX40 — Fullscreen HUD gọn lại (chỉ còn THOÁT + tua video)

**Ngày:** 24/11/2026. **Phiên bản:** v1.2.19 (versionCode 24).
**Nền:** FIX39 (`~/workspace/alad-fix39-github/`).

## Vấn đề (user báo 24/11 07:15, kèm ảnh)

Xem toàn màn hình, hàng nút điều khiển trễ (AUTO / THỦ CÔNG / − / "TỰ ĐỘNG • 7.4s" / + / THOÁT)
chữ to, che mất một phần màn hình. User yêu cầu: làm nhỏ lại, hoặc khi xem full chỉ để chữ THOÁT.

## Cách sửa (chọn phương án 2 — chỉ để THOÁT)

`BrowserActivity.kt` — `ensureFullscreenHud()`:

- **Bỏ hẳn hàng delay** (`delayRow`): các nút AUTO / THỦ CÔNG / − / + và nhãn trạng thái
  "TỰ ĐỘNG • 7.4s" không còn hiện trong HUD toàn màn hình nữa.
- **Nút THOÁT** chuyển xuống cuối hàng tua video (sau giờ "0:26 / 6:33"), nhỏ gọn
  (chữ 11sp, cao 40dp, như các nút tua).
- HUD toàn màn hình giờ chỉ còn **một hàng mỏng**: [−10s] [thanh tua] [+10s] [giờ] [THOÁT].
- Xóa `fullscreenHudStatus` (field + tạo view + cập nhật text); `updateFullscreenHud()`
  giờ chỉ làm việc hiện/ẩn HUD.

Không mất chức năng: chỉnh chế độ trễ (AUTO/THỦ CÔNG) và tăng/giảm trễ vẫn có ở thanh
điều khiển thường (ngoài toàn màn hình); trễ tự động vẫn chạy ngầm như cũ.

## Kiểm chứng

- Không còn tham chiếu nào tới `fullscreenHudStatus` / `delayRow`.
- File cân ngoặc (braces/parens/brackets = 0).
- Diff với FIX39 đúng 2 file: `BrowserActivity.kt`, `build.gradle.kts`.

## Build

Đẩy lên GitHub `kenj71737-afk/b8` (nhánh main) qua Git Data API, Actions build,
tải APK debug về giao user. (Muse tự build từ 24/11 sau khi user cấp PAT.)
