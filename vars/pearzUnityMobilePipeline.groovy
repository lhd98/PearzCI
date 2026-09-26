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
    // iOS bắt buộc chạy trên macOS; Android giữ nguyên "any" như trước để
    // không đổi cách chọn node của các job Android đang chạy. Nhãn rỗng
    // tương đương `agent any`.
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

    // Build webhook bị bỏ ngay khi bắt đầu nếu đã có push mới hơn đang chờ
    // trong hàng đợi của job; build bấm tay luôn được chạy tới cùng.
    if (isSupersededWebhookBuild()) {
        currentBuild.result = 'NOT_BUILT'
        currentBuild.description = 'Skipped: a newer push is queued'
        echo 'A newer webhook build is queued; this build is skipped.'
        return
    }

    pipeline {
        agent { label "${agentLabelExpression}" }

        options {
            timestamps()
            // Không huỷ build đang chạy: build tay (ví dụ AAB phát hành)
            // luôn chạy xong. Build webhook cũ tự bỏ qua khi có push mới
            // hơn đang chờ (xem isSupersededWebhookBuild).
            disableConcurrentBuilds()
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
            GRADLE_USER_HOME = "${env.HOME}/.gradle-jenkins/${env.JOB_BASE_NAME}"
            PEARZ_CI_VERSION = "${pearzCiVersion}"
            DRIVE_REMOTE = "${driveRemote}"
            DRIVE_ROOT = "${driveRoot}"
            MAC_RCLONE_EXE = "${macRcloneExe}"
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

                        sh '''
                            git submodule sync --recursive
                            git submodule update --init --recursive
                        '''

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

                        def kernelName = sh(
                            script: 'uname -s',
                            returnStdout: true
                        ).trim()

                        if (kernelName != 'Darwin') {
                            error(
                                "Unsupported Jenkins agent OS: ${kernelName}. " +
                                'PearzCI mobile builds require a macOS agent.'
                            )
                        }

                        env.NODE_OS = 'macOS'
                        env.RCLONE_EXE = env.MAC_RCLONE_EXE
                        env.UNITY_HUB_ROOT = env.MAC_UNITY_HUB_ROOT

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

                        env.PEARZCI_GIT_COMMIT = sh(
                            script: 'git rev-parse HEAD',
                            returnStdout: true
                        ).trim()

                        env.GIT_CHANGES = pearzGitChanges.collectGitChanges(telegramMaxCommits)
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

                        def unityExe = "${env.UNITY_HUB_ROOT}/${env.UNITY_VERSION}" +
                            '/Unity.app/Contents/MacOS/Unity'

                        env.UNITY_EXE = unityExe
                        echo "Unity path: ${unityExe}"

                        if (!fileExists(unityExe)) {
                            error("Unity not found: ${unityExe}")
                        }

                        sh "\"${unityExe}\" -version"
                        if (isIos) {
                            sh 'xcodebuild -version'
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
                        if (isSupersededWebhookBuild()) {
                            env.PEARZ_SUPERSEDED = 'true'
                            error('A newer webhook build is queued; stopping before the Unity build.')
                        }
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
                                requestedVersionCode.toInteger() >= pearzAndroid.readNextAabVersionCode()) {
                                env.AAB_VERSION_CODE = androidVersionCode
                            }
                        } else if (params.BUILD_APP_BUNDLE?.toString()?.toBoolean()) {
                            androidVersionCode = pearzAndroid.readNextAabVersionCode().toString()
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
                        if (isSupersededWebhookBuild()) {
                            env.PEARZ_SUPERSEDED = 'true'
                            error('A newer webhook build is queued; stopping before the Unity build.')
                        }
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

                            pearzIos.readIosVersionFromXcodeProject()
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
                            pearzAndroid.readBuildMetadata()
                            env.BUILD_INFO_FOUND =
                                pearzAndroid.ensureAndroidBuildInfo() ? 'true' : 'false'
                        } else if (isIos) {
                            env.BUILD_INFO_FOUND =
                                pearzIos.resolveIosBuildInfo() ? 'true' : 'false'
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
                        pearzAndroid.installOnConnectedDevices(
                            configuredAdbExe,
                            androidDeviceSerial
                        )
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

                        def uploadStartedAt = System.currentTimeMillis()

                        try {
                            retry(2) {
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

                            }

                            if (env.BUILD_INFO_FOUND == 'true') {
                                retry(2) {
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

                                }
                            }

                            if (isAndroid && fileExists(env.MAPPING_PATH)) {
                                def mappingUploadStatus

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
                                sh(
                                    script: '"$RCLONE_EXE" deletefile "$DRIVE_MAPPING_PATH" >/dev/null 2>&1',
                                    returnStatus: true
                                )

                            }
                        } finally {
                            env.UPLOAD_TIME_MILLIS = (
                                System.currentTimeMillis() - uploadStartedAt
                            ).toString()
                        }

                        sh '''
                            set -eu
                            "$RCLONE_EXE" lsjson \
                                "$DRIVE_FILE_PATH" \
                                --files-only
                            echo "Build artifact verified successfully on Google Drive."
                        '''

                        if (env.BUILD_INFO_FOUND == 'true') {
                            sh '''
                                set -eu
                                "$RCLONE_EXE" lsjson \
                                    "$DRIVE_BUILD_INFO_PATH" \
                                    --files-only
                            '''

                            echo 'Build info file verified on Google Drive.'
                        }

                        env.MAPPING_UPLOADED = 'false'

                        if (isAndroid && fileExists(env.MAPPING_PATH)) {
                            def mappingStatus = sh(
                                script:
                                    '"$RCLONE_EXE" lsjson ' +
                                    '"$DRIVE_MAPPING_PATH" --files-only',
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
                        pearzIos.uploadIpaToTestFlight(
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
                        pearzAndroid.saveNextAabVersionCode(env.AAB_VERSION_CODE.toInteger())
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

                    if (env.PEARZ_SUPERSEDED == 'true') {
                        currentBuild.result = 'NOT_BUILT'
                        currentBuild.description = 'Skipped: a newer push is queued'
                        echo 'Superseded by a newer webhook build; notification skipped.'
                    } else if (!sendNotifications) {
                        echo 'SEND_NOTIFICATIONS is disabled; notification skipped.'
                    } else if (isAndroid) {
                        pearzTelegram.sendTelegramNotification(telegramCredentialsId)
                    } else {
                        pearzTelegram.sendIosTelegramNotification(
                            telegramCredentialsId,
                            iosBuildToDevice
                        )
                    }
                }

                script {
                    sh(
                        'rm -f send-telegram.sh ' +
                        'read-build-metadata.sh ' +
                        'remove-applovin-spm.rb ' +
                        'telegram-message.txt'
                    )

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

def createRcloneLink(String remotePath) {
    if (!remotePath?.trim()) {
        return ''
    }

    try {
        def output

        withEnv(["RCLONE_LINK_TARGET=${remotePath.trim()}"]) {
            output = sh(
                script:
                    '"$RCLONE_EXE" link "$RCLONE_LINK_TARGET"',
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

// Build do Generic Webhook Trigger kích hoạt là lỗi thời khi hàng đợi của
// job đang có một build webhook khác: mọi item trong hàng đợi đều mới hơn
// build đang chạy. Build bấm tay không bao giờ bị coi là lỗi thời.
@NonCPS
def isSupersededWebhookBuild() {
    def webhookCause = 'org.jenkinsci.plugins.gwt.GenericCause'
    def build = currentBuild.rawBuild
    if (!build.causes.any { it.class.name == webhookCause }) {
        return false
    }

    return jenkins.model.Jenkins.get().queue.getItems(build.parent).any { item ->
        item.causes.any { it.class.name == webhookCause }
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
