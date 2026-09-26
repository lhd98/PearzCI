// Helper cho build iOS: đọc Info.plist, BUILD_INFO và upload TestFlight.

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
