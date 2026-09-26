def call(Map config = [:]) {
    def mobilePlatform = config.get('mobilePlatform', 'Android')
        .toString().trim()
    boolean isIos = mobilePlatform.equalsIgnoreCase('iOS')
    boolean isAndroid = !isIos
    def iosBuildToDevice = config.get(
        'iosBuildToDevice', params.IOS_BUILD_TO_DEVICE ?: false
    ).toString().trim().toBoolean()
    // Mặc định tắt: một job iOS đang chạy sẽ không tự nhiên bắt đầu đẩy build
    // lên App Store Connect chỉ vì nâng phiên bản thư viện.
    def uploadToTestFlight = config.get(
        'uploadToTestFlight', params.UPLOAD_TO_TESTFLIGHT ?: false
    ).toString().trim().toBoolean()
    def appStoreConnectApiKeyCredentialsId = config.get(
        'appStoreConnectApiKeyCredentialsId',
        'appstore-connect-api-key'
    ).toString().trim()
    // Key ID và Issuer ID là định danh, không phải bí mật, nên nhận thẳng từ
    // config/tham số job thay vì bắt tạo thêm hai credential.
    def appStoreConnectKeyId = config.get(
        'appStoreConnectKeyId', params.APP_STORE_CONNECT_KEY_ID ?: ''
    ).toString().trim()
    def appStoreConnectIssuerId = config.get(
        'appStoreConnectIssuerId', params.APP_STORE_CONNECT_ISSUER_ID ?: ''
    ).toString().trim()
    // iOS bắt buộc chạy trên macOS; Android giữ nguyên "any" như trước để
    // không đổi cách chọn node của các job Android đang chạy. Nhãn rỗng
    // tương đương `agent any`.
    // Mặc định tắt: chỉ cài APK lên máy Android cắm vào agent khi job bật rõ.
    def androidInstallToDevice = config.get(
        'androidInstallToDevice', params.ANDROID_INSTALL_TO_DEVICE ?: false
    ).toString().trim().toBoolean()
    // Để trống thì cài lên mọi máy đang ở trạng thái "device" trong
    // `adb devices`; điền serial (cách nhau bằng dấu cách/phẩy) để chọn máy.
    def androidDeviceSerial = config.get(
        'androidDeviceSerial', params.ANDROID_DEVICE_SERIAL ?: ''
    ).toString().trim()
    def configuredAdbExe = config.get('adbExe', '').toString().trim()
    def macAgentLabel = config.get('macAgentLabel', 'macos').toString().trim()
    if (isIos && !macAgentLabel) {
        throw new IllegalArgumentException(
            'macAgentLabel must not be empty for iOS builds.'
        )
    }
    def agentLabelExpression = isIos ? macAgentLabel : ''
    def pearzCiVersion = readPearzCiVersion()
    def repositoryUrl = config.get(
        'repositoryUrl',
        params.PROJECT_REPOSITORY_URL ?: ''
    ).toString().trim()
    def repositoryCredentialsId = config.get(
        'repositoryCredentialsId',
        'github-ssh'
    ).toString().trim()
    // Một repository có thể chứa project Unity trong thư mục con. Mặc định
    // vẫn là workspace để các job hiện có không đổi hành vi.
    def unityProjectPath = config.get(
        'unityProjectPath',
        params.UNITY_PROJECT_PATH ?: ''
    ).toString().trim()
    if (unityProjectPath) {
        if (
            unityProjectPath.startsWith('/') ||
            unityProjectPath ==~ /^[A-Za-z]:.*/
        ) {
            throw new IllegalArgumentException(
                'unityProjectPath must be a relative path inside the repository.'
            )
        }
        unityProjectPath = unityProjectPath.replace('\\', '/')
            .replaceAll('^/+|/+$', '')
        if (
            !unityProjectPath ||
            unityProjectPath.tokenize('/').contains('..')
        ) {
            throw new IllegalArgumentException(
                'unityProjectPath must be a relative path inside the repository.'
            )
        }
    }
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
    def webhookRepositoryJsonPath = config.get(
        'webhookRepositoryJsonPath',
        '$.repository.full_name'
    ).toString().trim()
    def webhookProviderName = config.get(
        'webhookProviderName',
        'GitHub'
    ).toString().trim()
    if (!webhookRepositoryJsonPath) {
        throw new IllegalArgumentException(
            'webhookRepositoryJsonPath must not be empty.'
        )
    }
    if (!webhookProviderName) {
        throw new IllegalArgumentException(
            'webhookProviderName must not be empty.'
        )
    }
    def webhookFilterExpression = webhookRepository
        ? '^' + regexEscape(webhookRepository) +
            ' refs/heads/' + regexEscape(webhookBranch) + '$'
        : '^refs/heads/' + regexEscape(webhookBranch) + '$'

    pipeline {
        agent { label "${agentLabelExpression}" }

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
                        value: webhookRepositoryJsonPath
                    ],
                    [
                        key: 'PEARZ_WEBHOOK_REF',
                        value: '$.ref'
                    ]
                ],
                causeString:
                    "Triggered by ${webhookProviderName} push: " +
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
            // Cô lập Gradle theo từng job (không theo workspace). Nhiều job
            // build song song trên cùng agent vốn dùng chung ~/.gradle, nên
            // khi một job gọi `gradle --stop` (Unity gọi lúc dọn dẹp cuối
            // build) sẽ giết luôn daemon đang minify R8 của job khác, làm
            // build hỏng với lỗi "Gradle build daemon has been stopped: stop
            // command received". Mỗi job có GRADLE_USER_HOME riêng thì lệnh
            // stop không ảnh hưởng chéo. Trước đây cache đặt trong
            // $WORKSPACE/.gradle nên `CLEAN_WORKSPACE=true` hoặc Jenkins đổi
            // workspace (`@2`, `@tmp`) là mất cache, khiến build kế tiếp cold
            // từ đầu (tải lại toàn bộ AGP/Kotlin/deps, warm-up R8/AAPT2).
            // Đặt ngoài workspace, gắn với JOB_BASE_NAME, để cache giữ nguyên
            // qua các lần checkout mà vẫn cô lập giữa các job. Unity spawn
            // tiến trình Gradle kế thừa biến môi trường này.
            GRADLE_USER_HOME = "${env.HOME ?: env.USERPROFILE}/.gradle-jenkins/${env.JOB_BASE_NAME}"
            PEARZ_CI_VERSION = "${pearzCiVersion}"
            DRIVE_REMOTE = "${driveRemote}"
            DRIVE_ROOT = "${driveRoot}"
            WINDOWS_RCLONE_EXE = "${windowsRcloneExe}"
            MAC_RCLONE_EXE = "${macRcloneExe}"
            WINDOWS_UNITY_HUB_ROOT = "${windowsUnityHubRoot}"
            MAC_UNITY_HUB_ROOT = "${macUnityHubRoot}"
            IOS_BUILD_TO_DEVICE = "${iosBuildToDevice}"
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
                        env.UNITY_PROJECT_PATH = unityProjectPath
                            ? "${env.WORKSPACE}/${unityProjectPath}"
                            : env.WORKSPACE
                        env.UNITY_VERSION = readUnityEditorVersion(
                            env.UNITY_PROJECT_PATH
                        )

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

                        // Artifact name precedence:
                        // 1. Explicit Jenkins PRODUCT_NAME override.
                        // 2. Unity Project Settings productName.
                        // Do not fall back to JOB_BASE_NAME: the job name is
                        // an operational label and may differ from the app.
                        def configuredProductName = params.PRODUCT_NAME
                            ?.toString()?.trim() ?: ''
                        def projectProductName = configuredProductName
                            ? ''
                            : readUnityProductName(env.UNITY_PROJECT_PATH)
                        def outputName = configuredProductName ?: projectProductName

                        if (!outputName) {
                            error(
                                'PRODUCT_NAME is empty and Unity Project ' +
                                'Settings has no productName. Set PRODUCT_NAME ' +
                                'or define productName in ProjectSettings.asset.'
                            )
                        }

                        outputName = outputName.replaceAll(
                            '[<>:"\\\\/|?*]',
                            '_'
                        )

                        if (!outputName.trim()) {
                            error(
                                'The resolved product name contains only ' +
                                'invalid filename characters.'
                            )
                        }

                        env.PEARZ_PRODUCT_NAME = outputName
                        env.PEARZ_PRODUCT_NAME_SOURCE = configuredProductName
                            ? 'Jenkins PRODUCT_NAME'
                            : 'Unity Project Settings productName'

                        // APP_VERSION bắt buộc: nó quyết định thư mục Drive
                        // của build, nên để trống sẽ không biết đặt artifact
                        // vào đâu. Chỉ gồm số và dấu chấm để hợp lệ cả với
                        // CFBundleShortVersionString của iOS.
                        def appVersion = params.APP_VERSION?.toString()?.trim() ?: ''
                        if (!(appVersion ==~ /[0-9]+(\.[0-9]+)*/)) {
                            error(
                                'APP_VERSION is required and must contain only ' +
                                "digits and dots, for example 1.0.0 (got '${appVersion}')."
                            )
                        }
                        env.PEARZ_APP_VERSION = appVersion

                        def artifactBuildNumber = env.BUILD_NUMBER
                        env.ARTIFACT_BUILD_NUMBER = artifactBuildNumber

                        env.OUTPUT_EXTENSION = isIos
                            ? 'ipa'
                            : (params.BUILD_APP_BUNDLE ? 'aab' : 'apk')
                        // Giữ một artifact Android duy nhất trong workspace để
                        // mỗi APK/AAB mới ghi đè file trước. Jenkins archive
                        // lưu riêng theo từng build nên vẫn còn lịch sử.
                        env.OUTPUT_FILE_NAME = !isIos
                            ? "${outputName}.${env.OUTPUT_EXTENSION}"
                            : "${outputName}-${artifactBuildNumber}.${env.OUTPUT_EXTENSION}"
                        // Trên Drive tên file không kèm số build: build lại
                        // cùng version sẽ ghi đè APK/AAB/IPA cũ thay vì đẻ
                        // thêm file. Lịch sử từng build vẫn nằm trong Jenkins
                        // archive.
                        env.DRIVE_OUTPUT_FILE_NAME =
                            "${outputName}.${env.OUTPUT_EXTENSION}"
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
                            env.DERIVED_DATA_PATH = "${env.WORKSPACE}/Builds/iOS/DerivedData"
                        }
                        env.BUILD_VERSION = "${appVersion}-${artifactBuildNumber}"
                        // Mỗi version một thư mục, APK/AAB/IPA dùng chung:
                        // JenkinsBuild/<Job>/1.0.0/MyGame.apk. Đổi APP_VERSION
                        // thì tạo thư mục mới; build lại version cũ thì ghi đè.
                        // Build info và mapping có hậu tố loại artifact để APK
                        // và AAB cùng version không đè lên nhau.
                        env.DRIVE_DIRECTORY =
                            "${env.DRIVE_REMOTE}:${env.DRIVE_ROOT}/" +
                            "${env.JOB_BASE_NAME}/${appVersion}"
                        env.DRIVE_FILE_PATH =
                            "${env.DRIVE_DIRECTORY}/${env.DRIVE_OUTPUT_FILE_NAME}"
                        env.DRIVE_BUILD_INFO_PATH =
                            "${env.DRIVE_DIRECTORY}/${outputName}_${env.OUTPUT_EXTENSION.toUpperCase()}_BUILD_INFO.txt"
                        env.DRIVE_MAPPING_PATH =
                            "${env.DRIVE_DIRECTORY}/mapping-${env.OUTPUT_EXTENSION}.txt"

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

            // 'Show Parameters' đã gộp vào đây để bớt cột Stage View: in
            // tham số trước, rồi validate Unity ngay trong cùng một stage.
            stage('Validate Unity') {
                steps {
                    echo "NODE_OS = ${env.NODE_OS}"
                    echo "PRODUCT_NAME = ${env.PEARZ_PRODUCT_NAME} (${env.PEARZ_PRODUCT_NAME_SOURCE})"
                    echo "GIT_BRANCH = ${params.GIT_BRANCH}"
                    echo "UNITY_VERSION = ${env.UNITY_VERSION}"
                    echo "OUTPUT_PATH = ${env.OUTPUT_PATH}"
                    echo "DRIVE_FILE_PATH = ${env.DRIVE_FILE_PATH}"
                    echo "GIT_COMMIT_SHORT = ${env.GIT_COMMIT_SHORT}"

                    script {
                        if (isIos) {
                            echo "XCODE_CONFIGURATION = ${params.XCODE_CONFIGURATION}"
                            echo "IOS_BUILD_TO_DEVICE = ${env.IOS_BUILD_TO_DEVICE}"
                            echo "UPLOAD_TO_TESTFLIGHT = ${uploadToTestFlight}"
                        }

                        def telegramConfig =
                            "${params.TELEGRAM_CHANNEL ?: ''}".trim()
                        def telegramTargets = telegramConfig
                            ? telegramConfig
                                .split(';')
                                .count { it.trim() }
                            : 0

                        echo "TELEGRAM_CHANNEL targets = ${telegramTargets}"

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
                        // APP_VERSION (bắt buộc) là base version; CI_BUILD_NUMBER
                        // luôn được nối vào để tester nhận biết chính xác bản
                        // build.
                        def ciAppVersion = env.PEARZ_APP_VERSION
                        def androidVersionCode = '1'
                        // ANDROID_VERSION_CODE tuỳ chọn: điền thì dùng đúng số
                        // đó cho cả APK lẫn AAB thay vì code 1 / bộ đếm AAB.
                        def requestedVersionCode =
                            params.ANDROID_VERSION_CODE?.toString()?.trim() ?: ''

                        if (requestedVersionCode) {
                            if (!(requestedVersionCode ==~ /[1-9][0-9]{0,9}/) ||
                                requestedVersionCode.toLong() > Integer.MAX_VALUE) {
                                error(
                                    'ANDROID_VERSION_CODE must be a positive ' +
                                    "integer (got '${requestedVersionCode}')."
                                )
                            }
                            androidVersionCode = requestedVersionCode
                            echo "Android version code from ANDROID_VERSION_CODE: ${androidVersionCode}"

                            // AAB điền tay mà vượt bộ đếm thì đẩy bộ đếm lên
                            // theo, để lần sau để trống không cấp lại số cũ
                            // mà Google Play đã nhận.
                            if (params.BUILD_APP_BUNDLE?.toString()?.toBoolean() &&
                                requestedVersionCode.toInteger() >= readNextAabVersionCode()) {
                                env.AAB_VERSION_CODE = androidVersionCode
                            }
                        } else if (params.BUILD_APP_BUNDLE?.toString()?.toBoolean()) {
                            androidVersionCode = readNextAabVersionCode().toString()
                            env.AAB_VERSION_CODE = androidVersionCode
                            echo(
                                'AAB version code reserved from the ' +
                                "per-job counter: ${androidVersionCode}"
                            )
                        }

                        echo "Android APP_VERSION base passed to Unity: ${ciAppVersion}"
                        echo "Android CI build number passed to Unity: ${env.ARTIFACT_BUILD_NUMBER}"
                        echo "Android version code passed to Unity: ${androidVersionCode}"

                        try {
                            // APK để version code cố định = 1. Tester nhận biết
                            // bản đang cài qua version name (BUILD_VERSION), không
                            // cần code tăng dần; code cố định còn cho cài đè qua lại
                            // giữa các bản APK mà không bị Android chặn downgrade.
                            // AAB dùng bộ đếm riêng, không bị các APK test xen kẽ
                            // làm nhảy version code trên Google Play.
                            withEnv([
                                "OUTPUT_PATH=${env.OUTPUT_PATH}",
                                "APP_VERSION=${ciAppVersion}",
                                "CI_BUILD_NUMBER=${env.ARTIFACT_BUILD_NUMBER}",
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
                                            -projectPath "$UNITY_PROJECT_PATH" \
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
                                            -projectPath "%UNITY_PROJECT_PATH%" ^
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
                        // CFBundleVersion phải tăng sau mỗi lần nộp, nếu không
                        // App Store Connect từ chối vì trùng build. Param để
                        // trống thì Unity giữ nguyên số của project, nên lấy
                        // BUILD_NUMBER của Jenkins làm mặc định.
                        // APP_VERSION thì không ép: CFBundleShortVersionString
                        // chỉ được gồm số và dấu chấm, mà BUILD_VERSION có
                        // dạng 1.0.0-42 nên sẽ bị Apple loại.
                        def iosBuildNumber =
                            params.IOS_BUILD_NUMBER?.toString()?.trim() ?:
                            env.ARTIFACT_BUILD_NUMBER
                        try {
                            withEnv([
                                "OUTPUT_PATH=${env.IOS_PROJECT_PATH}",
                                "PRODUCT_NAME=${env.PEARZ_PRODUCT_NAME ?: ''}",
                                'BUNDLE_IDENTIFIER=',
                                "SCRIPTING_DEFINE_SYMBOLS=${params.SCRIPTING_DEFINE_SYMBOLS ?: ''}",
                                "APP_VERSION=${env.PEARZ_APP_VERSION}",
                                "IOS_BUILD_NUMBER=${iosBuildNumber}",
                                "IOS_BUILD_TO_DEVICE=${iosBuildToDevice}",
                                "IL2CPP_CODE_GENERATION=${params.IL2CPP_CODE_GENERATION ?: ''}",
                                "MANAGED_STRIPPING_LEVEL=${params.MANAGED_STRIPPING_LEVEL ?: ''}",
                                "STRIP_ENGINE_CODE=${params.STRIP_ENGINE_CODE}"
                            ]) {
                                sh '''
                                    set +e
                                    # BuildEntry đọc marker này để gỡ capability
                                    # In-App Purchase khi export cho device build.
                                    device_build_marker="$WORKSPACE/.pearz-ci-ios-device-build"
                                    if [ "$IOS_BUILD_TO_DEVICE" = 'true' ]; then
                                        : > "$device_build_marker"
                                    else
                                        rm -f "$device_build_marker"
                                    fi
                                    trap 'rm -f "$device_build_marker"' EXIT
                                    "$UNITY_EXE" -batchmode -quit \\
                                        -projectPath "$UNITY_PROJECT_PATH" \\
                                        -buildTarget iOS \\
                                        -executeMethod Pearz.CI.BuildEntry.BuildIOS \\
                                        -logFile "$BUILD_LOG_PATH"
                                    result=$?
                                    [ ! -f "$BUILD_LOG_PATH" ] || cat "$BUILD_LOG_PATH"
                                    exit "$result"
                                '''
                            }

                            readIosVersionFromXcodeProject()
                        } finally {
                            env.BUILD_TIME_MILLIS = (System.currentTimeMillis() - startedAt).toString()
                        }

                        // 'Remove duplicate AppLovin SPM dependency' đã gộp vào
                        // đây để bớt cột Stage View: chạy ngay sau khi Unity sinh
                        // Xcode project, cùng điều kiện iOS. Script Ruby nằm ở
                        // resources thay vì nhúng base64 vào Groovy — bản nhúng
                        // từng tồn tại hai bản lệch nhau một ký tự mà không ai
                        // đọc ra được, vì base64 thì mắt thường không diff nổi.
                        writeFile(
                            file: 'remove-applovin-spm.rb',
                            encoding: 'UTF-8',
                            text: libraryResource(
                                'com/pearz/ci/remove-applovin-spm.rb'
                            )
                        )
                        sh '''
                            set -eu
                            xcodeproj_path="$IOS_PROJECT_PATH/Unity-iPhone.xcodeproj"
                            pbxproj_path="$xcodeproj_path/project.pbxproj"
                            pods_applovin_path="$IOS_PROJECT_PATH/Pods/AppLovinSDK"

                            if [ ! -f "$pbxproj_path" ] || [ ! -d "$pods_applovin_path" ]; then
                                echo 'AppLovin SPM cleanup skipped: CocoaPods AppLovinSDK was not found.'
                                exit 0
                            fi
                            if ! grep -Fiq 'applovin-max-swift-package' "$pbxproj_path"; then
                                echo 'AppLovin SPM cleanup skipped: no AppLovin Swift package reference found.'
                                exit 0
                            fi

                            ruby "$WORKSPACE/remove-applovin-spm.rb" "$xcodeproj_path"

                            if grep -Fiq 'applovin-max-swift-package' "$pbxproj_path"; then
                                echo 'ERROR: AppLovin Swift package reference remains after cleanup.'
                                exit 1
                            fi
                            echo 'Removed duplicate AppLovin Swift Package Manager dependency; using CocoaPods AppLovinSDK.'
                        '''
                    }
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
                        if (exportOptionsPath && !fileExists(exportOptionsPath)) {
                            error("IOS_EXPORT_OPTIONS_PLIST_PATH is not readable: ${exportOptionsPath}")
                        }
                        if (!exportOptionsPath) {
                            // Automatic signing resolves the provisioning profile from
                            // the project's bundle ID. This generic export file is safe
                            // to share across every app in the same Apple organization.
                            exportOptionsPath = 'PearzCI-ExportOptions-Automatic.plist'
                            writeFile(
                                file: exportOptionsPath,
                                encoding: 'UTF-8',
                                text: '''<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>method</key>
    <string>app-store</string>
    <key>signingStyle</key>
    <string>automatic</string>
</dict>
</plist>
'''
                            )
                            echo "No IOS_EXPORT_OPTIONS_PLIST_PATH set; using generated automatic-signing options: ${exportOptionsPath}"
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

            stage('Build and Install on iOS Device') {
                when { expression { isIos && iosBuildToDevice } }
                options { timeout(time: 45, unit: 'MINUTES') }
                steps {
                    script {
                        def deviceUdid = config.get(
                            'iosDeviceUdid', params.IOS_DEVICE_UDID ?: ''
                        ).toString().trim()
                        // Device build cần profile DEVELOPMENT, còn export IPA
                        // cần profile DISTRIBUTION — hai loại khác nhau. Nên có
                        // key riêng iosDeviceProvisioningProfileSpecifier để set
                        // sẵn cả hai trong script; nếu không đặt thì lùi về key
                        // chung iosProvisioningProfileSpecifier rồi tới param.
                        // Cả ba đều trống thì để rỗng: sh bên dưới tự suy tên
                        // profile Xcode-managed từ bundle id, nên job thường
                        // không cần khai báo profile ở đâu cả.
                        def profileSpecifier = config.get(
                            'iosDeviceProvisioningProfileSpecifier',
                            config.get(
                                'iosProvisioningProfileSpecifier',
                                params.IOS_PROVISIONING_PROFILE_SPECIFIER ?: ''
                            )
                        ).toString().trim()
                        def destinationTimeout = config.get(
                            'iosDestinationTimeoutSeconds', 300
                        ).toString().trim()
                        // Device build ký bằng 'Apple Development' + profile đã
                        // cài và luôn ở cấu hình Debug; XCODE_CONFIGURATION chỉ
                        // áp dụng cho export IPA. IOS_DEVELOPMENT_TEAM cũng không
                        // được dùng trong sh của device build (chỉ export IPA
                        // cần), nên không còn bắt buộc điền cho device.
                        def xcodeConfiguration = 'Debug'

                        if (!(destinationTimeout ==~ /[1-9][0-9]*/)) {
                            error(
                                'iosDestinationTimeoutSeconds must be a ' +
                                'positive integer, for example 300.'
                            )
                        }

                        // IOS_DEVICE_UDID để trống thì sh bên dưới tự dò iPhone
                        // đang cắm; chỉ khi 0 hoặc >1 máy mới cần điền tay.

                        withEnv([
                            "IOS_DEVICE_UDID=${deviceUdid}",
                            "XCODE_CONFIGURATION=${xcodeConfiguration}",
                            "IOS_PROFILE_SPECIFIER=${profileSpecifier}",
                            "IOS_DESTINATION_TIMEOUT=${destinationTimeout}"
                        ]) {
                            sh '''
                                set -eu
                                [ -d "$IOS_PROJECT_PATH/Unity-iPhone.xcodeproj" ] || {
                                    echo "ERROR: Unity-iPhone.xcodeproj was not exported."
                                    exit 1
                                }

                                # IOS_DEVICE_UDID trống: tự dò iPhone đã kết nối.
                                # Ưu tiên máy cắm dây, đã pair. Một số phiên bản
                                # CoreDevice mới đưa các trường này vào properties
                                # (thay cho các trường *Properties cũ). Hỗ trợ cả
                                # hai schema, và fallback State=connected khi các
                                # chi tiết pair/transport không có. Đúng 1 máy thì
                                # dùng luôn; 0 hoặc nhiều máy thì báo lỗi kèm danh
                                # sách để chỉ định UDID.
                                # tunnelState không dùng để lọc vì máy cắm dây vẫn
                                # hiện "disconnected" khi không debug. Lấy
                                # hardwareProperties.udid (đúng định dạng
                                # xcodebuild -destination id= và devicectl).
                                if [ -z "${IOS_DEVICE_UDID:-}" ]; then
                                    echo 'IOS_DEVICE_UDID trống — tự dò thiết bị đang cắm...'
                                    dev_json="$(mktemp -t devicectl-devices)"
                                    if ! xcrun devicectl list devices --json-output "$dev_json" >/dev/null 2>&1; then
                                        rm -f "$dev_json"
                                        echo 'ERROR: không chạy được "xcrun devicectl list devices".'
                                        exit 1
                                    fi
                                    candidates="$(ruby -rjson -e '
                                        data = (JSON.parse(File.read(ARGV[0])) rescue nil)
                                        exit(0) unless data
                                        devices = data.dig("result", "devices") || []
                                        strict = devices.select do |dev|
                                          properties = dev["properties"] || {}
                                          hw = properties["hardware"] || dev["hardwareProperties"] || {}
                                          cp = properties["connection"] || dev["connectionProperties"] || {}
                                          hw["reality"] == "physical" &&
                                            cp["pairingState"] == "paired" &&
                                            cp["transportType"] == "wired"
                                        end
                                        # Xcode/CoreDevice can omit the two connection
                                        # fields after a newly trusted device is attached.
                                        # A physical device reported as connected is still a
                                        # valid devicectl/xcodebuild target.
                                        connected = devices.select do |dev|
                                          properties = dev["properties"] || {}
                                          hw = properties["hardware"] || dev["hardwareProperties"] || {}
                                          cp = properties["connection"] || dev["connectionProperties"] || {}
                                          state = cp["state"] || dev["state"] || dev.dig("deviceProperties", "state")
                                          hw["reality"] == "physical" && state == "connected"
                                        end
                                        (strict.empty? ? connected : strict).each do |dev|
                                          properties = dev["properties"] || {}
                                          hw = properties["hardware"] || dev["hardwareProperties"] || {}
                                          state = properties["state"] || dev["deviceProperties"] || {}
                                          name = state["name"]
                                          puts "#{hw["udid"]}\\t#{name} (#{hw["marketingName"]})"
                                        end
                                    ' "$dev_json")"
                                    rm -f "$dev_json"
                                    count="$(echo "$candidates" | awk 'NF' | wc -l | tr -d ' ')"
                                    if [ "$count" -eq 0 ]; then
                                        echo 'ERROR: không thấy iPhone nào cắm dây và đã pair.'
                                        echo 'Cắm máy + Trust trên máy, hoặc điền IOS_DEVICE_UDID. Danh sách hiện có:'
                                        xcrun devicectl list devices || true
                                        exit 1
                                    fi
                                    if [ "$count" -gt 1 ]; then
                                        echo 'ERROR: có nhiều máy cắm cùng lúc — điền IOS_DEVICE_UDID một trong các máy sau:'
                                        echo "$candidates"
                                        exit 1
                                    fi
                                    IOS_DEVICE_UDID="$(echo "$candidates" | awk 'NF' | head -n1 | cut -f1)"
                                    echo "Đã tự dò UDID: $IOS_DEVICE_UDID"
                                fi

                                rm -rf "$DERIVED_DATA_PATH"
                                echo 'iOS device bundle identifier is sourced from Unity Project Settings.'

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

                                # Mặc định của Xcode chỉ chờ destination 30 giây. Máy đang
                                # ở giữa bước "Preparing device for development" - hay gặp
                                # sau khi cập nhật iOS - thì quá ngắn, build hỏng dù một
                                # phút sau máy đã sẵn sàng. Chờ lâu hơn không cứu được
                                # ghép đôi hỏng thật, chỉ bỏ qua lúc máy chậm sẵn sàng.
                                run_xcodebuild_unsigned() {
                                    xcodebuild_args="-scheme Unity-iPhone -configuration $XCODE_CONFIGURATION -destination id=$IOS_DEVICE_UDID -destination-timeout $IOS_DESTINATION_TIMEOUT -derivedDataPath $DERIVED_DATA_PATH CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY='' build"
                                    if [ -d "$IOS_PROJECT_PATH/Unity-iPhone.xcworkspace" ]; then
                                        xcodebuild -workspace "$IOS_PROJECT_PATH/Unity-iPhone.xcworkspace" $xcodebuild_args > "$XCODEBUILD_LOG_PATH" 2>&1
                                    else
                                        xcodebuild -project "$IOS_PROJECT_PATH/Unity-iPhone.xcodeproj" $xcodebuild_args > "$XCODEBUILD_LOG_PATH" 2>&1
                                    fi
                                }

                                set +e
                                # Để trống cũng được: sau khi build xong sẽ tự suy
                                # tên profile development từ bundle id của app.
                                profile_specifier="${IOS_PROFILE_SPECIFIER:-}"

                                # Build unsigned first. Xcode 26 performs provisioning validation
                                # before it builds and rejects Personal Team profiles for Unity IAP,
                                # even after the capability is removed. We then sign the built app
                                # directly with the installed development profile.
                                echo 'Building unsigned iOS app for direct development signing.'
                                run_xcodebuild_unsigned
                                result=$?
                                cat "$XCODEBUILD_LOG_PATH"
                                [ "$result" -eq 0 ] || exit "$result"

                                app_path="$(find "$DERIVED_DATA_PATH/Build/Products" \
                                    -type d -name '*.app' -print -quit)"
                                [ -d "$app_path" ] || {
                                    echo 'ERROR: Xcode did not produce an iOS .app bundle.'
                                    exit 1
                                }
                                echo "Built iOS app: $app_path"

                                # Chưa chỉ định profile thì tự suy tên profile
                                # development Xcode-managed từ bundle id của app —
                                # luôn có dạng "iOS Team Provisioning Profile:
                                # <bundle id>". Bundle id lấy từ Info.plist của .app
                                # vừa build (đã resolve, đúng cho từng game), nên mỗi
                                # job khỏi khai báo profile trong pipeline script.
                                if [ -z "$profile_specifier" ]; then
                                    bundle_id="$(/usr/libexec/PlistBuddy \\
                                        -c 'Print :CFBundleIdentifier' \\
                                        "$app_path/Info.plist" 2>/dev/null || true)"
                                    [ -n "$bundle_id" ] || {
                                        echo 'ERROR: không đọc được CFBundleIdentifier để tự suy provisioning profile.'
                                        echo 'Khắc phục: điền iosDeviceProvisioningProfileSpecifier trong pipeline script hoặc param IOS_PROVISIONING_PROFILE_SPECIFIER.'
                                        exit 2
                                    }
                                    profile_specifier="iOS Team Provisioning Profile: $bundle_id"
                                    echo "Tự suy provisioning profile theo bundle id: $profile_specifier"
                                fi

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
                                    echo 'Với game lần đầu build device trên máy Mac này: mở Xcode project export ra, chọn Team + Automatically manage signing rồi build lên iPhone 1 lần để Xcode sinh và cài profile. Sau đó CI chạy tự động.'
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
                                [ "$result" -eq 0 ] && \\
                                    echo 'iOS app installed successfully on the connected device.'
                                exit "$result"
                            '''
                        }
                    }
                }
            }

            // 'Read Build Metadata' và 'Archive Artifact' đã gộp vào đây để bớt
            // cột Stage View. Read Build Metadata vốn chỉ chạy cho Android nên
            // giữ nguyên bằng guard isAndroid bên trong.
            stage('Verify & Archive Artifact') {
                when { expression { !isIos || !iosBuildToDevice } }
                steps {
                    script {
                        if (!fileExists(env.OUTPUT_PATH)) {
                            error(
                                "Build artifact not found: ${env.OUTPUT_PATH}"
                            )
                        }

                        env.BUILD_INFO_FOUND = 'false'

                        if (isAndroid) {
                            // SDK cũ có thể sinh BUILD_INFO.txt, nhưng không
                            // phải project nào cũng cài SDK đó. Giữ file thật
                            // nếu có; nếu không thì tạo bản tối giản từ
                            // build-metadata.json để Verify/Drive vẫn hoạt động.
                            readBuildMetadata()
                            env.BUILD_INFO_FOUND =
                                ensureAndroidBuildInfo() ? 'true' : 'false'
                        } else if (isIos) {
                            env.BUILD_INFO_FOUND =
                                resolveIosBuildInfo() ? 'true' : 'false'
                        }

                        echo "Build artifact created successfully: ${env.OUTPUT_PATH}"

                        archiveArtifacts(
                            artifacts: isIos
                                ? "Builds/iOS/${env.OUTPUT_FILE_NAME},Builds/iOS/${env.BUILD_INFO_FILE_NAME},Builds/iOS/build-metadata.json,Builds/iOS/unity-build.log,Builds/iOS/xcodebuild.log"
                                : "Builds/Android/${env.OUTPUT_FILE_NAME},Builds/Android/${env.BUILD_INFO_FILE_NAME},Builds/Android/build-metadata.json,Builds/Android/mapping.txt,Builds/Android/unity-build.log",
                            allowEmptyArchive: true,
                            fingerprint: true,
                            onlyIfSuccessful: true
                        )
                    }
                }
            }

            // Cài APK vừa build lên máy Android qua adb (USB hoặc Wireless
            // debugging đã pair, adb tự kết nối qua mDNS). Không có máy nào
            // đang kết nối thì bỏ qua, build vẫn SUCCESS. Cài lỗi (sai chữ
            // ký, máy từ chối...) chỉ đánh UNSTABLE để upload và Telegram vẫn
            // chạy.
            stage('Install on Android Device') {
                when { expression { isAndroid && androidInstallToDevice } }
                options { timeout(time: 10, unit: 'MINUTES') }
                steps {
                    script {
                        if (env.OUTPUT_EXTENSION != 'apk') {
                            env.ANDROID_INSTALL_STATUS =
                                'Skipped: AAB cannot be installed with adb.'
                            echo env.ANDROID_INSTALL_STATUS
                            return
                        }

                        env.ADB_EXE = resolveAdbExe(configuredAdbExe)
                        env.PEARZ_ADB_SERIALS = androidDeviceSerial
                            .replace(',', ' ')
                        env.PEARZ_ADB_RESULT_PATH =
                            "${env.WORKSPACE}/Builds/Android/device-install.txt"
                        echo "adb path: ${env.ADB_EXE}"

                        // Exit code: 0 = đã cài, 3 = không có máy, khác = lỗi.
                        // Cookie dontKillMe giữ adb server sống qua các build
                        // để máy không dây không phải dò lại mDNS mỗi lần.
                        def installStatus
                        if (isUnix()) {
                            installStatus = sh(returnStatus: true, script: '''
                                set -u
                                rm -f "$PEARZ_ADB_RESULT_PATH"

                                JENKINS_NODE_COOKIE=dontKillMe BUILD_ID=dontKillMe \
                                    "$ADB_EXE" start-server || exit 1

                                list_devices() {
                                    "$ADB_EXE" devices |
                                        awk 'NR > 1 && $2 == "device" { print $1 }'
                                }

                                # Kết nối Wi-Fi hay bị adb làm rớt trong khi
                                # điện thoại vẫn tưởng còn kết nối. Trước khi cài,
                                # nối lại: phiên offline, máy adb thấy qua mDNS và
                                # các địa chỉ đã cài thành công lần trước.
                                known_file="$HOME/.pearz-ci/adb-known-devices.txt"
                                mkdir -p "$(dirname "$known_file")"
                                "$ADB_EXE" reconnect offline >/dev/null 2>&1 || true
                                {
                                    "$ADB_EXE" mdns services 2>/dev/null |
                                        awk '$2 ~ /^_adb(-tls-connect)?\\._tcp/ { print $3 }'
                                    [ -f "$known_file" ] && cat "$known_file"
                                } | grep -E '^[0-9.]+:[0-9]+$' | sort -u |
                                while read -r address; do
                                    echo "adb connect $address"
                                    output=$("$ADB_EXE" connect "$address" 2>&1 | head -1)
                                    echo "$output"
                                    case "$output" in
                                        *"connected to"*) echo "$address" >> "$known_file.new" ;;
                                    esac
                                done
                                # Giữ tối đa 20 địa chỉ còn kết nối được gần nhất.
                                if [ -f "$known_file.new" ]; then
                                    tail -n 20 "$known_file.new" > "$known_file"
                                    rm -f "$known_file.new"
                                fi

                                attempt=0
                                while [ -z "$(list_devices)" ] && [ "$attempt" -lt 5 ]; do
                                    sleep 1
                                    attempt=$((attempt + 1))
                                done

                                "$ADB_EXE" devices -l
                                connected=$(list_devices)

                                serials=""
                                if [ -n "$PEARZ_ADB_SERIALS" ]; then
                                    for serial in $PEARZ_ADB_SERIALS; do
                                        if printf '%s\\n' "$connected" | grep -Fqx "$serial"; then
                                            serials="$serials $serial"
                                        else
                                            echo "Device $serial is not connected; skipped."
                                        fi
                                    done
                                else
                                    serials="$connected"
                                fi

                                if [ -z "$(echo $serials)" ]; then
                                    echo "No Android device is connected; install skipped."
                                    exit 3
                                fi

                                failed=0
                                installed=""
                                done_ids=" "
                                for serial in $serials; do
                                    # Một máy có thể xuất hiện hai lần (tên mDNS
                                    # và ip:port); chỉ cài một lần theo serialno.
                                    device_id=$("$ADB_EXE" -s "$serial" get-serialno 2>/dev/null | tr -d '\\r')
                                    case "$done_ids" in
                                        *" ${device_id:-$serial} "*) continue ;;
                                    esac
                                    done_ids="$done_ids${device_id:-$serial} "

                                    echo "Installing $OUTPUT_PATH on $serial"
                                    if "$ADB_EXE" -s "$serial" install -r -d "$OUTPUT_PATH"; then
                                        model=$("$ADB_EXE" -s "$serial" shell getprop ro.product.model 2>/dev/null | tr -d '\\r')
                                        installed="$installed, ${model:-$serial}"
                                    else
                                        echo "ERROR: Install failed on $serial"
                                        failed=1
                                    fi
                                done

                                printf '%s' "${installed#, }" > "$PEARZ_ADB_RESULT_PATH"
                                exit "$failed"
                            ''')
                        } else {
                            installStatus = bat(returnStatus: true, script: '''
                                @echo off
                                setlocal EnableDelayedExpansion
                                del /q "%PEARZ_ADB_RESULT_PATH%" 2>nul

                                set JENKINS_NODE_COOKIE=dontKillMe
                                set BUILD_ID=dontKillMe
                                "%ADB_EXE%" start-server || exit /b 1

                                "%ADB_EXE%" reconnect offline >nul 2>&1
                                for /f "tokens=2,3" %%A in ('"%ADB_EXE%" mdns services 2^>nul') do (
                                    echo %%A | findstr /b "_adb" >nul && (
                                        echo adb connect %%B
                                        "%ADB_EXE%" connect %%B
                                    )
                                )
                                timeout /t 2 /nobreak >nul

                                "%ADB_EXE%" devices -l

                                set "CONNECTED="
                                for /f "skip=1 tokens=1,2" %%A in ('"%ADB_EXE%" devices') do (
                                    if "%%B"=="device" set "CONNECTED=!CONNECTED! %%A"
                                )

                                set "SERIALS="
                                if defined PEARZ_ADB_SERIALS (
                                    for %%S in (%PEARZ_ADB_SERIALS%) do (
                                        echo !CONNECTED! | findstr /c:" %%S" >nul && set "SERIALS=!SERIALS! %%S"
                                    )
                                ) else (
                                    set "SERIALS=!CONNECTED!"
                                )

                                if not defined SERIALS (
                                    echo No Android device is connected; install skipped.
                                    exit /b 3
                                )

                                set FAILED=0
                                set "INSTALLED="
                                set "DONE_IDS= "
                                for %%S in (!SERIALS!) do (
                                    set "DEVICE_ID=%%S"
                                    for /f %%I in ('"%ADB_EXE%" -s %%S get-serialno 2^>nul') do set "DEVICE_ID=%%I"
                                    echo !DONE_IDS! | findstr /c:" !DEVICE_ID! " >nul
                                    if errorlevel 1 (
                                        set "DONE_IDS=!DONE_IDS!!DEVICE_ID! "
                                        echo Installing %OUTPUT_PATH% on %%S
                                        "%ADB_EXE%" -s %%S install -r -d "%OUTPUT_PATH%"
                                        if errorlevel 1 (
                                            echo ERROR: Install failed on %%S
                                            set FAILED=1
                                        ) else (
                                            set "INSTALLED=!INSTALLED! %%S"
                                        )
                                    )
                                )

                                if defined INSTALLED (echo !INSTALLED!)> "%PEARZ_ADB_RESULT_PATH%"
                                exit /b !FAILED!
                            ''')
                        }

                        def installedOn = fileExists(env.PEARZ_ADB_RESULT_PATH)
                            ? readFile(env.PEARZ_ADB_RESULT_PATH).trim()
                            : ''

                        if (installStatus == 3) {
                            env.ANDROID_INSTALL_STATUS =
                                'Skipped: no device connected.'
                        } else if (installStatus == 0) {
                            env.ANDROID_INSTALL_STATUS =
                                "Installed on ${installedOn}."
                        } else {
                            env.ANDROID_INSTALL_STATUS = installedOn
                                ? "Partially installed (${installedOn}); see log."
                                : 'Install failed; see log.'
                            unstable('adb install failed on at least one device.')
                        }

                        echo "Device install: ${env.ANDROID_INSTALL_STATUS}"
                    }
                }
            }

            // Các stage 'Validate rclone', 'Verify Google Drive Upload',
            // 'Create Public Link' và 'Archive Notification Artifacts' đã gộp
            // hết vào đây để bớt cột Stage View. Đánh đổi: hỏng ở bất kỳ bước
            // Drive nào cũng hiện đỏ chung một cột, không định vị ngay được bước
            // nào — xem log của stage để biết chi tiết.
            stage('Upload to Google Drive') {
                when { expression { !isIos || !iosBuildToDevice } }
                options {
                    timeout(time: 30, unit: 'MINUTES')
                }
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

                            if (env.BUILD_INFO_FOUND == 'true') {
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
                            } else if (isAndroid) {
                                // Build lại cùng version mà lần này không có
                                // mapping (tắt minify): xoá mapping cũ trên Drive
                                // để nó không bị nhầm là của artifact mới.
                                if (isUnix()) {
                                    sh(
                                        script: '"$RCLONE_EXE" deletefile "$DRIVE_MAPPING_PATH" >/dev/null 2>&1',
                                        returnStatus: true
                                    )
                                } else {
                                    bat(
                                        script: '@"%RCLONE_EXE%" deletefile "%DRIVE_MAPPING_PATH%" >nul 2>&1',
                                        returnStatus: true
                                    )
                                }
                            }
                        } finally {
                            env.UPLOAD_TIME_MILLIS = (
                                System.currentTimeMillis() - uploadStartedAt
                            ).toString()
                        }

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

                        if (env.BUILD_INFO_FOUND == 'true') {
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

                        env.DOWNLOAD_URL =
                            createRcloneLink(env.DRIVE_FILE_PATH)

                        if (!env.DOWNLOAD_URL) {
                            error(
                                'ERROR: rclone did not return a public link.'
                            )
                        }

                        // Link ở dòng 'Build Info' phải mở thẳng file
                        // <PRODUCT_NAME>_BUILD_INFO.txt, không phải thư mục
                        // chứa nó như trước.
                        if (env.BUILD_INFO_FOUND == 'true') {
                            env.BUILD_INFO_URL =
                                createRcloneLink(env.DRIVE_BUILD_INFO_PATH)
                        }

                        if (isAndroid && env.MAPPING_UPLOADED == 'true') {
                            env.MAPPING_URL =
                                createRcloneLink(env.DRIVE_MAPPING_PATH)
                        }

                        // Dự phòng cho iOS khi không dò được file build info:
                        // dòng 'Build Info' quay về link thư mục Drive thay vì
                        // biến mất khỏi message.
                        if (isIos) {
                            env.DRIVE_FOLDER_URL =
                                createRcloneLink(env.DRIVE_DIRECTORY)
                        }

                        echo "Public download link: ${env.DOWNLOAD_URL}"

                        // 'Archive Notification Artifacts' đã gộp vào đây: chỉ
                        // upload.log là file mới sau bước upload; các file còn
                        // lại đã archive ở 'Verify & Archive Artifact'.
                        archiveArtifacts(
                            artifacts: isIos ? 'Builds/iOS/upload.log' : 'Builds/Android/upload.log',
                            allowEmptyArchive: true,
                            fingerprint: true
                        )
                    }
                }
            }

            // Chạy sau khi IPA đã nằm trên Drive: upload TestFlight hỏng thì
            // vẫn còn bản build tải về được, và message Telegram vẫn có link.
            stage('Upload to TestFlight') {
                when {
                    expression {
                        isIos && !iosBuildToDevice && uploadToTestFlight
                    }
                }
                options { timeout(time: 60, unit: 'MINUTES') }
                steps {
                    script {
                        uploadIpaToTestFlight(
                            appStoreConnectApiKeyCredentialsId,
                            appStoreConnectKeyId,
                            appStoreConnectIssuerId
                        )
                    }
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
                echo "Unity ${isIos ? 'iOS' : 'Android'} build failed."
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

                    if (!sendNotifications) {
                        echo 'SEND_NOTIFICATIONS is disabled; notification skipped.'
                    } else if (isAndroid) {
                        sendTelegramNotification(telegramCredentialsId)
                    } else {
                        sendIosTelegramNotification(
                            telegramCredentialsId,
                            iosBuildToDevice
                        )
                    }
                }

                script {
                    if (isUnix()) {
                        sh(
                            'rm -f send-telegram.sh send-telegram.ps1 ' +
                            'read-build-metadata.sh ' +
                            'read-build-metadata.ps1 ' +
                            'remove-applovin-spm.rb ' +
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
                            'next Android build replaces the existing APK/AAB.'
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

// Thứ tự dò adb: config `adbExe` > ANDROID_HOME/ANDROID_SDK_ROOT > SDK đi
// kèm Unity (Android Build Support) > `adb` trên PATH.
def resolveAdbExe(String configuredAdbExe) {
    if (configuredAdbExe) {
        return configuredAdbExe
    }

    def adbName = isUnix() ? 'adb' : 'adb.exe'
    def candidates = []

    [env.ANDROID_HOME, env.ANDROID_SDK_ROOT].each { sdkRoot ->
        if (sdkRoot?.trim()) {
            candidates << "${sdkRoot.trim()}/platform-tools/${adbName}"
        }
    }

    def unityRoot = "${env.UNITY_HUB_ROOT}/${env.UNITY_VERSION}"
    candidates << (isUnix()
        ? "${unityRoot}/PlaybackEngines/AndroidPlayer/SDK/platform-tools/${adbName}"
        : "${unityRoot}/Editor/Data/PlaybackEngines/AndroidPlayer/SDK/platform-tools/${adbName}")

    for (def candidate : candidates) {
        if (fileExists(candidate)) {
            return candidate
        }
    }

    return adbName
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

// iOS không có build-metadata.json để đọc ngược như Android, nên version thật
// của bản build nằm trong Info.plist mà Unity vừa sinh ra. Đọc từ đó thay vì
// suy từ tham số job: tham số để trống thì Unity giữ nguyên giá trị của
// project, và message sẽ nói sai nếu đoán theo tham số.
def readIosVersionFromXcodeProject() {
    def plistPath = "${env.IOS_PROJECT_PATH}/Info.plist"

    if (!fileExists(plistPath)) {
        echo(
            "Optional iOS Info.plist not found at ${plistPath}; the " +
            'notification will fall back to the job parameters.'
        )
        return
    }

    withEnv(["IOS_INFO_PLIST=${plistPath}"]) {
        env.IOS_VERSION_NAME = readPlistValue('CFBundleShortVersionString')
        env.IOS_BUILD_NUMBER_BUILT = readPlistValue('CFBundleVersion')
    }

    echo(
        "iOS version built: ${env.IOS_VERSION_NAME} " +
        "(build ${env.IOS_BUILD_NUMBER_BUILT})"
    )
}

// PlistBuddy trả về mã lỗi khi khoá không tồn tại; thiếu một khoá không đáng
// làm hỏng build, chỉ là message mất một mẩu thông tin.
def readPlistValue(String key) {
    def value = ''

    withEnv(["IOS_PLIST_KEY=${key}"]) {
        value = sh(
            script:
                '/usr/libexec/PlistBuddy -c ' +
                '"Print :$IOS_PLIST_KEY" "$IOS_INFO_PLIST" 2>/dev/null || true',
            returnStdout: true
        ).trim()
    }

    return value
}

// altool chỉ nhận private key qua file có tên cố định AuthKey_<KeyID>.p8 nằm
// trong ./private_keys, ~/private_keys, ~/.private_keys hoặc
// ~/.appstoreconnect/private_keys. Nên phải chép credential ra đĩa; trap xoá
// ngay khi lệnh kết thúc, kể cả lúc hỏng, để key không nằm lại trên agent.
def uploadIpaToTestFlight(
    String apiKeyCredentialsId,
    String keyId,
    String issuerId
) {
    if (!apiKeyCredentialsId) {
        error(
            'appStoreConnectApiKeyCredentialsId is required when ' +
            'UPLOAD_TO_TESTFLIGHT is enabled.'
        )
    }

    if (!keyId || !issuerId) {
        error(
            'APP_STORE_CONNECT_KEY_ID and APP_STORE_CONNECT_ISSUER_ID are ' +
            'required when UPLOAD_TO_TESTFLIGHT is enabled.'
        )
    }

    try {
        withCredentials([
            file(
                credentialsId: apiKeyCredentialsId,
                variable: 'ASC_API_KEY_FILE'
            )
        ]) {
            withEnv([
                "ASC_KEY_ID=${keyId}",
                "ASC_ISSUER_ID=${issuerId}"
            ]) {
                sh '''
                    set -eu

                    key_dir="$WORKSPACE/private_keys"
                    rm -rf "$key_dir"
                    mkdir -p "$key_dir"
                    trap 'rm -rf "$key_dir"' EXIT INT TERM
                    chmod 700 "$key_dir"
                    cp "$ASC_API_KEY_FILE" "$key_dir/AuthKey_$ASC_KEY_ID.p8"
                    chmod 600 "$key_dir/AuthKey_$ASC_KEY_ID.p8"

                    cd "$WORKSPACE"
                    xcrun altool --upload-app -f "$OUTPUT_PATH" -t ios \
                        --apiKey "$ASC_KEY_ID" \
                        --apiIssuer "$ASC_ISSUER_ID"
                '''
            }
        }

        // App Store Connect còn xử lý tiếp sau khi altool trả về, nên đây là
        // "đã nhận", chưa phải "tester tải được".
        env.TESTFLIGHT_STATUS =
            'Uploaded; App Store Connect is still processing the build.'
        echo 'IPA uploaded to App Store Connect for TestFlight.'
    } catch (Exception exception) {
        env.TESTFLIGHT_STATUS = 'Upload failed.'
        throw exception
    }
}

// Tên file đi qua biến môi trường chứ không nội suy thẳng vào script, để
// productName có dấu cách hay ký tự lạ không làm vỡ lệnh find.
def findIosBuildInfo(String namePattern) {
    def foundPath = ''

    withEnv(["IOS_BUILD_INFO_NAME=${namePattern}"]) {
        foundPath = sh(
            script:
                'find "$WORKSPACE/Builds/iOS" -maxdepth 3 -type f ' +
                '-name "$IOS_BUILD_INFO_NAME" 2>/dev/null | head -n 1',
            returnStdout: true
        ).trim()
    }

    return foundPath
}

// FGSDK sinh file build info sau khi Unity export xong. Trên Android nó nằm
// ngay cạnh APK/AAB nên đường dẫn dựng sẵn là đủ; trên iOS output của Unity
// là cả thư mục Xcode, nên file có thể nằm ở Builds/iOS hoặc bên trong
// Unity-iPhone. Dò thật rồi chép về đúng chỗ Android vẫn dùng, để các stage
// sau không phải phân biệt hai nền tảng. Không tìm thấy thì cảnh báo chứ
// không fail build: iOS chưa từng đòi file này.
def resolveIosBuildInfo() {
    if (fileExists(env.BUILD_INFO_PATH)) {
        echo "Build info file found: ${env.BUILD_INFO_PATH}"
        return true
    }

    def foundPath = findIosBuildInfo(env.BUILD_INFO_FILE_NAME)

    // FGSDK đặt tên file theo productName của Unity, còn BUILD_INFO_FILE_NAME
    // dựng từ tên đã resolve của pipeline. Vẫn dò rộng một lượt để tương
    // thích với các SDK cũ đã dùng tên khác.
    if (!foundPath) {
        foundPath = findIosBuildInfo('*_BUILD_INFO.txt')
    }

    if (!foundPath) {
        echo(
            'Optional build info file not found under Builds/iOS; the ' +
            'notification will link the Drive build folder instead.'
        )
        return false
    }

    // Chép chứ không đổi BUILD_INFO_PATH: archiveArtifacts nhận pattern
    // tương đối với workspace nên file phải nằm ngay trong Builds/iOS.
    withEnv(["IOS_BUILD_INFO_SOURCE=${foundPath}"]) {
        sh 'cp "$IOS_BUILD_INFO_SOURCE" "$BUILD_INFO_PATH"'
    }

    echo "Build info file found: ${foundPath}"
    return true
}

// Tiêu đề phải nói ngay build đậu hay hỏng. Không rút mọi kết quả khác
// SUCCESS thành FAILED: build bị abort hoặc UNSTABLE mà báo "FAILED" là sai
// sự thật, và UNSTABLE chính là trạng thái khi gửi Telegram bị lỗi.
def telegramBuildStatus() {
    def result = currentBuild.currentResult ?: 'SUCCESS'
    return result == 'FAILURE' ? 'FAILED' : result
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

    def changeDescription = env.GIT_CHANGES?.trim()

    def values = [
        PLATFORM: 'ANDROID',
        STATUS: telegramBuildStatus(),
        JOB_NAME: telegramHtmlEscape(env.JOB_NAME),
        BUILD_NUMBER: telegramHtmlEscape(env.BUILD_NUMBER),
        PEARZ_CI_VERSION: telegramHtmlEscape(env.PEARZ_CI_VERSION),
        VERSION: telegramHtmlEscape(versionParts.join(' / ')),
        VERSION_NAME: telegramHtmlEscape(env.META_VERSION_NAME),
        VERSION_CODE: telegramHtmlEscape(env.META_VERSION_CODE),
        PRODUCT_NAME: telegramHtmlEscape(
            env.META_PRODUCT_NAME ?: env.PEARZ_PRODUCT_NAME
        ),
        BUNDLE_ID: telegramHtmlEscape(env.META_BUNDLE_IDENTIFIER),
        GOOGLE_PLAY_URL: telegramHtmlEscape(googlePlayUrl),
        BRANCH: telegramHtmlEscape(params.GIT_BRANCH),
        BUILD_INFO_URL: telegramHtmlEscape(env.BUILD_INFO_URL),
        APK: telegramHtmlEscape(apkDescription),
        AAB: telegramHtmlEscape(aabDescription),
        MAPPING: telegramHtmlEscape(mappingDescription),
        INSTALL_STATUS: telegramHtmlEscape(env.ANDROID_INSTALL_STATUS),
        ERROR_SECTION: env.META_ERROR_MESSAGE?.trim()
            ? "<blockquote><b>Error</b>\n${telegramHtmlEscape(env.META_ERROR_MESSAGE.trim())}</blockquote>"
            : '',
        CHANGES_SECTION: changeDescription
            ? "<b>Changes</b>\n<blockquote>${telegramHtmlEscape(changeDescription)}</blockquote>"
            : ''
    ]

    return truncateTelegramMessage(renderTelegramTemplate(values))
}

// iOS không có build-metadata.json: BuildEntry chỉ ghi file đó trong
// BuildAndroid(), và mọi field bên trong đều là Android. Nên message iOS
// dựng từ tham số job cộng biến môi trường của pipeline. Vẫn đi qua đúng
// template của Android để hai nền tảng không trôi khỏi nhau như trước;
// những dòng không có dữ liệu sẽ tự bị renderTelegramTemplate bỏ đi.
def buildIosTelegramMessage(boolean deviceBuild) {
    def result = currentBuild.currentResult ?: 'SUCCESS'
    boolean succeeded = result == 'SUCCESS'
    def versionParts = []
    // Ưu tiên giá trị đọc từ Info.plist của bản vừa build; tham số job chỉ là
    // dự phòng cho lúc build hỏng trước khi Xcode project kịp sinh ra.
    def appVersion = env.IOS_VERSION_NAME?.trim() ?:
        params.APP_VERSION?.toString()?.trim()
    def iosBuildNumber = env.IOS_BUILD_NUMBER_BUILT?.trim() ?:
        params.IOS_BUILD_NUMBER?.toString()?.trim()

    if (appVersion) {
        versionParts << appVersion
    }

    if (iosBuildNumber) {
        versionParts << "build ${iosBuildNumber}"
    }

    def changeDescription = env.GIT_CHANGES?.trim()
    def errorSection = ''

    if (!succeeded) {
        // Tiêu đề đã nói kết quả rồi, ở đây chỉ cần lý do. IPA hỏng và
        // TestFlight hỏng là hai chuyện khác nhau: bản build vẫn tải được
        // từ Drive khi chỉ mỗi bước upload lên App Store Connect thất bại.
        def reason = 'The IPA was not produced.'

        if (deviceBuild) {
            reason = 'The app was not installed on the connected device.'
        } else if (env.TESTFLIGHT_STATUS == 'Upload failed.') {
            reason = 'The IPA was built but the TestFlight upload failed.'
        }

        errorSection = '<blockquote><b>Error</b>\n' +
            telegramHtmlEscape(reason) + '</blockquote>'
    }

    def values = [
        PLATFORM: deviceBuild ? 'IOS DEVICE' : 'IOS',
        STATUS: telegramBuildStatus(),
        PEARZ_CI_VERSION: telegramHtmlEscape(env.PEARZ_CI_VERSION),
        VERSION: telegramHtmlEscape(versionParts.join(' / ')),
        PRODUCT_NAME: telegramHtmlEscape(env.PEARZ_PRODUCT_NAME),
        BRANCH: telegramHtmlEscape(params.GIT_BRANCH),
        BUILD_INFO_URL: telegramHtmlEscape(
            deviceBuild ? '' : (env.BUILD_INFO_URL ?: env.DRIVE_FOLDER_URL)
        ),
        IPA: telegramHtmlEscape(deviceBuild ? '' : env.DOWNLOAD_URL),
        TESTFLIGHT: telegramHtmlEscape(
            deviceBuild ? '' : env.TESTFLIGHT_STATUS
        ),
        INSTALL_STATUS: deviceBuild
            ? (succeeded
                ? 'Installed on the connected device.'
                : 'Not installed.')
            : '',
        ERROR_SECTION: errorSection,
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

                    metadata[key] = value
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
    env.META_COMPRESSION_METHOD =
        metadata.COMPRESSION_METHOD?.toString() ?: ''
    env.META_SCRIPTING_BACKEND =
        metadata.SCRIPTING_BACKEND?.toString() ?: ''
    env.META_STRIPPING_LEVEL =
        metadata.STRIPPING_LEVEL?.toString() ?: ''
    env.META_ORIENTATION = metadata.ORIENTATION?.toString() ?: ''
    env.META_OUTPUT_SIZE_BYTES =
        metadata.OUTPUT_SIZE_BYTES?.toString() ?: ''
    env.META_MAPPING_SIZE_BYTES =
        metadata.MAPPING_SIZE_BYTES?.toString() ?: ''
}

// Unity stores the display name in ProjectSettings.asset. Read it before the
// build so the output filename can follow the project without requiring a
// duplicate Jenkins parameter. An explicit PRODUCT_NAME parameter is handled
// by the caller and takes precedence over this value.
def readUnityProductName(String projectPath) {
    def settingsPath = "${projectPath}/ProjectSettings/ProjectSettings.asset"

    if (!fileExists(settingsPath)) {
        return ''
    }

    def settings = readFile(file: settingsPath, encoding: 'UTF-8')
    def productLine = settings.readLines().find { line ->
        line ==~ /^\s*productName\s*:.*/
    }

    if (!productLine) {
        return ''
    }

    def productName = productLine
        .replaceFirst(/^\s*productName\s*:\s*/, '')
        .trim()

    if (productName.size() >= 2) {
        def first = productName[0]
        def last = productName[-1]

        if ((first == '"' && last == '"') ||
            (first == "'" && last == "'")) {
            productName = productName.substring(1, productName.size() - 1)
        }
    }

    return productName.trim()
}

// SDK cũ của một số project sinh một report BUILD_INFO.txt riêng. Đây là
// fallback tương thích cho project không cài SDK đó: không cố tái tạo các
// section quảng cáo/Firebase, chỉ ghi các thông tin CI chắc chắn có.
def ensureAndroidBuildInfo() {
    if (fileExists(env.BUILD_INFO_PATH)) {
        echo "Build info file found: ${env.BUILD_INFO_PATH}"
        upsertAndroidBuildInfoCompressionMethod()
        return true
    }

    def productName = env.META_PRODUCT_NAME?.trim()
        ?: env.PEARZ_PRODUCT_NAME?.trim()
    def bundleIdentifier = env.META_BUNDLE_IDENTIFIER?.trim() ?: ''
    def versionName = env.META_VERSION_NAME?.trim()
        ?: (env.PEARZ_APP_VERSION ?: '')
    def versionCode = env.META_VERSION_CODE?.trim()
        ?: (params.ANDROID_VERSION_CODE?.toString()?.trim() ?: '')
    def buildType = params.BUILD_APP_BUNDLE?.toString()?.toBoolean()
        ? 'AAB'
        : 'APK'
    def generatedAt = new Date().toString()

    writeFile(
        file: env.BUILD_INFO_PATH,
        encoding: 'UTF-8',
        text: """BUILD INFO — ${generatedAt}
===============================================================

BASIC BUILD INFO
---------------------------------------------------------------
Package Name      : ${bundleIdentifier}
App Name          : ${productName}
App Version       : ${versionName}
Build Type        : ${buildType}
Build Date/Time   : ${generatedAt}
Android Version   : ${versionCode}
Unity Version     : ${env.META_UNITY_VERSION ?: env.UNITY_VERSION}
Compression Method: ${env.META_COMPRESSION_METHOD ?: 'Unknown'}
CI Version        : ${env.PEARZ_CI_VERSION}
Result            : ${env.META_RESULT ?: 'Succeeded'}

NOTE
---------------------------------------------------------------
This minimal report was generated by PearzCI because the project SDK did not
export its own BUILD_INFO.txt file.
"""
    )

    echo "Generated fallback Android build info: ${env.BUILD_INFO_PATH}"
    return true
}

// Project SDKs can supply their own BUILD_INFO.txt. Keep that report intact
// while ensuring the compression field is present and reflects this CI build.
def upsertAndroidBuildInfoCompressionMethod() {
    def compressionMethod = env.META_COMPRESSION_METHOD?.trim()

    if (!compressionMethod) {
        echo 'Compression method is unavailable in build metadata; existing BUILD_INFO.txt is unchanged.'
        return
    }

    def contents = readFile(file: env.BUILD_INFO_PATH, encoding: 'UTF-8')
    def line = "Compression Method: ${compressionMethod}"
    def pattern = /(?m)^Compression Method\s*:\s*.*(?:\r?\n)?/
    def updated = (contents =~ pattern).find()
        ? contents.replaceFirst(pattern, line + '\n')
        : contents.replaceFirst(/\s*\z/, "\n${line}\n")

    if (updated != contents) {
        writeFile(file: env.BUILD_INFO_PATH, encoding: 'UTF-8', text: updated)
        echo "Added compression method to build info: ${compressionMethod}"
    }
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

// Dùng cho cả iOS IPA lẫn iOS device. Trước đây chỉ device build mới được
// báo, còn IPA thì im lặng hoàn toàn. iOS luôn chạy trên macOS nên chỉ cần
// nhánh sh, không cần bản PowerShell như Android.
def sendIosTelegramNotification(
    String telegramCredentialsId,
    boolean deviceBuild
) {
    boolean telegramConfigured = telegramCredentialsId ||
        "${params.TELEGRAM_CHANNEL ?: ''}".trim()

    if (!telegramConfigured) {
        echo 'No Telegram target configured; notification skipped.'
        return
    }

    try {
        writeFile(
            file: 'telegram-message.txt',
            encoding: 'UTF-8',
            text: buildIosTelegramMessage(deviceBuild)
        )

        def sendTelegram = {
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
        // Cùng cách xử lý với Android: lỗi thông báo không được ghi đè kết
        // quả build thật, chỉ hạ SUCCESS xuống UNSTABLE.
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
