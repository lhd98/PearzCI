// Gọi Unity batchmode với Pearz.CI.BuildEntry.<method> rồi in log Unity vào
// console. Dùng chung cho Android, iOS và WebGL.
def run(String buildTarget, String method) {
    withEnv([
        "PEARZ_UNITY_BUILD_TARGET=${buildTarget}",
        "PEARZ_UNITY_METHOD=Pearz.CI.BuildEntry.${method}"
    ]) {
        sh '''
            set +e

            "$UNITY_EXE" \
                -batchmode \
                -quit \
                -projectPath "$UNITY_PROJECT_PATH" \
                -buildTarget "$PEARZ_UNITY_BUILD_TARGET" \
                -executeMethod "$PEARZ_UNITY_METHOD" \
                -logFile "$BUILD_LOG_PATH"

            unity_exit_code=$?

            if [ -f "$BUILD_LOG_PATH" ]; then
                cat "$BUILD_LOG_PATH"
            fi

            exit "$unity_exit_code"
        '''
    }
}
