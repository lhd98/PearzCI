# Changelog

All notable changes to this package are documented in this file.

## [0.6.57] - 2026-08-21

### Fixed

- Android AAB output now uses a stable local filename just like APK output, so
  each new AAB replaces the previous workspace artifact instead of accumulating
  one file per Jenkins build. Jenkins archives and Google Drive uploads still
  use build-numbered names, preserving release history.

## [0.6.56] - 2026-08-21

### Fixed

- Android CI builds no longer write the Jenkins build number into
  `PlayerSettings.bundleVersion` before Unity creates player data. That write
  dirtied `ProjectSettings.asset` on every run and forced a costly Gradle
  rebuild. The generated Android launcher project now receives the version
  name immediately before Gradle runs, preserving Unity's incremental
  player-data cache while APK/AAB version names still use
  `<base version>-<build number>`.

## [0.6.55] - 2026-08-20

### Fixed

- Concurrent Jenkins jobs building on the same agent no longer break each
  other's Gradle builds. They shared `~/.gradle`, so when one job's Unity
  teardown ran `gradle --stop` it stopped the busy Gradle daemon of another
  job mid-R8, failing that build with `Gradle build daemon has been stopped:
  stop command received`. `GRADLE_USER_HOME` now points at the per-job
  `$WORKSPACE/.gradle`, isolating each job's daemons and caches so a stop from
  one job cannot reach another; a job also reuses its own warm daemon across
  builds instead of starting a cold one whenever a shared daemon is busy.

## [0.6.54] - 2026-08-20

### Fixed

- Telegram build notifications now list APK, AAB, and IPA download links before
  the Build Info link.

## [0.6.53] - 2026-08-19

### Removed

- The optional Android Gradle profiling stage. It reran the Unity-generated
  Gradle project with `--rerun-tasks`, unnecessarily adding several minutes to
  profiled CI builds.
## [0.6.52] - 2026-08-19

### Added

- Optional `PROFILE_GRADLE` Android/macOS diagnostic parameter. When enabled,
  PearzCI reruns the Unity-generated Gradle project with Gradle profiling and
  archives the HTML report plus `gradle-profile.log`, without affecting the
  build artifact or the AAB version-code counter.

## [0.6.51] - 2026-08-18

### Fixed

- Connected-iPhone auto-detection now supports CoreDevice's current
  `properties` JSON schema as well as its legacy fields. It also accepts
  exactly one physical device reported as `connected` when its wired/pairing
  details are unavailable, so a newly trusted iPhone is not treated as absent.

## [0.6.50] - 2026-08-17

### Fixed

- Android Jenkins builds now use the Unity Project Settings version as their
  base version when `APP_VERSION` is empty, and append the Jenkins build number
  in both cases. For example, Project Settings version `1.0.0` on build `67`
  is exposed through `Application.version` as `1.0.0-67`.
## [0.6.49] - 2026-08-16

### Added

- Auto-derivation of the connected-device provisioning profile. When no device
  profile is configured, PearzCI derives the Xcode-managed name `iOS Team
  Provisioning Profile: <bundle id>` from the built app's bundle identifier, so
  a device-only job needs no iOS signing lines in its pipeline script. An
  explicit `iosDeviceProvisioningProfileSpecifier`,
  `iosProvisioningProfileSpecifier`, or `IOS_PROVISIONING_PROFILE_SPECIFIER`
  still overrides it. The profile file must already be installed on the Mac
  (a one-time Xcode signing step per new game — see the README).

### Changed

- The connected-device build no longer requires a provisioning-profile
  specifier up front. When the installed profile is not found, the error now
  explains the one-time Xcode provisioning step for a new game.

## [0.6.48] - 2026-08-16

### Changed

- Renamed the shared build pipeline from `pearzUnityAndroidPipeline` to
  `pearzUnityMobilePipeline`, since it builds both Android and iOS (Windows has
  its own `pearzUnityWindowsPipeline`). Jobs that use the recommended
  `pearzUnityPipeline()` dispatcher need no change. **Breaking** only for jobs
  that call `pearzUnityAndroidPipeline(...)` directly in their pipeline script:
  update the name to `pearzUnityMobilePipeline(...)`.

## [0.6.47] - 2026-08-16

### Added

- `iosDeviceProvisioningProfileSpecifier` config key for connected-device
  builds. IPA export needs a distribution profile while a device build needs a
  development one, so the two now have separate keys and can both be pinned in
  the job's pipeline script. The device key falls back to
  `iosProvisioningProfileSpecifier`, then to the
  `IOS_PROVISIONING_PROFILE_SPECIFIER` parameter, when unset.

### Removed

- The stale `pearzUnityIosPipeline` entry point. It predated the shared
  Android/iOS graph's connected-device improvements (auto-detected UDID, no
  required development team) and still forced `IOS_DEVICE_UDID` and
  `IOS_DEVELOPMENT_TEAM`. Build iOS through `pearzUnityPipeline(platform:
  'iOS')` (or `pearzUnityAndroidPipeline(mobilePlatform: 'iOS')`), which covers
  IPA export, TestFlight upload, and connected-device installs in one job.

## [0.6.46] - 2026-08-16

### Removed

- The `RELEASE_BUILD_NUMBER` parameter. It let an AAB reuse a tested APK's
  Jenkins build number so the pair shared one version name/Drive folder. Now
  that APK, AAB, and iOS artifacts each go to their own Drive folder, every
  build simply uses the current `BUILD_NUMBER`. The AAB Google Play version
  code is still managed independently by the per-job counter.

## [0.6.45] - 2026-08-16

### Removed

- The `TARGET_ARCHITECTURES` parameter. Android builds now always target both
  `ARMv7` and `ARM64` instead of offering an `ARM64`-only choice, so there is
  no architecture parameter left to configure.
- The `UNITY_DEVELOPMENT_BUILD` and `SCRIPT_DEBUGGING` parameters across the
  Android, iOS, and Windows pipelines. PearzCI produces release/distribution
  artifacts (tester APK, Play AAB, App Store/TestFlight IPA, device dev-install)
  that never used Unity development builds, so both builds always run with
  `BuildOptions.None`.

## [0.6.44] - 2026-08-15

### Changed

- Simplified connected-iPhone (device) builds so a test build needs only
  `IOS_BUILD_TO_DEVICE=true`. `IOS_DEVICE_UDID` may be left empty: the pipeline
  auto-detects the single wired, paired device via `xcrun devicectl list
  devices`, using its `hardwareProperties.udid`, and fails with the device list
  when zero or several are connected so you can set `IOS_DEVICE_UDID`
  explicitly. Device builds now always use the `Debug` configuration.
  `IOS_PROVISIONING_PROFILE_SPECIFIER` is best set once in the job's pipeline
  script as `iosProvisioningProfileSpecifier`; the parameter still works as a
  per-build override.

### Removed

- The `IOS_DEVELOPMENT_TEAM` requirement for connected-device builds. It was
  never used by the device build's signing (which signs with `Apple Development`
  plus the installed provisioning profile); it is still read and used for IPA
  export.

## [0.6.43] - 2026-08-15

### Changed

- Consolidated the Jenkins Stage View from 18 stages to 10 without changing any
  build behaviour. Android and iOS still share one pipeline and one Stage View;
  only intermediate stages were folded into the adjacent stage that already ran
  under the same condition: `Show Parameters` into `Validate Unity`; `Remove
  duplicate AppLovin SPM dependency` into `Build Unity iOS`; `Read Build
  Metadata` and `Archive Artifact` into a combined `Verify & Archive Artifact`;
  and `Validate rclone`, `Verify Google Drive Upload`, `Create Public Link` and
  `Archive Notification Artifacts` into `Upload to Google Drive`. Trade-off: a
  failure in any Google Drive sub-step now shows on the single `Upload to Google
  Drive` column, so read the stage log to see which step failed. README documents
  the resulting stage list.

## [0.6.42] - 2026-08-15

### Changed

- The device build passes `-destination-timeout` to `xcodebuild`, defaulting to
  300 seconds instead of Xcode's 30. A phone still running "Preparing device
  for development" — common right after an iOS update — became available later
  than the default allowed, so the build failed with `Timed out waiting for all
  destinations` even though the device was fine a minute later. Override with
  `iosDestinationTimeoutSeconds`. This does not rescue a genuinely broken
  pairing; that still has to be fixed on the Mac.

## [0.6.41] - 2026-08-15

### Fixed

- iOS notifications lost the `Version` line whenever `APP_VERSION` and
  `IOS_BUILD_NUMBER` were both empty, because the message was built from those
  parameters alone. It now reads `CFBundleShortVersionString` and
  `CFBundleVersion` from the `Info.plist` of the exported Xcode project, so it
  reports the version that was actually built; the parameters remain the
  fallback for a build that fails before the export.

### Changed

- `IOS_BUILD_NUMBER` defaults to the Jenkins `BUILD_NUMBER` when the parameter
  is empty. Unity previously kept the project's own `CFBundleVersion`, so every
  iOS build carried the same one and App Store Connect would reject the second
  and later TestFlight uploads as duplicates. `APP_VERSION` is left alone on
  purpose: it becomes `CFBundleShortVersionString`, which Apple requires to be
  period-separated numbers, so the `<version>-<build>` form used for Android
  and for Drive folder names cannot be reused there.

## [0.6.40] - 2026-08-15

### Changed

- Google Drive is now split by artifact type and then by version:
  `<driveRoot>/<jobName>/{apk,aab,ios}/<version>/`. APK and AAB no longer share
  a version folder, and iOS gets version folders instead of one flat job folder
  where every build overwrote the one before it. Each folder holds the artifact
  and its `<PRODUCT_NAME>_BUILD_INFO.txt`.
- The iOS build info file drops the build-number suffix added in 0.6.39; its
  version folder now keeps builds apart.

### Added

- `UPLOAD_TO_TESTFLIGHT` submits the exported IPA to App Store Connect with
  `xcrun altool --upload-app`, after the Drive upload so a failed submission
  still leaves a downloadable build. It authenticates with an App Store Connect
  API key from a Jenkins **Secret file** credential
  (`appStoreConnectApiKeyCredentialsId`, default `appstore-connect-api-key`)
  plus `APP_STORE_CONNECT_KEY_ID` and `APP_STORE_CONNECT_ISSUER_ID`. The key is
  copied into `$WORKSPACE/private_keys` only for the duration of the upload,
  because `altool` reads it only from a file named `AuthKey_<KeyID>.p8`.
- Telegram shows a `TestFlight` line for iOS builds that ran the upload, and a
  failed upload is reported as such rather than as a missing IPA.

## [0.6.39] - 2026-08-15

### Added

- iOS builds now collect `<PRODUCT_NAME>_BUILD_INFO.txt` as well. The file is
  written by the FGSDK integration in the Unity project, and on iOS it lands
  somewhere under `Builds/iOS` rather than beside the artifact, so the pipeline
  searches that tree — the exact name first, then any `*_BUILD_INFO.txt` — and
  archives, uploads, and links whatever it finds. Its Drive name carries the
  artifact build number because the iOS Drive folder is not split per version.
  A missing file does not fail the build; the `Build Info` line falls back to
  the Drive folder link it used before.

## [0.6.38] - 2026-08-15

### Changed

- Android APK builds now use a fixed Android version code of `1` instead of the
  Jenkins `BUILD_NUMBER`. Testers identify a build by its version name
  (`<BUILD_NUMBER>` or `<APP_VERSION>-<BUILD_NUMBER>`), so the code does not need
  to increase; keeping it constant also lets any APK be reinstalled over another
  without Android blocking it as a downgrade. AAB builds are unchanged and keep
  their separate auto-incrementing per-job counter for Google Play.

## [0.6.37] - 2026-08-15

### Changed

- The Telegram `Build Info` link of an Android build now opens
  `<PRODUCT_NAME>_BUILD_INFO.txt` directly instead of the Google Drive folder
  that holds the APK/AAB and that file. iOS produces no such file, so its
  `Build Info` line still links the Drive build folder.

### Removed

- The `Scripting Define Symbols` block is gone from the Telegram message on
  both Android and iOS, along with the metadata plumbing that fed it. The
  `SCRIPTING_DEFINE_SYMBOLS` parameter still applies to the build itself.

## [0.6.36] - 2026-08-14

### Fixed

- The AppLovin Swift Package cleanup no longer aborts the iOS build with
  `undefined method 'packae_product_dependencies'`. The Ruby program was
  embedded as base64 in both pipelines and the two copies had drifted by one
  character; the shared graph held the broken copy, which 0.6.30 started using
  for connected-device builds.
- The failure message on an iOS run said `Unity Android build failed.`

### Changed

- The AppLovin cleanup program now lives in
  `resources/com/pearz/ci/remove-applovin-spm.rb` and is loaded with
  `libraryResource`, so there is one readable copy instead of two base64 blobs
  that cannot be diffed by eye.

## [0.6.35] - 2026-08-14

### Fixed

- `pearzUnityIosPipeline()` derives its webhook branch filter from the job's
  configured `GIT_BRANCH` default instead of the current run's value. Running
  the job once with a different branch no longer rewrites the branch the
  webhook listens on. The shared graph already worked this way.

## [0.6.34] - 2026-08-14

### Removed

- `BUILD_CONFIGURATION` (and its legacy `BUILD_TYPE` fallback). It never
  affected the built player: development builds are controlled by
  `UNITY_DEVELOPMENT_BUILD` and `SCRIPT_DEBUGGING`, while `BUILD_CONFIGURATION`
  only labelled the Android `build-metadata.json` and a few log lines, and iOS
  and Windows ignored it entirely. The `buildConfiguration` field is gone from
  `build-metadata.json`; nothing read it back.

## [0.6.33] - 2026-08-14

### Changed

- Telegram notification titles carry the build result, for example
  `ANDROID BUILD SUCCESS` or `IOS DEVICE BUILD FAILED`. Results other than
  success and failure keep their own name (`UNSTABLE`, `ABORTED`) instead of
  being reported as a failure.

## [0.6.32] - 2026-08-14

### Changed

- Telegram notifications drop the build-settings block: `Configuration`,
  `Xcode`, `Scripting Backend`, `Stripping Level`, `Orientation`, and `Unity`.
  These rarely change between builds and pushed the artifact links further down
  the message.

## [0.6.31] - 2026-08-14

### Added

- iOS IPA builds now send a Telegram notification; previously only Android and
  iOS connected-device builds were reported.

### Changed

- iOS Telegram notifications are rendered from the same message template as
  Android, so both platforms report version, product, branch, configuration,
  Unity version, artifact links, scripting define symbols, and the commit list
  in one layout. Rows without data for a platform are omitted, and the Android
  message is unchanged.
- iOS connected-device notifications no longer include Jenkins URLs or build-log
  links, matching the notification content decided in 0.6.22.

## [0.6.30] - 2026-08-14

### Changed

- The shared mobile stage graph now also runs iOS connected-device builds, so
  Android, iOS IPA, and iOS device runs of the same job declare one identical
  Jenkins Stage View layout; stages that do not apply are shown as skipped.
- `pearzUnityPipeline()` no longer routes `IOS_BUILD_TO_DEVICE=true` to
  `pearzUnityIosPipeline()`; that entry point remains available for a separate
  iOS-only Jenkins job.
- The shared pipeline picks its agent by platform: iOS builds require the
  `macAgentLabel` node (default `macos`), Android keeps the previous
  any-available-agent behaviour.

## [0.6.29] - 2026-08-14

### Fixed

- Use the CocoaPods-generated iOS workspace for device builds, so pod headers such as AppsFlyer are available to Xcode.

## [0.6.28] - 2026-08-14

### Fixed

- Pass the Xcode project directory, rather than its `project.pbxproj` file, to the AppLovin Swift Package cleanup.

## [0.6.27] - 2026-08-14

### Fixed

- Detect AppLovin Swift Package Manager references case-insensitively before removing them from iOS exports, including mediation packages generated by Unity.

## [0.6.26] - 2026-08-14

### Fixed

- Run the duplicate AppLovin Swift Package cleanup in the shared iOS release
  pipeline, before Xcode starts archiving the exported project.

## [0.6.25] - 2026-08-14

### Fixed

- The iOS AppLovin cleanup now removes the Swift package through Xcode project's
  dependency model instead of text matching, so it removes the duplicate SDK
  reference reliably.

## [0.6.24] - 2026-08-14

### Fixed

- Encoded the iOS AppLovin cleanup program before execution so Jenkins Groovy
  compiles the shared library without parsing Perl regular expressions.

## [0.6.23] - 2026-08-14

### Fixed

- Fixed the iOS AppLovin cleanup script so Jenkins can compile the shared
  pipeline before starting a build.

## [0.6.22] - 2026-08-14

### Fixed

- `pearzUnityPipeline()` routes iOS connected-device builds to
  `pearzUnityIosPipeline()` instead of the shared Android/iOS release graph.
- iOS exports remove a duplicate AppLovin Swift Package Manager reference when
  AppLovin is already installed through CocoaPods, preventing Xcode's
  `Multiple commands produce AppLovinSDK.framework` build failure.

### Changed

- Telegram build notifications now link Build Info to the Google Drive artifact
  folder and omit Jenkins URLs, timings, Drive-root links, and Jenkins logs.

## [0.6.21] - 2026-08-14

### Added

- AAB builds can use optional `RELEASE_BUILD_NUMBER` to share the tested APK's
  version-named Drive folder and artifact filename without reusing the AAB
  Google Play version code.

## [0.6.20] - 2026-08-14

### Changed

- Android builds now verify, archive, and upload the existing
  `<PRODUCT_NAME>_BUILD_INFO.txt` file next to the APK/AAB. Google Drive puts
  each Android build in a version-named folder containing both files.

## [0.6.19] - 2026-08-14

### Changed

- Android APK output is retained in `Builds/Android` on the Jenkins agent.
  Each new APK build replaces the prior local APK, while Google Drive uploads
  remain versioned by Jenkins build number.

## [0.6.18] - 2026-08-14

### Changed

- `pearzUnityPipeline()` now uses one fixed Declarative stage graph for Android
  and iOS. Changing `BUILD_PLATFORM` skips irrelevant stages instead of
  replacing the Jenkins Stage View layout.

### Compatibility

- iOS device builds (`IOS_BUILD_TO_DEVICE=true`) remain available through the
  dedicated `pearzUnityIosPipeline()` entry point while the shared graph covers
  Android builds and signed iOS IPA exports.

## [0.6.17] - 2026-08-14

### Fixed

- Corrected the initial `v0.6.17` Declarative Pipeline syntax before release.

## [0.6.16] - 2026-08-14

### Changed

- The iOS Jenkins stage view now follows the Android release flow: parameters,
  Unity validation, artifact verification and archiving, rclone validation,
  Drive upload verification, public-link creation, and upload-log archiving
  are displayed as separate stages.

## [0.6.15] - 2026-08-14

### Fixed

- Added the missing Unity `.meta` file for `readUnityEditorVersion.groovy` so
  the Jenkins Shared Library asset is imported from the UPM package.

## [0.6.14] - 2026-08-14

### Changed

- Jenkins project checkout now uses the shared `github-ssh` credential instead
  of a per-job `GIT_CREDENTIALS_ID` parameter.
- Jenkins reads the Unity editor version from
  `ProjectSettings/ProjectVersion.txt` instead of a `UNITY_VERSION` parameter.
- Android and iOS builds now always use the bundle identifier from Unity
  Project Settings; the Jenkins `BUNDLE_IDENTIFIER` override is no longer used.

## [0.6.13] - 2026-08-13

### Added

- Windows 64-bit standalone player builds through `BUILD_PLATFORM=Windows`,
  including Jenkins artifact archiving and Telegram notifications.
- Android App Bundle builds now use a persistent, per-job version-code counter
  beginning at `1`, independent of APK test-build numbers.

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
