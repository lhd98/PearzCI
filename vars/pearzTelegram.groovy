// Thông báo Telegram cho build Android/iOS: dựng nội dung từ template và gửi.

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

def sendTelegramNotification(String telegramCredentialsId) {
    boolean telegramConfigured = telegramCredentialsId ||
        "${params.TELEGRAM_CHANNEL ?: ''}".trim()

    if (!telegramConfigured) {
        echo 'No Telegram target configured; notification skipped.'
        return
    }

    try {
        // Build hỏng thì stage 'Read Build Metadata' chưa từng chạy.
        pearzAndroid.readBuildMetadata()

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
// nhánh sh.
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
