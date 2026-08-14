def call(Map config = [:]) {
    def mobilePlatform = config.get('mobilePlatform', 'Android')
        .toString().trim()
    boolean isIos = mobilePlatform.equalsIgnoreCase('iOS')
    boolean isAndroid = !isIos
    def iosBuildToDevice = config.get(
        'iosBuildToDevice', params.IOS_BUILD_TO_DEVICE ?: false
    ).toString().trim().toBoolean()
    if (isIos && iosBuildToDevice) {
        throw new IllegalArgumentException(
            'IOS_BUILD_TO_DEVICE=true is not supported by the shared mobile ' +
            'stage graph yet. Use pearzUnityIosPipeline() for device builds.'
        )
    }
    def pearzCiVersion = readPearzCiVersion()
    def repositoryUrl = config.get(
        'repositoryUrl',
        params.PROJECT_REPOSITORY_URL ?: ''
    ).toString().trim()
    def repositoryCredentialsId = config.get(
        'repositoryCredentialsId',
        'github-ssh'
    ).toString().trim()
    def telegramCredentialsId = config.get(
        'telegramCredentialsId',
        ''
    ).toString().trim()
    def defaultGitBranch = config.get('gitBranch', 'master')
    def configuredRcloneExe = config.get(
        'rcloneExe',
        ''
    ).toString().trim()
    def configuredUnityHubRoot = config.get(
        'unityHubRoot',
        ''
    ).toString().trim()
    def windowsRcloneExe = config.get(
        'windowsRcloneExe',
        configuredRcloneExe ?: 'D:\\Tools\\rclone\\rclone.exe'
    )
    def macRcloneExe = config.get(
        'macRcloneExe',
        configuredRcloneExe ?: 'rclone'
    )
    def driveRemote = config.get('driveRemote', 'gdrive')
    def driveRoot = config.get('driveRoot', 'JenkinsBuild')
    def buildsToKeep = config.get('buildsToKeep', 30).toString()
    def artifactBuildsToKeep = config.get('artifactBuildsToKeep', 10).toString()
    def telegramMaxCommits = config.get('telegramMaxCommits', 10).toString().toInteger()
    if (telegramMaxCommits < 1) {
        throw new IllegalArgumentException('telegramMaxCommits must be at least 1.')
    }
    def windowsUnityHubRoot = config.get(
        'windowsUnityHubRoot',
        configuredUnityHubRoot ?: 'C:\\Program Files\\Unity\\Hub\\Editor'
    )
    def macUnityHubRoot = config.get(
        'macUnityHubRoot',
        configuredUnityHubRoot ?: '/Applications/Unity/Hub/Editor'
    )
    // Bộ lọc webhook bám giá trị MẶC ĐỊNH của job, không bám giá trị của
    // lần chạy này. Dùng params.GIT_BRANCH ở đây sẽ khiến một lần
    // "Build with Parameters" nhập branch khác âm thầm đổi luôn branch mà
    // webhook lắng nghe, và job bắt đầu phản ứng với sai branch cho tới
    // lần build webhook kế tiếp. Việc checkout vẫn dùng giá trị lần chạy,
    // nên build tay một branch khác vẫn hoạt động như cũ.
    def webhookBranch = normalizeGitBranch(
        readConfiguredBranchDefault() ?:
        (params.GIT_BRANCH?.toString()?.trim() ?: defaultGitBranch)
    )
    def webhookRepository = extractGitHubRepository(repositoryUrl)
    def webhookFilterExpression = webhookRepository
        ? '^' + regexEscape(webhookRepository) +
            ' refs/heads/' + regexEscape(webhookBranch) + '$'
        : '^refs/heads/' + regexEscape(webhookBranch) + '$'

    pipeline {
        agent any

        options {
            timestamps()
            disableConcurrentBuilds(abortPrevious: true)
            quietPeriod(5)
            skipDefaultCheckout(true)
            buildDiscarder(
                logRotator(
                    numToKeepStr: buildsToKeep,
                    artifactNumToKeepStr: artifactBuildsToKeep
                )
            )
        }

        triggers {
            GenericTrigger(
                genericVariables: [
                    [
                        key: 'PEARZ_WEBHOOK_REPOSITORY',
                        value: '$.repository.full_name'
                    ],
                    [
                        key: 'PEARZ_WEBHOOK_REF',
                        value: '$.ref'
                    ]
                ],
                causeString:
                    'Triggered by GitHub push: ' +
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
            PEARZ_CI_VERSION = "${pearzCiVersion}"
            DRIVE_REMOTE = "${driveRemote}"
            DRIVE_ROOT = "${driveRoot}"
            WINDOWS_RCLONE_EXE = "${windowsRcloneExe}"
            MAC_RCLONE_EXE = "${macRcloneExe}"
            WINDOWS_UNITY_HUB_ROOT = "${windowsUnityHubRoot}"
            MAC_UNITY_HUB_ROOT = "${macUnityHubRoot}"
        }

        stages {
            stage('Checkout') {
                steps {
                    script {
                        env.PIPELINE_START_MILLIS =
                            System.currentTimeMillis().toString()

                        if (!repositoryUrl) {
                            error(
                                'repositoryUrl is required. Configure it in the Jenkins job.'
                            )
                        }

                        // Workspace của Pipeline không luôn xuất hiện trong UI
                        // Jenkins. Cho phép xoá bản checkout/caches cũ theo yêu
                        // cầu của build, trước khi Git checkout lại toàn bộ project.
                        if (params.CLEAN_WORKSPACE?.toString()?.toBoolean()) {
                            echo(
                                'CLEAN_WORKSPACE is enabled; removing the ' +
                                'current job workspace before checkout.'
                            )
                            deleteDir()
                        }

                        def branchSpec = params.GIT_BRANCH?.trim()
                            ? params.GIT_BRANCH.trim()
                            : defaultGitBranch

                        // Chỉ cảnh báo, không chặn: build tay không có ref
                        // webhook, và một lần lệch không đáng để huỷ build.
                        // Nếu dòng này xuất hiện ở một build do webhook kích
                        // hoạt thì bộ lọc trigger đang trỏ sai branch.
                        def webhookRef = env.PEARZ_WEBHOOK_REF?.trim()

                        if (
                            webhookRef &&
                            normalizeGitBranch(webhookRef) !=
                                normalizeGitBranch(branchSpec)
                        ) {
                            echo(
                                "WARNING: webhook reported ${webhookRef} " +
                                "but this build checks out ${branchSpec}."
                            )
                        }

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

                        if (isUnix()) {
                            sh '''
                                git submodule sync --recursive
                                git submodule update --init --recursive
                            '''
                        } else {
                            bat '''
                                git submodule sync --recursive
                                git submodule update --init --recursive
                            '''
                        }
                    }
                }
            }

            stage('Prepare Build Variables') {
                steps {
                    script {
                        env.UNITY_VERSION = readUnityEditorVersion()

                        if (isUnix()) {
                            def kernelName = sh(
                                script: 'uname -s',
                                returnStdout: true
                            ).trim()

                            if (kernelName != 'Darwin') {
                                error(
                                    "Unsupported Jenkins agent OS: ${kernelName}. " +
                                    'PearzCI supports Windows and macOS.'
                                )
                            }

                            env.NODE_OS = 'macOS'
                            env.RCLONE_EXE = env.MAC_RCLONE_EXE
                            env.UNITY_HUB_ROOT = env.MAC_UNITY_HUB_ROOT
                        } else {
                            env.NODE_OS = 'Windows'
                            env.RCLONE_EXE = env.WINDOWS_RCLONE_EXE
                            env.UNITY_HUB_ROOT = env.WINDOWS_UNITY_HUB_ROOT
                        }

                        def outputName = params.PRODUCT_NAME?.trim()
                            ? params.PRODUCT_NAME.trim()
                            : env.JOB_BASE_NAME

                        outputName = outputName.replaceAll(
                            '[<>:"\\\\/|?*]',
                            '_'
                        )

                        def requestedReleaseBuildNumber = isAndroid &&
                            params.BUILD_APP_BUNDLE?.toString()?.toBoolean()
                            ? params.RELEASE_BUILD_NUMBER?.toString()?.trim()
                            : ''
                        if (requestedReleaseBuildNumber &&
                            !(requestedReleaseBuildNumber ==~ /[1-9][0-9]*/)) {
                            error(
                                'RELEASE_BUILD_NUMBER must be a positive ' +
                                'integer, for example 157.'
                            )
                        }
                        def artifactBuildNumber =
                            requestedReleaseBuildNumber ?: env.BUILD_NUMBER
                        env.ARTIFACT_BUILD_NUMBER = artifactBuildNumber

                        env.OUTPUT_EXTENSION = isIos
                            ? 'ipa'
                            : (params.BUILD_APP_BUNDLE ? 'aab' : 'apk')
                        // Giữ một APK duy nhất trong workspace để mỗi build
                        // Android mới ghi đè APK của build trước. AAB có thể
                        // dùng RELEASE_BUILD_NUMBER để ghép với APK đã duyệt.
                        env.OUTPUT_FILE_NAME = !isIos &&
                            env.OUTPUT_EXTENSION == 'apk'
                            ? "${outputName}.apk"
                            : "${outputName}-${artifactBuildNumber}.${env.OUTPUT_EXTENSION}"
                        env.DRIVE_OUTPUT_FILE_NAME =
                            "${outputName}-${artifactBuildNumber}.${env.OUTPUT_EXTENSION}"
                        def buildFolder = isIos ? 'iOS' : 'Android'
                        env.OUTPUT_PATH = "${env.WORKSPACE}/Builds/${buildFolder}/${env.OUTPUT_FILE_NAME}"
                        env.BUILD_INFO_FILE_NAME = "${outputName}_BUILD_INFO.txt"
                        env.BUILD_INFO_PATH =
                            "${env.WORKSPACE}/Builds/${buildFolder}/${env.BUILD_INFO_FILE_NAME}"
                        env.METADATA_PATH = "${env.WORKSPACE}/Builds/${buildFolder}/build-metadata.json"
                        env.MAPPING_PATH = "${env.WORKSPACE}/Builds/${buildFolder}/mapping.txt"
                        env.BUILD_LOG_PATH = "${env.WORKSPACE}/Builds/${buildFolder}/unity-build.log"
                        env.UPLOAD_LOG_PATH = "${env.WORKSPACE}/Builds/${buildFolder}/upload.log"
                        if (isIos) {
                            env.IOS_PROJECT_PATH = "${env.WORKSPACE}/Builds/iOS/Unity-iPhone"
                            env.ARCHIVE_PATH = "${env.WORKSPACE}/Builds/iOS/Unity-iPhone.xcarchive"
                            env.EXPORT_PATH = "${env.WORKSPACE}/Builds/iOS/export"
                            env.XCODEBUILD_LOG_PATH = "${env.WORKSPACE}/Builds/iOS/xcodebuild.log"
                        }
                        def androidBuildVersion = params.APP_VERSION?.trim()
                            ? "${params.APP_VERSION.trim()}-${artifactBuildNumber}"
                            : artifactBuildNumber
                        env.BUILD_VERSION = androidBuildVersion
                        env.DRIVE_DIRECTORY = isAndroid
                            ? "${env.DRIVE_REMOTE}:${env.DRIVE_ROOT}/${env.JOB_BASE_NAME}/${androidBuildVersion}"
                            : "${env.DRIVE_REMOTE}:${env.DRIVE_ROOT}/${env.JOB_BASE_NAME}"
                        env.DRIVE_FILE_PATH =
                            "${env.DRIVE_DIRECTORY}/${env.DRIVE_OUTPUT_FILE_NAME}"
                        env.DRIVE_BUILD_INFO_PATH =
                            "${env.DRIVE_DIRECTORY}/${env.BUILD_INFO_FILE_NAME}"
                        env.DRIVE_MAPPING_PATH =
                            "${env.DRIVE_DIRECTORY}/mapping-${artifactBuildNumber}.txt"

                        if (isUnix()) {
                            env.GIT_COMMIT_SHORT = sh(
                                script: 'git rev-parse --short HEAD',
                                returnStdout: true
                            ).trim()
                            env.GIT_COMMIT_MESSAGE = sh(
                                script: 'git log -1 --pretty=%s',
                                returnStdout: true
                            ).trim()
                            env.GIT_COMMIT_AUTHOR = sh(
                                script: 'git log -1 --pretty=%an',
                                returnStdout: true
                            ).trim()
                        } else {
                            env.GIT_COMMIT_SHORT = bat(
                                script: '@git rev-parse --short HEAD',
                                returnStdout: true
                            ).trim()
                            env.GIT_COMMIT_MESSAGE = bat(
                                script: '@git log -1 --pretty=%%s',
                                returnStdout: true
                            ).trim()
                            env.GIT_COMMIT_AUTHOR = bat(
                                script: '@git log -1 --pretty=%%an',
                                returnStdout: true
                            ).trim()
                        }

                        if (isUnix()) {
                            env.PEARZCI_GIT_COMMIT = sh(
                                script: 'git rev-parse HEAD',
                                returnStdout: true
                            ).trim()
                        } else {
                            env.PEARZCI_GIT_COMMIT = bat(
                                script: '@git rev-parse HEAD',
                                returnStdout: true
                            ).trim()
                        }

                        env.GIT_CHANGES = collectGitChanges(telegramMaxCommits)
                    }
                }
            }

            stage('Show Parameters') {
                steps {
                    echo "NODE_OS = ${env.NODE_OS}"
                    echo "PRODUCT_NAME = ${params.PRODUCT_NAME}"
                    echo "BUILD_CONFIGURATION = ${params.BUILD_CONFIGURATION}"
                    echo "GIT_BRANCH = ${params.GIT_BRANCH}"
                    echo "UNITY_VERSION = ${env.UNITY_VERSION}"
                    echo "TARGET_ARCHITECTURES = ${params.TARGET_ARCHITECTURES}"
                    echo "OUTPUT_PATH = ${env.OUTPUT_PATH}"
                    echo "DRIVE_FILE_PATH = ${env.DRIVE_FILE_PATH}"
                    echo "GIT_COMMIT_SHORT = ${env.GIT_COMMIT_SHORT}"

                    script {
                        def telegramConfig =
                            "${params.TELEGRAM_CHANNEL ?: ''}".trim()
                        def telegramTargets = telegramConfig
                            ? telegramConfig
                                .split(';')
                                .count { it.trim() }
                            : 0

                        echo "TELEGRAM_CHANNEL targets = ${telegramTargets}"
                    }
                }
            }

            stage('Validate Unity') {
                steps {
                    script {
                        def unityExe = isUnix()
                            ? "${env.UNITY_HUB_ROOT}/${env.UNITY_VERSION}" +
                                '/Unity.app/Contents/MacOS/Unity'
                            : "${env.UNITY_HUB_ROOT}/${env.UNITY_VERSION}" +
                                '/Editor/Unity.exe'

                        env.UNITY_EXE = unityExe
                        echo "Unity path: ${unityExe}"

                        if (!fileExists(unityExe)) {
                            error("Unity not found: ${unityExe}")
                        }

                        if (isUnix()) {
                            sh "\"${unityExe}\" -version"
                            if (isIos) {
                                sh 'xcodebuild -version'
                            }
                        } else {
                            bat "\"${unityExe}\" -version"
                        }
                    }
                }
            }

            stage('Build Unity Android') {
                when { expression { isAndroid } }
                options {
                    timeout(time: 60, unit: 'MINUTES')
                }

                steps {
                    script {
                        def buildStartedAt = System.currentTimeMillis()
                        def ciAppVersion = env.BUILD_VERSION
                        def androidVersionCode = env.BUILD_NUMBER

                        if (params.BUILD_APP_BUNDLE?.toString()?.toBoolean()) {
                            androidVersionCode = readNextAabVersionCode().toString()
                            env.AAB_VERSION_CODE = androidVersionCode
                            echo(
                                'AAB version code reserved from the ' +
                                "per-job counter: ${androidVersionCode}"
                            )
                        }

                        echo "Android APP_VERSION passed to Unity: ${ciAppVersion}"
                        echo "Android version code passed to Unity: ${androidVersionCode}"

                        try {
                            // APK dùng BUILD_NUMBER để tester nhận biết bản đang cài.
                            // AAB dùng bộ đếm riêng, không bị các APK test xen kẽ
                            // làm nhảy version code trên Google Play.
                            withEnv([
                                "OUTPUT_PATH=${env.OUTPUT_PATH}",
                                "APP_VERSION=${ciAppVersion}",
                                "ANDROID_VERSION_CODE=${androidVersionCode}",
                                // Bundle ID is always sourced from Unity Project Settings.
                                'BUNDLE_IDENTIFIER='
                            ]) {
                                if (isUnix()) {
                                    sh '''
                                        set +e

                                        "$UNITY_EXE" \
                                            -batchmode \
                                            -quit \
                                            -projectPath "$WORKSPACE" \
                                            -buildTarget Android \
                                            -executeMethod Pearz.CI.BuildEntry.BuildAndroid \
                                            -logFile "$BUILD_LOG_PATH"

                                        unity_exit_code=$?

                                        if [ -f "$BUILD_LOG_PATH" ]; then
                                            cat "$BUILD_LOG_PATH"
                                        fi

                                        exit "$unity_exit_code"
                                    '''
                                } else {
                                    bat '''
                                        @echo off
                                        "%UNITY_EXE%" ^
                                            -batchmode ^
                                            -nographics ^
                                            -quit ^
                                            -projectPath "%WORKSPACE%" ^
                                            -buildTarget Android ^
                                            -executeMethod Pearz.CI.BuildEntry.BuildAndroid ^
                                            -logFile "%BUILD_LOG_PATH%"

                                        set UNITY_EXIT_CODE=%ERRORLEVEL%

                                        if exist "%BUILD_LOG_PATH%" (
                                            type "%BUILD_LOG_PATH%"
                                        )

                                        exit /b %UNITY_EXIT_CODE%
                                    '''
                                }
                            }
                        } finally {
                            env.BUILD_TIME_MILLIS = (
                                System.currentTimeMillis() - buildStartedAt
                            ).toString()
                        }
                    }
                }
            }

            stage('Build Unity iOS') {
                when { expression { isIos } }
                options { timeout(time: 60, unit: 'MINUTES') }
                steps {
                    script {
                        def startedAt = System.currentTimeMillis()
                        try {
                            withEnv([
                                "OUTPUT_PATH=${env.IOS_PROJECT_PATH}",
                                "PRODUCT_NAME=${params.PRODUCT_NAME ?: ''}",
                                'BUNDLE_IDENTIFIER=',
                                "SCRIPTING_DEFINE_SYMBOLS=${params.SCRIPTING_DEFINE_SYMBOLS ?: ''}",
                                "APP_VERSION=${params.APP_VERSION ?: ''}",
                                "IOS_BUILD_NUMBER=${params.IOS_BUILD_NUMBER ?: ''}",
                                "IOS_BUILD_TO_DEVICE=${iosBuildToDevice}",
                                "IL2CPP_CODE_GENERATION=${params.IL2CPP_CODE_GENERATION ?: ''}",
                                "MANAGED_STRIPPING_LEVEL=${params.MANAGED_STRIPPING_LEVEL ?: ''}",
                                "STRIP_ENGINE_CODE=${params.STRIP_ENGINE_CODE}",
                                "UNITY_DEVELOPMENT_BUILD=${params.UNITY_DEVELOPMENT_BUILD}",
                                "SCRIPT_DEBUGGING=${params.SCRIPT_DEBUGGING}"
                            ]) {
                                sh '''
                                    set +e
                                    "$UNITY_EXE" -batchmode -quit \\
                                        -projectPath "$WORKSPACE" \\
                                        -buildTarget iOS \\
                                        -executeMethod Pearz.CI.BuildEntry.BuildIOS \\
                                        -logFile "$BUILD_LOG_PATH"
                                    result=$?
                                    [ ! -f "$BUILD_LOG_PATH" ] || cat "$BUILD_LOG_PATH"
                                    exit "$result"
                                '''
                            }
                        } finally {
                            env.BUILD_TIME_MILLIS = (System.currentTimeMillis() - startedAt).toString()
                        }
                    }
                }
            }

            stage('Remove duplicate AppLovin SPM dependency') {
                when { expression { isIos } }
                steps {
                    sh '''
                        set -eu
                        pbxproj_path="$IOS_PROJECT_PATH/Unity-iPhone.xcodeproj/project.pbxproj"
                        pods_applovin_path="$IOS_PROJECT_PATH/Pods/AppLovinSDK"

                        if [ ! -f "$pbxproj_path" ] || [ ! -d "$pods_applovin_path" ]; then
                            echo 'AppLovin SPM cleanup skipped: CocoaPods AppLovinSDK was not found.'
                            exit 0
                        fi
                        if ! grep -Fiq 'applovin-max-swift-package' "$pbxproj_path"; then
                            echo 'AppLovin SPM cleanup skipped: no AppLovin Swift package reference found.'
                            exit 0
                        fi

                        printf '%s' 'cmVxdWlyZSAneGNvZGVwcm9qJwpwcm9qZWN0ID0gWGNvZGVwcm9qOjpQcm9qZWN0Lm9wZW4oQVJHVi5mZXRjaCgwKSkKcGFja2FnZXMgPSBwcm9qZWN0LnJvb3Rfb2JqZWN0LnBhY2thZ2VfcmVmZXJlbmNlcy5zZWxlY3QgZG8gfHJlZmVyZW5jZXwKICByZWZlcmVuY2UuaXNhID09ICdYQ1JlbW90ZVN3aWZ0UGFja2FnZVJlZmVyZW5jZScgJiYKICAgIHJlZmVyZW5jZS5yZXBvc2l0b3J5VVJMLnRvX3MuZG93bmNhc2UuaW5jbHVkZT8oJ2FwcGxvdmluLW1heC1zd2lmdC1wYWNrYWdlJykKZW5kCnByb2R1Y3RzID0gcHJvamVjdC5vYmplY3RzLnNlbGVjdCBkbyB8b2JqZWN0fAogIG9iamVjdC5pc2EgPT0gJ1hDU3dpZnRQYWNrYWdlUHJvZHVjdERlcGVuZGVuY3knICYmCiAgICBwYWNrYWdlcy5pbmNsdWRlPyhvYmplY3QucGFja2FnZSkKZW5kCnByb2plY3QudGFyZ2V0cy5lYWNoIGRvIHx0YXJnZXR8CiAgbmV4dCB1bmxlc3MgdGFyZ2V0LnJlc3BvbmRfdG8/KDpwYWNrYWdlX3Byb2R1Y3RfZGVwZW5kZW5jaWVzKQogIHByb2R1Y3RzLmVhY2ggeyB8cHJvZHVjdHwgdGFyZ2V0LnBhY2thZV9wcm9kdWN0X2RlcGVuZGVuY2llcy5kZWxldGUocHJvZHVjdCkgfQplbmQKcHJvZHVjdHMuZWFjaCgmOnJlbW92ZV9mcm9tX3Byb2plY3QpCnBhY2thZ2VzLmVhY2goJjpyZW1vdmVfZnJvbV9wcm9qZWN0KQpwcm9qZWN0LnNhdmUK' | base64 -D | ruby - "$pbxproj_path"

                        if grep -Fiq 'applovin-max-swift-package' "$pbxproj_path"; then
                            echo 'ERROR: AppLovin Swift package reference remains after cleanup.'
                            exit 1
                        fi
                        echo 'Removed duplicate AppLovin Swift Package Manager dependency; using CocoaPods AppLovinSDK.'
                    '''
                }
            }

            stage('Archive and Export IPA') {
                when { expression { isIos && !iosBuildToDevice } }
                options { timeout(time: 45, unit: 'MINUTES') }
                steps {
                    script {
                        def exportOptionsPath = config.get('iosExportOptionsPlistPath', params.IOS_EXPORT_OPTIONS_PLIST_PATH ?: '').toString().trim()
                        def developmentTeam = config.get('iosDevelopmentTeam', params.IOS_DEVELOPMENT_TEAM ?: '').toString().trim()
                        def profileSpecifier = config.get('iosProvisioningProfileSpecifier', params.IOS_PROVISIONING_PROFILE_SPECIFIER ?: '').toString().trim()
                        def xcodeConfiguration = config.get('xcodeConfiguration', params.XCODE_CONFIGURATION ?: 'Release').toString().trim()
                        if (!exportOptionsPath || !fileExists(exportOptionsPath)) {
                            error('A readable IOS_EXPORT_OPTIONS_PLIST_PATH is required for IPA export.')
                        }
                        withEnv(["IOS_EXPORT_OPTIONS=${exportOptionsPath}", "IOS_DEVELOPMENT_TEAM=${developmentTeam}", "IOS_PROFILE_SPECIFIER=${profileSpecifier}", "XCODE_CONFIGURATION=${xcodeConfiguration}"]) {
                            sh '''
                                set -eu
                                [ -d "$IOS_PROJECT_PATH/Unity-iPhone.xcodeproj" ]
                                rm -rf "$ARCHIVE_PATH" "$EXPORT_PATH"
                                mkdir -p "$EXPORT_PATH"
                                signing_args=""
                                [ -z "$IOS_DEVELOPMENT_TEAM" ] || signing_args="$signing_args DEVELOPMENT_TEAM=$IOS_DEVELOPMENT_TEAM"
                                [ -z "$IOS_PROFILE_SPECIFIER" ] || signing_args="$signing_args CODE_SIGN_STYLE=Manual PROVISIONING_PROFILE_SPECIFIER=$IOS_PROFILE_SPECIFIER"
                                xcodebuild -project "$IOS_PROJECT_PATH/Unity-iPhone.xcodeproj" -scheme Unity-iPhone -configuration "$XCODE_CONFIGURATION" -archivePath "$ARCHIVE_PATH" archive $signing_args > "$XCODEBUILD_LOG_PATH" 2>&1
                                xcodebuild -exportArchive -archivePath "$ARCHIVE_PATH" -exportOptionsPlist "$IOS_EXPORT_OPTIONS" -exportPath "$EXPORT_PATH" >> "$XCODEBUILD_LOG_PATH" 2>&1
                                cat "$XCODEBUILD_LOG_PATH"
                                ipa=$(find "$EXPORT_PATH" -maxdepth 1 -type f -name '*.ipa' -print -quit)
                                [ -n "$ipa" ]
                                cp "$ipa" "$OUTPUT_PATH"
                            '''
                        }
                    }
                }
            }

            stage('Verify Artifact') {
                when { expression { !isIos || !iosBuildToDevice } }
                steps {
                    script {
                        if (!fileExists(env.OUTPUT_PATH)) {
                            error(
                                "Build artifact not found: ${env.OUTPUT_PATH}"
                            )
                        }

                        if (isAndroid && !fileExists(env.BUILD_INFO_PATH)) {
                            error(
                                "Build info file not found: ${env.BUILD_INFO_PATH}"
                            )
                        }

                        echo "Build artifact created successfully: ${env.OUTPUT_PATH}"
                    }
                }
            }

            stage('Read Build Metadata') {
                when { expression { isAndroid } }
                steps {
                    script {
                        readBuildMetadata()
                    }
                }
            }

            stage('Archive Artifact') {
                when { expression { !isIos || !iosBuildToDevice } }
                steps {
                    script {
                        archiveArtifacts(
                            artifacts: isIos
                                ? "Builds/iOS/${env.OUTPUT_FILE_NAME},Builds/iOS/build-metadata.json,Builds/iOS/unity-build.log,Builds/iOS/xcodebuild.log"
                                : "Builds/Android/${env.OUTPUT_FILE_NAME},Builds/Android/${env.BUILD_INFO_FILE_NAME},Builds/Android/build-metadata.json,Builds/Android/mapping.txt,Builds/Android/unity-build.log",
                            allowEmptyArchive: true,
                            fingerprint: true,
                            onlyIfSuccessful: true
                        )
                    }
                }
            }

            stage('Validate rclone') {
                when { expression { !isIos || !iosBuildToDevice } }
                steps {
                    script {
                        if (isUnix()) {
                            sh '''
                                set -eu

                                if ! command -v "$RCLONE_EXE" >/dev/null 2>&1 &&
                                    [ ! -x "$RCLONE_EXE" ]; then
                                    echo "ERROR: rclone not found: $RCLONE_EXE"
                                    exit 1
                                fi

                                "$RCLONE_EXE" version

                                if ! "$RCLONE_EXE" listremotes |
                                    grep -Fqx "$DRIVE_REMOTE:"; then
                                    echo "ERROR: rclone remote $DRIVE_REMOTE: does not exist."
                                    exit 1
                                fi
                            '''
                        } else {
                            bat '''
                                if not exist "%RCLONE_EXE%" (
                                    echo ERROR: rclone.exe not found:
                                    echo %RCLONE_EXE%
                                    exit /b 1
                                )

                                "%RCLONE_EXE%" version
                                "%RCLONE_EXE%" listremotes | findstr /B /C:"%DRIVE_REMOTE%:" >nul

                                if errorlevel 1 (
                                    echo ERROR: rclone remote "%DRIVE_REMOTE%:" does not exist.
                                    exit /b 1
                                )
                            '''
                        }
                    }
                }
            }

            stage('Upload Google Drive') {
                when { expression { !isIos || !iosBuildToDevice } }
                options {
                    timeout(time: 30, unit: 'MINUTES')
                }

                steps {
                    script {
                        def uploadStartedAt = System.currentTimeMillis()

                        try {
                            retry(2) {
                                if (isUnix()) {
                                    sh '''
                                        set -eu

                                        "$RCLONE_EXE" copyto \
                                            "$OUTPUT_PATH" \
                                            "$DRIVE_FILE_PATH" \
                                            --progress \
                                            --stats 10s \
                                            --retries 3 \
                                            --low-level-retries 10 \
                                            --log-file "$UPLOAD_LOG_PATH" \
                                            --log-level INFO

                                    '''
                                } else {
                                    bat '''
                                        "%RCLONE_EXE%" copyto "%OUTPUT_PATH%" "%DRIVE_FILE_PATH%" ^
                                            --progress ^
                                            --stats 10s ^
                                            --retries 3 ^
                                            --low-level-retries 10 ^
                                            --log-file "%UPLOAD_LOG_PATH%" ^
                                            --log-level INFO

                                        if errorlevel 1 (
                                            echo ERROR: Google Drive upload failed.
                                            exit /b 1
                                        )

                                    '''
                                }
                            }

                            if (isAndroid) {
                                retry(2) {
                                    if (isUnix()) {
                                        sh '''
                                            set -eu
                                            "$RCLONE_EXE" copyto \
                                                "$BUILD_INFO_PATH" \
                                                "$DRIVE_BUILD_INFO_PATH" \
                                                --progress \
                                                --stats 10s \
                                                --retries 3 \
                                                --low-level-retries 10 \
                                                --log-file "$UPLOAD_LOG_PATH" \
                                                --log-level INFO
                                        '''
                                    } else {
                                        bat '''
                                            "%RCLONE_EXE%" copyto "%BUILD_INFO_PATH%" "%DRIVE_BUILD_INFO_PATH%" ^
                                                --progress ^
                                                --stats 10s ^
                                                --retries 3 ^
                                                --low-level-retries 10 ^
                                                --log-file "%UPLOAD_LOG_PATH%" ^
                                                --log-level INFO

                                            if errorlevel 1 (
                                                echo ERROR: Build info upload failed.
                                                exit /b 1
                                            )
                                        '''
                                    }
                                }
                            }

                            if (isAndroid && fileExists(env.MAPPING_PATH)) {
                                def mappingUploadStatus

                                if (isUnix()) {
                                    mappingUploadStatus = sh(
                                        script: '''
                                            "$RCLONE_EXE" copyto \
                                                "$MAPPING_PATH" \
                                                "$DRIVE_MAPPING_PATH" \
                                                --retries 3 \
                                                --low-level-retries 10 \
                                                --log-file "$UPLOAD_LOG_PATH" \
                                                --log-level INFO
                                        ''',
                                        returnStatus: true
                                    )
                                } else {
                                    mappingUploadStatus = bat(
                                        script: '''
                                            @"%RCLONE_EXE%" copyto "%MAPPING_PATH%" "%DRIVE_MAPPING_PATH%" ^
                                                --retries 3 ^
                                                --low-level-retries 10 ^
                                                --log-file "%UPLOAD_LOG_PATH%" ^
                                                --log-level INFO
                                        ''',
                                        returnStatus: true
                                    )
                                }

                                if (mappingUploadStatus != 0) {
                                    echo(
                                        'Optional mapping.txt upload failed; ' +
                                        'the main artifact remains valid.'
                                    )
                                }
                            }
                        } finally {
                            env.UPLOAD_TIME_MILLIS = (
                                System.currentTimeMillis() - uploadStartedAt
                            ).toString()
                        }
                    }
                }
            }

            stage('Verify Google Drive Upload') {
                when { expression { !isIos || !iosBuildToDevice } }
                steps {
                    script {
                        if (isUnix()) {
                            sh '''
                                set -eu
                                "$RCLONE_EXE" lsjson \
                                    "$DRIVE_FILE_PATH" \
                                    --files-only
                                echo "Build artifact verified successfully on Google Drive."
                            '''
                        } else {
                            bat '''
                                "%RCLONE_EXE%" lsjson "%DRIVE_FILE_PATH%" --files-only

                                if errorlevel 1 (
                                    echo ERROR: Uploaded artifact could not be verified.
                                    exit /b 1
                                )

                                echo Build artifact verified successfully on Google Drive.
                            '''
                        }

                        if (isAndroid) {
                            if (isUnix()) {
                                sh '''
                                    set -eu
                                    "$RCLONE_EXE" lsjson \
                                        "$DRIVE_BUILD_INFO_PATH" \
                                        --files-only
                                '''
                            } else {
                                bat '''
                                    "%RCLONE_EXE%" lsjson "%DRIVE_BUILD_INFO_PATH%" --files-only

                                    if errorlevel 1 (
                                        echo ERROR: Uploaded build info file could not be verified.
                                        exit /b 1
                                    )
                                '''
                            }

                            echo 'Build info file verified on Google Drive.'
                        }

                        env.MAPPING_UPLOADED = 'false'

                        if (isAndroid && fileExists(env.MAPPING_PATH)) {
                            def mappingStatus = isUnix()
                                ? sh(
                                    script:
                                        '"$RCLONE_EXE" lsjson ' +
                                        '"$DRIVE_MAPPING_PATH" --files-only',
                                    returnStatus: true
                                )
                                : bat(
                                    script:
                                        '@"%RCLONE_EXE%" lsjson ' +
                                        '"%DRIVE_MAPPING_PATH%" --files-only',
                                    returnStatus: true
                                )

                            if (mappingStatus == 0) {
                                env.MAPPING_UPLOADED = 'true'
                                echo 'mapping.txt verified on Google Drive.'
                            } else {
                                echo(
                                    'Optional mapping.txt could not be ' +
                                    'verified; its link will be omitted.'
                                )
                            }
                        }
                    }
                }
            }

            stage('Create Public Link') {
                when { expression { !isIos || !iosBuildToDevice } }
                steps {
                    script {
                        env.DOWNLOAD_URL =
                            createRcloneLink(env.DRIVE_FILE_PATH)

                        if (!env.DOWNLOAD_URL) {
                            error(
                                'ERROR: rclone did not return a public link.'
                            )
                        }

                        if (isAndroid && env.MAPPING_UPLOADED == 'true') {
                            env.MAPPING_URL =
                                createRcloneLink(env.DRIVE_MAPPING_PATH)
                        }

                        env.DRIVE_FOLDER_URL =
                            createRcloneLink(env.DRIVE_DIRECTORY)

                        echo "Public download link: ${env.DOWNLOAD_URL}"
                    }
                }
            }

            stage('Archive Notification Artifacts') {
                when { expression { !isIos || !iosBuildToDevice } }
                steps {
                    // Chỉ upload.log là file mới kể từ stage 'Archive Artifact'.
                    // Các file còn lại đã được archive ở đó.
                    archiveArtifacts(
                        artifacts: isIos ? 'Builds/iOS/upload.log' : 'Builds/Android/upload.log',
                        allowEmptyArchive: true,
                        fingerprint: true
                    )
                }
            }
        }

        post {
            success {
                script {
                    echo "Unity ${isIos ? 'iOS' : 'Android'} build completed: ${env.OUTPUT_FILE_NAME}"
                    if (!isIos || !iosBuildToDevice) {
                        echo "Uploaded to Google Drive: ${env.DRIVE_FILE_PATH}"
                    }

                    if (isAndroid && env.AAB_VERSION_CODE?.trim()) {
                        saveNextAabVersionCode(env.AAB_VERSION_CODE.toInteger())
                    }

                    if (env.DOWNLOAD_URL?.trim()) {
                        echo "Download URL: ${env.DOWNLOAD_URL}"
                    }
                }
            }

            failure {
                echo 'Unity Android build failed.'
            }

            always {
                // Bắt log chẩn đoán cho build thất bại, khi các stage
                // archive phía trên không kịp chạy. Không archive lại
                // artifact chính vì stage 'Archive Artifact' đã làm.
                // Phải chạy trước khi gửi Telegram để link log có hiệu lực.
                archiveArtifacts(
                    artifacts: isIos
                        ? 'Builds/iOS/build-metadata.json,Builds/iOS/unity-build.log,Builds/iOS/xcodebuild.log,Builds/iOS/upload.log'
                        : 'Builds/Android/build-metadata.json,Builds/Android/unity-build.log,Builds/Android/upload.log',
                    allowEmptyArchive: true
                )

                // Gửi ở post chứ không phải ở stage: một stage nằm cuối
                // pipeline sẽ bị bỏ qua khi build hỏng, đúng lúc cần báo
                // nhất. Đặt trước bước dọn dẹp vì còn cần đọc metadata.
                script {
                    // Mặc định bật để không thay đổi hành vi các job hiện có.
                    // Đây là cờ tổng cho Telegram và các nền tảng thông báo
                    // khác được bổ sung sau này.
                    def sendNotifications = params.SEND_NOTIFICATIONS == null ||
                        params.SEND_NOTIFICATIONS.toString().toBoolean()

                    if (isAndroid && sendNotifications) {
                        sendTelegramNotification(telegramCredentialsId)
                    } else if (isIos && iosBuildToDevice && sendNotifications) {
                        pearzUnityIosPipeline.sendIosDeviceTelegramNotification(
                            telegramCredentialsId
                        )
                    } else {
                        echo 'Notification skipped for this platform or configuration.'
                    }
                }

                script {
                    if (isUnix()) {
                        sh(
                            'rm -f send-telegram.sh send-telegram.ps1 ' +
                            'read-build-metadata.sh ' +
                            'read-build-metadata.ps1 ' +
                            'telegram-message.txt'
                        )
                    } else {
                        bat '''
                            if exist "%WORKSPACE%\\send-telegram.ps1" (
                                del /F /Q "%WORKSPACE%\\send-telegram.ps1"
                            )

                            if exist "%WORKSPACE%\\send-telegram.sh" (
                                del /F /Q "%WORKSPACE%\\send-telegram.sh"
                            )

                            if exist "%WORKSPACE%\\read-build-metadata.ps1" (
                                del /F /Q "%WORKSPACE%\\read-build-metadata.ps1"
                            )

                            if exist "%WORKSPACE%\\read-build-metadata.sh" (
                                del /F /Q "%WORKSPACE%\\read-build-metadata.sh"
                            )

                            if exist "%WORKSPACE%\\telegram-message.txt" (
                                del /F /Q "%WORKSPACE%\\telegram-message.txt"
                            )
                        '''
                    }

                    if (isAndroid) {
                        echo(
                            'Keeping Builds/Android in the workspace; the ' +
                            'next APK build replaces the existing APK.'
                        )
                    } else if (fileExists('Builds')) {
                        echo 'Cleaning build output...'
                        dir('Builds') {
                            deleteDir()
                        }
                    }
                }
            }
        }
    }
}

def createRcloneLink(String remotePath) {
    if (!remotePath?.trim()) {
        return ''
    }

    try {
        def output

        withEnv(["RCLONE_LINK_TARGET=${remotePath.trim()}"]) {
            output = isUnix()
                ? sh(
                    script:
                        '"$RCLONE_EXE" link "$RCLONE_LINK_TARGET"',
                    returnStdout: true
                )
                : bat(
                    script: '''@echo off
                        "%RCLONE_EXE%" link "%RCLONE_LINK_TARGET%"
                    ''',
                    returnStdout: true
                )
        }

        def urls = output
            .readLines()
            .collect { it.trim() }
            .findAll { it ==~ /^https?:\/\/.+/ }

        return urls ? urls[urls.size() - 1] : ''
    } catch (Exception exception) {
        echo(
            "Optional public link unavailable for ${remotePath}: " +
            exception.message
        )
        return ''
    }
}

def buildTelegramMessage() {
    // Kết quả của Jenkins mới là kết quả thật: Unity có thể build xong
    // nhưng upload lên Drive vẫn hỏng sau đó.
    def versionParts = []

    if (env.META_VERSION_NAME?.trim()) {
        versionParts << env.META_VERSION_NAME.trim()
    }

    if (env.META_VERSION_CODE?.trim()) {
        versionParts << "code ${env.META_VERSION_CODE.trim()}"
    }

    def googlePlayUrl = ''
    if (env.META_BUNDLE_IDENTIFIER?.trim()) {
        googlePlayUrl =
            'https://play.google.com/store/apps/details?id=' +
            env.META_BUNDLE_IDENTIFIER.trim()
    }

    def outputSize = formatBytes(env.META_OUTPUT_SIZE_BYTES)
    def artifactDescription = env.DOWNLOAD_URL?.trim()

    if (artifactDescription && outputSize) {
        artifactDescription += " (${outputSize})"
    }

    def apkDescription =
        env.OUTPUT_EXTENSION == 'aab' ? '' : artifactDescription
    def aabDescription = ''
    if (env.OUTPUT_EXTENSION == 'aab') {
        aabDescription = artifactDescription
            ? "Built - ${artifactDescription}"
            : 'Built'
    }

    def mappingDescription = ''
    if (env.MAPPING_URL?.trim()) {
        mappingDescription = env.MAPPING_URL.trim()
        def mappingSize = formatBytes(env.META_MAPPING_SIZE_BYTES)

        if (mappingSize) {
            mappingDescription += " (${mappingSize})"
        }
    }

    def symbols = env.META_DEFINE_SYMBOLS
        ?.readLines()
        ?.collect { it.trim() }
        ?.findAll { it }

    def symbolsSection = symbols
        ? '<b>Scripting Define Symbols:</b>\n' +
            symbols.collect { "• <code>${telegramHtmlEscape(it)}</code>" }
                .join('\n')
        : ''
    def changeDescription = env.GIT_CHANGES?.trim()

    def values = [
        PLATFORM: 'ANDROID',
        JOB_NAME: telegramHtmlEscape(env.JOB_NAME),
        BUILD_NUMBER: telegramHtmlEscape(env.BUILD_NUMBER),
        PEARZ_CI_VERSION: telegramHtmlEscape(env.PEARZ_CI_VERSION),
        VERSION: telegramHtmlEscape(versionParts.join(' / ')),
        VERSION_NAME: telegramHtmlEscape(env.META_VERSION_NAME),
        VERSION_CODE: telegramHtmlEscape(env.META_VERSION_CODE),
        PRODUCT_NAME: telegramHtmlEscape(env.META_PRODUCT_NAME ?: params.PRODUCT_NAME),
        BUNDLE_ID: telegramHtmlEscape(env.META_BUNDLE_IDENTIFIER),
        GOOGLE_PLAY_URL: telegramHtmlEscape(googlePlayUrl),
        BRANCH: telegramHtmlEscape(params.GIT_BRANCH),
        CONFIGURATION: telegramHtmlEscape(params.BUILD_CONFIGURATION),
        SCRIPTING_BACKEND: telegramHtmlEscape(env.META_SCRIPTING_BACKEND),
        STRIPPING_LEVEL: telegramHtmlEscape(env.META_STRIPPING_LEVEL),
        ORIENTATION: telegramHtmlEscape(env.META_ORIENTATION),
        UNITY_VERSION: telegramHtmlEscape(env.META_UNITY_VERSION ?: env.UNITY_VERSION),
        BUILD_INFO_URL: telegramHtmlEscape(env.DRIVE_FOLDER_URL),
        APK: telegramHtmlEscape(apkDescription),
        AAB: telegramHtmlEscape(aabDescription),
        MAPPING: telegramHtmlEscape(mappingDescription),
        DEFINE_SYMBOLS_SECTION: symbolsSection,
        ERROR_SECTION: env.META_ERROR_MESSAGE?.trim()
            ? "<blockquote><b>Error</b>\n${telegramHtmlEscape(env.META_ERROR_MESSAGE.trim())}</blockquote>"
            : '',
        CHANGES_SECTION: changeDescription
            ? "<b>Changes</b>\n<blockquote>${telegramHtmlEscape(changeDescription)}</blockquote>"
            : ''
    ]

    return truncateTelegramMessage(renderTelegramTemplate(values))
}

// Telegram sendMessage từ chối text dài hơn 4096 ký tự. Cắt bớt để một
// khoảng cách commit lớn không làm hỏng toàn bộ thông báo.
def truncateTelegramMessage(String message) {
    int maximumLength = 4096

    if (!message || message.length() <= maximumLength) {
        return message
    }

    def notice = '\n... (message truncated)'
    return message.substring(0, maximumLength - notice.length()) + notice
}

// Telegram HTML chỉ cho phép một tập thẻ giới hạn; escape toàn bộ dữ liệu
// đến từ Jenkins để commit message/branch không thể làm hỏng markup.
def telegramHtmlEscape(Object value) {
    if (value == null) {
        return ''
    }

    return value.toString()
        .replace('&', '&amp;')
        .replace('<', '&lt;')
        .replace('>', '&gt;')
}

// Gọi được từ cả stage lẫn khối post. Lần gọi thứ hai không làm gì, nên
// build thành công không phải đọc lại metadata.
def readBuildMetadata() {
    if (env.METADATA_READ == 'true') {
        return
    }

    env.METADATA_READ = 'true'

    def metadata = [:]
    def symbols = []
    def metadataPath = env.METADATA_PATH?.trim()

    if (metadataPath && fileExists(metadataPath)) {
        try {
            def metadataOutput

            if (isUnix()) {
                writeFile(
                    file: 'read-build-metadata.sh',
                    encoding: 'UTF-8',
                    text: libraryResource(
                        'com/pearz/ci/read-build-metadata.sh'
                    )
                )
                metadataOutput = sh(
                    script:
                        'sh ./read-build-metadata.sh ' +
                        '"$METADATA_PATH"',
                    returnStdout: true
                )
            } else {
                writeFile(
                    file: 'read-build-metadata.ps1',
                    encoding: 'UTF-8',
                    text: libraryResource(
                        'com/pearz/ci/read-build-metadata.ps1'
                    )
                )
                metadataOutput = bat(
                    script: '''@powershell.exe -NoLogo -NoProfile -NonInteractive ^
                        -ExecutionPolicy Bypass ^
                        -File "%WORKSPACE%\\read-build-metadata.ps1" ^
                        -MetadataPath "%METADATA_PATH%"
                    ''',
                    returnStdout: true
                )
            }

            metadataOutput.readLines().each { line ->
                def separatorIndex = line.indexOf('=')

                if (separatorIndex > 0) {
                    def key = line.substring(0, separatorIndex)
                    def value = line.substring(separatorIndex + 1)

                    if (key == 'DEFINE_SYMBOL') {
                        symbols << value
                    } else {
                        metadata[key] = value
                    }
                }
            }
        } catch (Exception exception) {
            echo(
                'Optional build metadata could not be read: ' +
                exception.message
            )
        }
    } else {
        echo(
            'Optional build metadata not found; ' +
            'notification will use Jenkins values.'
        )
    }

    env.META_RESULT = metadata.RESULT?.toString() ?: ''
    env.META_ERROR_MESSAGE = metadata.ERROR_MESSAGE?.toString() ?: ''
    env.META_PRODUCT_NAME = metadata.PRODUCT_NAME?.toString() ?: ''
    env.META_BUNDLE_IDENTIFIER =
        metadata.BUNDLE_IDENTIFIER?.toString() ?: ''
    env.META_VERSION_NAME = metadata.VERSION_NAME?.toString() ?: ''
    env.META_VERSION_CODE = metadata.VERSION_CODE?.toString() ?: ''
    env.META_UNITY_VERSION = metadata.UNITY_VERSION?.toString() ?: ''
    env.META_SCRIPTING_BACKEND =
        metadata.SCRIPTING_BACKEND?.toString() ?: ''
    env.META_STRIPPING_LEVEL =
        metadata.STRIPPING_LEVEL?.toString() ?: ''
    env.META_ORIENTATION = metadata.ORIENTATION?.toString() ?: ''
    env.META_OUTPUT_SIZE_BYTES =
        metadata.OUTPUT_SIZE_BYTES?.toString() ?: ''
    env.META_MAPPING_SIZE_BYTES =
        metadata.MAPPING_SIZE_BYTES?.toString() ?: ''
    env.META_DEFINE_SYMBOLS = symbols
        .collect { it?.toString()?.trim() }
        .findAll { it }
        .join('\n')
}

def sendTelegramNotification(String telegramCredentialsId) {
    boolean telegramConfigured = telegramCredentialsId ||
        "${params.TELEGRAM_CHANNEL ?: ''}".trim()

    if (!telegramConfigured) {
        echo 'No Telegram target configured; notification skipped.'
        return
    }

    try {
        // Build hỏng thì stage 'Read Build Metadata' chưa từng chạy.
        readBuildMetadata()

        if (env.PIPELINE_START_MILLIS?.trim()) {
            env.TOTAL_TIME_MILLIS = (
                System.currentTimeMillis() -
                env.PIPELINE_START_MILLIS.toLong()
            ).toString()
        }

        writeFile(
            file: 'telegram-message.txt',
            encoding: 'UTF-8',
            text: buildTelegramMessage()
        )

        def sendTelegram = {
            if (isUnix()) {
                writeFile(
                    file: 'send-telegram.sh',
                    encoding: 'UTF-8',
                    text: libraryResource(
                        'com/pearz/ci/send-telegram.sh'
                    )
                )
                withEnv([
                    'TELEGRAM_MESSAGE_FILE=telegram-message.txt'
                ]) {
                    sh 'sh ./send-telegram.sh'
                }
            } else {
                writeFile(
                    file: 'send-telegram.ps1',
                    encoding: 'UTF-8',
                    text: libraryResource(
                        'com/pearz/ci/send-telegram.ps1'
                    )
                )
                withEnv([
                    'TELEGRAM_MESSAGE_FILE=telegram-message.txt'
                ]) {
                    bat '''
                        powershell.exe -NoLogo -NoProfile -NonInteractive ^
                            -ExecutionPolicy Bypass ^
                            -File "%WORKSPACE%\\send-telegram.ps1"
                    '''
                }
            }
        }

        if (telegramCredentialsId) {
            withCredentials([
                string(
                    credentialsId: telegramCredentialsId,
                    variable: 'TELEGRAM_CHANNEL'
                )
            ]) {
                sendTelegram()
            }
        } else {
            withEnv([
                "TELEGRAM_CHANNEL=${params.TELEGRAM_CHANNEL ?: ''}"
            ]) {
                sendTelegram()
            }
        }
    } catch (Exception exception) {
        // Không để lỗi thông báo ghi đè kết quả build thật. Chỉ hạ xuống
        // UNSTABLE khi build vốn đang thành công, để sự cố không bị chìm.
        echo("Telegram notification failed: ${exception.message}")

        if (currentBuild.currentResult == 'SUCCESS') {
            currentBuild.result = 'UNSTABLE'
        }
    }
}

// Đọc giá trị mặc định của GIT_BRANCH khai báo trong Configure, tách biệt
// với giá trị có thể bị override khi bấm Build with Parameters. Trả về ''
// nếu không đọc được, để phía gọi tự lùi về hành vi cũ.
def readConfiguredBranchDefault() {
    try {
        def definitionProperty = currentBuild.rawBuild.parent.getProperty(
            hudson.model.ParametersDefinitionProperty.class
        )
        def definition =
            definitionProperty?.getParameterDefinition('GIT_BRANCH')
        def branch = definition
            ?.getDefaultParameterValue()
            ?.getValue()
            ?.toString()
            ?.trim()

        if (branch) {
            return branch
        }
    } catch (Exception exception) {
        echo(
            'Could not read the GIT_BRANCH job default; the webhook ' +
            'filter falls back to this run\'s value: ' + exception.message
        )
    }

    return ''
}

def readPearzCiVersion() {
    try {
        def version = libraryResource(
            'com/pearz/ci/version.txt'
        )?.trim()

        if (version) {
            return version
        }
    } catch (Exception exception) {
        echo(
            'Could not read the PearzCI version resource: ' +
            exception.message
        )
    }

    return 'unknown'
}

def collectGitChanges(int maximumChanges) {
    // Mốc là build THÀNH CÔNG gần nhất, không phải build gần nhất. Git
    // plugin ghi lại commit ngay ở bước checkout, nên một build bị huỷ
    // (disableConcurrentBuilds abortPrevious) hoặc build hỏng vẫn kịp
    // đẩy GIT_PREVIOUS_COMMIT lên. Build chạy tới cùng sau đó sẽ tưởng
    // không có gì mới và báo "No new commits", dù chính nó tạo artifact.
    // GIT_PREVIOUS_SUCCESSFUL_COMMIT không phải lúc nào cũng được expose khi
    // dùng checkout(...) thủ công. Đọc thêm metadata của build thành công
    // trước đó để không rơi về git log -1 chỉ vì thiếu biến môi trường.
    def previousBuildCandidates = [
        [
            source: 'previous successful Jenkins build variables',
            commit: readPreviousSuccessfulBuildCommit()
        ],
        [
            source: 'GIT_PREVIOUS_SUCCESSFUL_COMMIT',
            commit: env.GIT_PREVIOUS_SUCCESSFUL_COMMIT?.trim()
        ],
        [
            source: 'GIT_PREVIOUS_COMMIT',
            commit: env.GIT_PREVIOUS_COMMIT?.trim()
        ]
    ].findAll { it.commit }
    def previousBuildCommit = null
    def previousBuildSource = 'none'

    for (def candidate : previousBuildCandidates) {
        if (isAncestorCommit(candidate.commit)) {
            previousBuildCommit = candidate.commit
            previousBuildSource = candidate.source
            break
        }
    }

    def hasValidPreviousCommit = previousBuildCommit != null
    def logOutput = ''

    echo(
        previousBuildCommit
            ? 'Telegram commit baseline: ' + previousBuildCommit +
                ' (source: ' + previousBuildSource + ').'
            : 'Telegram commit baseline unavailable; using HEAD only.'
    )
    def changes = collectJenkinsChangeSets()
    if (!changes) {
        if (isUnix()) {
        withEnv([
            "PREVIOUS_BUILD_COMMIT=${previousBuildCommit ?: ''}",
            "HAS_VALID_PREVIOUS_COMMIT=${hasValidPreviousCommit}"
        ]) {
            logOutput = sh(
                script: '''
                    if [ "$HAS_VALID_PREVIOUS_COMMIT" = "true" ]; then
                        git log --pretty=format:'%h%x09%an%x09%B%x1e' \
                            "$PREVIOUS_BUILD_COMMIT..HEAD"
                    else
                        git log -1 --pretty=format:'%h%x09%an%x09%B%x1e'
                    fi
                ''',
                returnStdout: true
            ).trim()
        }
    } else {
        withEnv([
            "PREVIOUS_BUILD_COMMIT=${previousBuildCommit ?: ''}",
            "HAS_VALID_PREVIOUS_COMMIT=${hasValidPreviousCommit}"
        ]) {
            logOutput = bat(
                script: '''
                    @echo off
                    if "%HAS_VALID_PREVIOUS_COMMIT%"=="true" (
                        git log --pretty=format:%%h%%x09%%an%%x09%%B%%x1e "%PREVIOUS_BUILD_COMMIT%..HEAD"
                    ) else (
                        git log -1 --pretty=format:%%h%%x09%%an%%x09%%B%%x1e
                    )
                ''',
                returnStdout: true
            ).trim()
        }
        }
    }

    if (!changes) {
        changes = logOutput
            .split('\u001e')
            .collect { record ->
                def fields = record.trim().split('\t', 3)
                if (fields.size() == 3) {
                    '- ' + fields[0] + ' - ' + fields[1] + ': ' +
                        formatCommitMessage(fields[2])
                } else {
                    ''
                }
            }
            .findAll { it }
    }

    if (changes) {
        previousBuildSource = previousBuildCommit
            ? previousBuildSource
            : 'Jenkins checkout changelog'
    }
    int totalChanges = changes.size()
    int hiddenCount = Math.max(0, totalChanges - maximumChanges)

    echo(
        'Telegram commit summary: source=' + previousBuildSource +
        ', total=' + totalChanges +
        ', shown=' + Math.min(totalChanges, maximumChanges) +
        ', hidden=' + hiddenCount + '.'
    )
    if (hasValidPreviousCommit && !changes) {
        return '- No new commits since the previous successful build.'
    }

    // Telegram giữ message ngắn; toàn bộ nội dung mỗi commit được gộp về
    // một dòng để markdown checklist trong body không làm vỡ bố cục.

    if (changes.size() > maximumChanges) {
        def limitedChanges = []

        limitedChanges.addAll(changes[0..(maximumChanges - 1)])
        limitedChanges << "- ... and ${hiddenCount} more commit(s)."
        changes = limitedChanges
    }

    return changes.join('\n')
}

def collectJenkinsChangeSets() {
    def changes = []

    try {
        currentBuild.changeSets?.each { changeSet ->
            changeSet.items?.each { entry ->
                def commit = entry.commitId?.toString()?.trim()
                def author = entry.author?.fullName?.toString()?.trim()
                def message = formatCommitMessage(entry.msg?.toString())

                if (commit && message) {
                    changes << '- ' + commit.take(7) + ' - ' +
                        (author ?: 'unknown') + ': ' + message
                }
            }
        }
    } catch (Exception exception) {
        echo(
            'Could not read the Jenkins checkout changelog: ' +
            exception.message
        )
    }

    return changes
}

def formatCommitMessage(String message) {
    if (!message?.trim()) {
        return ''
    }

    return message
        .readLines()
        .collect { it.trim().replaceFirst(/^[-*•]\\s+/, '') }
        .findAll { it }
        .join(' • ')
}

def readPreviousSuccessfulBuildCommit() {
    try {
        def previousSuccessfulBuild = currentBuild.previousSuccessfulBuild
        def buildVariables = previousSuccessfulBuild?.getBuildVariables()
        def commit = buildVariables?.get('PEARZCI_GIT_COMMIT')?.trim()

        if (!commit) {
            // Compatibility with successful builds created before v0.5.4.
            commit = buildVariables?.get('GIT_COMMIT')?.trim()
        }

        if (commit) {
            return commit
        }
    } catch (Exception exception) {
        echo(
            'Could not read the previous successful build variables: ' +
            exception.message
        )
    }

    return ''
}

def isAncestorCommit(String commit) {
    if (!commit?.trim()) {
        return false
    }

    withEnv(["PREVIOUS_BUILD_COMMIT=" + commit.trim()]) {
        if (isUnix()) {
            return sh(
                script: '''
                    git rev-parse --verify \
                        "$PREVIOUS_BUILD_COMMIT^{commit}" >/dev/null 2>&1 &&
                    git merge-base --is-ancestor \
                        "$PREVIOUS_BUILD_COMMIT" HEAD
                ''',
                returnStatus: true
            ) == 0
        }

        return bat(
            script: '''
                @echo off
                git rev-parse --verify "%PREVIOUS_BUILD_COMMIT%^{commit}" >nul 2>&1
                if errorlevel 1 exit /b 1
                git merge-base --is-ancestor "%PREVIOUS_BUILD_COMMIT%" HEAD
            ''',
            returnStatus: true
        ) == 0
    }
}

def renderTelegramTemplate(Map values) {
    def template = libraryResource(
        'com/pearz/ci/telegram-message-template.txt'
    )
    def outputLines = []

    template.readLines().each { sourceLine ->
        def renderedLine = sourceLine
        def omitLine = false

        values.each { placeholder, value ->
            def token = "{{${placeholder}}}"

            if (sourceLine.contains(token)) {
                def replacement = value?.toString()?.trim()

                if (!replacement) {
                    omitLine = true
                } else {
                    renderedLine = renderedLine.replace(token, replacement)
                }
            }
        }

        if (
            renderedLine.contains('{{') &&
            renderedLine.contains('}}')
        ) {
            omitLine = true
        }

        if (!omitLine) {
            if (renderedLine.contains('\n')) {
                outputLines.addAll(renderedLine.readLines())
            } else {
                outputLines << renderedLine
            }
        }
    }

    def compactLines = []

    outputLines.each { line ->
        if (line || !compactLines || compactLines[-1]) {
            compactLines << line
        }
    }

    while (compactLines && !compactLines[-1]) {
        compactLines.remove(compactLines.size() - 1)
    }

    return compactLines.join('\n')
}

def formatDurationMillis(Object value) {
    if (!value?.toString()?.trim()) {
        return ''
    }

    try {
        long totalSeconds = Math.max(
            0L,
            Math.round(value.toString().toLong() / 1000.0d)
        )
        long hours = totalSeconds.intdiv(3600)
        long minutes = totalSeconds.intdiv(60) % 60
        long seconds = totalSeconds % 60
        def parts = []

        if (hours > 0) {
            parts << "${hours}h"
        }

        if (minutes > 0 || hours > 0) {
            parts << "${minutes}m"
        }

        parts << "${seconds}s"
        return parts.join(' ')
    } catch (Exception ignored) {
        return ''
    }
}

def formatBytes(Object value) {
    if (!value?.toString()?.trim()) {
        return ''
    }

    try {
        double size = value.toString().toLong()
        def units = ['B', 'KB', 'MB', 'GB', 'TB']
        int unitIndex = 0

        while (size >= 1024.0d && unitIndex < units.size() - 1) {
            size /= 1024.0d
            unitIndex++
        }

        return String.format(
            java.util.Locale.US,
            unitIndex == 0 ? '%.0f %s' : '%.2f %s',
            size,
            units[unitIndex]
        )
    } catch (Exception ignored) {
        return ''
    }
}
def normalizeGitBranch(Object branchValue) {
    def branch = branchValue?.toString()?.trim() ?: 'master'
    branch = branch.replaceFirst(/^refs\/heads\//, '')
    branch = branch.replaceFirst(/^origin\//, '')
    branch = branch.replaceFirst(/^\*\//, '')
    return branch
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

def readNextAabVersionCode() {
    def stateFile = getAabVersionCodeStateFile()

    if (!stateFile.exists()) {
        return 1
    }

    def value = stateFile.getText('UTF-8').trim()
    if (!(value ==~ /[1-9][0-9]*/)) {
        error(
            "Invalid AAB version-code counter at ${stateFile}: '${value}'. " +
            'Fix the file to a positive integer before building another AAB.'
        )
    }

    try {
        return value.toInteger()
    } catch (NumberFormatException ignored) {
        error(
            "AAB version-code counter at ${stateFile} is outside Android's " +
            'supported integer range.'
        )
    }
}

def saveNextAabVersionCode(int usedVersionCode) {
    if (usedVersionCode == Integer.MAX_VALUE) {
        error('AAB version code has reached Android\'s maximum integer value.')
    }

    def stateFile = getAabVersionCodeStateFile()
    def temporaryFile = new File(
        stateFile.parentFile,
        ".${stateFile.name}.${env.BUILD_TAG}.tmp"
    )

    temporaryFile.setText("${usedVersionCode + 1}\n", 'UTF-8')

    if (stateFile.exists() && !stateFile.delete()) {
        temporaryFile.delete()
        error("Unable to update AAB version-code counter at ${stateFile}.")
    }

    if (!temporaryFile.renameTo(stateFile)) {
        temporaryFile.delete()
        error("Unable to save AAB version-code counter at ${stateFile}.")
    }

    echo "Next AAB version code: ${usedVersionCode + 1}"
}

def getAabVersionCodeStateFile() {
    // Job root lives on the Jenkins controller, unlike a workspace it is not
    // removed by CLEAN_WORKSPACE and is shared by Windows/macOS agents.
    def jobRoot = currentBuild.rawBuild.parent.rootDir
    return new File(jobRoot, 'pearz-ci-aab-version-code.txt')
}
