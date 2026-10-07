# WebGL → Cloudflare Pages

Jenkins build bản Unity WebGL, deploy lên Cloudflare Pages, người chơi chỉ cần
reload trang (ví dụ `https://pg05.pearz.space` cho AntVoxel) là chơi bản mới.
Mỗi dev dùng domain/subdomain riêng trên tài khoản Cloudflare của mình.

## Luồng

```
Jenkins (BUILD_PLATFORM = WebGL)
  → Unity BuildEntry.BuildWebGL → Builds/WebGL/site/ (Build/, StreamingAssets/)
  → cloudflare-pages-deploy.mjs:
      1. ghi index.html (khung 9:16, độ phân giải WEB_RESOLUTION)
      2. kiểm tra giới hạn 25 MiB/file của Pages
      3. tìm account từ token
      4. tìm project Pages đang gắn WEB_DOMAIN (chưa có thì tạo)
      5. wrangler pages deploy (production branch)
      6. gắn domain + CNAME nếu còn thiếu
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
| `WEB_RESOLUTION` | Choice | `1080x1920` | `720x1280`, `1080x1920`, `1440x2560` |
| `CLOUDFLARE_ACCOUNT_ID` | String | để trống | Chỉ cần khi token thấy nhiều account |

`APP_VERSION`, `GIT_BRANCH`, `PROJECT_REPOSITORY_URL`, `SCRIPTING_DEFINE_SYMBOLS`,
`DEVELOPMENT_BUILD`, `IL2CPP_CODE_GENERATION`, `MANAGED_STRIPPING_LEVEL`,
`STRIP_ENGINE_CODE`, `TELEGRAM_CHANNEL`… dùng như build Android.

Các giá trị này cũng truyền được qua `pearzUnityPipeline(...)`:
`webDomain`, `cloudflareCredentialsId`, `cloudflareAccountId`,
`webResolution`, `wranglerVersion` (mặc định `4`).

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

## Khung và độ phân giải

UI game thiết kế 1440×2560 (9:16). Trang luôn giữ khung 9:16 và co vừa màn
hình (điện thoại lẫn PC). `WEB_RESOLUTION` là kích thước canvas Unity render
thật: `720x1280` nhẹ cho máy yếu, `1440x2560` nét nhất. Kích thước khung trên
màn hình không đổi theo lựa chọn này.

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
| Trang mở được nhưng domain báo SSL đang chờ | Lần đầu gắn domain Cloudflare cần vài phút cấp chứng chỉ |
