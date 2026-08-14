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
            // The unified graph handles iOS IPA exports, but a connected-device
            // build needs the direct-signing flow in the dedicated pipeline.
            // Keep the job entry point stable for callers using
            // pearzUnityPipeline() with IOS_BUILD_TO_DEVICE=true.
            def iosBuildToDevice = config.get(
                'iosBuildToDevice', params.IOS_BUILD_TO_DEVICE ?: false
            ).toString().trim().toBoolean()
            if (iosBuildToDevice) {
                pearzUnityIosPipeline(config)
                break
            }
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
