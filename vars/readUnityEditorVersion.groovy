def call() {
    final String projectVersionPath = 'ProjectSettings/ProjectVersion.txt'

    if (!fileExists(projectVersionPath)) {
        error("Unity project version file not found: ${projectVersionPath}")
    }

    def versionLine = readFile(projectVersionPath)
        .readLines()
        .find { it.startsWith('m_EditorVersion:') }
    def unityVersion = versionLine
        ?.substring('m_EditorVersion:'.length())
        ?.trim()

    if (!unityVersion) {
        error("m_EditorVersion is missing in ${projectVersionPath}")
    }

    return unityVersion
}
