def call(Map config = [:]) {
    def platform = config.get(
        'platform',
        params.BUILD_PLATFORM ?: 'Android'
    ).toString().trim()

    switch (platform.toLowerCase(Locale.ROOT)) {
        case 'android':
            pearzUnityAndroidPipeline(config)
            break
        case 'ios':
            pearzUnityIosPipeline(config)
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
