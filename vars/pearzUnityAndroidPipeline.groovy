def call(Map config = [:]) {
    def pearzCiVersion = readPearzCiVersion()
    def repositoryUrl = config.get(
        'repositoryUrl',
        params.PROJECT_REPOSITORY_URL ?: ''
    ).toString().trim()
    def repositoryCredentialsId = config.get(
        'repositoryCredentialsId',
        params.GIT_CREDENTIALS_ID ?: 'github-ssh'
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
    def windowsUnityHubRoot = config.get(
        'windowsUnityHubRoot',
        configuredUnityHubRoot ?: 'C:\\Program Files\\Unity\\Hub\\Editor'
    )
    def macUnityHubRoot = config.get(
        'macUnityHubRoot',
        configuredUnityHubRoot ?: '/Applications/Unity/Hub/Editor'
    )
    def webhookBranch = normalizeGitBranch(
        params.GIT_BRANCH?.toString()?.trim() ?: defaultGitBranch
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

                        def branchSpec = params.GIT_BRANCH?.trim()
                            ? params.GIT_BRANCH.trim()
                            : defaultGitBranch

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

                        env.OUTPUT_EXTENSION = params.BUILD_APP_BUNDLE
                            ? 'aab'
                            : 'apk'
                        env.OUTPUT_FILE_NAME =
                            "${outputName}-${env.BUILD_NUMBER}.${env.OUTPUT_EXTENSION}"
                        env.OUTPUT_PATH =
                            "${env.WORKSPACE}/Builds/Android/${env.OUTPUT_FILE_NAME}"
                        env.METADATA_PATH =
                            "${env.WORKSPACE}/Builds/Android/build-metadata.json"
                        env.MAPPING_PATH =
                            "${env.WORKSPACE}/Builds/Android/mapping.txt"
                        env.BUILD_LOG_PATH =
                            "${env.WORKSPACE}/Builds/Android/unity-build.log"
                        env.UPLOAD_LOG_PATH =
                            "${env.WORKSPACE}/Builds/Android/upload.log"
                        env.DRIVE_DIRECTORY =
                            "${env.DRIVE_REMOTE}:${env.DRIVE_ROOT}/${env.JOB_BASE_NAME}"
                        env.DRIVE_FILE_PATH =
                            "${env.DRIVE_DIRECTORY}/${env.OUTPUT_FILE_NAME}"
                        env.DRIVE_MAPPING_PATH =
                            "${env.DRIVE_DIRECTORY}/mapping-${env.BUILD_NUMBER}.txt"

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

                        env.GIT_CHANGES = collectGitChanges()
                    }
                }
            }

            stage('Show Parameters') {
                steps {
                    echo "NODE_OS = ${env.NODE_OS}"
                    echo "PRODUCT_NAME = ${params.PRODUCT_NAME}"
                    echo "BUILD_CONFIGURATION = ${params.BUILD_CONFIGURATION}"
                    echo "GIT_BRANCH = ${params.GIT_BRANCH}"
                    echo "UNITY_VERSION = ${params.UNITY_VERSION}"
                    echo "BUNDLE_IDENTIFIER = ${params.BUNDLE_IDENTIFIER}"
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
                            ? "${env.UNITY_HUB_ROOT}/${params.UNITY_VERSION}" +
                                '/Unity.app/Contents/MacOS/Unity'
                            : "${env.UNITY_HUB_ROOT}/${params.UNITY_VERSION}" +
                                '/Editor/Unity.exe'

                        env.UNITY_EXE = unityExe
                        echo "Unity path: ${unityExe}"

                        if (!fileExists(unityExe)) {
                            error("Unity not found: ${unityExe}")
                        }

                        if (isUnix()) {
                            sh "\"${unityExe}\" -version"
                        } else {
                            bat "\"${unityExe}\" -version"
                        }
                    }
                }
            }

            stage('Build Unity Android') {
                options {
                    timeout(time: 60, unit: 'MINUTES')
                }

                steps {
                    script {
                        def buildStartedAt = System.currentTimeMillis()

                        try {
                            withEnv(["OUTPUT_PATH=${env.OUTPUT_PATH}"]) {
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

            stage('Verify Artifact') {
                steps {
                    script {
                        if (!fileExists(env.OUTPUT_PATH)) {
                            error(
                                "Build artifact not found: ${env.OUTPUT_PATH}"
                            )
                        }

                        echo "Build artifact created successfully: ${env.OUTPUT_PATH}"
                    }
                }
            }

            stage('Read Build Metadata') {
                steps {
                    script {
                        readBuildMetadata()
                    }
                }
            }

            stage('Archive Artifact') {
                steps {
                    archiveArtifacts(
                        artifacts:
                            "Builds/Android/${env.OUTPUT_FILE_NAME}," +
                            'Builds/Android/build-metadata.json,' +
                            'Builds/Android/mapping.txt,' +
                            'Builds/Android/unity-build.log',
                        allowEmptyArchive: true,
                        fingerprint: true,
                        onlyIfSuccessful: true
                    )
                }
            }

            stage('Validate rclone') {
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

                            if (fileExists(env.MAPPING_PATH)) {
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

                        env.MAPPING_UPLOADED = 'false'

                        if (fileExists(env.MAPPING_PATH)) {
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
                steps {
                    script {
                        env.DOWNLOAD_URL =
                            createRcloneLink(env.DRIVE_FILE_PATH)

                        if (!env.DOWNLOAD_URL) {
                            error(
                                'ERROR: rclone did not return a public link.'
                            )
                        }

                        if (env.MAPPING_UPLOADED == 'true') {
                            env.MAPPING_URL =
                                createRcloneLink(env.DRIVE_MAPPING_PATH)
                        }

                        env.DRIVE_FOLDER_URL =
                            createRcloneLink(env.DRIVE_DIRECTORY)
                        env.DRIVE_ROOT_URL =
                            createRcloneLink(
                                "${env.DRIVE_REMOTE}:${env.DRIVE_ROOT}"
                            )

                        echo "Public download link: ${env.DOWNLOAD_URL}"
                    }
                }
            }

            stage('Archive Notification Artifacts') {
                steps {
                    // Chỉ upload.log là file mới kể từ stage 'Archive Artifact'.
                    // Các file còn lại đã được archive ở đó.
                    archiveArtifacts(
                        artifacts: 'Builds/Android/upload.log',
                        allowEmptyArchive: true,
                        fingerprint: true
                    )
                }
            }
        }

        post {
            success {
                echo "Unity Android build completed: ${env.OUTPUT_FILE_NAME}"
                echo "Uploaded to Google Drive: ${env.DRIVE_FILE_PATH}"

                script {
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
                    artifacts:
                        'Builds/Android/build-metadata.json,' +
                        'Builds/Android/unity-build.log,' +
                        'Builds/Android/upload.log',
                    allowEmptyArchive: true
                )

                // Gửi ở post chứ không phải ở stage: một stage nằm cuối
                // pipeline sẽ bị bỏ qua khi build hỏng, đúng lúc cần báo
                // nhất. Đặt trước bước dọn dẹp vì còn cần đọc metadata.
                script {
                    sendTelegramNotification(telegramCredentialsId)
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

                    if (fileExists('Builds')) {
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
    def result = currentBuild.currentResult ?: 'SUCCESS'
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
        ? 'Scripting Define Symbols:\n' +
            symbols.collect { "- ${it}" }.join('\n')
        : ''
    def changeDescription = env.GIT_CHANGES?.trim()

    def logLines = []

    if (env.JENKINS_BUILD_LOG_URL?.trim()) {
        logLines << "Build: ${env.JENKINS_BUILD_LOG_URL.trim()}"
    }

    if (env.JENKINS_UPLOAD_LOG_URL?.trim()) {
        logLines << "Upload: ${env.JENKINS_UPLOAD_LOG_URL.trim()}"
    }

    def values = [
        RESULT: result,
        JOB: "${env.JOB_NAME} #${env.BUILD_NUMBER}",
        JOB_NAME: env.JOB_NAME,
        BUILD_NUMBER: env.BUILD_NUMBER,
        BUILD_URL: env.BUILD_URL,
        PEARZ_CI_VERSION: env.PEARZ_CI_VERSION,
        VERSION: versionParts.join(' / '),
        VERSION_NAME: env.META_VERSION_NAME,
        VERSION_CODE: env.META_VERSION_CODE,
        PRODUCT_NAME: env.META_PRODUCT_NAME ?: params.PRODUCT_NAME,
        BUNDLE_ID: env.META_BUNDLE_IDENTIFIER,
        GOOGLE_PLAY_URL: googlePlayUrl,
        BRANCH: params.GIT_BRANCH,
        CONFIGURATION: params.BUILD_CONFIGURATION,
        SCRIPTING_BACKEND: env.META_SCRIPTING_BACKEND,
        STRIPPING_LEVEL: env.META_STRIPPING_LEVEL,
        ORIENTATION: env.META_ORIENTATION,
        UNITY_VERSION: env.META_UNITY_VERSION ?: params.UNITY_VERSION,
        BUILD_TIME: formatDurationMillis(env.BUILD_TIME_MILLIS),
        UPLOAD_TIME: formatDurationMillis(env.UPLOAD_TIME_MILLIS),
        TOTAL_TIME: formatDurationMillis(env.TOTAL_TIME_MILLIS),
        DRIVE_FOLDER_URL: env.DRIVE_FOLDER_URL,
        DRIVE_ROOT_URL: env.DRIVE_ROOT_URL,
        APK: apkDescription,
        AAB: aabDescription,
        MAPPING: mappingDescription,
        DEFINE_SYMBOLS_SECTION: symbolsSection,
        ERROR_SECTION: env.META_ERROR_MESSAGE?.trim()
            ? "Error:\n${env.META_ERROR_MESSAGE.trim()}"
            : '',
        CHANGES_SECTION: changeDescription
            ? "Changes:\n${changeDescription}"
            : '',
        JENKINS_LOGS_SECTION: logLines
            ? 'Jenkins Logs:\n' + logLines.join('\n')
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

        env.JENKINS_BUILD_LOG_URL =
            env.BUILD_LOG_PATH?.trim() && fileExists(env.BUILD_LOG_PATH)
                ? "${env.BUILD_URL}artifact/" +
                    'Builds/Android/unity-build.log'
                : ''
        env.JENKINS_UPLOAD_LOG_URL =
            env.UPLOAD_LOG_PATH?.trim() && fileExists(env.UPLOAD_LOG_PATH)
                ? "${env.BUILD_URL}artifact/" +
                    'Builds/Android/upload.log'
                : ''

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

def collectGitChanges() {
    // Mốc là build THÀNH CÔNG gần nhất, không phải build gần nhất. Git
    // plugin ghi lại commit ngay ở bước checkout, nên một build bị huỷ
    // (disableConcurrentBuilds abortPrevious) hoặc build hỏng vẫn kịp
    // đẩy GIT_PREVIOUS_COMMIT lên. Build chạy tới cùng sau đó sẽ tưởng
    // không có gì mới và báo "No new commits", dù chính nó tạo artifact.
    def previousBuildCommit = (
        env.GIT_PREVIOUS_SUCCESSFUL_COMMIT?.trim() ?:
        env.GIT_PREVIOUS_COMMIT?.trim()
    )
    def logOutput = ''
    def hasValidPreviousCommit = false

    if (previousBuildCommit) {
        if (isUnix()) {
            withEnv([
                "PREVIOUS_BUILD_COMMIT=${previousBuildCommit}"
            ]) {
                hasValidPreviousCommit = sh(
                    script: '''
                        git rev-parse --verify \
                            "$PREVIOUS_BUILD_COMMIT^{commit}" >/dev/null 2>&1 &&
                        git merge-base --is-ancestor \
                            "$PREVIOUS_BUILD_COMMIT" HEAD
                    ''',
                    returnStatus: true
                ) == 0
            }
        } else {
            withEnv([
                "PREVIOUS_BUILD_COMMIT=${previousBuildCommit}"
            ]) {
                hasValidPreviousCommit = bat(
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
    }

    if (isUnix()) {
        withEnv([
            "PREVIOUS_BUILD_COMMIT=${previousBuildCommit ?: ''}",
            "HAS_VALID_PREVIOUS_COMMIT=${hasValidPreviousCommit}"
        ]) {
            logOutput = sh(
                script: '''
                    if [ "$HAS_VALID_PREVIOUS_COMMIT" = "true" ]; then
                        git log --pretty=format:'%h%x09%an%x09%s' \
                            "$PREVIOUS_BUILD_COMMIT..HEAD"
                    else
                        git log -1 --pretty=format:'%h%x09%an%x09%s'
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
                        git log --pretty=format:%%h%%x09%%an%%x09%%s "%PREVIOUS_BUILD_COMMIT%..HEAD"
                    ) else (
                        git log -1 --pretty=format:%%h%%x09%%an%%x09%%s
                    )
                ''',
                returnStdout: true
            ).trim()
        }
    }

    def changes = logOutput
        .readLines()
        .collect { line ->
            def fields = line.split('\t', 3)
            if (fields.size() == 3) {
                "- ${fields[0]} - ${fields[1]}: ${fields[2]}"
            } else {
                ''
            }
        }
        .findAll { it }

    if (hasValidPreviousCommit && !changes) {
        return '- No new commits since the previous successful build.'
    }

    int maximumChanges = 20

    if (changes.size() > maximumChanges) {
        int hiddenCount = changes.size() - maximumChanges
        def limitedChanges = []

        limitedChanges.addAll(changes[0..(maximumChanges - 1)])
        limitedChanges << "- ... and ${hiddenCount} more commit(s)."
        changes = limitedChanges
    }

    return changes.join('\n')
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
