def call(Map config = [:]) {
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
                            "${params.TELEGRAM_CHANNEL ?: ''}".trim()
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
