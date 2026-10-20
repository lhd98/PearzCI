// Helper cho build iOS: đọc Info.plist, BUILD_INFO và upload TestFlight.

// Version thật của bản build nằm trong Info.plist mà Unity vừa sinh ra (Xcode
// có thể còn đổi nó, build-metadata.json thì không). Đọc từ đó thay vì
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

// Không có API key: export lại archive với destination=upload để Xcode tự đẩy
// lên App Store Connect bằng Apple ID trong Xcode > Settings > Accounts của
// user chạy Jenkins (giống Distribute App > TestFlight & App Store). Archive
// còn nguyên vì thư mục Builds chỉ bị xoá ở post. manageAppVersionAndBuildNumber
// tắt để Xcode không tự đổi build number, giữ bản trên TestFlight trùng IPA
// đã đưa lên Drive.
def uploadArchiveToTestFlightWithXcodeAccount(String exportOptionsPath) {
    if (!exportOptionsPath || !fileExists(exportOptionsPath)) {
        error(
            'Cannot upload to TestFlight: the export options used for the IPA ' +
            "were not found (${exportOptionsPath ?: 'none'})."
        )
    }
    if (!isStoreExport(exportOptionsPath)) {
        error(
            'UPLOAD_TO_TESTFLIGHT needs an App Store export (method ' +
            'app-store or app-store-connect); the configured export options ' +
            'use another method.'
        )
    }

    try {
        withEnv(["IOS_EXPORT_OPTIONS=${exportOptionsPath}"]) {
            sh '''
                set -eu
                [ -d "$ARCHIVE_PATH" ]
                upload_options="$WORKSPACE/PearzCI-ExportOptions-Upload.plist"
                upload_dir="$EXPORT_PATH-upload"
                upload_log="$WORKSPACE/Builds/iOS/testflight-upload.log"
                cp "$IOS_EXPORT_OPTIONS" "$upload_options"
                for key in destination manageAppVersionAndBuildNumber; do
                    /usr/libexec/PlistBuddy -c "Delete :$key" "$upload_options" 2>/dev/null || true
                done
                /usr/libexec/PlistBuddy -c 'Add :destination string upload' "$upload_options"
                /usr/libexec/PlistBuddy -c 'Add :manageAppVersionAndBuildNumber bool false' "$upload_options"
                rm -rf "$upload_dir"
                trap 'rm -f "$upload_options"; [ ! -f "$upload_log" ] || cat "$upload_log"' EXIT
                echo 'Uploading to App Store Connect with the Apple ID signed in to Xcode...'
                xcodebuild -exportArchive -allowProvisioningUpdates \
                    -archivePath "$ARCHIVE_PATH" \
                    -exportOptionsPlist "$upload_options" \
                    -exportPath "$upload_dir" > "$upload_log" 2>&1
            '''
        }

        env.TESTFLIGHT_STATUS =
            'Uploaded; App Store Connect is still processing the build.'
        echo 'Archive uploaded to App Store Connect for TestFlight.'
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

// Đường dẫn export options do job khai; rỗng nghĩa là PearzCI tự sinh file
// method app-store lúc export IPA.
def exportOptionsPath(Map config) {
    return config.get(
        'iosExportOptionsPlistPath',
        params.IOS_EXPORT_OPTIONS_PLIST_PATH ?: ''
    ).toString().trim()
}

// IPA xuất theo method app-store là bản lên App Store Connect: chính file đó
// được TestFlight rồi nộp duyệt, Apple không cho build lại ở giữa, nên nó
// được biên dịch với RELEASE như AAB. Export options tự khai mà method khác
// (ad-hoc, development, enterprise...) là bản cài thử, không phải bản store.
def isStoreExport(String exportOptionsPath) {
    if (!exportOptionsPath) {
        return true
    }
    if (!fileExists(exportOptionsPath)) {
        // Stage export sẽ báo lỗi đường dẫn; ở đây coi như không phải store.
        return false
    }

    def method = withEnv(["IOS_EXPORT_OPTIONS=${exportOptionsPath}"]) {
        sh(
            script:
                '/usr/libexec/PlistBuddy -c "Print :method" ' +
                '"$IOS_EXPORT_OPTIONS" 2>/dev/null || true',
            returnStdout: true
        ).trim()
    }

    return method in ['app-store', 'app-store-connect']
}
