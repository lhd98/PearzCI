/**
 * Gửi thông báo Telegram khi PearzCI được phát hành bằng Git tag vX.Y.Z.
 *
 * Job Jenkins dùng Pipeline script (không dùng Pipeline script from SCM):
 *
 * pearzCiReleasePipeline(
 *     repositoryUrl: 'ssh://git@github.com/lhd98/PearzCI.git',
 *     gitCredentialsId: 'github-ssh'
 * )
 */
def call(Map config = [:]) {
    def repositoryUrl = config.repositoryUrl?.toString()?.trim()
    def gitCredentialsId = config.gitCredentialsId?.toString()?.trim()
    def telegramCredentialsId =
        config.get('telegramCredentialsId', 'pearz-telegram-release').toString().trim()
    def webhookCredentialsId =
        config.get('webhookCredentialsId', 'pearz-github-webhook').toString().trim()

    if (!repositoryUrl) {
        error('repositoryUrl is required.')
    }

    if (!gitCredentialsId) {
        error('gitCredentialsId is required.')
    }

    def webhookRepository = extractGitHubRepository(repositoryUrl)
    if (!webhookRepository) {
        error("repositoryUrl must point to a GitHub repository: '${repositoryUrl}'.")
    }

    pipeline {
        agent any

        triggers {
            GenericTrigger(
                genericVariables: [
                    [key: 'GITHUB_REPOSITORY', value: '$.repository.full_name'],
                    [key: 'GITHUB_REF', value: '$.ref']
                ],
                causeString: 'PearzCI release $GITHUB_REPOSITORY $GITHUB_REF',
                tokenCredentialId: webhookCredentialsId,
                printContributedVariables: false,
                printPostContent: false,
                regexpFilterText: '$GITHUB_REPOSITORY $GITHUB_REF',
                regexpFilterExpression: '^' + regexEscape(webhookRepository) +
                    ' refs/tags/v[0-9]+\\.[0-9]+\\.[0-9]+$'
            )
        }

        stages {
            stage('Validate release tag') {
                steps {
                    script {
                        def releaseRef = (env.GITHUB_REF ?: '').trim()
                        if (!(releaseRef ==~ /^refs\/tags\/v\d+\.\d+\.\d+$/)) {
                            error("Expected a release tag ref, got '${releaseRef}'.")
                        }

                        env.PEARZ_CI_TAG = releaseRef - 'refs/tags/'
                    }
                }
            }

            stage('Checkout release') {
                steps {
                    checkout([
                        $class: 'GitSCM',
                        branches: [[name: "refs/tags/${env.PEARZ_CI_TAG}"]],
                        extensions: [],
                        userRemoteConfigs: [[
                            url: repositoryUrl,
                            credentialsId: gitCredentialsId
                        ]]
                    ])
                }
            }

            stage('Verify package version') {
                steps {
                    script {
                        def packageVersion = readPackageVersion()
                        if ("v${packageVersion}" != env.PEARZ_CI_TAG) {
                            error(
                                "Tag ${env.PEARZ_CI_TAG} does not match " +
                                "package.json version ${packageVersion}."
                            )
                        }

                        env.PEARZ_CI_VERSION = "v${packageVersion}"
                        writeFile(
                            file: 'pearzci-release-notes.txt',
                            text: readReleaseNotes(packageVersion)
                        )
                    }
                }
            }
        }

        post {
            success {
                script {
                    sendReleaseTelegramNotification(telegramCredentialsId, repositoryUrl)
                }
            }
        }
    }
}

def readPackageVersion() {
    def packageJson = readFile(file: 'package.json')
    def matcher = packageJson =~ /(?m)^\s*"version"\s*:\s*"([^"]+)"/
    if (!matcher.find()) {
        error('Could not read the version from package.json.')
    }

    return matcher.group(1).trim()
}

def readReleaseNotes(String version) {
    def changelog = readFile(file: 'CHANGELOG.md').replace('\r\n', '\n')
    def heading = "## [${version}]"
    def start = changelog.indexOf(heading)

    if (start < 0) {
        return 'Release notes are not available.'
    }

    start += heading.length()
    def end = changelog.indexOf('\n## ', start)
    def notes = changelog.substring(start, end < 0 ? changelog.length() : end).trim()

    return notes ?: 'Release notes are not available.'
}

def sendReleaseTelegramNotification(String credentialsId, String repositoryUrl) {
    def commit = (env.GIT_COMMIT ?: '').trim()
    def repositoryLink = repositoryUrl
        .replaceFirst('^ssh://git@github\\.com/', 'https://github.com/')
        .replaceFirst('^git@github\\.com:', 'https://github.com/')
        .replaceFirst('\\.git$', '')
    def releaseLink = "${repositoryLink}/releases/tag/${env.PEARZ_CI_TAG}"
    def commitLink = commit ? "${repositoryLink}/commit/${commit}" : ''
    def message = """<b>PearzCI ${telegramHtmlEscape(env.PEARZ_CI_VERSION)} đã phát hành</b>

<b>Tag:</b> <code>${telegramHtmlEscape(env.PEARZ_CI_TAG)}</code>
<b>Release:</b> <a href=\"${telegramHtmlEscape(releaseLink)}\">Xem trên GitHub</a>${commitLink ? "\n<b>Commit:</b> <a href=\"${telegramHtmlEscape(commitLink)}\">${telegramHtmlEscape(commit.take(7))}</a>" : ''}

<b>Thay đổi</b>
<blockquote>${telegramHtmlEscape(readFile(file: 'pearzci-release-notes.txt'))}</blockquote>

Dev nào cần cập nhật: Unity Package Manager → PearzCI → Update, rồi commit <code>Packages/manifest.json</code> và <code>Packages/packages-lock.json</code>."""

    writeFile(file: 'pearzci-release-telegram-message.txt', text: message)

    withCredentials([string(credentialsId: credentialsId, variable: 'TELEGRAM_CHANNEL')]) {
        if (isUnix()) {
            writeFile(
                file: 'send-telegram.sh',
                text: libraryResource('com/pearz/ci/send-telegram.sh')
            )
            withEnv(['TELEGRAM_MESSAGE_FILE=pearzci-release-telegram-message.txt']) {
                sh 'sh ./send-telegram.sh'
            }
        }
        else {
            writeFile(
                file: 'send-telegram.ps1',
                text: libraryResource('com/pearz/ci/send-telegram.ps1')
            )
            withEnv(['TELEGRAM_MESSAGE_FILE=pearzci-release-telegram-message.txt']) {
                bat 'powershell -NoProfile -ExecutionPolicy Bypass -File "%WORKSPACE%\\send-telegram.ps1"'
            }
        }
    }
}

def telegramHtmlEscape(Object value) {
    return (value ?: '').toString()
        .replace('&', '&amp;')
        .replace('<', '&lt;')
        .replace('>', '&gt;')
        .replace('"', '&quot;')
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
