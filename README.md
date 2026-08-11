# Pearz CI

Reusable Unity Editor build entry points and a Jenkins Shared Library for
Pearz CI pipelines.

## Requirements

- Unity 6000.3 or newer on Windows or macOS
- Android Build Support when building Android players
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
- `BUILD_CONFIGURATION`
- `PRODUCT_NAME`
- `BUNDLE_IDENTIFIER`
- `SCRIPTING_DEFINE_SYMBOLS`
- `APP_VERSION`
- `ANDROID_VERSION_CODE`
- `TARGET_ARCHITECTURES`
- `KEYSTORE_PATH`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS_NAME`
- `KEY_ALIAS_PASSWORD`

See `BuildEntry.cs` for the complete list and accepted values.

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
pearzUnityAndroidPipeline()
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
- String `GIT_CREDENTIALS_ID`, for example `github-ssh`
- String `GIT_BRANCH`, for example `main`
- String `UNITY_VERSION`, for example `6000.3.14f1`
- String `PRODUCT_NAME`, for example `MyGame`
- Choice `BUILD_CONFIGURATION`: `Development` or `Release`

Common optional parameters:

- String `TELEGRAM_CHANNEL` using
  `botToken|chatId|messageThreadId`; separate targets with semicolons
- String `BUNDLE_IDENTIFIER`
- Multi-line String `SCRIPTING_DEFINE_SYMBOLS`
- Choice `TARGET_ARCHITECTURES`: `ARM64` or `ARMV7_ARM64`
- Choice `IL2CPP_CODE_GENERATION`: `OptimizeSize` or `OptimizeSpeed`
- Choice `MANAGED_STRIPPING_LEVEL`: `Low`, `Medium`, or `High`
- Boolean `STRIP_ENGINE_CODE`, `MINIFY_RELEASE`, `SCRIPT_DEBUGGING`,
  `UNITY_DEVELOPMENT_BUILD`, and `BUILD_APP_BUNDLE`
- String `APP_VERSION`, `KEYSTORE_PATH`, and `KEY_ALIAS_NAME`
- Password `KEYSTORE_PASSWORD` and `KEY_ALIAS_PASSWORD`

`ANDROID_VERSION_CODE` is managed automatically by PearzCI from Jenkins
`BUILD_NUMBER`; do not create it as a Jenkins parameter. Every Android build
also gets a visible version for use through Unity `Application.version`:
`<BUILD_NUMBER>`, or `<APP_VERSION>-<BUILD_NUMBER>` when the optional
`APP_VERSION` parameter is set. For example, Jenkins build `67` with
`APP_VERSION=1.0.0` is shown in-game as `Build 1.0.0-67`.

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
  Google Play URL.
- Scripting backend, managed stripping level, orientation, Unity version, and
  scripting define symbols.
- Jenkins build, upload, and total durations.
- APK or AAB public link and size.
- Google Drive build-folder and root links when rclone can create them.
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
{{DRIVE_FOLDER_URL}} {{DRIVE_ROOT_URL}}
{{APK}} {{AAB}} {{MAPPING}}
{{ERROR_SECTION}} {{DEFINE_SYMBOLS_SECTION}} {{CHANGES_SECTION}}
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
pearzUnityAndroidPipeline(
    windowsUnityHubRoot: 'C:\\Program Files\\Unity\\Hub\\Editor',
    windowsRcloneExe: 'D:\\Tools\\rclone\\rclone.exe',
    macUnityHubRoot: '/Applications/Unity/Hub/Editor',
    macRcloneExe: 'rclone'
)
```

For a single Mac Mini Jenkins installation, the standard one-line call remains
enough:

```groovy
pearzUnityAndroidPipeline()
```

### Build retention

PearzCI keeps the last 30 build records and the artifacts of the last 10
builds. Without this limit, every archived APK or AAB stays on the Jenkins
controller permanently. Override the limits per job when a project needs a
longer history:

```groovy
pearzUnityAndroidPipeline(
    buildsToKeep: 30,
    artifactBuildsToKeep: 10
)
```

### Telegram commit list

The commit limit is configurable per pipeline job and defaults to 10.
Example: pearzUnityAndroidPipeline(telegramMaxCommits: 10)

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

Create a second Pipeline job for the same Unity project. Its Pipeline script
is:

```groovy
pearzUnityIosPipeline()
```

The job uses the existing required parameters `PROJECT_REPOSITORY_URL`,
`GIT_CREDENTIALS_ID`, `GIT_BRANCH`, `UNITY_VERSION`, `PRODUCT_NAME`, and
`BUILD_CONFIGURATION`. Add these iOS parameters under **This project is
parameterized**:

- String `IOS_BUILD_NUMBER` (for example `42`)
- String `IOS_DEVELOPMENT_TEAM` (Apple Developer Team ID)
- String `IOS_PROVISIONING_PROFILE_SPECIFIER` (profile name; leave empty when
  the Xcode project is configured for automatic signing)
- String `IOS_EXPORT_OPTIONS_PLIST_PATH` (absolute, readable path on the Mac)
- Choice `XCODE_CONFIGURATION`: `Release` or `Debug`

The remaining shared optional values are also supported: `BUNDLE_IDENTIFIER`,
`SCRIPTING_DEFINE_SYMBOLS`, `APP_VERSION`, `IL2CPP_CODE_GENERATION`,
`MANAGED_STRIPPING_LEVEL`, `STRIP_ENGINE_CODE`, `UNITY_DEVELOPMENT_BUILD`, and
`SCRIPT_DEBUGGING`.

The pipeline first exports `Unity-iPhone.xcodeproj`, then invokes
`xcodebuild archive` and `xcodebuild -exportArchive`. The final signed IPA,
Unity log, and Xcode log are archived in Jenkins and uploaded to the configured
Google Drive remote just like Android artifacts.

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

To use another macOS node or nonstandard paths:

```groovy
pearzUnityIosPipeline(
    macAgentLabel: 'mac-mini',
    macUnityHubRoot: '/Applications/Unity/Hub/Editor',
    macRcloneExe: 'rclone'
)
```
