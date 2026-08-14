def call(Map config = [:]) {
    def repositoryUrl = config.get(
        'repositoryUrl', params.PROJECT_REPOSITORY_URL ?: ''
    ).toString().trim()
    def credentialsId = config.get(
        'repositoryCredentialsId', 'github-ssh'
    ).toString().trim()
    def defaultGitBranch = config.get('gitBranch', 'master').toString()
    def unityHubRoot = config.get(
        'windowsUnityHubRoot', 'C:\\Program Files\\Unity\\Hub\\Editor'
    ).toString()

    pipeline {
        agent any

        options {
            timestamps()
            disableConcurrentBuilds(abortPrevious: true)
            skipDefaultCheckout(true)
        }

        stages {
            stage('Checkout') {
                steps {
                    script {
                        if (isUnix()) {
                            error('Windows standalone builds require a Windows Jenkins agent.')
                        }
                        if (!repositoryUrl) {
                            error('PROJECT_REPOSITORY_URL is required.')
                        }
                        if (params.CLEAN_WORKSPACE?.toString()?.toBoolean()) {
                            deleteDir()
                        }
                        def branch = params.GIT_BRANCH?.trim() ?: defaultGitBranch
                        checkout([
                            $class: 'GitSCM',
                            branches: [[name: branch]],
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
                            userRemoteConfigs: [[credentialsId: credentialsId, url: repositoryUrl]]
                        ])
                        bat 'git submodule sync --recursive && git submodule update --init --recursive'
                    }
                }
            }

            stage('Build Windows Player') {
                options { timeout(time: 60, unit: 'MINUTES') }
                steps {
                    script {
                        def unityVersion = readUnityEditorVersion()
                        env.UNITY_VERSION = unityVersion
                        env.UNITY_EXE = "${unityHubRoot}/${unityVersion}/Editor/Unity.exe"
                        if (!fileExists(env.UNITY_EXE)) {
                            error("Unity with Windows Build Support was not found: ${env.UNITY_EXE}")
                        }
                        bat '''
                            if exist "%WORKSPACE%\\Builds\\Windows" rmdir /s /q "%WORKSPACE%\\Builds\\Windows"
                        '''
                        def name = (params.PRODUCT_NAME?.trim() ?: env.JOB_BASE_NAME)
                            .replaceAll('[<>:"\\\\/|?*]', '_')
                        env.OUTPUT_FILE_NAME = "${name}-${env.BUILD_NUMBER}.exe"
                        env.OUTPUT_PATH = "${env.WORKSPACE}/Builds/Windows/${env.OUTPUT_FILE_NAME}"
                        env.BUILD_LOG_PATH = "${env.WORKSPACE}/Builds/Windows/unity-build.log"
                        def appVersion = params.APP_VERSION?.trim()
                            ? "${params.APP_VERSION.trim()}-${env.BUILD_NUMBER}"
                            : "${env.BUILD_NUMBER}"

                        withEnv([
                            "OUTPUT_PATH=${env.OUTPUT_PATH}",
                            "APP_VERSION=${appVersion}",
                            "PRODUCT_NAME=${params.PRODUCT_NAME ?: ''}",
                            "SCRIPTING_DEFINE_SYMBOLS=${params.SCRIPTING_DEFINE_SYMBOLS ?: ''}",
                            "IL2CPP_CODE_GENERATION=${params.IL2CPP_CODE_GENERATION ?: ''}",
                            "MANAGED_STRIPPING_LEVEL=${params.MANAGED_STRIPPING_LEVEL ?: ''}",
                            "STRIP_ENGINE_CODE=${params.STRIP_ENGINE_CODE ?: ''}",
                            "UNITY_DEVELOPMENT_BUILD=${params.UNITY_DEVELOPMENT_BUILD ?: false}",
                            "SCRIPT_DEBUGGING=${params.SCRIPT_DEBUGGING ?: false}"
                        ]) {
                            bat '''
                                @echo off
                                "%UNITY_EXE%" -batchmode -nographics -quit ^
                                    -projectPath "%WORKSPACE%" ^
                                    -buildTarget StandaloneWindows64 ^
                                    -executeMethod Pearz.CI.BuildEntry.BuildWindows ^
                                    -logFile "%BUILD_LOG_PATH%"
                                set RESULT=%ERRORLEVEL%
                                if exist "%BUILD_LOG_PATH%" type "%BUILD_LOG_PATH%"
                                exit /b %RESULT%
                            '''
                        }
                    }
                }
            }

            stage('Archive Windows Player') {
                steps {
                    script {
                        if (!fileExists(env.OUTPUT_PATH)) {
                            error("Windows executable was not created: ${env.OUTPUT_PATH}")
                        }
                    }
                    archiveArtifacts(
                        artifacts: 'Builds/Windows/**',
                        allowEmptyArchive: false,
                        fingerprint: true,
                        onlyIfSuccessful: true
                    )
                }
            }
        }

        post {
            always {
                archiveArtifacts(
                    artifacts: 'Builds/Windows/build-metadata.json,Builds/Windows/unity-build.log',
                    allowEmptyArchive: true
                )
                script {
                    sendWindowsTelegramNotification()
                }
            }
        }
    }
}

def sendWindowsTelegramNotification() {
    if (!"${params.TELEGRAM_CHANNEL ?: ''}".trim()) return

    def result = currentBuild.currentResult ?: 'SUCCESS'
    def lines = [
        "<b>WINDOWS BUILD ${telegramHtmlEscape(result)}</b>",
        '━━━━━━━━━━━━━━━━━━',
        "<b>Job:</b> ${telegramHtmlEscape("${env.JOB_NAME} #${env.BUILD_NUMBER}")}",
        "<b>Product:</b> ${telegramHtmlEscape(params.PRODUCT_NAME ?: env.JOB_BASE_NAME)}",
        "<b>Branch:</b> <code>${telegramHtmlEscape(params.GIT_BRANCH ?: '')}</code>",
        "<b>Unity:</b> ${telegramHtmlEscape(env.UNITY_VERSION ?: '')}",
        "<b>Jenkins:</b> ${telegramHtmlEscape(env.BUILD_URL)}",
        env.BUILD_LOG_PATH?.trim() && fileExists(env.BUILD_LOG_PATH)
            ? "<b>Unity log:</b> ${telegramHtmlEscape("${env.BUILD_URL}artifact/Builds/Windows/unity-build.log")}" : ''
    ].findAll { it }.join('\n')

    try {
        writeFile(file: 'telegram-message.txt', encoding: 'UTF-8', text: lines)
        writeFile(file: 'send-telegram.ps1', encoding: 'UTF-8',
            text: libraryResource('com/pearz/ci/send-telegram.ps1'))
        withEnv([
            "TELEGRAM_CHANNEL=${params.TELEGRAM_CHANNEL}",
            'TELEGRAM_MESSAGE_FILE=telegram-message.txt'
        ]) {
            bat 'powershell -ExecutionPolicy Bypass -File "%WORKSPACE%\\send-telegram.ps1"'
        }
    } catch (Exception exception) {
        echo("Telegram notification failed: ${exception.message}")
        if (currentBuild.currentResult == 'SUCCESS') currentBuild.result = 'UNSTABLE'
    }
}

def telegramHtmlEscape(Object value) {
    value?.toString()?.replace('&', '&amp;')?.replace('<', '&lt;')
        ?.replace('>', '&gt;')?.replace('"', '&quot;') ?: ''
}
