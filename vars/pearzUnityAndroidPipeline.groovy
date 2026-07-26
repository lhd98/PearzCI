def call(Map config = [:]) {
    def repositoryUrl = config.get('repositoryUrl', '').toString().trim()
    def repositoryCredentialsId = config.get(
        'repositoryCredentialsId',
        'github-ssh'
    ).toString().trim()
    def telegramCredentialsId = config.get(
        'telegramCredentialsId',
        ''
    ).toString().trim()
    def defaultUnityVersion = config.get('unityVersion', '6000.3.14f1')
    def defaultGitBranch = config.get('gitBranch', 'master')
    def defaultDefines = config.get('scriptingDefineSymbols', '')
    def defaultProductName = config.get('productName', '')
    def defaultBundleIdentifier = config.get('bundleIdentifier', '')
    def rcloneExe = config.get('rcloneExe', 'D:\\Tools\\rclone\\rclone.exe')
    def driveRemote = config.get('driveRemote', 'gdrive')
    def driveRoot = config.get('driveRoot', 'JenkinsBuild')
    def unityHubRoot = config.get(
        'unityHubRoot',
        'C:\\Program Files\\Unity\\Hub\\Editor'
    )

    pipeline {
        agent any

        options {
            timestamps()
            disableConcurrentBuilds()
            skipDefaultCheckout(true)
        }

        parameters {
            string(
                name: 'PRODUCT_NAME',
                defaultValue: defaultProductName,
                description: 'Optional product and artifact name override.'
            )
            choice(
                name: 'BUILD_CONFIGURATION',
                choices: ['Development', 'Release'],
                description: 'Build configuration.'
            )
            string(
                name: 'GIT_BRANCH',
                defaultValue: defaultGitBranch,
                description: 'Branch name included in build notifications.'
            )
            string(
                name: 'UNITY_VERSION',
                defaultValue: defaultUnityVersion,
                description: 'Unity version installed through Unity Hub.'
            )
            text(
                name: 'SCRIPTING_DEFINE_SYMBOLS',
                defaultValue: defaultDefines,
                description: 'Optional Android scripting define symbols.'
            )
            string(
                name: 'BUNDLE_IDENTIFIER',
                defaultValue: defaultBundleIdentifier,
                description: 'Optional Android application identifier override.'
            )
            password(
                name: 'TELEGRAM_CHANNEL',
                defaultValue: '',
                description: 'botToken|chatId|messageThreadId; separate targets with semicolons.'
            )
            choice(
                name: 'TARGET_ARCHITECTURES',
                choices: ['ARM64', 'ARMV7_ARM64'],
                description: 'Android target architectures.'
            )
            choice(
                name: 'IL2CPP_CODE_GENERATION',
                choices: ['OptimizeSize', 'OptimizeSpeed'],
                description: 'IL2CPP code generation mode.'
            )
            choice(
                name: 'MANAGED_STRIPPING_LEVEL',
                choices: ['Low', 'Medium', 'High'],
                description: 'Managed code stripping level.'
            )
            booleanParam(
                name: 'STRIP_ENGINE_CODE',
                defaultValue: true,
                description: 'Strip unused Unity engine code.'
            )
            booleanParam(
                name: 'MINIFY_RELEASE',
                defaultValue: false,
                description: 'Run Android minification for release builds.'
            )
            booleanParam(
                name: 'SCRIPT_DEBUGGING',
                defaultValue: false,
                description: 'Enable script debugging.'
            )
            booleanParam(
                name: 'UNITY_DEVELOPMENT_BUILD',
                defaultValue: false,
                description: 'Enable the Unity Development Build option.'
            )
            booleanParam(
                name: 'BUILD_APP_BUNDLE',
                defaultValue: false,
                description: 'Build an Android App Bundle instead of an APK.'
            )
            string(
                name: 'APP_VERSION',
                defaultValue: '',
                description: 'Optional bundle version override.'
            )
            string(
                name: 'ANDROID_VERSION_CODE',
                defaultValue: '',
                description: 'Optional Android version code override.'
            )
            string(
                name: 'KEYSTORE_PATH',
                defaultValue: '',
                description: 'Optional keystore path on the Jenkins machine.'
            )
            password(
                name: 'KEYSTORE_PASSWORD',
                defaultValue: '',
                description: 'Optional keystore password.'
            )
            string(
                name: 'KEY_ALIAS_NAME',
                defaultValue: '',
                description: 'Optional key alias.'
            )
            password(
                name: 'KEY_ALIAS_PASSWORD',
                defaultValue: '',
                description: 'Optional key alias password.'
            )
        }

        environment {
            RCLONE_EXE = "${rcloneExe}"
            DRIVE_REMOTE = "${driveRemote}"
            DRIVE_ROOT = "${driveRoot}"
            UNITY_HUB_ROOT = "${unityHubRoot}"
        }

        stages {
            stage('Checkout') {
                steps {
                    script {
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
                    }

                    bat '''
                        git submodule sync --recursive
                        git submodule update --init --recursive
                    '''
                }
            }

            stage('Prepare Build Variables') {
                steps {
                    script {
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
                            "${env.WORKSPACE}\\Builds\\Android\\${env.OUTPUT_FILE_NAME}"
                        env.DRIVE_DIRECTORY =
                            "${env.DRIVE_REMOTE}:${env.DRIVE_ROOT}/${env.JOB_BASE_NAME}"
                        env.DRIVE_FILE_PATH =
                            "${env.DRIVE_DIRECTORY}/${env.OUTPUT_FILE_NAME}"
                        env.GIT_COMMIT_SHORT = bat(
                            script: '@git rev-parse --short HEAD',
                            returnStdout: true
                        ).trim()
                        env.GIT_COMMIT_MESSAGE = bat(
                            script: '@git log -1 --pretty=%%s',
                            returnStdout: true
                        ).trim()
                    }
                }
            }

            stage('Show Parameters') {
                steps {
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
                        def telegramTargets = env.TELEGRAM_CHANNEL?.trim()
                            ? env.TELEGRAM_CHANNEL
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
                        def unityExe =
                            "${env.UNITY_HUB_ROOT}\\${params.UNITY_VERSION}\\Editor\\Unity.exe"

                        echo "Unity path: ${unityExe}"

                        bat """
                            if not exist "${unityExe}" (
                                echo ERROR: Unity not found:
                                echo ${unityExe}
                                exit /b 1
                            )

                            "${unityExe}" -version
                        """
                    }
                }
            }

            stage('Build Unity Android') {
                options {
                    timeout(time: 60, unit: 'MINUTES')
                }

                steps {
                    script {
                        def unityExe =
                            "${env.UNITY_HUB_ROOT}\\${params.UNITY_VERSION}\\Editor\\Unity.exe"

                        withEnv(["OUTPUT_PATH=${env.OUTPUT_PATH}"]) {
                            bat """
                                "${unityExe}" ^
                                -batchmode ^
                                -nographics ^
                                -quit ^
                                -projectPath "${env.WORKSPACE}" ^
                                -executeMethod Pearz.CI.BuildEntry.BuildAndroid ^
                                -logFile -
                            """
                        }
                    }
                }
            }

            stage('Verify Artifact') {
                steps {
                    bat """
                        if not exist "${env.OUTPUT_PATH}" (
                            echo ERROR: Build artifact not found:
                            echo ${env.OUTPUT_PATH}
                            exit /b 1
                        )

                        echo Build artifact created successfully:
                        echo ${env.OUTPUT_PATH}
                    """
                }
            }

            stage('Archive Artifact') {
                steps {
                    archiveArtifacts(
                        artifacts: "Builds/Android/${env.OUTPUT_FILE_NAME}",
                        fingerprint: true,
                        onlyIfSuccessful: true
                    )
                }
            }

            stage('Validate rclone') {
                steps {
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

            stage('Upload Google Drive') {
                options {
                    timeout(time: 30, unit: 'MINUTES')
                }

                steps {
                    retry(2) {
                        bat '''
                            "%RCLONE_EXE%" copyto "%OUTPUT_PATH%" "%DRIVE_FILE_PATH%" ^
                                --progress ^
                                --stats 10s ^
                                --retries 3 ^
                                --low-level-retries 10

                            if errorlevel 1 (
                                echo ERROR: Google Drive upload failed.
                                exit /b 1
                            )
                        '''
                    }
                }
            }

            stage('Verify Google Drive Upload') {
                steps {
                    bat '''
                        "%RCLONE_EXE%" lsjson "%DRIVE_FILE_PATH%" --files-only

                        if errorlevel 1 (
                            echo ERROR: Uploaded artifact could not be verified.
                            exit /b 1
                        )

                        echo Build artifact verified successfully on Google Drive.
                    '''
                }
            }

            stage('Create Public Link') {
                steps {
                    script {
                        def publicLink = bat(
                            script: '''@echo off
                                "%RCLONE_EXE%" link "%DRIVE_FILE_PATH%"
                            ''',
                            returnStdout: true
                        ).trim()

                        if (!publicLink) {
                            error(
                                'ERROR: rclone did not return a public link.'
                            )
                        }

                        def linkLines = publicLink.readLines()
                        env.DOWNLOAD_URL =
                            linkLines[linkLines.size() - 1].trim()

                        if (!(env.DOWNLOAD_URL ==~ /^https?:\\/\\/.+/)) {
                            error(
                                "ERROR: Invalid public link: ${env.DOWNLOAD_URL}"
                            )
                        }

                        echo "Public download link: ${env.DOWNLOAD_URL}"
                    }
                }
            }

            stage('Send Telegram') {
                when {
                    expression {
                        return telegramCredentialsId ||
                            params.TELEGRAM_CHANNEL?.trim()
                    }
                }

                steps {
                    script {
                        writeFile(
                            file: 'send-telegram.ps1',
                            encoding: 'UTF-8',
                            text: libraryResource(
                                'com/pearz/ci/send-telegram.ps1'
                            )
                        )

                        def sendTelegram = {
                            bat '''
                                powershell.exe -NoLogo -NoProfile -NonInteractive ^
                                    -ExecutionPolicy Bypass ^
                                    -File "%WORKSPACE%\\send-telegram.ps1"
                            '''
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
                    }
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
                archiveArtifacts(
                    artifacts: 'Builds/Android/*.apk,Builds/Android/*.aab',
                    allowEmptyArchive: true
                )

                bat '''
                    if exist "%WORKSPACE%\\send-telegram.ps1" (
                        del /F /Q "%WORKSPACE%\\send-telegram.ps1"
                    )

                    if exist "%WORKSPACE%\\Builds" (
                        echo Cleaning build output...
                        rmdir /S /Q "%WORKSPACE%\\Builds"
                    )
                '''
            }
        }
    }
}
