// Thông báo Telegram cho build Android/iOS: dựng nội dung từ template và gửi.

// Tiêu đề phải nói ngay build đậu hay hỏng. Không rút mọi kết quả khác
// SUCCESS thành FAILED: build bị abort hoặc UNSTABLE mà báo "FAILED" là sai
// sự thật, và UNSTABLE chính là trạng thái khi gửi Telegram bị lỗi.
def telegramBuildStatus() {
    def result = currentBuild.currentResult ?: 'SUCCESS'
    return result == 'FAILURE' ? 'FAILED' : result
}

def buildTelegramMessage() {
    return renderWithinTelegramLimit(env.GIT_CHANGES?.trim()) { changes ->
        renderAndroidTelegramMessage(changes)
    }
}

// Caption của sendDocument chỉ được 1024 ký tự (sendMessage là 4096), nên
// bỏ hẳn phần Changes: đó là phần duy nhất dài không giới hạn. Trả về chuỗi
// rỗng khi phần còn lại vẫn quá dài: lúc đó giữ cách gửi tin nhắn rồi file
// riêng.
def buildTelegramCaption() {
    def caption = renderAndroidTelegramMessage('')

    return telegramVisibleLength(caption) <= 1024 ? caption : ''
}

// Telegram tính giới hạn trên text sau khi parse: thẻ HTML không tính, mỗi
// entity tính một ký tự.
def telegramVisibleLength(String message) {
    return (message ?: '')
        .replaceAll(/<[^>]+>/, '')
        .replace('&lt;', '<')
        .replace('&gt;', '>')
        .replace('&amp;', '&')
        .length()
}

def renderAndroidTelegramMessage(String changeDescription) {
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
        ERROR_SECTION: telegramErrorSection(env.META_ERROR_MESSAGE),
        CHANGES_SECTION: telegramChangesSection(changeDescription)
    ]

    return renderTelegramTemplate(values)
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

        errorSection = telegramErrorSection(reason)
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
        ERROR_SECTION: errorSection
    ]

    return renderWithinTelegramLimit(changeDescription) { changes ->
        renderTelegramTemplate(
            values + [CHANGES_SECTION: telegramChangesSection(changes)]
        )
    }
}

// Telegram sendMessage từ chối text dài hơn 4096 ký tự (đếm sau khi parse
// HTML). Không cắt chuỗi HTML đã render: điểm cắt có thể rơi vào giữa một thẻ
// và Telegram từ chối cả tin. Thay vào đó bớt phần Changes - phần duy nhất dài
// không giới hạn - theo từng dòng commit rồi render lại; vẫn quá dài thì bỏ
// hẳn phần đó.
def renderWithinTelegramLimit(String changeDescription, Closure render) {
    int maximumLength = 4096
    def message = render(changeDescription)
    int overflow = telegramVisibleLength(message) - maximumLength

    if (overflow <= 0 || !changeDescription) {
        return message
    }

    // Phần Changes đếm đúng bằng độ dài chuỗi gốc (mỗi entity tính một ký
    // tự), nên bớt đi overflow ký tự là vừa giới hạn.
    def notice = '- ... (more changes omitted)'
    int budget = changeDescription.length() - overflow - notice.length() - 1
    def kept = []
    int used = 0

    for (def line : changeDescription.readLines()) {
        int cost = line.length() + (kept ? 1 : 0)

        if (used + cost > budget) {
            // Commit mới nhất đã dài quá: giữ phần đầu của nó.
            if (!kept && budget > 3) {
                kept << line.take(budget - 3) + '...'
            }
            break
        }

        kept << line
        used += cost
    }

    message = render(kept ? (kept + [notice]).join('\n') : '')

    return telegramVisibleLength(message) <= maximumLength
        ? message
        : render('')
}

def telegramChangesSection(String changeDescription) {
    return changeDescription
        ? "<b>Changes</b>\n<blockquote>${telegramHtmlEscape(changeDescription)}</blockquote>"
        : ''
}

// Lỗi Unity/Cloudflare có thể rất dài (stack, danh sách file). Giới hạn để
// phần còn lại của tin luôn vừa 4096 ký tự khi đã bỏ phần Changes.
def telegramErrorSection(String reason) {
    def text = reason?.trim()

    if (!text) {
        return ''
    }

    int maximumLength = 1000

    if (text.length() > maximumLength) {
        text = text.take(maximumLength - 3) + '...'
    }

    return "<blockquote><b>Error</b>\n${telegramHtmlEscape(text)}</blockquote>"
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

def sendTelegramNotification(
    String telegramCredentialsId,
    boolean telegramSilent = false
) {
    sendTelegramText(
        telegramCredentialsId,
        telegramSilent,
        {
            // Build hỏng thì stage 'Verify & Archive Artifact' chưa đọc
            // metadata; lần gọi thứ hai không làm gì.
            pearzAndroid.readBuildMetadata()
            buildTelegramMessage()
        },
        { buildTelegramCaption() }
    )
}

// Dùng cho cả iOS IPA lẫn iOS device. Trước đây chỉ device build mới được
// báo, còn IPA thì im lặng hoàn toàn. iOS luôn chạy trên macOS nên chỉ cần
// nhánh sh.
def sendIosTelegramNotification(
    String telegramCredentialsId,
    boolean deviceBuild,
    boolean telegramSilent = false
) {
    sendTelegramText(telegramCredentialsId, telegramSilent) {
        buildIosTelegramMessage(deviceBuild)
    }
}

// WebGL không có file để gửi: chỉ một tin nhắn kèm link chơi trên web.
def sendWebTelegramNotification(
    String telegramCredentialsId,
    boolean telegramSilent = false
) {
    sendTelegramText(telegramCredentialsId, telegramSilent) {
        buildWebTelegramMessage()
    }
}

def buildWebTelegramMessage() {
    boolean succeeded = (currentBuild.currentResult ?: 'SUCCESS') == 'SUCCESS'
    def changeDescription = env.GIT_CHANGES?.trim()
    def errorSection = ''

    if (!succeeded) {
        def reason = env.WEB_DEPLOY_ERROR?.trim() ?:
            env.META_ERROR_MESSAGE?.trim() ?:
            'The WebGL build was not deployed.'
        errorSection = telegramErrorSection(reason)
    }

    def values = [
        PLATFORM: 'WEBGL',
        STATUS: telegramBuildStatus(),
        PEARZ_CI_VERSION: telegramHtmlEscape(env.PEARZ_CI_VERSION),
        VERSION: telegramHtmlEscape(env.BUILD_VERSION),
        PRODUCT_NAME: telegramHtmlEscape(env.PEARZ_PRODUCT_NAME),
        BRANCH: telegramHtmlEscape(params.GIT_BRANCH),
        WEB: telegramHtmlEscape(succeeded ? env.WEB_URL : ''),
        ERROR_SECTION: errorSection
    ]

    return renderWithinTelegramLimit(changeDescription) { changes ->
        renderTelegramTemplate(
            values + [CHANGES_SECTION: telegramChangesSection(changes)]
        )
    }
}

// buildCaption khác null: kèm file build (APK/AAB) khi job bật
// telegramSendFile, build thành công và file còn trong workspace. Nội dung
// thông báo đi làm caption, chung một tin; telegram-message.txt vẫn được ghi
// để gửi thay thế khi gửi file hỏng hoặc caption quá dài.
def sendTelegramText(
    String telegramCredentialsId,
    boolean telegramSilent,
    Closure buildMessage,
    Closure buildCaption = null
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
            text: buildMessage()
        )

        def documentPath = ''
        def captionFile = ''
        if (buildCaption != null &&
            env.TELEGRAM_SEND_FILE == 'true' &&
            currentBuild.currentResult == 'SUCCESS' &&
            env.OUTPUT_PATH?.trim() &&
            fileExists(env.OUTPUT_PATH)) {
            documentPath = env.OUTPUT_PATH
            def caption = buildCaption()

            if (caption) {
                captionFile = 'telegram-caption.txt'
                writeFile(
                    file: captionFile,
                    encoding: 'UTF-8',
                    text: caption
                )
            }
        }

        def sendTelegram = {
            withEnv([
                'TELEGRAM_MESSAGE_FILE=telegram-message.txt',
                "TELEGRAM_SILENT=${telegramSilent}",
                "TELEGRAM_DOCUMENT_PATH=${documentPath}",
                "TELEGRAM_CAPTION_FILE=${captionFile}",
                "PEARZ_SEND_TELEGRAM=${pearzScript.resource('send-telegram.sh')}"
            ]) {
                sh 'sh "$PEARZ_SEND_TELEGRAM"'
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
