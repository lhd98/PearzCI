# Pearz CI

Reusable Unity Editor build entry points and a Jenkins Shared Library for
Pearz CI pipelines.

## Requirements

- Unity 6000.3 or newer on Windows or macOS
- Android Build Support when building Android players
- Windows Build Support (IL2CPP) when building Windows players with IL2CPP
- Jenkins with Pipeline, Git, Credentials, Shared Library, and Generic Webhook Trigger support
- rclone with a configured Google Drive remote
- `curl` on macOS for Telegram notifications

## Installation

### Unity Package Manager

1. Open **Window > Package Management > Package Manager**.
2. Select **+ > Install package from git URL...**.
3. Enter:

```text
ssh://git@github.com/lhd98/PearzCI.git#upm
```

4. Select **Install**.

Unity automatically adds the dependency to `Packages/manifest.json` and locks
the resolved commit in `Packages/packages-lock.json`. Commit both files with
the project.

### PearzCI release Telegram notification

This repository sends a Telegram notice when an exact release tag such as
`v0.6.6` is pushed. It runs entirely through GitHub Actions; Jenkins game
build jobs are not involved.

In GitHub, open **PearzCI > Settings > Secrets and variables > Actions** and
create the secret `TELEGRAM_BOT_TOKEN` with the token from BotFather. Add the
bot to the developer group. The group ID is configured in the workflow.

The tag must match the `package.json` version and the changelog must include
the corresponding `## [0.6.6]` section. Normal commits and non-semantic tags
do not send a message.

### Manual installation

For CI setup or troubleshooting, add the package directly to
`Packages/manifest.json`:

```json
{
  "dependencies": {
    "com.pearz.ci": "ssh://git@github.com/lhd98/PearzCI.git#upm"
  }
}
```

Because this is a private repository, each computer that installs or restores
the package must use a GitHub account or SSH key with read access to
`lhd98/PearzCI`. A computer that only pushes project code without opening the
Unity project does not need package access.

## Android

Run Unity in batch mode with:

```text
-executeMethod Pearz.CI.BuildEntry.BuildAndroid
```

Configuration is read from environment variables. The commonly used variables
are:

- `OUTPUT_PATH`
- `PRODUCT_NAME`
- `SCRIPTING_DEFINE_SYMBOLS`
- `APP_VERSION`
- `ANDROID_VERSION_CODE`
- `KEYSTORE_PATH` (optional path override)
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS_NAME`
- `KEY_ALIAS_PASSWORD`

See `BuildEntry.cs` for the complete list and accepted values.

Android builds always target both `ARMv7` and `ARM64`; there is no
architecture parameter to configure.

When Android signing credentials are provided without `KEYSTORE_PATH`, PearzCI
loads the keystore from the project convention below:

```text
<ProjectRoot>/Config/<BundleIdentifier>.keystore
```

For example, package name `com.pg.sushi.sort` uses
`Config/com.pg.sushi.sort.keystore`. The Android bundle identifier always comes
from Unity Project Settings. `KEYSTORE_PATH` remains supported for existing jobs
and non-standard locations; relative overrides are resolved from the Unity
project root.

## Windows standalone (.exe)

Run Unity on a **Windows Jenkins agent** with Windows Build Support installed:

```text
-executeMethod Pearz.CI.BuildEntry.BuildWindows
```

Set `BUILD_PLATFORM` to `Windows`. The pipeline writes and archives the full
player under `Builds/Windows/`: the `.exe`, its matching `*_Data` folder, and
Unity runtime files. Keep the whole folder together when distributing or
running the game; the `.exe` alone is not a runnable Unity player.

Supported optional settings are `PRODUCT_NAME`, `APP_VERSION`,
`SCRIPTING_DEFINE_SYMBOLS`, `IL2CPP_CODE_GENERATION`,
`MANAGED_STRIPPING_LEVEL`, and `STRIP_ENGINE_CODE`.

## Jenkins Shared Library

The package contains the Unity build entry point, while this repository also
provides the reusable Jenkins pipeline. Configure this repository once in
**Manage Jenkins > System > Global Pipeline Libraries**:

- Name: `pearz-ci`
- Default version: `upm`
- Retrieval method: Modern SCM
- Source Code Management: Git
- Project repository:
  `ssh://git@github.com/lhd98/PearzCI.git`
- Credentials: an SSH credential with read access to this repository
- Load implicitly: enabled
- Allow default version to be overridden: enabled
- Include `@Library` changes in job recent changes: disabled

Create one Jenkins **Pipeline** job for each Unity project. Select
**Pipeline script** (not **Pipeline script from SCM**) and store this script in
the Jenkins job:

```groovy
pearzUnityPipeline()
```

This one-line script is identical for every project. Jenkins loads PearzCI
implicitly at the globally configured version, so project jobs do not need an
`@Library` declaration, a second CI repository, or a project-specific
pipeline.

Keep **Include `@Library` changes in job recent changes** disabled. Jenkins
Poll SCM otherwise also polls the PearzCI repository and can repeatedly start
game builds when the library's `main` branch is ahead of the pinned release
tag. With this option disabled, Poll SCM reacts only to changes in the game
repository.

Enable **This project is parameterized** in the Jenkins job, then create and
save the project values under **Configure**. PearzCI reads them but never
creates, resets, or overwrites them.

Required parameters:

- String `PROJECT_REPOSITORY_URL`, for example
  `git@github.com:PearzGame/MyGame.git`
- String `GIT_BRANCH`, for example `main`
- String `PRODUCT_NAME`, for example `MyGame`
- Choice `BUILD_PLATFORM`: `Android`, `iOS`, or `Windows` (default: `Android`)

Common optional parameters:

- String `TELEGRAM_CHANNEL` using
  `botToken|chatId|messageThreadId`; separate targets with semicolons
- Multi-line String `SCRIPTING_DEFINE_SYMBOLS`
- Choice `IL2CPP_CODE_GENERATION`: `OptimizeSize` or `OptimizeSpeed`
- Choice `MANAGED_STRIPPING_LEVEL`: `Low`, `Medium`, or `High`
- Boolean `STRIP_ENGINE_CODE`, `MINIFY_RELEASE`, `BUILD_APP_BUNDLE`,
  `CLEAN_WORKSPACE`, `SEND_NOTIFICATIONS`, and `PROFILE_GRADLE`
- String `APP_VERSION` and `KEY_ALIAS_NAME`
- Password `KEYSTORE_PASSWORD` and `KEY_ALIAS_PASSWORD`
- Optional String `KEYSTORE_PATH`, only to override the default
  `Config/<BundleIdentifier>.keystore` location
- String `IOS_BUILD_NUMBER`, `IOS_DEVELOPMENT_TEAM`, and
  `IOS_PROVISIONING_PROFILE_SPECIFIER`
- String `IOS_EXPORT_OPTIONS_PLIST_PATH`
- Choice `XCODE_CONFIGURATION`: `Release` or `Debug`

PearzCI uses the Jenkins credential ID `github-ssh` for project checkout. The
Unity editor version is read from `ProjectSettings/ProjectVersion.txt`, and the
bundle identifier is read from Unity Project Settings; do not create Jenkins
parameters for these values.

`CLEAN_WORKSPACE` mặc định là `false`. Khi bật, PearzCI xoá toàn bộ workspace
của riêng Jenkins job trước bước checkout rồi tải lại project từ Git. Dùng tuỳ
chọn này khi Unity/Package cache hoặc `ProjectSettings` còn trạng thái từ build
trước; không bật nếu cần giữ file cục bộ chưa được commit trong workspace.

`SEND_NOTIFICATIONS` mặc định là `true`. Tắt nó để bỏ qua toàn bộ thông báo
sau build (hiện tại là Telegram); cờ này cũng áp dụng cho Discord, Lark hoặc
nền tảng khác khi được bổ sung sau này.

`PROFILE_GRADLE` mặc định là `false` và chỉ dùng cho Android build trên macOS.
Khi bật, sau khi Unity tạo APK/AAB, PearzCI chạy lại Gradle với `--rerun-tasks`
và tạo Gradle profile HTML. Tải `Builds/Android/gradle-profile/` và
`gradle-profile.log` từ Jenkins artifacts để xem task Gradle nào chậm. Chế độ
này có thể làm build chẩn đoán lâu thêm gần bằng một lần Gradle build, nhưng
không thay đổi source project, artifact chính, hoặc AAB version-code counter.

`ANDROID_VERSION_CODE` is managed automatically; do not create it as a Jenkins
parameter. APK builds use a fixed version code of `1`, because testers identify
a build by its version name, not its code; a constant code also lets any APK be
reinstalled over another without Android blocking it as a downgrade. AAB builds
use a separate, per-job persistent counter starting at `1`; it advances only
after a successful AAB pipeline, so APK test builds do not consume Google Play
version codes. The
counter is stored in the Jenkins job directory as `pearz-ci-aab-version-code.txt`,
which is retained even when `CLEAN_WORKSPACE` is enabled. Every Android build
also gets a visible version for use through Unity `Application.version`:
`<base version>-<BUILD_NUMBER>`. The base version comes from the optional
Jenkins `APP_VERSION` parameter; when that parameter is empty, it comes from
Unity Project Settings. For example, with Project Settings version `1.0.0`,
Jenkins build `67` is shown in-game as `Build 1.0.0-67`.

The FGSDK integration in the Unity project writes `<PRODUCT_NAME>_BUILD_INFO.txt`
after a successful export. PearzCI verifies and archives that file, uploads it
with the artifact, and puts a direct link to it on the `Build Info` line of the
Telegram message.

On Android the file must sit in `Builds/Android` beside the APK/AAB, and a
missing file fails the build. On iOS the Unity output is the whole Xcode
project, so the file can land either in `Builds/iOS` or inside
`Builds/iOS/Unity-iPhone`. The pipeline searches `Builds/iOS` up to three
levels deep — first for the exact `<PRODUCT_NAME>_BUILD_INFO.txt`, then for any
`*_BUILD_INFO.txt` in case the job's `PRODUCT_NAME` differs from the Unity
product name — and copies what it finds into `Builds/iOS`. A missing file does
not fail an iOS build; the `Build Info` line falls back to the Drive folder
link.

#### Google Drive layout

Every build goes into its own version folder, grouped by artifact type:

```text
<driveRoot>/<jobName>/apk/<version>/
<driveRoot>/<jobName>/aab/<version>/
<driveRoot>/<jobName>/ios/<version>/
```

For example, `JenkinsBuild/FoodSort/apk/1.0.0-157/` holds
`FoodSort-157.apk` and `FoodSort_BUILD_INFO.txt`, and the matching AAB lands in
`JenkinsBuild/FoodSort/aab/1.0.0-157/`. APK and AAB are kept apart because they
travel different routes — testers and Google Play.

`<version>` is `<APP_VERSION>-<artifact build number>`, or just the artifact
build number when `APP_VERSION` is empty. The artifact build number is the
current Jenkins `BUILD_NUMBER`.

#### Stage View của pipeline

Android và iOS **dùng chung một pipeline**, nên Stage View hiển thị chung một
đồ thị cho mọi nền tảng; stage nào không áp dụng cho build hiện tại thì hiện ở
trạng thái skipped. Pipeline gồm **10 stage**:

| Stage | Chạy khi |
|-------|----------|
| Checkout | mọi build |
| Prepare Build Variables | mọi build |
| Validate Unity | mọi build — in tham số rồi kiểm tra Unity/Xcode |
| Build Unity Android | Android |
| Build Unity iOS | iOS — kèm dọn trùng dependency AppLovin SPM |
| Archive and Export IPA | iOS, build IPA (không phải cắm máy) |
| Build and Install on iOS Device | iOS, build cắm thẳng vào iPhone |
| Verify & Archive Artifact | mọi build trừ iOS cắm máy — kèm đọc metadata |
| Upload to Google Drive | mọi build trừ iOS cắm máy |
| Upload to TestFlight | iOS IPA và `UPLOAD_TO_TESTFLIGHT=true` |

Để bớt số cột, nhiều bước phụ đã được gộp vào stage liền kề thay vì tách riêng:
`Show Parameters` gộp vào `Validate Unity`; dọn trùng AppLovin SPM gộp vào
`Build Unity iOS`; đọc metadata và archive artifact gộp vào `Verify & Archive
Artifact`; còn kiểm tra rclone, xác minh upload Drive, tạo public link và
archive `upload.log` gộp hết vào `Upload to Google Drive`.

Đánh đổi: khi một bước con của `Upload to Google Drive` hỏng, Stage View chỉ báo
đỏ ở đúng cột đó chứ không chỉ ra ngay bước nào (kiểm tra rclone, upload, xác
minh, hay tạo link) — mở log của stage để xem chi tiết.

### Configure the shared GitHub webhook

For existing Pipeline jobs, first create a Jenkins **Secret text** credential:

- ID: `pearz-github-webhook`
- Secret: a long random value, stored only in Jenkins and the webhook URL

Then bootstrap the Generic Webhook Trigger configuration with [jenkins/configure-generic-webhook-trigger.groovy](jenkins/configure-generic-webhook-trigger.groovy):

1. Paste the script into **Manage Jenkins > Script Console** with `dryRun = true`
   and review the repository, branch, and skipped-job output.
2. Run it again with `dryRun = false` to save the configuration.
3. Add one GitHub push webhook for all repositories:
   `https://<JENKINS_URL>/generic-webhook-trigger/invoke?token=<SECRET_VALUE>`

The `token` query value must be the secret value from the Jenkins credential.
Leave GitHub's separate **Secret** field empty; this setup authenticates with
the Generic Webhook Trigger token credential.

The script configures only Pipeline jobs with default `PROJECT_REPOSITORY_URL`
and `GIT_BRANCH` values. Each job filters the shared webhook by its own
`owner/repository` and `refs/heads/branch`. Existing Generic Webhook Trigger
and **GitHub hook trigger for GITScm polling** entries are replaced/removed;
other trigger types are preserved. Jobs missing either required value are
reported for separate handling. The Generic Webhook Trigger plugin must be
installed before running the script.

New jobs keep the `GenericTrigger(...)` declaration in the PearzCI pipeline, so
their trigger is synchronized automatically on the first pipeline run. A
manual build is not required for the bootstrap script.

The webhook filter always follows the **default** value of `GIT_BRANCH` saved
under **Configure**, never the value used by the current run. Building a
different branch through **Build with Parameters** therefore builds that
branch without changing which branch the webhook listens to. Changing the
default in **Configure** takes effect on the next build of the job, because
that is when the pipeline rewrites the trigger; a single manual build is
enough to apply it, and the Script Console bootstrap is not needed.

**Poll SCM** may still be kept as a separate fallback when Jenkins cannot be
reached by GitHub; for example, `H/5 * * * *` checks approximately every five
minutes.

After the trigger is configured, developers only push game code; the webhook
starts the matching job, PearzCI checks out the game repository, and Jenkins
builds it.

The game repository does not need a `Jenkinsfile`. It only needs the PearzCI
UPM dependency committed in `Packages/manifest.json` and
`Packages/packages-lock.json`, so Unity can compile the build entry point.

The reusable pipeline performs checkout, recursive submodule initialization,
Unity validation and Android build, artifact verification and archiving,
Google Drive upload and verification, public-link creation, Telegram
notification, and build-output cleanup.

### Build metadata and Telegram notification

Unity writes `Builds/Android/build-metadata.json` after every Android build,
successful or not; a failed build records the Unity error message in
`errorMessage`. Jenkins reads this file without trying to modify the parent
process environment. Missing or invalid optional metadata is reported as a
warning and omitted from the notification.

The notification is sent from the pipeline's `post` block, so a failed,
aborted, or unstable build is reported as well. `{{RESULT}}` is the Jenkins
result rather than Unity's, because a build can produce a valid APK and still
fail while uploading to Google Drive. `{{ERROR_SECTION}}` shows the Unity
error message when one exists.

A build is never failed because of the notification itself. If sending fails,
the error is printed to the Jenkins console and an otherwise successful build
is marked `UNSTABLE`.

Telegram only displays values that really exist. Depending on the build, the
message can contain:

- Full Jenkins job name, build number, result, and `BUILD_URL`.
- Version name, Android version code, product name, bundle ID, and generated
  Google Play URL. Android reads these from `build-metadata.json`; iOS reads
  `CFBundleShortVersionString` and `CFBundleVersion` from the `Info.plist` of
  the exported Xcode project, so the reported version is the one actually
  built rather than the one requested.
- Scripting backend, managed stripping level, orientation, and Unity version.
- Jenkins build, upload, and total durations.
- APK or AAB public link and size.
- A direct Google Drive link to `<PRODUCT_NAME>_BUILD_INFO.txt`, so the
  `Build Info` line opens the text file itself instead of the folder that
  contains it. An iOS build that has no such file falls back to the Drive
  folder link.
- TestFlight upload status when `UPLOAD_TO_TESTFLIGHT` is enabled.
- `mapping.txt` public link and size when Unity reports a mapping file.
- Every commit (short hash, author, and subject) since the previous
  **successful** Jenkins build, up to the 10 most recent, followed by a count
  of the remaining commits. The first build falls back to the current commit.
  The baseline deliberately skips aborted and failed builds: Jenkins records a
  build's commit at checkout, so a build that never finished would otherwise
  consume the changelog and leave the next build reporting no changes.
- Jenkins artifact links for `unity-build.log` and `upload.log` when present.

Telegram rejects a message longer than 4096 characters, so the rendered
message is truncated at that limit and marked with `... (message truncated)`.
The commit list is capped first because it is the only unbounded section.

The notification keeps the existing `TELEGRAM_CHANNEL` format and supports
multiple semicolon-separated targets. Build metadata and notification helper
files are archived before the `Builds` directory is cleaned.

#### Shared Telegram template

The Telegram layout is stored in the PearzCI shared-library resource:

```text
resources/com/pearz/ci/telegram-message-template.txt
```

Edit this file to change labels, move fields, or add literal separator lines
such as `--------------------`. No file is added to a Unity project's `Assets`
folder. Release a new PearzCI tag and update the Jenkins Global Pipeline
Library version to apply the layout to all jobs.

Telegram messages use `parse_mode=HTML`, so the template can use Telegram's
supported tags such as `<b>`, `<i>`, `<u>`, `<code>`, and `<blockquote>`. Values
coming from Jenkins are escaped automatically before rendering; do not add
HTML markup inside placeholder values.

Available placeholders are:

```text
{{RESULT}} {{JOB}} {{JOB_NAME}} {{BUILD_NUMBER}} {{BUILD_URL}}
{{PEARZ_CI_VERSION}}
{{VERSION}} {{VERSION_NAME}} {{VERSION_CODE}}
{{PRODUCT_NAME}} {{BUNDLE_ID}} {{GOOGLE_PLAY_URL}} {{BRANCH}}
{{CONFIGURATION}} {{SCRIPTING_BACKEND}} {{STRIPPING_LEVEL}}
{{ORIENTATION}} {{UNITY_VERSION}}
{{BUILD_TIME}} {{UPLOAD_TIME}} {{TOTAL_TIME}}
{{BUILD_INFO_URL}}
{{APK}} {{AAB}} {{IPA}} {{TESTFLIGHT}} {{MAPPING}} {{INSTALL_STATUS}}
{{ERROR_SECTION}} {{CHANGES_SECTION}}
{{JENKINS_LOGS_SECTION}}
```

A line containing a placeholder with no value is removed automatically.
Multi-line section placeholders include their own heading and content.

### Build machine setup

PearzCI detects the operating system of the Jenkins agent automatically.
Windows and macOS use the following defaults:

| Setting | Windows | macOS |
| --- | --- | --- |
| Unity Hub editors | `C:\Program Files\Unity\Hub\Editor` | `/Applications/Unity/Hub/Editor` |
| rclone command | `D:\Tools\rclone\rclone.exe` | `rclone` from `PATH` |
| Telegram client | Windows PowerShell | POSIX shell and `curl` |

The Jenkins agent user must have:

- Git and SSH access to both the Unity project and private PearzCI repository.
- Unity Hub, the configured Unity version, Android Build Support, and a valid
  Unity license.
- An rclone remote matching `DRIVE_REMOTE` (`gdrive` by default).
- Network access to GitHub, Google Drive, and Telegram when those stages are
  enabled.

Platform paths can be overridden when calling the pipeline:

```groovy
pearzUnityMobilePipeline(
    windowsUnityHubRoot: 'C:\\Program Files\\Unity\\Hub\\Editor',
    windowsRcloneExe: 'D:\\Tools\\rclone\\rclone.exe',
    macUnityHubRoot: '/Applications/Unity/Hub/Editor',
    macRcloneExe: 'rclone'
)
```

For a single Mac Mini Jenkins installation, the standard one-line call remains
enough:

```groovy
pearzUnityMobilePipeline()
```

### Build retention

PearzCI keeps the last 30 build records and the artifacts of the last 10
builds. Without this limit, every archived APK or AAB stays on the Jenkins
controller permanently. Override the limits per job when a project needs a
longer history:

```groovy
pearzUnityMobilePipeline(
    buildsToKeep: 30,
    artifactBuildsToKeep: 10
)
```

### Telegram commit list

The commit limit is configurable per pipeline job and defaults to 10.
Example: pearzUnityMobilePipeline(telegramMaxCommits: 10)

The Jenkins log reports the baseline source, total commits found, visible commits,
and hidden commits.

## Versioning

Unity projects should use the stable UPM update channel:

```text
ssh://git@github.com/lhd98/PearzCI.git#upm
```

PearzCI advances the `upm` branch whenever a new version is released. Unity
keeps the resolved commit in `Packages/packages-lock.json`; select Pearz CI in
Package Manager and use **Update** to move to the latest release. Commit both
`Packages/manifest.json` and `Packages/packages-lock.json` after updating so
every developer and Jenkins build resolves the same commit.

Immutable tags such as `v0.5.5` remain available for rollback or projects that
prefer a fixed UPM version. The Jenkins administrator should pin a release tag
such as `v0.5.5` when automatic updates through the `upm` branch are not
desired.

### Releasing a new version

The version appears in `package.json` and in
`resources/com/pearz/ci/version.txt`, the string the pipeline reports as
`{{PEARZ_CI_VERSION}}` in Telegram. Set both with one command instead of
editing them by hand:

```powershell
pwsh ./tools/bump-version.ps1 -Version 0.5.5
```

Then add the matching `CHANGELOG.md` entry, commit, create an annotated tag
(`git tag -a v0.5.5 -m "Release v0.5.5"`), and advance the `upm` branch.

## iOS Jenkins pipeline

iOS needs a **macOS** Jenkins agent. The iOS job is separate from Android and
must run only on a node with the `macos` label (or another label configured in
the call). Install the same Unity editor version with **iOS Build Support**,
Xcode Command Line Tools, Git, rclone, and a valid Unity license under the
Jenkins agent user.

With `pearzUnityPipeline()`, no second job is needed: select `iOS` from
`BUILD_PLATFORM` in the existing job. Android, iOS IPA, and iOS connected-device
builds share one fixed Jenkins Stage View; stages that do not apply to the run
are shown as skipped, so changing `BUILD_PLATFORM` or `IOS_BUILD_TO_DEVICE` does
not replace the graph layout. iOS runs are pinned to the `macos` agent label
(override with `macAgentLabel`), while Android keeps running on any available
agent. One job covers Android and iOS: select `iOS` from `BUILD_PLATFORM`.

The job uses the existing required parameters `PROJECT_REPOSITORY_URL`,
`GIT_BRANCH`, and `PRODUCT_NAME`. iOS runs read these additional values; keep
the stable ones in the job's pipeline script (see the config keys below) and
leave only the per-build toggles as job parameters:

- String `IOS_BUILD_NUMBER` (for example `42`; when left empty, the Jenkins
  `BUILD_NUMBER` is used)
- String `IOS_DEVELOPMENT_TEAM` (Apple Developer Team ID) — IPA export only
- String `IOS_PROVISIONING_PROFILE_SPECIFIER` (profile name; leave empty when
  the Xcode project is configured for automatic signing)
- String `IOS_EXPORT_OPTIONS_PLIST_PATH` (absolute, readable path on the Mac)
- Choice `XCODE_CONFIGURATION`: `Release` or `Debug` (IPA export only; a
  connected-device build always uses `Debug`)
- Boolean `IOS_BUILD_TO_DEVICE` (default: false)
- String `IOS_DEVICE_UDID` (leave empty to auto-detect the single wired,
  paired device)
- Boolean `UPLOAD_TO_TESTFLIGHT` (default: false)
- String `APP_STORE_CONNECT_KEY_ID` and `APP_STORE_CONNECT_ISSUER_ID`
  (required when `UPLOAD_TO_TESTFLIGHT` is enabled)

Because IPA export needs a **distribution** provisioning profile while a
connected-device build needs a **development** one, set them separately in the
pipeline script rather than retyping a parameter each build:

- `iosProvisioningProfileSpecifier` — distribution profile, used for IPA
  export / TestFlight
- `iosDeviceProvisioningProfileSpecifier` — development profile, used for
  connected-device builds; falls back to `iosProvisioningProfileSpecifier`,
  then to the `IOS_PROVISIONING_PROFILE_SPECIFIER` parameter, when unset

The remaining shared optional values are also supported: `SCRIPTING_DEFINE_SYMBOLS`,
`APP_VERSION`, `IL2CPP_CODE_GENERATION`, `MANAGED_STRIPPING_LEVEL`, and
`STRIP_ENGINE_CODE`.

The pipeline first exports `Unity-iPhone.xcodeproj`, then invokes
`xcodebuild archive` and `xcodebuild -exportArchive`. The final signed IPA,
Unity log, and Xcode log are archived in Jenkins and uploaded to the configured
Google Drive remote just like Android artifacts.

### Upload to TestFlight

Set `UPLOAD_TO_TESTFLIGHT` to `true` to submit the exported IPA to App Store
Connect with `xcrun altool --upload-app`. The stage runs only for iOS IPA builds
(never for connected-device builds) and only after the IPA has already been
uploaded to Google Drive, so a rejected submission still leaves a downloadable
build and a working link in the Telegram message. A failed upload fails the
build, and the Telegram `Error` block says the IPA was built but the upload
failed, so it is not confused with a broken build.

Authentication uses an App Store Connect API key. In App Store Connect, open
**Users and Access > Integrations > App Store Connect API**, create a key with
the **App Manager** role, and download the `.p8` file — Apple allows that
download only once. Then, in Jenkins, create a **Secret file** credential
holding the `.p8`:

- ID: `appstore-connect-api-key` (override with `appStoreConnectApiKeyCredentialsId`)

The Key ID and Issuer ID are identifiers rather than secrets, so they are passed
as job parameters (`APP_STORE_CONNECT_KEY_ID`, `APP_STORE_CONNECT_ISSUER_ID`) or
as call options:

```groovy
pearzUnityPipeline(
    uploadToTestFlight: true,
    appStoreConnectKeyId: 'ABCD1234EF',
    appStoreConnectIssuerId: '69a6de70-0000-0000-0000-000000000000'
)
```

`altool` only reads the private key from a file named `AuthKey_<KeyID>.p8` in a
`private_keys` directory, so the pipeline copies the credential into
`$WORKSPACE/private_keys` for the duration of the upload and removes it
immediately afterwards, including when the upload fails.

App Store Connect rejects a build whose `CFBundleVersion` matches one already
uploaded, so `IOS_BUILD_NUMBER` defaults to the Jenkins `BUILD_NUMBER` when the
parameter is empty. `APP_VERSION` is deliberately **not** filled in the same
way: it becomes `CFBundleShortVersionString`, which Apple requires to be
period-separated numbers, so the `<version>-<build>` form used for Android
version names and Drive folders would be rejected. Leave `APP_VERSION` empty to
keep the marketing version set in the Unity project.

The export options plist must be configured for App Store distribution
(`<key>method</key><string>app-store</string>`); a development or ad-hoc export
is rejected by App Store Connect. App Store Connect keeps processing the build
after `altool` returns, so the Telegram message reports the upload as accepted,
not as ready for testers.

### Build directly to a connected iPhone

`pearzUnityPipeline()` runs this mode through the same shared stage graph as
Android and iOS IPA builds, so the Jenkins Stage View layout does not change
when `IOS_BUILD_TO_DEVICE` is toggled.

For a development-only device build, set `IOS_BUILD_TO_DEVICE=true` and build.
PearzCI always uses the `Debug` configuration for device builds, and
`IOS_DEVELOPMENT_TEAM` is not needed here (it applies only to IPA export).
Leave `IOS_DEVICE_UDID` empty to auto-detect the connected iPhone: the build
reads `xcrun devicectl list devices` and, when exactly one wired, paired device
is present, uses its UDID. It supports both the legacy CoreDevice JSON fields
and the current `properties` schema. When connection details are unavailable,
it falls back to exactly one physical device reported as `connected`; with zero
or several devices it fails and prints the list so you can set
`IOS_DEVICE_UDID` explicitly. PearzCI
builds the exported Xcode project for that device and installs the app with
`xcrun devicectl`; it does not require `IOS_EXPORT_OPTIONS_PLIST_PATH`, create
an IPA, or upload to Drive. The connected device must be trusted and visible to
the Jenkins macOS user through `xcrun devicectl list devices`.

`xcodebuild` waits up to `iosDestinationTimeoutSeconds` (default 300) for the
device to become an available destination, rather than Xcode's own 30-second
default. A phone that is still running *Preparing device for development* —
which Xcode does after an iOS update — can take longer than 30 seconds, and the
build would otherwise fail with `Timed out waiting for all destinations` even
though the device is ready shortly afterwards. When the failure message also
says the device *may need to be unlocked to recover from previously reported
preparation errors*, no timeout helps: check the device state on the Mac with
`xcrun devicectl list devices`, or re-pair it in **Xcode > Window > Devices and
Simulators**.
When `TELEGRAM_CHANNEL` is configured and `SEND_NOTIFICATIONS` is enabled,
PearzCI also sends a success or failure notification for this device build,
including the Jenkins and build-log links.
An installed development provisioning profile is required for device signing.
**By default no configuration is needed:** when no profile is set, PearzCI
derives the Xcode-managed name `iOS Team Provisioning Profile: <bundle id>`
from the built app's bundle identifier and signs with the matching installed
profile. So a device-only job's script stays as short as
`pearzUnityPipeline(macRcloneExe: '…')` with no iOS signing lines.

This works for any game whose development profile is already installed on the
Mac agent (see [Onboarding a new iOS game](#onboarding-a-new-ios-game)). To
override the derived name — a custom profile, or a non-standard setup — set it
in the pipeline script as `iosDeviceProvisioningProfileSpecifier`, for example
`iosDeviceProvisioningProfileSpecifier: 'iOS Team Provisioning Profile: com.pg.sushi.sort'`.
This is a **development** profile, distinct from the **distribution** profile
that IPA export uses (`iosProvisioningProfileSpecifier`), which is why the two
have separate keys. When `iosDeviceProvisioningProfileSpecifier` is unset it
falls back to `iosProvisioningProfileSpecifier`, then to the
`IOS_PROVISIONING_PROFILE_SPECIFIER` parameter, and finally to the derived
name.
When the Xcode project includes In-App Purchase, this device-only flow removes
that capability from the generated export so a free Apple Personal Team can
sign it. In-App Purchase is therefore unavailable in that test build; normal
IPA export leaves the capability unchanged.

This is compatible with an Xcode Personal Team for temporary local testing.
Personal Team provisioning expires frequently and is not appropriate for
TestFlight, App Store, or general distribution.

#### Onboarding a new iOS game

Auto-derivation only computes the profile *name*; the profile *file* must
already exist on the Mac agent. Apple generates it once, per game, when Xcode
signs that bundle id for the first time. So the first time a new game builds to
a device on this Mac, do this one-time step as the Jenkins macOS account:

1. Open the Unity-exported Xcode project (`Builds/iOS/Unity-iPhone`) in Xcode.
2. Select the signing **Team** and enable **Automatically manage signing**.
3. Plug in the iPhone and build/run to it once.

Xcode then creates `iOS Team Provisioning Profile: <bundle id>`, registers the
device, and installs the profile into
`~/Library/MobileDevice/Provisioning Profiles`. After that, CI device builds
for that game run with no signing configuration. A game that already builds to
a device (for example FoodSort) has completed this step and needs nothing more.
On a **Personal Team** the development profile expires after 7 days and Xcode
regenerates it the next time you build there; the derived name never changes.

### Signing setup on the Mac agent

Run these commands as the **same macOS account that starts the Jenkins agent**:

```sh
xcode-select -p
xcodebuild -version
security find-identity -v -p codesigning
```

Install the Apple distribution certificate (with its private key) and the
matching provisioning profile in that account's Keychain/profile directory.
Keep `ExportOptions.plist` outside the workspace in a directory readable only
by the Jenkins user, for example `/Users/jenkins/ci/ExportOptions.plist`.
Do not commit certificates, `.mobileprovision` files, private keys, or this
plist to the Unity repository.

For manual signing, the export plist's `provisioningProfiles` dictionary must
map the configured bundle identifier to `IOS_PROVISIONING_PROFILE_SPECIFIER`.
For automatic signing, leave that parameter empty and configure the required
Apple account/team access for the Jenkins macOS user in Xcode. In both cases,
run the job once manually before enabling its webhook to confirm that the IPA
is signed by the expected team.

To use another macOS node or nonstandard paths, and to pin the iOS signing
values so they need not be entered per build:

```groovy
pearzUnityPipeline(
    platform: 'iOS',
    macAgentLabel: 'mac-mini',
    macUnityHubRoot: '/Applications/Unity/Hub/Editor',
    macRcloneExe: 'rclone',
    iosExportOptionsPlistPath: '/Users/jenkins/ci/ExportOptions.plist',
    iosDevelopmentTeam: 'ABCDE12345',
    iosProvisioningProfileSpecifier: 'FoodSort App Store',
    iosDeviceProvisioningProfileSpecifier: 'FoodSort Dev'
)
```
