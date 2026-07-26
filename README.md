# Pearz CI

Reusable Unity Editor build entry points and a Jenkins Shared Library for
Pearz CI pipelines.

## Requirements

- Unity 6000.3 or newer on Windows or macOS
- Android Build Support when building Android players
- Jenkins with Pipeline, Git, Credentials, and Shared Library support
- rclone with a configured Google Drive remote
- `curl` on macOS for Telegram notifications

## Installation

### Unity Package Manager

1. Open **Window > Package Management > Package Manager**.
2. Select **+ > Install package from git URL...**.
3. Enter:

```text
ssh://git@github.com/lhd98/PearzCI.git#v0.3.0
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
    "com.pearz.ci": "ssh://git@github.com/lhd98/PearzCI.git#v0.3.0"
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
- Default version: `v0.3.0`
- Retrieval method: Modern SCM
- Source Code Management: Git
- Project repository:
  `ssh://git@github.com/lhd98/PearzCI.git`
- Credentials: an SSH credential with read access to this repository
- Load implicitly: enabled
- Allow default version to be overridden: enabled

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
- String `APP_VERSION`, `ANDROID_VERSION_CODE`, `KEYSTORE_PATH`, and
  `KEY_ALIAS_NAME`
- Password `KEYSTORE_PASSWORD` and `KEY_ALIAS_PASSWORD`

Choose one automatic trigger:

- If Jenkins is reachable from GitHub, enable **GitHub hook trigger for
  GITScm polling** and add the Jenkins webhook URL to the GitHub repository.
- If Jenkins only runs on a local PC, enable **Poll SCM** instead. For example,
  `H/5 * * * *` checks for pushed commits approximately every five minutes
  without exposing Jenkins to the internet.

After **Configure > Save**, run one manual build to register the repository
checkout. After that, developers only push game code; the configured trigger
starts the job, PearzCI checks out the game repository, and Jenkins builds it.

The game repository does not need a `Jenkinsfile`. It only needs the PearzCI
UPM dependency committed in `Packages/manifest.json` and
`Packages/packages-lock.json`, so Unity can compile the build entry point.

The reusable pipeline performs checkout, recursive submodule initialization,
Unity validation and Android build, artifact verification and archiving,
Google Drive upload and verification, public-link creation, Telegram
notification, and build-output cleanup.

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

## Versioning

Projects should pin a release tag such as `v0.3.0` for the UPM package.
The Jenkins administrator should pin the same tag as the Global Pipeline
Library's default version. Avoid depending directly on `main` in builds.
