# WebGL → Cloudflare Pages

Jenkins build bản Unity WebGL, deploy lên Cloudflare Pages, người chơi chỉ cần
reload trang (ví dụ `https://pg05.pearz.space` cho AntVoxel) là chơi bản mới.
Mỗi dev dùng domain/subdomain riêng trên tài khoản Cloudflare của mình.

## Luồng

```
Jenkins (BUILD_PLATFORM = WebGL)
  → Unity BuildEntry.BuildWebGL → Builds/WebGL/site/ (Build/, StreamingAssets/)
  → cloudflare-pages-deploy.mjs:
      1. kiểm tra giới hạn 25 MiB/file của Pages
      2. tìm account từ token
      3. tìm project Pages đang gắn WEB_DOMAIN (chưa có thì tạo)
      4. ghi index.html (khung 9:16, render 1080x1920)
      5. wrangler pages deploy (production branch)
      6. gắn domain + CNAME nếu còn thiếu
     game có WebTool/ thì bước 4-5 chạy hai lần: bản build vào project
     <tên>-game, trang khung + tool/ vào project gắn domain
  → Telegram: link chơi + version/build
```

Pages chỉ chuyển domain sang bản mới khi upload xong, nên deploy hỏng giữa
chừng thì trang vẫn chạy bản cũ. Bản cũ vẫn nằm trong **Deployments** của
project để rollback bằng một click. URL file build có `?v=<version>-<build>`
nên reload luôn tải đúng bản mới.

## Tham số job Jenkins

| Tham số | Kiểu | Ví dụ | Ghi chú |
|---|---|---|---|
| `BUILD_PLATFORM` | Choice | `Android` / `iOS` / `WebGL` | Bỏ `Windows` khỏi danh sách |
| `WEB_DOMAIN` | String | `pg05.pearz.space` | Domain người chơi mở |
| `CLOUDFLARE_CREDENTIAL` | Credentials (Secret text) | `cf-token-duc` | Token Cloudflare của dev |
| `CLOUDFLARE_ACCOUNT_ID` | String | để trống | Chỉ cần khi token thấy nhiều account |
| `WEBGL_EXCLUDE_PACKAGES` | String | `com.funtap.global.sdk` | Package gỡ ra trước khi build WebGL |

`APP_VERSION`, `GIT_BRANCH`, `PROJECT_REPOSITORY_URL`, `SCRIPTING_DEFINE_SYMBOLS`,
`DEVELOPMENT_BUILD`, `IL2CPP_CODE_GENERATION`, `MANAGED_STRIPPING_LEVEL`,
`STRIP_ENGINE_CODE`, `TELEGRAM_CHANNEL`… dùng như build Android.

Các giá trị này cũng truyền được qua `pearzUnityPipeline(...)`:
`webDomain`, `cloudflareCredentialsId`, `cloudflareAccountId`,
`webResolution` (mặc định `1080x1920`; `720x1280` hoặc `1440x2560`),
`webToolDir` (mặc định `WebTool`, xem [Web-tool cho GD](#web-tool-cho-gd)),
`wranglerVersion` (mặc định `4`).

### Account ID và Pages project tự suy ra thế nào

- **Account:** gọi `GET /accounts` bằng token. Token chỉ thuộc một tài khoản
  thì dùng luôn; thấy nhiều tài khoản thì build dừng và in danh sách để điền
  `CLOUDFLARE_ACCOUNT_ID`.
- **Project:** liệt kê project Pages, chọn project có custom domain (hoặc
  `*.pages.dev`) trùng `WEB_DOMAIN`. Với AntVoxel là `pg05`.
- **Domain mới chưa gắn đâu:** tạo project tên theo domain
  (`antvoxel.duc-games.com` → `antvoxel-duc-games-com`), gắn domain và tạo
  CNAME `antvoxel.duc-games.com → antvoxel-duc-games-com.pages.dev` nếu DNS
  của domain nằm trên Cloudflare. Bản ghi DNS đã có thì không bị ghi đè. DNS
  ở nơi khác thì log in ra đúng dòng CNAME cần thêm tay; build vẫn SUCCESS.

## Mỗi dev làm một lần

1. (Tuỳ chọn) Mua domain và **Add a site** vào Cloudflare, đổi nameserver.
2. Tạo API token: **My Profile → API Tokens → Create Token → Create Custom Token**
   - `Account` → `Cloudflare Pages` → `Edit`
   - `Zone` → `DNS` → `Edit`, Zone Resources: chỉ zone domain của mình
     (bỏ qua nếu tự tạo CNAME bằng tay)
   - Account Resources: chỉ tài khoản của mình
3. Jenkins: **Manage Jenkins → Credentials → Add Credentials → Secret text**,
   dán token, đặt ID (ví dụ `cf-token-duc`).
4. Trong job: chọn `WEBGL`, điền `WEB_DOMAIN`, chọn credential.

## Máy agent

- Unity cùng version với project, có module **WebGL Build Support**
  (Unity Hub → Installs → Add modules). Thiếu thì stage `Validate Unity` báo lỗi.
- Node.js 18+ (`brew install node`); wrangler chạy qua `npx` nên không cần cài
  riêng. PearzCI tìm `node` trong PATH của Jenkins, `/opt/homebrew/bin` và
  `/usr/local/bin`.

## Build Unity

`BuildEntry.BuildWebGL` (Jenkins gọi với `-buildTarget WebGL`):

- Nén **Gzip + Decompression Fallback**: loader tự giải nén bằng JS nên Pages
  không cần header `Content-Encoding`.
- Tên file cố định (`site.loader.js`, `site.data.unityweb`, …), không hash;
  `index.html` của Unity bị thay bằng `resources/com/pearz/ci/webgl-index.html`.
- `build-metadata.json` và `unity-build.log` nằm ở `Builds/WebGL/`, cạnh
  thư mục `site/`, nên không bị deploy công khai.

## SDK không hỗ trợ WebGL

SDK native (Funtap, AppLovin, Firebase…) thường không biên dịch được cho
WebGL. Không cần sửa SDK:

1. Điền tên package vào `WEBGL_EXCLUDE_PACKAGES`, ví dụ
   `com.funtap.global.sdk`. Trước khi build WebGL, PearzCI xoá package khỏi
   `Packages/manifest.json` và `packages-lock.json` trong workspace (không
   commit; lần checkout sau trả lại nguyên trạng). Package nhúng trong
   `Packages/<tên>` không gỡ được theo cách này.
2. Trong game, chỉ gọi SDK qua một lớp wrapper, phần gọi thật bọc trong
   `#if !UNITY_WEBGL`, nhánh `#else` trả kết quả dummy. Code nào khác
   `using` namespace của SDK cũng phải bọc `#if !UNITY_WEBGL`.

## Khung và độ phân giải

UI game thiết kế 1440×2560 (9:16). Trang luôn giữ khung 9:16 và co vừa màn
hình (điện thoại lẫn PC). Canvas Unity render cố định 1080x1920; đổi qua
`pearzUnityPipeline(webResolution: '1440x2560')` nếu cần nét hơn, hoặc
`'720x1280'` cho máy yếu. Kích thước khung trên màn hình không đổi.

## Web-tool cho GD

Game có thư mục `WebTool/` thì trang deploy ra thành công cụ dựng level trên
PC: khung game 9:16 căn trái cao hết màn, panel của game chiếm phần còn lại
(màn 1920x1080: game 608px, panel 1313px). Màn dọc hoặc hẹp hơn 900px (điện
thoại) thì panel ẩn, trang là khung 9:16 căn giữa như game không có tool. Không
cần job hay khai báo gì thêm.

### Hai site

Game và tool là hai project Pages riêng, để sửa tool không phải build lại game:

| Project | Địa chỉ | Chứa gì | Ai deploy |
|---|---|---|---|
| `<tên>` | `WEB_DOMAIN` (ví dụ `pg07.pearz.space`) | Trang khung + `tool/` | Job WebGL, hoặc `webtool-publish.mjs` ở máy dev |
| `<tên>-game` | `<tên>-game.pages.dev` (Cloudflare cấp sẵn) | Bản build Unity | Chỉ job WebGL |

Mọi người vẫn chỉ mở `WEB_DOMAIN`; trang khung nhúng bản build bằng `iframe`.
Project `-game` được tạo tự động ở lần chạy job đầu tiên. Dự án đang chạy bản
PearzCI cũ: lần build đầu sau khi nâng cấp chuyển bản build sang project
`-game`, nên dữ liệu game lưu trong trình duyệt (PlayerPrefs) bắt đầu lại.

### Thư mục WebTool

Đặt trong thư mục project Unity, ngang hàng `Assets/` (ví dụ
`CubeTapSort/UnityCubeTapSort/WebTool/`). Để ngoài `Assets/` nên Unity không
import và không sinh `.meta`.

| File | Bắt buộc | Vai trò |
|---|---|---|
| `panel.html` | có | Nội dung panel. Chỉ là fragment: không có `<!DOCTYPE>`, `<html>`, `<head>`, `<body>` |
| `tool.js` | không | Logic panel, nạp sau khi trang dựng xong |
| `tool.css` | không | Giao diện panel |
| `pearz-tool.json` | không | `{ "domain": "pg07.pearz.space" }` cho `webtool-publish.mjs` |
| file khác | không | Ảnh, font…; copy nguyên thư mục con |

PearzCI nhận diện tool bằng `WebTool/panel.html`. Đổi thư mục bằng
`pearzUnityPipeline(webToolDir: 'Tools/LevelEditor')`; `webToolDir: ''` tắt
hẳn. Thư mục tự khai mà thiếu `panel.html` thì build dừng trước bước Unity.

Cả thư mục được deploy tại `tool/`. `panel.html` nằm thẳng trong `index.html`
nên đường dẫn trong đó tính từ gốc site (`tool/img/a.png`); trong `tool.css`
thì viết tương đối như thường (`img/a.png`). Mọi file trong `WebTool/` đều
công khai trên web, đừng để gì nhạy cảm ở đó.

### Sửa tool không build lại game

Sau khi job WebGL đã chạy ít nhất một lần cho domain, dev đẩy riêng tool từ
máy mình. Trong thư mục project Unity:

```bash
node Library/PackageCache/com.pearz.ci@*/tools/webtool-publish.mjs --domain pg07.pearz.space
```

(PowerShell: thay đường dẫn bằng
`(Get-Item Library/PackageCache/com.pearz.ci@*/tools/webtool-publish.mjs)`.)

- Cần biến môi trường `CLOUDFLARE_API_TOKEN` (quyền `Cloudflare Pages: Edit`)
  và Node.js 18+.
- `--domain` bỏ được nếu `WebTool/pearz-tool.json` có `domain`.
- `--watch`: đẩy lại mỗi lần lưu file trong `WebTool/`. GD reload là thấy cả
  bản đang sửa dở, nên chỉ bật khi chấp nhận điều đó.
- `--tool-dir`: thư mục tool khác `WebTool`.

Lệnh chỉ thay trang khung và `tool/` của project gắn domain; không tạo
project, không đụng DNS hay bản build. Xong thì reload `WEB_DOMAIN`.

Job WebGL deploy lại trang khung từ git, nên tool đã đẩy mà **chưa commit sẽ
bị bản trong git thay thế** ở lần build kế tiếp; lệnh in nhắc khi `WebTool/`
còn thay đổi chưa commit.

### pearzTool

Trang khung có sẵn `window.pearzTool` cho `tool.js`. Game chạy trong `iframe`
ở origin khác, nên mọi lệnh đi qua `postMessage`; trang game chỉ nhận lệnh từ
`WEB_DOMAIN` và địa chỉ `*.pages.dev` của chính project đó. PearzCI chỉ chuyển
dữ liệu, không đọc nội dung: cấu trúc level do từng game tự quy ước.

| Hàm | Chức năng |
|---|---|
| `pearzTool.ready` | Promise xong khi Unity nạp xong; bị từ chối nếu game không nạp được. Không còn trả `unityInstance` |
| `pearzTool.post(channel, data)` | Gửi tới `PearzTool.On(channel, ...)` trong C#. Object/mảng thành chuỗi JSON. Gọi trước khi game nạp xong thì tự chờ. Trả về Promise |
| `pearzTool.send(object, method, data)` | Gọi thẳng `method` trên GameObject tên `object` (qua `SendMessage`), cho game tự viết bridge riêng |
| `pearzTool.saveFile(name, data)` | Tải file về máy. Object/mảng ghi thành JSON thụt lề |
| `pearzTool.openFile(accept)` | Hộp chọn file (mặc định `.json`). Trả Promise `{ name, text }`, hoặc `null` nếu huỷ |
| `pearzTool.on(event, fn)` | Nghe sự kiện game báo ra (`PearzTool.Emit`); trả về hàm huỷ đăng ký |

### Phía game: Pearz.CI.PearzTool

Package có sẵn đầu nhận trong C#, game không cần GameObject hay `.jslib` riêng:

| API | Chức năng |
|---|---|
| `PearzTool.On(channel, handler)` | Nhận chuỗi panel gửi bằng `pearzTool.post`. Tin tới trước khi đăng ký (game còn đang khởi động) được giữ và giao ngay khi đăng ký, tối đa 16 tin mỗi channel |
| `PearzTool.Off(channel, handler)` | Huỷ đăng ký |
| `PearzTool.Emit(event, data)` | Báo ra panel (`pearzTool.on`). Ngoài bản WebGL thì không làm gì |
| `PearzTool.Dispatch(channel, data)` | Giả lập một tin từ panel, để test trong Editor |

### Ví dụ tối thiểu

`WebTool/panel.html`:

```html
<h2>Level editor</h2>
<textarea id="level-json" rows="12">{ "id": "level-001" }</textarea>
<button id="play">Play</button>
<button id="save">Save</button>
<button id="open">Open</button>
<p id="status"></p>
```

`WebTool/tool.js`:

```js
const json = document.getElementById('level-json');
const status = document.getElementById('status');

document.getElementById('play').onclick = () =>
    pearzTool.post('playLevel', json.value);
document.getElementById('save').onclick = () =>
    pearzTool.saveFile(`${JSON.parse(json.value).id}.json`, json.value);
document.getElementById('open').onclick = async () => {
    const file = await pearzTool.openFile();
    if (file) json.value = file.text;
};
pearzTool.on('levelFinished', (result) => { status.textContent = result; });
```

Phía game (mỗi game tự viết phần xử lý level):

```csharp
using Pearz.CI;

public sealed class LevelToolBridge : IDisposable
{
    public LevelToolBridge() => PearzTool.On("playLevel", PlayLevel);
    public void Dispose() => PearzTool.Off("playLevel", PlayLevel);

    private void PlayLevel(string json)
    {
        // Parse bằng class level của game rồi chơi level này; không đụng tới
        // save/tiến trình người chơi.
    }

    public void ReportFinished(string result) => PearzTool.Emit("levelFinished", result);
}
```

Level GD lưu là file trên máy GD; muốn vào bản game chính thức thì vẫn phải
commit vào repo game.

## Giới hạn

- Cloudflare Pages nhận file tối đa **25 MiB**. File vượt mức thì stage
  `Deploy to Cloudflare Pages` dừng và nêu tên file; cần giảm dung lượng build
  (texture, audio, Addressables).
- Tối đa 20.000 file mỗi deployment.

## Xử lý lỗi thường gặp

| Lỗi | Cách xử lý |
|---|---|
| `token cannot see any account` | Token thiếu quyền `Cloudflare Pages: Edit` |
| `token can see several accounts` | Giới hạn token còn một account hoặc điền `CLOUDFLARE_ACCOUNT_ID` |
| `could not create the DNS record` | Thêm quyền `Zone: DNS: Edit` hoặc tạo CNAME bằng tay theo log |
| `WebGL Build Support is not installed` | Cài module WebGL cho đúng version Unity |
| `webToolDir '…' has no panel.html` | Thư mục khai trong `webToolDir` thiếu `panel.html`, hoặc sai đường dẫn (tính từ thư mục project Unity) |
| `panel.html must be an HTML fragment` | Bỏ `<!DOCTYPE>`, `<html>`, `<head>`, `<body>` khỏi `panel.html`, chỉ giữ nội dung panel |
| Có `WebTool/` nhưng trang vẫn là khung 9:16 | Thiếu `panel.html`, thư mục không nằm trong project Unity, hoặc cửa sổ hẹp hơn 900px / màn dọc |
| Bấm nút trên panel game không phản ứng | Channel trong `pearzTool.post` phải khớp `PearzTool.On`; với `pearzTool.send` thì tên GameObject/method phải khớp và GameObject đang active. Xem Console của trình duyệt (chọn đúng khung của game) |
| `Pages project '…-game' does not exist yet` khi chạy `webtool-publish.mjs` | Domain chưa được job WebGL deploy bằng bản PearzCI có hai site; chạy job một lần |
| Khung game trắng ngay sau lần build đầu | Địa chỉ `*.pages.dev` của project `-game` mới tạo cần vài phút mới truy cập được |
| Trang mở được nhưng domain báo SSL đang chờ | Lần đầu gắn domain Cloudflare cần vài phút cấp chứng chỉ |
