def call(Map config = [:]) {
    def repositoryUrl = config.get(
        'repositoryUrl', params.PROJECT_REPOSITORY_URL ?: ''
    ).toString().trim()
    def repositoryCredentialsId = config.get(
        'repositoryCredentialsId', params.GIT_CREDENTIALS_ID ?: 'github-ssh'
    ).toString().trim()
    def macAgentLabel = config.get('macAgentLabel', 'macos').toString().trim()
    def unityHubRoot = config.get(
        'macUnityHubRoot', '/Applications/Unity/Hub/Editor'
    ).toString().trim()
    def rcloneExe = config.get('macRcloneExe', 'rclone').toString().trim()
    def driveRemote = config.get('driveRemote', 'gdrive').toString().trim()
    def driveRoot = config.get('driveRoot', 'JenkinsBuild').toString().trim()
    def defaultGitBranch = config.get(
        'gitBranch', params.GIT_BRANCH ?: 'master'
    ).toString().trim()
    def buildsToKeep = config.get('buildsToKeep', 30).toString()
    def artifactBuildsToKeep = config.get('artifactBuildsToKeep', 10).toString()
    def buildToDevice = config.get(
        'iosBuildToDevice', params.IOS_BUILD_TO_DEVICE ?: false
    ).toString().trim().toBoolean()
    def webhookBranch = defaultGitBranch.replaceFirst(/^refs\/heads\//, '')
    def webhookRepository = extractGitHubRepository(repositoryUrl)
    def webhookFilterExpression = webhookRepository
        ? '^' + regexEscape(webhookRepository) +
            ' refs/heads/' + regexEscape(webhookBranch) + '$'
        : '^refs/heads/' + regexEscape(webhookBranch) + '$'

    if (!macAgentLabel) {
        throw new IllegalArgumentException('macAgentLabel must not be empty.')
    }

    pipeline {
        agent { label "${macAgentLabel}" }

        options {
            timestamps()
            disableConcurrentBuilds(abortPrevious: true)
            quietPeriod(5)
            skipDefaultCheckout(true)
            buildDiscarder(logRotator(
                numToKeepStr: buildsToKeep,
                artifactNumToKeepStr: artifactBuildsToKeep
            ))
        }

        triggers {
            GenericTrigger(
                genericVariables: [
                    [key: 'PEARZ_WEBHOOK_REPOSITORY', value: '$.repository.full_name'],
                    [key: 'PEARZ_WEBHOOK_REF', value: '$.ref']
                ],
                causeString: 'Triggered by GitHub push: ' +
                    '$PEARZ_WEBHOOK_REPOSITORY $PEARZ_WEBHOOK_REF',
                tokenCredentialId: 'pearz-github-webhook',
                printContributedVariables: false,
                printPostContent: false,
                regexpFilterText:
                    '$PEARZ_WEBHOOK_REPOSITORY $PEARZ_WEBHOOK_REF',
                regexpFilterExpression: webhookFilterExpression
            )
        }

        environment {
            UNITY_HUB_ROOT = "${unityHubRoot}"
            RCLONE_EXE = "${rcloneExe}"
            DRIVE_REMOTE = "${driveRemote}"
            DRIVE_ROOT = "${driveRoot}"
            IOS_BUILD_TO_DEVICE = "${buildToDevice}"
        }

        stages {
            stage('Checkout') {
                steps {
                    script {
                        if (!repositoryUrl) {
                            error('repositoryUrl is required. Configure it in the Jenkins job.')
                        }

                        def branchSpec = params.GIT_BRANCH?.trim()
                            ? params.GIT_BRANCH.trim() : defaultGitBranch
                        checkout([
                            $class: 'GitSCM',
                            branches: [[name: branchSpec]],
                            doGenerateSubmoduleConfigurations: false,
                            extensions: [[
                                $class: 'SubmoduleOption',
                                disableSubmodules: false,
                                parentCredentials: true,
                                recursiveSubmodules: true,
                                reference: '',
                                shallow: false,
                                trackingSubmodules: false
                            ]],
                            submoduleCfg: [],
                            userRemoteConfigs: [[
                                credentialsId: repositoryCredentialsId,
                                url: repositoryUrl
                            ]]
                        ])
                        sh '''
                            set -eu
                            git submodule sync --recursive
                            git submodule update --init --recursive
                        '''
                    }
                }
            }

            stage('Prepare Build Variables') {
                steps {
                    script {
                        def outputName = params.PRODUCT_NAME?.trim()
                            ? params.PRODUCT_NAME.trim() : env.JOB_BASE_NAME
                        outputName = outputName.replaceAll('[<>:"\\\\/|?*]', '_')

                        env.OUTPUT_FILE_NAME = "${outputName}-${env.BUILD_NUMBER}.ipa"
                        env.IOS_PROJECT_PATH =
                            "${env.WORKSPACE}/Builds/iOS/Unity-iPhone"
                        env.OUTPUT_PATH =
                            "${env.WORKSPACE}/Builds/iOS/${env.OUTPUT_FILE_NAME}"
                        env.ARCHIVE_PATH =
                            "${env.WORKSPACE}/Builds/iOS/Unity-iPhone.xcarchive"
                        env.EXPORT_PATH = "${env.WORKSPACE}/Builds/iOS/export"
                        env.DERIVED_DATA_PATH =
                            "${env.WORKSPACE}/Builds/iOS/DerivedData"
                        env.BUILD_LOG_PATH =
                            "${env.WORKSPACE}/Builds/iOS/unity-build.log"
                        env.XCODEBUILD_LOG_PATH =
                            "${env.WORKSPACE}/Builds/iOS/xcodebuild.log"
                        env.UPLOAD_LOG_PATH =
                            "${env.WORKSPACE}/Builds/iOS/upload.log"
                        env.DRIVE_DIRECTORY =
                            "${env.DRIVE_REMOTE}:${env.DRIVE_ROOT}/${env.JOB_BASE_NAME}"
                        env.DRIVE_FILE_PATH =
                            "${env.DRIVE_DIRECTORY}/${env.OUTPUT_FILE_NAME}"
                    }
                }
            }

            stage('Validate macOS Toolchain') {
                steps {
                    script {
                        env.UNITY_EXE = "${env.UNITY_HUB_ROOT}/${params.UNITY_VERSION}" +
                            '/Unity.app/Contents/MacOS/Unity'
                        if (!fileExists(env.UNITY_EXE)) {
                            error("Unity not found: ${env.UNITY_EXE}")
                        }
                        sh '''
                            set -eu
                            uname -s | grep -Fx Darwin
                            "$UNITY_EXE" -version
                            xcodebuild -version
                            if [ "$IOS_BUILD_TO_DEVICE" != 'true' ]; then
                                command -v "$RCLONE_EXE" >/dev/null 2>&1 ||
                                    [ -x "$RCLONE_EXE" ]
                                "$RCLONE_EXE" listremotes | grep -Fqx "$DRIVE_REMOTE:"
                            fi
                        '''
                    }
                }
            }

            stage('Export Unity iOS Project') {
                options { timeout(time: 60, unit: 'MINUTES') }
                steps {
                    script {
                        def startedAt = System.currentTimeMillis()
                        try {
                            withEnv([
                                "OUTPUT_PATH=${env.IOS_PROJECT_PATH}",
                                "PRODUCT_NAME=${params.PRODUCT_NAME ?: ''}",
                                "BUNDLE_IDENTIFIER=${params.BUNDLE_IDENTIFIER ?: ''}",
                                "SCRIPTING_DEFINE_SYMBOLS=${params.SCRIPTING_DEFINE_SYMBOLS ?: ''}",
                                "APP_VERSION=${params.APP_VERSION ?: ''}",
                                "IOS_BUILD_NUMBER=${params.IOS_BUILD_NUMBER ?: ''}",
                                "IOS_BUILD_TO_DEVICE=${buildToDevice}",
                                "IL2CPP_CODE_GENERATION=${params.IL2CPP_CODE_GENERATION ?: ''}",
                                "MANAGED_STRIPPING_LEVEL=${params.MANAGED_STRIPPING_LEVEL ?: ''}",
                                "STRIP_ENGINE_CODE=${params.STRIP_ENGINE_CODE}",
                                "UNITY_DEVELOPMENT_BUILD=${params.UNITY_DEVELOPMENT_BUILD}",
                                "SCRIPT_DEBUGGING=${params.SCRIPT_DEBUGGING}"
                            ]) {
                                sh '''
                                    set +e
                                    device_build_marker="$WORKSPACE/.pearz-ci-ios-device-build"
                                    if [ "$IOS_BUILD_TO_DEVICE" = 'true' ]; then
                                        : > "$device_build_marker"
                                    else
                                        rm -f "$device_build_marker"
                                    fi
                                    trap 'rm -f "$device_build_marker"' EXIT
                                    "$UNITY_EXE" -batchmode -quit \
                                        -projectPath "$WORKSPACE" \
                                        -buildTarget iOS \
                                        -executeMethod Pearz.CI.BuildEntry.BuildIOS \
                                        -logFile "$BUILD_LOG_PATH"
                                    result=$?
                                    [ ! -f "$BUILD_LOG_PATH" ] || cat "$BUILD_LOG_PATH"
                                    exit "$result"
                                '''
                            }
                        } finally {
                            env.BUILD_TIME_MILLIS = (
                                System.currentTimeMillis() - startedAt
                            ).toString()
                        }
                    }
                }
            }

            stage('Archive and Export IPA') {
                when { expression { !buildToDevice } }
                options { timeout(time: 45, unit: 'MINUTES') }
                steps {
                    script {
                        def exportOptionsPath = config.get(
                            'iosExportOptionsPlistPath',
                            params.IOS_EXPORT_OPTIONS_PLIST_PATH ?: ''
                        ).toString().trim()
                        def developmentTeam = config.get(
                            'iosDevelopmentTeam', params.IOS_DEVELOPMENT_TEAM ?: ''
                        ).toString().trim()
                        def profileSpecifier = config.get(
                            'iosProvisioningProfileSpecifier',
                            params.IOS_PROVISIONING_PROFILE_SPECIFIER ?: ''
                        ).toString().trim()
                        def xcodeConfiguration = config.get(
                            'xcodeConfiguration', params.XCODE_CONFIGURATION ?: 'Release'
                        ).toString().trim()

                        if (!exportOptionsPath) {
                            error('IOS_EXPORT_OPTIONS_PLIST_PATH is required for IPA export.')
                        }
                        if (!fileExists(exportOptionsPath)) {
                            error("ExportOptions.plist not found: ${exportOptionsPath}")
                        }

                        withEnv([
                            "IOS_EXPORT_OPTIONS=${exportOptionsPath}",
                            "IOS_DEVELOPMENT_TEAM=${developmentTeam}",
                            "IOS_PROFILE_SPECIFIER=${profileSpecifier}",
                            "XCODE_CONFIGURATION=${xcodeConfiguration}"
                        ]) {
                            sh '''
                                set -eu
                                [ -d "$IOS_PROJECT_PATH/Unity-iPhone.xcodeproj" ] || {
                                    echo "ERROR: Unity-iPhone.xcodeproj was not exported."
                                    exit 1
                                }
                                rm -rf "$ARCHIVE_PATH" "$EXPORT_PATH"
                                mkdir -p "$EXPORT_PATH"

                                signing_args=""
                                if [ -n "$IOS_DEVELOPMENT_TEAM" ]; then
                                    signing_args="$signing_args DEVELOPMENT_TEAM=$IOS_DEVELOPMENT_TEAM"
                                fi
                                if [ -n "$IOS_PROFILE_SPECIFIER" ]; then
                                    signing_args="$signing_args CODE_SIGN_STYLE=Manual PROVISIONING_PROFILE_SPECIFIER=$IOS_PROFILE_SPECIFIER"
                                fi

                                set +e
                                xcodebuild -project "$IOS_PROJECT_PATH/Unity-iPhone.xcodeproj" \
                                    -scheme Unity-iPhone \
                                    -configuration "$XCODE_CONFIGURATION" \
                                    -archivePath "$ARCHIVE_PATH" archive $signing_args \
                                    > "$XCODEBUILD_LOG_PATH" 2>&1
                                result=$?
                                cat "$XCODEBUILD_LOG_PATH"
                                [ "$result" -eq 0 ] || exit "$result"

                                xcodebuild -exportArchive \
                                    -archivePath "$ARCHIVE_PATH" \
                                    -exportOptionsPlist "$IOS_EXPORT_OPTIONS" \
                                    -exportPath "$EXPORT_PATH" \
                                    >> "$XCODEBUILD_LOG_PATH" 2>&1
                                result=$?
                                cat "$XCODEBUILD_LOG_PATH"
                                [ "$result" -eq 0 ] || exit "$result"

                                ipa=$(find "$EXPORT_PATH" -maxdepth 1 -type f -name '*.ipa' -print -quit)
                                [ -n "$ipa" ] || { echo 'ERROR: IPA was not exported.'; exit 1; }
                                cp "$ipa" "$OUTPUT_PATH"
                            '''
                        }
                    }
                }
            }

            stage('Build and Install on iOS Device') {
                when { expression { buildToDevice } }
                options { timeout(time: 45, unit: 'MINUTES') }
                steps {
                    script {
                        def deviceUdid = config.get(
                            'iosDeviceUdid', params.IOS_DEVICE_UDID ?: ''
                        ).toString().trim()
                        def developmentTeam = config.get(
                            'iosDevelopmentTeam', params.IOS_DEVELOPMENT_TEAM ?: ''
                        ).toString().trim()
                        def xcodeConfiguration = config.get(
                            'xcodeConfiguration', params.XCODE_CONFIGURATION ?: 'Debug'
                        ).toString().trim()
                        def bundleIdentifier = params.BUNDLE_IDENTIFIER?.toString()?.trim() ?: ''
                        def profileSpecifier = config.get(
                            'iosProvisioningProfileSpecifier',
                            params.IOS_PROVISIONING_PROFILE_SPECIFIER ?: ''
                        ).toString().trim()

                        if (!deviceUdid) {
                            error('IOS_DEVICE_UDID is required when IOS_BUILD_TO_DEVICE=true.')
                        }
                        if (!developmentTeam) {
                            error('IOS_DEVELOPMENT_TEAM is required when IOS_BUILD_TO_DEVICE=true.')
                        }
                        if (xcodeConfiguration != 'Debug' && xcodeConfiguration != 'Release') {
                            error('XCODE_CONFIGURATION must be Release or Debug.')
                        }

                        withEnv([
                            "IOS_DEVICE_UDID=${deviceUdid}",
                            "IOS_DEVELOPMENT_TEAM=${developmentTeam}",
                            "XCODE_CONFIGURATION=${xcodeConfiguration}",
                            "IOS_BUNDLE_IDENTIFIER=${bundleIdentifier}",
                            "IOS_PROFILE_SPECIFIER=${profileSpecifier}"
                        ]) {
                            sh '''
                                set -eu
                                [ -d "$IOS_PROJECT_PATH/Unity-iPhone.xcodeproj" ] || {
                                    echo "ERROR: Unity-iPhone.xcodeproj was not exported."
                                    exit 1
                                }
                                rm -rf "$DERIVED_DATA_PATH"
                                echo "iOS device bundle identifier: ${IOS_BUNDLE_IDENTIFIER:-<from Unity project>}"

                                # Personal Teams cannot provision the In-App Purchase capability.
                                # This is a development-only device build, so remove it from the
                                # generated Xcode project without changing the Unity source project
                                # or the normal IPA-export pipeline.
                                pbxproj_path="$IOS_PROJECT_PATH/Unity-iPhone.xcodeproj/project.pbxproj"
                                if grep -Fq 'com.apple.InAppPurchase' "$pbxproj_path"; then
                                    perl -0pi -e 's/\\s*com\\.apple\\.InAppPurchase\\s*=\\s*\\{\\s*enabled\\s*=\\s*1;\\s*\\};\\s*//g' "$pbxproj_path"
                                    echo 'Removed In-App Purchase capability for Personal Team device signing.'
                                fi
                                find "$IOS_PROJECT_PATH" -name '*.entitlements' -type f -print |
                                    while IFS= read -r entitlements_path; do
                                        /usr/libexec/PlistBuddy \\
                                            -c 'Delete :com.apple.developer.in-app-payments' \\
                                            "$entitlements_path" >/dev/null 2>&1 || true
                                    done

                                run_xcodebuild_unsigned() {
                                    xcodebuild -project "$IOS_PROJECT_PATH/Unity-iPhone.xcodeproj" \\
                                        -scheme Unity-iPhone \\
                                        -configuration "$XCODE_CONFIGURATION" \\
                                        -destination "id=$IOS_DEVICE_UDID" \\
                                        -derivedDataPath "$DERIVED_DATA_PATH" \\
                                        DEVELOPMENT_TEAM="$IOS_DEVELOPMENT_TEAM" \\
                                        "PRODUCT_BUNDLE_IDENTIFIER=$IOS_BUNDLE_IDENTIFIER" \\
                                        CODE_SIGNING_ALLOWED=NO \\
                                        CODE_SIGNING_REQUIRED=NO \\
                                        CODE_SIGN_IDENTITY='' \\
                                        build > "$XCODEBUILD_LOG_PATH" 2>&1
                                }

                                set +e
                                profile_specifier="${IOS_PROFILE_SPECIFIER:-}"
                                [ -n "$profile_specifier" ] || {
                                    echo 'ERROR: IOS_PROVISIONING_PROFILE_SPECIFIER is required for an iOS device build.'
                                    exit 2
                                }

                                # Build unsigned first. Xcode 26 performs provisioning validation
                                # before it builds and rejects Personal Team profiles for Unity IAP,
                                # even after the capability is removed. We then sign the built app
                                # directly with the installed development profile.
                                echo 'Building unsigned iOS app for direct development signing.'
                                run_xcodebuild_unsigned
                                result=$?
                                cat "$XCODEBUILD_LOG_PATH"
                                [ "$result" -eq 0 ] || exit "$result"

                                app_path="$DERIVED_DATA_PATH/Build/Products/$XCODE_CONFIGURATION-iphoneos/Unity-iPhone.app"
                                [ -d "$app_path" ] || {
                                    echo "ERROR: Device app was not built: $app_path"
                                    exit 1
                                }
                                profile_path=''
                                for profiles_dir in \\
                                    "$HOME/Library/MobileDevice/Provisioning Profiles" \\
                                    "$HOME/Library/Developer/Xcode/UserData/Provisioning Profiles"; do
                                    [ -d "$profiles_dir" ] || continue
                                    for candidate_profile in "$profiles_dir"/*.mobileprovision; do
                                        [ -f "$candidate_profile" ] || continue
                                        candidate_name="$(security cms -D -i "$candidate_profile" 2>/dev/null | \\
                                            plutil -extract Name raw - 2>/dev/null || true)"
                                        if [ "$candidate_name" = "$profile_specifier" ]; then
                                            profile_path="$candidate_profile"
                                            break 2
                                        fi
                                    done
                                done
                                [ -n "$profile_path" ] || {
                                    echo "ERROR: Installed provisioning profile was not found: $profile_specifier"
                                    exit 3
                                }

                                profile_plist="$DERIVED_DATA_PATH/provisioning-profile.plist"
                                signing_entitlements="$DERIVED_DATA_PATH/signing-entitlements.plist"
                                security cms -D -i "$profile_path" > "$profile_plist"
                                plutil -extract Entitlements xml1 -o "$signing_entitlements" "$profile_plist"
                                cp "$profile_path" "$app_path/embedded.mobileprovision"

                                echo "Signing iOS device app with installed profile: $profile_specifier"
                                find "$app_path" -depth -type d \\
                                    \\( -name '*.framework' -o -name '*.appex' \\) \\
                                    -exec codesign --force --sign 'Apple Development' --timestamp=none {} \\;
                                find "$app_path" -type f -name '*.dylib' \\
                                    -exec codesign --force --sign 'Apple Development' --timestamp=none {} \\;
                                codesign --force --sign 'Apple Development' \\
                                    --entitlements "$signing_entitlements" \\
                                    --timestamp=none "$app_path"
                                codesign --verify --deep --strict "$app_path"

                                xcrun devicectl device install app \\
                                    --device "$IOS_DEVICE_UDID" "$app_path" \\
                                    >> "$XCODEBUILD_LOG_PATH" 2>&1
                                result=$?
                                cat "$XCODEBUILD_LOG_PATH"
                                exit "$result"
                            '''
                        }
                    }
                }
            }

            stage('Archive and Upload IPA') {
                when { expression { !buildToDevice } }
                steps {
                    script {
                        if (!fileExists(env.OUTPUT_PATH)) {
                            error("IPA not found: ${env.OUTPUT_PATH}")
                        }
                        archiveArtifacts(
                            artifacts: "Builds/iOS/${env.OUTPUT_FILE_NAME},Builds/iOS/unity-build.log,Builds/iOS/xcodebuild.log",
                            allowEmptyArchive: true,
                            fingerprint: true,
                            onlyIfSuccessful: true
                        )
                        sh '''
                            set -eu
                            "$RCLONE_EXE" copyto "$OUTPUT_PATH" "$DRIVE_FILE_PATH" \
                                --retries 3 --low-level-retries 10 \
                                --log-file "$UPLOAD_LOG_PATH" --log-level INFO
                            "$RCLONE_EXE" lsjson "$DRIVE_FILE_PATH" --files-only
                            "$RCLONE_EXE" link "$DRIVE_FILE_PATH" || true
                        '''
                    }
                }
            }
        }

        post {
            success {
                echo(buildToDevice
                    ? 'iOS device build and install completed.'
                    : "iOS IPA completed: ${env.OUTPUT_FILE_NAME}")
            }
            always {
                archiveArtifacts(
                    artifacts: 'Builds/iOS/unity-build.log,Builds/iOS/xcodebuild.log,Builds/iOS/upload.log',
                    allowEmptyArchive: true
                )
                script {
                    if (fileExists('Builds')) {
                        dir('Builds') { deleteDir() }
                    }
                }
            }
        }
    }
}

def extractGitHubRepository(String repositoryUrl) {
    def repository = repositoryUrl?.trim() ?: ''
    repository = repository.replaceFirst(/^ssh:\/\/[^\/]+\//, '')
    repository = repository.replaceFirst(/^https?:\/\/[^\/]+\//, '')
    repository = repository.replaceFirst(/^git@[^:]+:/, '')
    repository = repository.replaceFirst(/\.git$/, '')
    return repository.replaceAll(/^\/+|\/+$/, '')
}

def regexEscape(String value) {
    def specialCharacters = '\\.^$|()[]{}*+?'
    return value.collect { character ->
        specialCharacters.indexOf(character as String) >= 0
            ? "\\${character}"
            : character
    }.join('')
}
