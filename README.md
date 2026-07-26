# Pearz CI

Reusable Unity Editor build entry points and a Jenkins Shared Library for
Pearz CI pipelines.

## Requirements

- Unity 6000.3 or newer
- Android Build Support when building Android players

## Installation

### Unity Package Manager

1. Open **Window > Package Management > Package Manager**.
2. Select **+ > Install package from git URL...**.
3. Enter:

```text
ssh://git@github.com/lhd98/PearzCI.git#v0.2.1
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
    "com.pearz.ci": "ssh://git@github.com/lhd98/PearzCI.git#v0.2.1"
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
- Default version: `v0.2.1`
- Retrieval method: Modern SCM
- Source Code Management: Git
- Project repository:
  `ssh://git@github.com/lhd98/PearzCI.git`
- Credentials: an SSH credential with read access to this repository
- Allow default version to be overridden: enabled

Create one Jenkins **Pipeline** job for each Unity project. Select
**Pipeline script** (not **Pipeline script from SCM**) and store this script in
the Jenkins job:

```groovy
@Library('pearz-ci@v0.2.1') _

pearzUnityAndroidPipeline(
    repositoryUrl: 'git@github.com:PearzGame/MyGame.git',
    repositoryCredentialsId: 'github-ssh',
    gitBranch: 'main',
    unityVersion: '6000.3.14f1',
    productName: 'MyGame',
    bundleIdentifier: 'com.pearz.mygame',
    telegramCredentialsId: 'mygame-telegram'
)
```

`telegramCredentialsId` is optional. When used, create a Jenkins **Secret
text** credential whose value uses
`botToken|chatId|messageThreadId`; separate multiple targets with semicolons.
Do not store the token in the Pipeline script.

Choose one automatic trigger:

- If Jenkins is reachable from GitHub, enable **GitHub hook trigger for
  GITScm polling** and add the Jenkins webhook URL to the GitHub repository.
- If Jenkins only runs on a local PC, enable **Poll SCM** instead. For example,
  `H/5 * * * *` checks for pushed commits approximately every five minutes
  without exposing Jenkins to the internet.

The first manual build registers the repository checkout and creates the
remaining build parameters. After that, developers only push game code; the
configured trigger starts the job, PearzCI checks out the game repository, and
Jenkins builds it.

The game repository does not need a `Jenkinsfile`. It only needs the PearzCI
UPM dependency committed in `Packages/manifest.json` and
`Packages/packages-lock.json`, so Unity can compile the build entry point.

The reusable pipeline performs checkout, recursive submodule initialization,
Unity validation and Android build, artifact verification and archiving,
Google Drive upload and verification, public-link creation, Telegram
notification, and build-output cleanup.

## Versioning

Projects should pin a release tag such as `v0.2.1` for both the UPM package and
the Jenkins Shared Library. Avoid depending directly on `main` in builds.
