# Gửi file APK/AAB vào Telegram

Từ PearzCI **0.7.3**, build Android có thể gửi file APK/AAB vào Telegram,
**kèm theo** nội dung thông báo có link Google Drive như cũ. Ai thích tải kiểu
nào thì tải kiểu đó. Từ **0.7.4**, file và nội dung nằm chung **một tin**.

```
[Bot] 📎 MyGame.apk (120 MB)              ← file APK
      ANDROID BUILD SUCCESS               ← nội dung như hiện tại, nằm dưới file
      Product: MyGame
      ...
      APK: https://drive.google.com/...   ← link Drive vẫn còn
      Changes
      - a1b2c3d - ...
      - ... and 3 more commit(s).         ← commit cũ hơn được gộp thành số đếm
```

- Telegram giới hạn nội dung đi kèm file ở **1024 ký tự** (tin nhắn thường là
  4096), nên danh sách commit bị cắt bớt từ commit cũ nhất cho tới khi vừa.
  Thường còn khoảng 4–5 commit.
- Tin chỉ xuất hiện sau khi upload xong file (khoảng 1 phút với APK 120 MB).
- Gửi file lỗi (mạng lỗi, bot thiếu quyền gửi file...) chỉ ghi cảnh báo trong
  log. Khi đó tin nhắn thường có link Drive và đủ danh sách commit được gửi
  thay thế, build vẫn **SUCCESS**.
- Chỉ gửi file khi build thành công. Build hỏng vẫn chỉ có tin báo lỗi.
- Hiện chỉ áp dụng cho Android. iOS vẫn chỉ gửi tin nhắn.

## Vì sao cần server riêng

Bot API chính thức (`api.telegram.org`) chỉ cho bot gửi file **tối đa 50 MB**.
APK game luôn lớn hơn, nên phải chạy server
[`telegram-bot-api`](https://github.com/tdlib/telegram-bot-api) (mã nguồn mở
của Telegram, miễn phí) ở chế độ `--local` ngay trên máy Mac build. Server này
cho gửi file **đến 2 GB**.

## Jenkins có gì mới

**Không có tham số mới trên màn hình *Build with Parameters*.** Chỉ thêm 2
tuỳ chọn trong `Jenkinsfile` của từng game:

| Tuỳ chọn | Mặc định | Ý nghĩa |
|---|---|---|
| `telegramApiUrl` | trống (= `https://api.telegram.org`) | Địa chỉ Bot API. Đặt `http://127.0.0.1:8081` để dùng server local. |
| `telegramSendFile` | `false` | `true` thì gửi file APK/AAB chung một tin với nội dung thông báo. |

Job không đặt hai tuỳ chọn này chạy y như trước.

```groovy
pearzUnityMobilePipeline(
    // ... các tuỳ chọn đang có ...
    telegramApiUrl: 'http://127.0.0.1:8081',
    telegramSendFile: true
)
```

## Cài đặt trên máy Mac build

### 1. Lấy `api_id` và `api_hash` (miễn phí)

1. Vào <https://my.telegram.org>, đăng nhập bằng số điện thoại.
2. Chọn **API development tools**, tạo một app (tên tuỳ ý, Platform: *Desktop*).
3. Lưu `api_id` (số) và `api_hash`. Giữ bí mật như mật khẩu, không commit vào repo.

### 2. Build server

```bash
brew install gperf cmake openssl zlib
git clone --recursive https://github.com/tdlib/telegram-bot-api.git
cd telegram-bot-api
mkdir build && cd build
cmake -DCMAKE_BUILD_TYPE=Release \
      -DOPENSSL_ROOT_DIR="$(brew --prefix openssl)" \
      -DCMAKE_INSTALL_PREFIX:PATH="$HOME/.local" ..
cmake --build . --target install -j"$(sysctl -n hw.ncpu)"
~/.local/bin/telegram-bot-api --version
```

Mất khoảng 10–20 phút.

### 3. Chạy thử

```bash
mkdir -p ~/telegram-bot-api-data
~/.local/bin/telegram-bot-api \
  --api-id=<API_ID> --api-hash=<API_HASH> \
  --local --http-port=8081 --http-ip-address=127.0.0.1 \
  --dir="$HOME/telegram-bot-api-data"
```

`--http-ip-address=127.0.0.1` giới hạn server chỉ nhận kết nối từ chính máy
Mac, không lộ ra mạng ngoài.

### 4. Tự khởi động cùng máy (launchd)

Tạo `~/Library/LaunchAgents/org.telegram.bot-api.plist` (thay `<USER>`,
`<API_ID>`, `<API_HASH>`):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>org.telegram.bot-api</string>
  <key>ProgramArguments</key><array>
    <string>/Users/<USER>/.local/bin/telegram-bot-api</string>
    <string>--api-id=<API_ID></string>
    <string>--api-hash=<API_HASH></string>
    <string>--local</string>
    <string>--http-port=8081</string>
    <string>--http-ip-address=127.0.0.1</string>
    <string>--dir=/Users/<USER>/telegram-bot-api-data</string>
  </array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardErrorPath</key><string>/Users/<USER>/telegram-bot-api-data/server.log</string>
</dict></plist>
```

```bash
launchctl load ~/Library/LaunchAgents/org.telegram.bot-api.plist
curl http://127.0.0.1:8081   # có phản hồi (kể cả 404) là server đang chạy
```

Server tạm lưu file trong `~/telegram-bot-api-data`. Thỉnh thoảng kiểm tra
dung lượng thư mục này và dọn nếu cần.

### 5. Tạo bot riêng cho build (khuyên dùng)

1. Chat với [@BotFather](https://t.me/BotFather), `/newbot`, lấy token.
2. Thêm bot vào group nhận build (nếu group có topic thì cho bot quyền gửi
   vào topic đó).

Dùng bot riêng để bot cũ và workflow release trên GitHub vẫn dùng
`api.telegram.org` bình thường.

### 6. Chuyển bot sang server local (làm một lần)

```bash
curl "https://api.telegram.org/bot<BOT_TOKEN_MỚI>/logOut"
```

> ⚠️ Sau lệnh này, bot **chỉ** dùng được qua `http://127.0.0.1:8081`, kể cả
> gửi tin nhắn. Mọi job / script khác dùng bot này phải chuyển sang server
> local. **Không** `logOut` bot đang dùng chung cho việc khác.

Muốn quay về server chính thức: gọi `logOut` trên server local
(`http://127.0.0.1:8081/bot<TOKEN>/logOut`), đợi khoảng 10 phút rồi gọi lại
`api.telegram.org` như thường.

### 7. Test gửi file

```bash
curl -F chat_id=<CHAT_ID> -F document=@/path/to/game.apk \
  "http://127.0.0.1:8081/bot<BOT_TOKEN_MỚI>/sendDocument"
```

### 8. Bật cho job

1. Đổi token trong `TELEGRAM_CHANNEL` (hoặc credential `telegramCredentialsId`)
   sang bot mới. Định dạng giữ nguyên: `TOKEN|CHAT_ID` hoặc
   `TOKEN|CHAT_ID|THREAD_ID`, nhiều target cách nhau bằng `;`.
2. Thêm `telegramApiUrl` và `telegramSendFile: true` vào `Jenkinsfile` như ở
   trên.
3. Chạy một build và kiểm tra group.

## Xử lý sự cố

| Hiện tượng | Nguyên nhân thường gặp |
|---|---|
| Không có tin nhắn nào, build UNSTABLE | Server local không chạy (`curl http://127.0.0.1:8081`), hoặc bot chưa `logOut` khỏi server chính thức. |
| Có tin nhắn, không có file, log có `WARNING: could not send the build file` | Bot thiếu quyền gửi file trong group/topic, file > 2 GB, hoặc server hết dung lượng đĩa. |
| Có tin nhắn, không có file, không có cảnh báo | Job chưa đặt `telegramSendFile: true`, hoặc build không SUCCESS. |
| Tin nhắn và file vẫn là hai tin riêng | Phần cố định của thông báo (chưa tính commit) đã dài hơn 1024 ký tự, nên không gộp được. |
| Job khác (chưa bật) mất thông báo | Job đó dùng chung bot đã `logOut`. Dùng bot riêng cho job gửi file. |
