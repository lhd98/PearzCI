// Upload artifact lên Google Drive bằng rclone và tạo link chia sẻ. Tách khỏi
// pearzUnityMobilePipeline để hàm pipeline không vượt giới hạn kích thước
// method của Jenkins CPS ("Method too large").
def upload(boolean isIos) {
    boolean isAndroid = !isIos

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
