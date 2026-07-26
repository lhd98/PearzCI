# Changelog

All notable changes to this package are documented in this file.

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
