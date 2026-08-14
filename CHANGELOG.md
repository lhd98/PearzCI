# Changelog

All notable changes to this package are documented in this file.

## [0.6.21] - 2026-08-14

### Fixed

- iOS exports now remove a duplicate AppLovin Swift Package Manager reference
  when AppLovin is already installed through CocoaPods, preventing Xcode's
  `Multiple commands produce AppLovinSDK.framework` build failure.

### Changed

- Android build notifications omit Jenkins URLs, timing details, Drive root
  links, and log links. The new Build Info link opens the Drive artifact folder,
  which now includes `buildinfo.txt` with the build metadata.

## [0.6.12] - 2026-08-13

### Added

- Windows 64-bit standalone player builds through `BUILD_PLATFORM=Windows`,
  including Jenkins artifact archiving and Telegram notifications.

### Changed

- Telegram build titles now identify the selected platform, such as Android,
  iOS device, or Windows.

## [0.6.11] - 2026-08-13

### Added

- iOS device-test builds can compile unsigned, sign directly with an installed
  development profile, and install to the selected connected iPhone.

### Fixed

- Personal Team device builds now remove the Unity IAP capability only for the
  device-test path, while keeping normal IPA exports unchanged.
- Device installs now locate the generated app bundle by its actual product
  name and preserve the UnityFramework bundle identifier.

## [0.6.10] - 2026-08-13

### Added

- iOS builds can now target a connected device through `IOS_BUILD_TO_DEVICE`
  and `IOS_DEVICE_UDID`, building and installing the app without IPA export.

### Fixed

- Added missing Unity `.meta` files for the Jenkins and tooling folders so
  Git-installed packages no longer emit import warnings.

## [0.6.9] - 2026-08-13

### Added

- Added the optional `CLEAN_WORKSPACE` build parameter. When enabled, it
  removes the job workspace before checkout so the build starts from a fresh
  project state.
- Added the optional `SEND_NOTIFICATIONS` build parameter. It defaults to
  enabled and can suppress all post-build notification platforms for one run.

## [0.6.8] - 2026-08-12

### Changed

- Android signing now defaults to
  `Config/<BundleIdentifier>.keystore` when `KEYSTORE_PATH` is not provided,
  while retaining explicit path overrides for existing Jenkins jobs.

## [0.6.7] - 2026-08-12

### Fixed

- Fixed the PearzCI release Telegram workflow YAML so release tags can run it.

## [0.6.6] - 2026-08-12

### Added

- Added a GitHub Actions workflow that notifies the developer Telegram group
  when an exact PearzCI release tag is pushed.

## [0.6.5] - 2026-08-11

### Added

- Added `pearzUnityPipeline`, which selects the Android or iOS build pipeline
  from the Jenkins `BUILD_PLATFORM` parameter.

## [0.6.4] - 2026-08-11

### Changed

- Telegram notifications now use HTML formatting with safely escaped Jenkins
  values and a more readable default template.

## [0.6.3] - 2026-08-11

### Fixed

- Telegram commit summaries now preserve multi-line commit bodies on one line,
  joining non-empty lines with ` • `.

## [0.6.2] - 2026-08-11

### Fixed

- Android builds now expose the Jenkins build number in Unity as
  `<APP_VERSION>-<BUILD_NUMBER>` (for example `1.0.0-67`).

## [0.6.1] - 2026-08-11

### Added

- Android builds now embed the Jenkins `BUILD_NUMBER` automatically: it is the
  Android version code and is included in the Unity-visible app version (for
  example `1.0.0 (CI 67)`).

## [0.6.0] - 2026-08-10

### Added

- Added `BuildIOS`, a Unity batch-mode entry point that exports an iOS Xcode project.
- Added `pearzUnityIosPipeline`, a macOS-only Jenkins pipeline that archives, signs, exports, archives, and uploads IPA artifacts.
- Added iOS Jenkins, Xcode signing, and macOS agent setup documentation.

### Changed

- Added `ios` to the package keywords.

## [0.5.5] - 2026-08-10

### Fixed

- Telegram commit collection now uses the current Jenkins checkout changelog when no previous-build baseline is available, including the first build after an upgrade.

## [0.5.4] - 2026-08-10

### Fixed

- Telegram commit collection now uses whitelisted Jenkins build variables instead of raw build APIs, and persists the full HEAD commit for the next build baseline.

## [0.5.3] - 2026-08-10

### Added

- Added the telegramMaxCommits pipeline option, defaulting to 10.
- Jenkins logs now report the Telegram commit baseline source and commit counts.

### Fixed

- Telegram commit changes now fall back to the previous successful Jenkins
  build's Git metadata when the Git plugin does not expose its previous-commit
  environment variable. The notification lists each commit on its own line.

## [0.5.2] - 2026-08-04

### Fixed

- The webhook branch filter is built from the **default** value of the
  `GIT_BRANCH` job parameter instead of the value used by the current run.
  Running **Build with Parameters** with a different branch silently
  repointed the job's webhook filter at that branch, so the job stopped
  reacting to its configured branch until the next webhook build changed it
  back. Checkout still uses the current run's value, so building another
  branch manually works exactly as before.

### Added

- A warning in the build log when the ref reported by the webhook does not
  match the branch being checked out. This does not stop the build; it makes a
  mispointed trigger filter visible.

## [0.5.1] - 2026-08-04

### Fixed

- The Telegram commit list is measured from the previous **successful** build
  instead of the previous build of any result. Jenkins records a build's
  commit at checkout, so a build cancelled by `abortPrevious` or one that
  failed already advanced `GIT_PREVIOUS_COMMIT`. The build that then ran to
  completion reported "No new commits since the previous build" even though it
  was the build that produced the artifact.

## [0.5.0] - 2026-08-04

### Added

- Failed, aborted, and unstable builds now send a Telegram notification. The
  message is built in the pipeline's `post` block instead of a final stage,
  which was skipped precisely when a build broke.
- Unity writes `build-metadata.json` for failed builds as well, recording the
  error message in a new `errorMessage` field.
- `{{ERROR_SECTION}}` template placeholder showing the Unity error message.

### Changed

- `{{RESULT}}` now reports the Jenkins build result instead of Unity's. A
  build could previously produce a valid APK, fail while uploading to Google
  Drive, and still be reported as a success.
- Build metadata is read on demand, so a build that fails before the
  `Read Build Metadata` stage still reports Unity's own values.
- `build-metadata.json` uses `schemaVersion` 2.

### Fixed

- A build failing before the `Prepare Build Variables` stage no longer reports
  a nonsensical total duration derived from an unset start time.
- A Telegram delivery failure no longer fails the build. The error is printed
  to the Jenkins console and an otherwise successful build is marked
  `UNSTABLE`.

## [0.4.10] - 2026-08-04

### Added

- `tools/bump-version.ps1` sets `package.json` and the new
  `resources/com/pearz/ci/version.txt` together, so the version reported in
  Telegram can no longer drift from the released package version.
- Configurable `buildsToKeep` and `artifactBuildsToKeep` pipeline options.

### Changed

- The pipeline reads its version from a shared-library resource instead of a
  hard-coded string.
- Jenkins now discards builds beyond the last 30, and artifacts beyond the
  last 10 builds. Previously every archived APK or AAB was kept forever.
- The Telegram commit list is capped at the 20 most recent commits followed by
  a count of the remaining ones.
- Build artifacts are no longer archived more than once. The post-build step
  archives only `build-metadata.json`, `unity-build.log`, and `upload.log`,
  which still captures diagnostics when a build fails without re-transferring
  the APK or AAB from the agent.

### Fixed

- Telegram notifications are truncated to the 4096-character API limit. A long
  gap between builds could previously produce an oversized message that
  Telegram rejected, losing the whole notification.

## [0.4.9] - 2026-08-03

### Fixed

- Corrected Groovy URL regex escaping so the shared pipeline compiles on Jenkins
  when loading the `upm` library.

## [0.4.8] - 2026-08-03

### Changed

- Generic Webhook Trigger authentication now uses the Jenkins Secret text
  credential `pearz-github-webhook`, allowing GitHub webhooks to reach secured
  Jenkins controllers without anonymous access.
- Jenkins pipeline and bootstrap configuration stay synchronized with the
  credential-backed webhook token.

## [0.4.7] - 2026-08-03

### Added

- One-time Jenkins Script Console bootstrap configures Generic Webhook
  Triggers for all matching Pipeline jobs and filters shared GitHub webhooks
  by repository and branch.
- Jenkins jobs missing repository or branch defaults are reported for separate
  handling instead of being modified.

### Changed

- The Jenkins setup documentation now uses the shared Generic Webhook Trigger
  endpoint and preserves unrelated job triggers during bootstrap.

## [0.4.6] - 2026-08-03

### Added

- GitHub webhook filtering by repository and configured job branch.
- Manual parameterized builds cancel an in-progress build and restart with the
  latest parameters.

### Changed

- GitHub push triggers now use Generic Webhook Trigger instead of unconditional
  `githubPush()` triggering.

## [0.4.5] - 2026-08-01

### Added

- Telegram build notifications now display the PearzCI shared-library version.

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
