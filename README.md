# IPTV Box
Xem truyền hình qua link m3u8 / IPTV, danh sách kênh tải từ file txt trên Google Drive.

## Danh sách kênh
- Link file nằm ở hằng số `LIST_URL` đầu `MainActivity.kt`. Muốn đổi mà không build lại: tạo file `source.txt`
  trong `Android/data/com.example.iptvbox/files/`, dòng đầu ghi link mới.
- Mỗi lần mở app, danh sách được cập nhật ở nền (có bản lưu trong máy để dùng khi mất mạng). Mục "Cập nhật danh sách kênh" ở đầu danh sách để cập nhật ngay.
- Các dạng dòng hỗ trợ:
  - `http://bit.ly/abc` hoặc `https://.../index.m3u8` (chỉ link; tên tự đặt là "Kênh N")
  - `VTV1|http://.../vtv1.m3u8` (có tên; ngăn cách bằng `|` `,` `;` hoặc tab)
  - M3U chuẩn: `#EXTINF:-1 group-title="Tin tức",VTV1` rồi dòng link
  - Chia nhóm: dòng `## Tên nhóm` hoặc `[Tên nhóm]` (các kênh bên dưới thuộc nhóm đó)
  - Dòng bắt đầu bằng `#` (khác các dạng trên) là ghi chú, bỏ qua.

## Điều khiển
- Chọn kênh trong danh sách -> xem toàn màn hình. BACK = về danh sách (kênh vẫn phát ở khung nhỏ).
- Toàn màn hình: phím Lên = kênh kế, Xuống = kênh trước, OK/Trái/Phải = hiện bảng điều khiển, bấm số = nhảy tới kênh đó.
- Mở lại app sẽ tự phát kênh xem lần cuối (đổi `AUTOPLAY_LAST` thành false để tắt).

## Build
Push lên GitHub, Actions tạo APK (Artifacts: IPTV-Box-apk).
