def call(Map config = [:]) {
    def platform = config.get(
        'platform',
        params.BUILD_PLATFORM ?: 'Android'
    ).toString().trim()

    switch (platform.toLowerCase(Locale.ROOT)) {
        case 'android':
        case 'ios':
        case 'webgl':
            // Android, iOS (kể cả build cài lên máy) và WebGL dùng chung một
            // Declarative Pipeline để Stage View giữ nguyên bố cục giữa các
            // BUILD_PLATFORM; stage không liên quan hiện là skipped.
            pearzUnityMobilePipeline(config + [mobilePlatform: platform])
            break
        default:
            throw new IllegalArgumentException(
                "Unsupported BUILD_PLATFORM '${platform}'. " +
                'Choose Android, iOS or WebGL.'
            )
    }
}
