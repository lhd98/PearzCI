def call(Map config = [:]) {
    def platform = config.get(
        'platform',
        params.BUILD_PLATFORM ?: 'Android'
    ).toString().trim()

    switch (platform.toLowerCase(Locale.ROOT)) {
        case 'android':
            pearzUnityMobilePipeline(config + [mobilePlatform: platform])
            break
        case 'ios':
            // Android and iOS intentionally share one Declarative Pipeline.
            // The shared graph also covers connected-device builds, so the
            // Stage View layout stays identical across BUILD_PLATFORM and
            // IOS_BUILD_TO_DEVICE; irrelevant stages are shown as skipped.
            pearzUnityMobilePipeline(config + [mobilePlatform: platform])
            break
        case 'webgl':
            // WebGL dùng chung graph để Stage View giữ nguyên bố cục; các
            // stage Android/iOS hiện là skipped.
            pearzUnityMobilePipeline(config + [mobilePlatform: 'WebGL'])
            break
        default:
            throw new IllegalArgumentException(
                "Unsupported BUILD_PLATFORM '${platform}'. " +
                'Choose Android, iOS or WebGL.'
            )
    }
}
