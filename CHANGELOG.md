# Changelog

All notable changes to this package are documented in this file.

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
