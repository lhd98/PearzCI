// Helper cho build Android: adb, build metadata/BUILD_INFO và bộ đếm AAB version code.

// Thứ tự dò adb: config `adbExe` > ANDROID_HOME/ANDROID_SDK_ROOT > SDK đi
// kèm Unity (Android Build Support) > `adb` trên PATH.
def resolveAdbExe(String configuredAdbExe) {
    if (configuredAdbExe) {
        return configuredAdbExe
    }

    def adbName = 'adb'
    def candidates = []

    [env.ANDROID_HOME, env.ANDROID_SDK_ROOT].each { sdkRoot ->
        if (sdkRoot?.trim()) {
            candidates << "${sdkRoot.trim()}/platform-tools/${adbName}"
        }
    }

    def unityRoot = "${env.UNITY_HUB_ROOT}/${env.UNITY_VERSION}"
    candidates << "${unityRoot}/PlaybackEngines/AndroidPlayer/SDK/platform-tools/${adbName}"

    for (def candidate : candidates) {
        if (fileExists(candidate)) {
            return candidate
        }
    }

    return adbName
}

// Gọi được từ cả stage lẫn khối post. Lần gọi thứ hai không làm gì, nên
// build thành công không phải đọc lại metadata.
def readBuildMetadata() {
    if (env.METADATA_READ == 'true') {
        return
    }

    env.METADATA_READ = 'true'

    def metadata = [:]
    def metadataPath = env.METADATA_PATH?.trim()

    if (metadataPath && fileExists(metadataPath)) {
        try {
            def metadataOutput

            writeFile(
                file: 'read-build-metadata.sh',
                encoding: 'UTF-8',
                text: libraryResource(
                    'com/pearz/ci/read-build-metadata.sh'
                )
            )
            metadataOutput = sh(
                script:
                    'sh ./read-build-metadata.sh ' +
                    '"$METADATA_PATH"',
                returnStdout: true
            )

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

// SDK cũ của một số project sinh một report BUILD_INFO.txt riêng. Đây là
// fallback tương thích cho project không cài SDK đó: không cố tái tạo các
// section quảng cáo/Firebase, chỉ ghi các thông tin CI chắc chắn có.
def ensureAndroidBuildInfo() {
    if (fileExists(env.BUILD_INFO_PATH)) {
        echo "Build info file found: ${env.BUILD_INFO_PATH}"
        upsertAndroidBuildInfoCompressionMethod()
        return true
    }

    def productName = env.META_PRODUCT_NAME?.trim()
        ?: env.PEARZ_PRODUCT_NAME?.trim()
    def bundleIdentifier = env.META_BUNDLE_IDENTIFIER?.trim() ?: ''
    def versionName = env.META_VERSION_NAME?.trim()
        ?: (env.PEARZ_APP_VERSION ?: '')
    def versionCode = env.META_VERSION_CODE?.trim()
        ?: (params.ANDROID_VERSION_CODE?.toString()?.trim() ?: '')
    def buildType = params.BUILD_APP_BUNDLE?.toString()?.toBoolean()
        ? 'AAB'
        : 'APK'
    def generatedAt = new Date().toString()

    writeFile(
        file: env.BUILD_INFO_PATH,
        encoding: 'UTF-8',
        text: """BUILD INFO — ${generatedAt}
===============================================================

BASIC BUILD INFO
---------------------------------------------------------------
Package Name      : ${bundleIdentifier}
App Name          : ${productName}
App Version       : ${versionName}
Build Type        : ${buildType}
Build Date/Time   : ${generatedAt}
Android Version   : ${versionCode}
Unity Version     : ${env.META_UNITY_VERSION ?: env.UNITY_VERSION}
Compression Method: ${env.META_COMPRESSION_METHOD ?: 'Unknown'}
CI Version        : ${env.PEARZ_CI_VERSION}
Result            : ${env.META_RESULT ?: 'Succeeded'}

NOTE
---------------------------------------------------------------
This minimal report was generated by PearzCI because the project SDK did not
export its own BUILD_INFO.txt file.
"""
    )

    echo "Generated fallback Android build info: ${env.BUILD_INFO_PATH}"
    return true
}

// Project SDKs can supply their own BUILD_INFO.txt. Keep that report intact
// while ensuring the compression field is present and reflects this CI build.
def upsertAndroidBuildInfoCompressionMethod() {
    def compressionMethod = env.META_COMPRESSION_METHOD?.trim()

    if (!compressionMethod) {
        echo 'Compression method is unavailable in build metadata; existing BUILD_INFO.txt is unchanged.'
        return
    }

    def contents = readFile(file: env.BUILD_INFO_PATH, encoding: 'UTF-8')
    def line = "Compression Method: ${compressionMethod}"
    def pattern = /(?m)^Compression Method\s*:\s*.*(?:\r?\n)?/
    def updated = (contents =~ pattern).find()
        ? contents.replaceFirst(pattern, line + '\n')
        : contents.replaceFirst(/\s*\z/, "\n${line}\n")

    if (updated != contents) {
        writeFile(file: env.BUILD_INFO_PATH, encoding: 'UTF-8', text: updated)
        echo "Added compression method to build info: ${compressionMethod}"
    }
}

def readNextAabVersionCode() {
    def stateFile = getAabVersionCodeStateFile()

    if (!stateFile.exists()) {
        return 1
    }

    def value = stateFile.getText('UTF-8').trim()
    if (!(value ==~ /[1-9][0-9]*/)) {
        error(
            "Invalid AAB version-code counter at ${stateFile}: '${value}'. " +
            'Fix the file to a positive integer before building another AAB.'
        )
    }

    try {
        return value.toInteger()
    } catch (NumberFormatException ignored) {
        error(
            "AAB version-code counter at ${stateFile} is outside Android's " +
            'supported integer range.'
        )
    }
}

def saveNextAabVersionCode(int usedVersionCode) {
    if (usedVersionCode == Integer.MAX_VALUE) {
        error('AAB version code has reached Android\'s maximum integer value.')
    }

    def stateFile = getAabVersionCodeStateFile()
    def temporaryFile = new File(
        stateFile.parentFile,
        ".${stateFile.name}.${env.BUILD_TAG}.tmp"
    )

    temporaryFile.setText("${usedVersionCode + 1}\n", 'UTF-8')

    if (stateFile.exists() && !stateFile.delete()) {
        temporaryFile.delete()
        error("Unable to update AAB version-code counter at ${stateFile}.")
    }

    if (!temporaryFile.renameTo(stateFile)) {
        temporaryFile.delete()
        error("Unable to save AAB version-code counter at ${stateFile}.")
    }

    echo "Next AAB version code: ${usedVersionCode + 1}"
}

def getAabVersionCodeStateFile() {
    // Job root lives on the Jenkins controller, unlike a workspace it is not
    // removed by CLEAN_WORKSPACE and is shared by every macOS agent.
    def jobRoot = currentBuild.rawBuild.parent.rootDir
    return new File(jobRoot, 'pearz-ci-aab-version-code.txt')
}

// Cài APK vừa build lên mọi máy Android đang kết nối (USB hoặc Wireless
// debugging), nối lại máy Wi-Fi bị rớt trước khi cài. Ghi kết quả vào
// env.ANDROID_INSTALL_STATUS cho Telegram.
def installOnConnectedDevices(String configuredAdbExe, String androidDeviceSerial) {
    if (env.OUTPUT_EXTENSION != 'apk') {
        env.ANDROID_INSTALL_STATUS =
            'Skipped: AAB cannot be installed with adb.'
        echo env.ANDROID_INSTALL_STATUS
        return
    }

    env.ADB_EXE = resolveAdbExe(configuredAdbExe)
    env.PEARZ_ADB_SERIALS = androidDeviceSerial
        .replace(',', ' ')
    env.PEARZ_ADB_RESULT_PATH =
        "${env.WORKSPACE}/Builds/Android/device-install.txt"
    echo "adb path: ${env.ADB_EXE}"

    // Exit code: 0 = đã cài, 3 = không có máy, khác = lỗi.
    // Cookie dontKillMe giữ adb server sống qua các build
    // để máy không dây không phải dò lại mDNS mỗi lần.
    def installStatus
    installStatus = pearzScript.run('android-install-devices.sh', true)

    def installedOn = fileExists(env.PEARZ_ADB_RESULT_PATH)
        ? readFile(env.PEARZ_ADB_RESULT_PATH).trim()
        : ''

    if (installStatus == 3) {
        env.ANDROID_INSTALL_STATUS =
            'Skipped: no device connected.'
    } else if (installStatus == 0) {
        env.ANDROID_INSTALL_STATUS =
            "Installed on ${installedOn}."
    } else {
        env.ANDROID_INSTALL_STATUS = installedOn
            ? "Partially installed (${installedOn}); see log."
            : 'Install failed; see log.'
        unstable('adb install failed on at least one device.')
    }

    echo "Device install: ${env.ANDROID_INSTALL_STATUS}"
}
