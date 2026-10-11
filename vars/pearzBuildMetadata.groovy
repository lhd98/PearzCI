// Đọc build-metadata.json mà BuildEntry ghi cạnh output (Android, iOS và
// WebGL đều có) vào các biến env.META_*. Thiếu file hay hỏng file thì các
// biến để trống và thông báo dùng giá trị của Jenkins.

// Gọi được từ cả stage lẫn khối post. Lần gọi thứ hai không làm gì, nên
// build thành công không phải đọc lại metadata.
def read() {
    if (env.METADATA_READ == 'true') {
        return
    }

    env.METADATA_READ = 'true'

    def metadata = [:]
    def metadataPath = env.METADATA_PATH?.trim()

    if (metadataPath && fileExists(metadataPath)) {
        try {
            def metadataOutput

            withEnv([
                "PEARZ_READ_METADATA=${pearzScript.resource('read-build-metadata.sh')}"
            ]) {
                metadataOutput = sh(
                    script: 'sh "$PEARZ_READ_METADATA" "$METADATA_PATH"',
                    returnStdout: true
                )
            }

            metadataOutput.readLines().each { line ->
                def separatorIndex = line.indexOf('=')

                if (separatorIndex > 0) {
                    def key = line.substring(0, separatorIndex)
                    def value = line.substring(separatorIndex + 1)

                    metadata[key] = value
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
    env.META_COMPRESSION_METHOD =
        metadata.COMPRESSION_METHOD?.toString() ?: ''
    env.META_SCRIPTING_BACKEND =
        metadata.SCRIPTING_BACKEND?.toString() ?: ''
    env.META_STRIPPING_LEVEL =
        metadata.STRIPPING_LEVEL?.toString() ?: ''
    env.META_ORIENTATION = metadata.ORIENTATION?.toString() ?: ''
    env.META_OUTPUT_SIZE_BYTES =
        metadata.OUTPUT_SIZE_BYTES?.toString() ?: ''
    env.META_MAPPING_SIZE_BYTES =
        metadata.MAPPING_SIZE_BYTES?.toString() ?: ''
}
