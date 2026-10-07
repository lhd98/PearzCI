def call(Map config = [:]) {
    def mobilePlatform = config.get('mobilePlatform', 'Android')
        .toString().trim()
    boolean isIos = mobilePlatform.equalsIgnoreCase('iOS')
    boolean isWebGL = mobilePlatform.equalsIgnoreCase('WebGL')
    boolean isAndroid = !isIos && !isWebGL
    // WebGL: chỉ cần domain + token; account và project Pages tự suy ra
    // trong cloudflare-pages-deploy.mjs.
    def webDomain = config.get(
        'webDomain', params.WEB_DOMAIN ?: ''
    ).toString().trim().toLowerCase()
        .replaceFirst(/^https?:\/\//, '').replaceAll(/\/+$/, '')
    def cloudflareCredentialsId = config.get(
        'cloudflareCredentialsId', params.CLOUDFLARE_CREDENTIAL ?: ''
    ).toString().trim()
    def cloudflareAccountId = config.get(
        'cloudflareAccountId', params.CLOUDFLARE_ACCOUNT_ID ?: ''
    ).toString().trim()
    // Cố định 1080x1920 (9:16) để job bớt một tham số; khung trên trang tự
    // co theo màn hình. Đổi được qua config webResolution nếu cần.
    def webResolution = config.get('webResolution', '1080x1920').toString().trim()
    def wranglerVersion = config.get('wranglerVersion', '4').toString().trim()
    if (isWebGL) {
        if (!(webDomain ==~ /^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,}$/)) {
            throw new IllegalArgumentException(
                "WEB_DOMAIN must be a domain such as pg05.pearz.space (got '${webDomain}')."
            )
        }
        if (!cloudflareCredentialsId) {
            throw new IllegalArgumentException(
                'CLOUDFLARE_CREDENTIAL is required for WebGL builds: choose ' +
                'the Secret text credential that holds your Cloudflare API token.'
            )
        }
        if (!(webResolution in ['720x1280', '1080x1920', '1440x2560'])) {
            throw new IllegalArgumentException(
                'webResolution must be 720x1280, 1080x1920 or 1440x2560 ' +
                "(got '${webResolution}')."
            )
        }
    }
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
    // telegramApiUrl: để trống là Bot API chính thức (file tối đa 50 MB).
    // Đặt 'http://127.0.0.1:8081' khi chạy telegram-bot-api --local trên
    // agent để gửi được file build đến 2 GB.
    def telegramApiUrl = config.get('telegramApiUrl', '').toString().trim()
    def telegramSendFile = config.get('telegramSendFile', false).toString().toBoolean()
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
            TELEGRAM_API_URL = "${telegramApiUrl}"
            TELEGRAM_SEND_FILE = "${telegramSendFile}"
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

                        pullGitLfsObjects(repositoryCredentialsId)
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

                        env.OUTPUT_EXTENSION = isWebGL
                            ? 'web'
                            : isIos
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
                        def buildFolder = isWebGL ? 'WebGL' : (isIos ? 'iOS' : 'Android')
                        // WebGL: OUTPUT_PATH là thư mục site deploy nguyên
                        // lên Cloudflare Pages; metadata/log nằm cạnh nó.
                        env.OUTPUT_PATH = isWebGL
                            ? "${env.WORKSPACE}/Builds/WebGL/site"
                            : "${env.WORKSPACE}/Builds/${buildFolder}/${env.OUTPUT_FILE_NAME}"
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
                        env.DRIVE_BUILD_INFO_FILE_NAME =
                            "${outputName}_${env.OUTPUT_EXTENSION.toUpperCase()}_BUILD_INFO.txt"
                        env.DRIVE_BUILD_INFO_PATH =
                            "${env.DRIVE_DIRECTORY}/${env.DRIVE_BUILD_INFO_FILE_NAME}"
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
                    script {
                        if (isWebGL) {
                            echo "WEB_DOMAIN = ${webDomain}"
                            echo "Web resolution = ${webResolution}"
                        } else {
                            echo "DRIVE_FILE_PATH = ${env.DRIVE_FILE_PATH}"
                        }
                    }
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
                        if (isWebGL) {
                            def webGlSupport = "${env.UNITY_HUB_ROOT}/${env.UNITY_VERSION}" +
                                '/PlaybackEngines/WebGLSupport'
                            if (!fileExists(webGlSupport)) {
                                error(
                                    "WebGL Build Support is not installed for Unity ${env.UNITY_VERSION}. " +
                                    'Add the module in Unity Hub on the agent.'
                                )
                            }
                            def nodeStatus = sh(
                                script: 'export PATH="$PATH:/opt/homebrew/bin:/usr/local/bin"; node --version && npx --version',
                                returnStatus: true
                            )
                            if (nodeStatus != 0) {
                                error(
                                    'Node.js 18+ is required on the agent to deploy ' +
                                    'WebGL builds (brew install node).'
                                )
                            }
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
                                "DEVELOPMENT_BUILD=${params.DEVELOPMENT_BUILD ?: false}",
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

            stage('Build Unity WebGL') {
                when { expression { isWebGL } }
                options {
                    timeout(time: 90, unit: 'MINUTES')
                }

                steps {
                    script {
                        if (isSupersededWebhookBuild()) {
                            env.PEARZ_SUPERSEDED = 'true'
                            error('A newer webhook build is queued; stopping before the Unity build.')
                        }
                        def buildStartedAt = System.currentTimeMillis()

                        try {
                            withEnv([
                                "OUTPUT_PATH=${env.OUTPUT_PATH}",
                                "APP_VERSION=${env.PEARZ_APP_VERSION}",
                                "CI_BUILD_NUMBER=${env.ARTIFACT_BUILD_NUMBER}",
                                "DEVELOPMENT_BUILD=${params.DEVELOPMENT_BUILD ?: false}",
                                "PRODUCT_NAME=${env.PEARZ_PRODUCT_NAME ?: ''}"
                            ]) {
                                sh '''
                                    set +e

                                    "$UNITY_EXE" \
                                        -batchmode \
                                        -quit \
                                        -projectPath "$UNITY_PROJECT_PATH" \
                                        -buildTarget WebGL \
                                        -executeMethod Pearz.CI.BuildEntry.BuildWebGL \
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
                        // áp dụng cho export IPA. IOS_DEVELOPMENT_TEAM không bắt
                        // buộc: chỉ dùng khi tự tạo profile, trống thì lấy từ
                        // chứng chỉ Apple Development.
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
                            "IOS_DESTINATION_TIMEOUT=${destinationTimeout}",
                            // Chỉ dùng khi phải nhờ Xcode tự tạo profile.
                            "IOS_DEVELOPMENT_TEAM=${config.get('iosDevelopmentTeam', params.IOS_DEVELOPMENT_TEAM ?: '').toString().trim()}"
                        ]) {
                            pearzScript.run('ios-device-install.sh')
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
                        } else if (isWebGL && !fileExists("${env.OUTPUT_PATH}/Build")) {
                            error("WebGL output has no Build folder: ${env.OUTPUT_PATH}")
                        }

                        echo "Build artifact created successfully: ${env.OUTPUT_PATH}"

                        archiveArtifacts(
                            artifacts: isWebGL
                                ? 'Builds/WebGL/build-metadata.json,Builds/WebGL/unity-build.log'
                                : isIos
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
            stage('Deploy to Cloudflare Pages') {
                when { expression { isWebGL } }
                options { timeout(time: 30, unit: 'MINUTES') }
                steps {
                    script {
                        deployWebGlToCloudflarePages(
                            cloudflareCredentialsId,
                            cloudflareAccountId,
                            webDomain,
                            webResolution,
                            wranglerVersion
                        )
                    }
                }
            }

            stage('Upload to Google Drive') {
                when { expression { !isWebGL && (!isIos || !iosBuildToDevice) } }
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

                        // Đổi PRODUCT_NAME / productName trong cùng version
                        // sẽ để lại APK/AAB/IPA tên cũ trong thư mục version.
                        // Upload mới đã verify xong nên xoá chúng (và build
                        // info cùng loại) để mỗi version chỉ còn một file.
                        // Lỗi dọn dẹp không làm hỏng build.
                        def staleCleanupStatus = sh(
                            script: '''
                                set -u
                                ext_upper=$(printf '%s' "$OUTPUT_EXTENSION" | tr '[:lower:]' '[:upper:]')
                                "$RCLONE_EXE" lsf "$DRIVE_DIRECTORY" --files-only --max-depth 1 |
                                while IFS= read -r name; do
                                    case "$name" in
                                        "$DRIVE_OUTPUT_FILE_NAME"|"$DRIVE_BUILD_INFO_FILE_NAME")
                                            continue
                                            ;;
                                        *."$OUTPUT_EXTENSION"|*_"$ext_upper"_BUILD_INFO.txt)
                                            echo "Deleting stale Drive file: $name"
                                            "$RCLONE_EXE" deletefile "$DRIVE_DIRECTORY/$name" || true
                                            ;;
                                    esac
                                done
                            ''',
                            returnStatus: true
                        )

                        if (staleCleanupStatus != 0) {
                            echo 'Could not clean stale files in the Drive version folder; continuing.'
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
                    echo "Unity ${mobilePlatform} build completed: ${env.OUTPUT_FILE_NAME}"
                    if (isWebGL) {
                        echo "Play: ${env.WEB_URL}"
                    } else if (!isIos || !iosBuildToDevice) {
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
                echo "Unity ${mobilePlatform} build failed."
            }

            always {
                // Bắt log chẩn đoán cho build thất bại, khi các stage
                // archive phía trên không kịp chạy. Không archive lại
                // artifact chính vì stage 'Archive Artifact' đã làm.
                // Phải chạy trước khi gửi Telegram để link log có hiệu lực.
                archiveArtifacts(
                    artifacts: isWebGL
                        ? 'Builds/WebGL/build-metadata.json,Builds/WebGL/unity-build.log'
                        : isIos
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
                    def telegramSilent = params.TELEGRAM_SILENT != null &&
                        params.TELEGRAM_SILENT.toString().toBoolean()

                    if (env.PEARZ_SUPERSEDED == 'true') {
                        currentBuild.result = 'NOT_BUILT'
                        currentBuild.description = 'Skipped: a newer push is queued'
                        echo 'Superseded by a newer webhook build; notification skipped.'
                    } else if (!sendNotifications) {
                        echo 'SEND_NOTIFICATIONS is disabled; notification skipped.'
                    } else if (isWebGL) {
                        pearzTelegram.sendWebTelegramNotification(
                            telegramCredentialsId,
                            telegramSilent
                        )
                    } else if (isAndroid) {
                        pearzTelegram.sendTelegramNotification(
                            telegramCredentialsId,
                            telegramSilent
                        )
                    } else {
                        pearzTelegram.sendIosTelegramNotification(
                            telegramCredentialsId,
                            iosBuildToDevice,
                            telegramSilent
                        )
                    }
                }

                script {
                    sh(
                        'rm -f send-telegram.sh ' +
                        'read-build-metadata.sh ' +
                        'remove-applovin-spm.rb ' +
                        'telegram-message.txt telegram-caption.txt ' +
                        'cloudflare-pages-deploy.mjs webgl-index.html ' +
                        'web-deploy-result.txt'
                    )

                    if (isAndroid) {
                        // Chỉ giữ APK/AAB của build này; file tên cũ (đổi
                        // PRODUCT_NAME) bị xoá cho nhẹ workspace.
                        sh(
                            script: '''
                                [ -d Builds/Android ] || exit 0
                                find Builds/Android -maxdepth 1 -type f \\( -name '*.apk' -o -name '*.aab' \\) \\
                                    ! -name "${OUTPUT_FILE_NAME:-}" -print -delete
                            ''',
                            returnStatus: true
                        )
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

// Sinh index.html, kiểm tra giới hạn 25 MiB của Pages, tìm account và
// project Pages theo domain (tạo mới nếu chưa có), deploy bằng wrangler rồi
// gắn domain/CNAME. Toàn bộ logic nằm trong cloudflare-pages-deploy.mjs.
def deployWebGlToCloudflarePages(
    String credentialsId,
    String accountId,
    String domain,
    String resolution,
    String wranglerVersion
) {
    writeFile(
        file: 'cloudflare-pages-deploy.mjs',
        encoding: 'UTF-8',
        text: libraryResource('com/pearz/ci/cloudflare-pages-deploy.mjs')
    )
    writeFile(
        file: 'webgl-index.html',
        encoding: 'UTF-8',
        text: libraryResource('com/pearz/ci/webgl-index.html')
    )

    def status
    withCredentials([
        string(credentialsId: credentialsId, variable: 'CLOUDFLARE_API_TOKEN')
    ]) {
        withEnv([
            "CLOUDFLARE_ACCOUNT_ID=${accountId}",
            "WEB_DOMAIN=${domain}",
            "WEB_RESOLUTION=${resolution}",
            "WEB_SITE_DIR=${env.OUTPUT_PATH}",
            "WEB_INDEX_TEMPLATE=${env.WORKSPACE}/webgl-index.html",
            "WEB_RESULT_FILE=${env.WORKSPACE}/web-deploy-result.txt",
            "WRANGLER_VERSION=${wranglerVersion}"
        ]) {
            status = sh(
                script: '''
                    export PATH="$PATH:/opt/homebrew/bin:/usr/local/bin"
                    node cloudflare-pages-deploy.mjs
                ''',
                returnStatus: true
            )
        }
    }

    if (fileExists('web-deploy-result.txt')) {
        readFile(file: 'web-deploy-result.txt', encoding: 'UTF-8')
            .readLines()
            .each { line ->
                def separator = line.indexOf('=')
                if (separator > 0) {
                    env[line.substring(0, separator)] = line.substring(separator + 1)
                }
            }
    }

    if (status != 0) {
        error(env.WEB_DEPLOY_ERROR ?: 'Cloudflare Pages deploy failed.')
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

// Bước checkout của Jenkins không tải nội dung Git LFS: thiếu bước này các
// file LFS chỉ là file con trỏ ~130 byte, và Unity báo lỗi compile khó hiểu
// (thiếu namespace) vì không nạp được DLL. Chỉ chạy với repository/submodule
// có khai `filter=lfs` trong .gitattributes, nên project không dùng LFS
// không cần cài git-lfs và không đổi hành vi.
def pullGitLfsObjects(String credentialsId) {
    // Smudge bị tắt (`--skip`) để lần `git checkout` kế tiếp của Jenkins
    // không tự tải LFS ngoài ngữ cảnh credential; filter clean vẫn bật để
    // file đã tải không bị Git coi là thay đổi. Mọi lần tải đều đi qua
    // `git lfs pull` bên dưới.
    //
    // Jenkins chạy qua launchd (brew services) không có thư mục Homebrew
    // trong PATH, nên `git lfs` không tìm thấy git-lfs dù máy đã cài. Thêm
    // các thư mục Homebrew vào cuối PATH (không đổi `git` đang dùng), và ghi
    // đường dẫn tuyệt đối của git-lfs vào filter để các lệnh Git ở bước
    // khác (không có PATH này) vẫn chạy được filter.
    def lfsScript = '''
        set -eu
        export PATH="$PATH:/opt/homebrew/bin:/usr/local/bin"
        if [ "$PEARZ_GIT_LFS_MODE" = check ]; then
            git lfs version
            exit 0
        fi
        if [ -n "${PEARZ_GIT_SSH_KEY:-}" ]; then
            export GIT_SSH_COMMAND="ssh -i '$PEARZ_GIT_SSH_KEY' -o IdentitiesOnly=yes -o BatchMode=yes"
        fi
        {
            echo .
            git submodule foreach --quiet --recursive 'echo "$displaypath"'
        } |
        while IFS= read -r repository; do
            if ! git -C "$repository" grep -q -e 'filter=lfs' -- ':(glob)**/.gitattributes'; then
                continue
            fi
            if [ "$PEARZ_GIT_LFS_MODE" = detect ]; then
                echo "$repository"
                continue
            fi
            git_lfs="$(command -v git-lfs)"
            git -C "$repository" config --local filter.lfs.clean "'$git_lfs' clean -- %f"
            git -C "$repository" config --local filter.lfs.smudge "'$git_lfs' smudge --skip -- %f"
            git -C "$repository" config --local filter.lfs.process "'$git_lfs' filter-process --skip"
            git -C "$repository" config --local filter.lfs.required true
            git -C "$repository" lfs pull < /dev/null
        done
    '''

    def lfsRepositories = withEnv(['PEARZ_GIT_LFS_MODE=detect']) {
        sh(script: lfsScript, returnStdout: true).trim()
    }
    if (!lfsRepositories) {
        return
    }

    def lfsCheckStatus = withEnv(['PEARZ_GIT_LFS_MODE=check']) {
        sh(script: lfsScript, returnStatus: true)
    }
    if (lfsCheckStatus != 0) {
        error(
            'This repository uses Git LFS, but git-lfs is not available ' +
            'on the Jenkins agent. Install it (brew install git-lfs). ' +
            'PearzCI looks in the Jenkins PATH, /opt/homebrew/bin and ' +
            '/usr/local/bin.'
        )
    }

    // GIT_TERMINAL_PROMPT=0 và timeout để bước này fail thay vì treo chờ
    // đăng nhập khi xác thực hỏng.
    def pull = {
        withEnv(['PEARZ_GIT_LFS_MODE=pull', 'GIT_TERMINAL_PROMPT=0']) {
            timeout(time: 30, unit: 'MINUTES') {
                sh lfsScript
            }
        }
    }

    if (!credentialsId) {
        pull()
        return
    }

    withCredentials([
        sshUserPrivateKey(
            credentialsId: credentialsId,
            keyFileVariable: 'PEARZ_GIT_SSH_KEY'
        )
    ]) {
        pull()
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
