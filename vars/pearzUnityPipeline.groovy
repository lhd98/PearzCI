def call(Map config = [:]) {
    def platform = config.get(
        'platform',
        params.BUILD_PLATFORM ?: 'Android'
    ).toString().trim()

    switch (platform.toLowerCase(Locale.ROOT)) {
        case 'android':
            pearzUnityAndroidPipeline(config + [mobilePlatform: platform])
            break
        case 'ios':
            // Android and iOS intentionally share one Declarative Pipeline.
            // The shared graph also covers connected-device builds, so the
            // Stage View layout stays identical across BUILD_PLATFORM and
            // IOS_BUILD_TO_DEVICE; irrelevant stages are shown as skipped.
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
