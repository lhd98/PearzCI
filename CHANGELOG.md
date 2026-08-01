# Changelog

All notable changes to this package are documented in this file.

## [Unreleased]

## [0.4.4] - 2026-08-01

### Changed

- Telegram build notifications now list every commit since the previous
  Jenkins build instead of only the current commit.

## [0.4.3] - 2026-08-01

### Changed

- New GitHub pushes abort an in-progress build and use a short debounce period
  before Jenkins starts the replacement build.
- The reusable pipeline registers a GitHub push trigger directly.

## [0.4.2] - 2026-07-27

### Added

- A permanent `upm` release branch for one-click Git package updates from the
  Unity Package Manager.

### Changed

- UPM installation documentation now follows `#upm`, while immutable release
  tags remain available for rollback and Jenkins Shared Library pinning.
- The legacy `codex/telegram-build-metadata` branch is advanced to this release
  so existing test installations can update without being removed.

## [0.4.1] - 2026-07-27

### Added

- A shared `telegram-message-template.txt` resource controls the Telegram
  notification layout for every Jenkins job using PearzCI.
- Template placeholders support moving fields, changing labels, and inserting
  literal separators without adding files to individual Unity projects.

### Changed

- Lines containing unavailable placeholder values are omitted automatically,
  preserving optional build metadata behavior.

## [0.4.0] - 2026-07-27

### Added

- Unity now writes optional Android build metadata to
  `Builds/Android/build-metadata.json`.
- Telegram notifications include real build settings, stage timings, file
  sizes, Drive links, Google Play URL, changes, and Jenkins log links when
  those values exist.
- `mapping.txt`, Unity build logs, and rclone upload logs are collected and
  archived when available.

### Changed

- Jenkins constructs the Telegram message from Unity metadata instead of
  relying on environment variables written by the Unity child process.
- Metadata parsing now works without Jenkins Script Approval or an additional
  Pipeline utility plugin.
- Optional metadata, mapping files, folder links, and log files no longer
  prevent a successful main artifact build or notification.

## [0.3.0] - 2026-07-26

### Added

- Native Jenkins pipeline support for Windows and macOS agents.
- macOS Unity Hub discovery using the standard
  `/Applications/Unity/Hub/Editor` location.
- POSIX shell Telegram notifications using the built-in macOS `curl`.
- Configurable platform-specific Unity Hub and rclone paths.

### Changed

- Build paths now use portable separators accepted by Unity and Jenkins.
- Git, Unity, rclone, upload, verification, public-link, and cleanup stages
  automatically select Windows Batch or macOS shell commands.
- Unity command-line builds explicitly select the Android build target.
- Artifact verification and build-directory cleanup use Jenkins
  cross-platform steps.
- Shared Library setup now excludes PearzCI changes from job changesets so
  Poll SCM only triggers for game repository changes.

## [0.2.2] - 2026-07-26

### Changed

- Jenkins job parameters are now owned entirely by **Configure > Save**.
- PearzCI no longer creates or overwrites job parameters during a build.
- Every project can use the same two-line Pipeline script and provide its
  repository, branch, Unity version, Telegram targets, and other build values
  through Jenkins parameters.

## [0.2.1] - 2026-07-26

### Changed

- Jenkins jobs can store the game repository, branch, and credential settings
  directly in Jenkins; game repositories no longer need a `Jenkinsfile`.
- The shared pipeline explicitly checks out the configured game repository and
  its recursive submodules.
- Telegram targets can be read from a Jenkins Secret text credential for
  automatic webhook builds.

## [0.2.0] - 2026-07-26

### Added

- Reusable `pearzUnityAndroidPipeline` Jenkins Shared Library entry point.
- Two-line `Jenkinsfile.example` for new Unity projects.
- Shared Telegram notification resource.
- Reusable APK and Android App Bundle pipeline handling.

## [0.1.0] - 2026-07-26

### Added

- Android build entry point `Pearz.CI.BuildEntry.BuildAndroid`.
- Environment-based build configuration.
- APK and AAB output support.
- Android signing configuration.
- Architecture, IL2CPP, stripping, and minification overrides.
- Batch-mode exit codes suitable for Jenkins.
