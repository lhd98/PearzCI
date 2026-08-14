def call(Map config = [:]) {
    def platform = config.get(
        'platform',
        params.BUILD_PLATFORM ?: 'Android'
    ).toString().trim()

    switch (platform.toLowerCase(Locale.ROOT)) {
        case 'android':
        case 'ios':
            // Android and iOS intentionally share one Declarative Pipeline.
            // A fixed stage declaration keeps Jenkins Stage View stable when a
            // user changes BUILD_PLATFORM; irrelevant stages are skipped.
            pearzUnityAndroidPipeline(config + [mobilePlatform: platform])
            break
        case 'windows':
        case 'windows64':
            pearzUnityWindowsPipeline(config)
            break
        default:
            throw new IllegalArgumentException(
                "Unsupported BUILD_PLATFORM '${platform}'. " +
                'Choose Android, iOS, or Windows.'
            )
    }
}
