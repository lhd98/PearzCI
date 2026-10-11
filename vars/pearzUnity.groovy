// Gọi Unity batchmode với Pearz.CI.BuildEntry.<method> rồi in log Unity vào
// console. Dùng chung cho Android, iOS và WebGL.
def run(String buildTarget, String method) {
    withEnv([
        "PEARZ_UNITY_BUILD_TARGET=${buildTarget}",
        "PEARZ_UNITY_METHOD=Pearz.CI.BuildEntry.${method}"
    ]) {
        // Builds/Android được giữ giữa các build. Không xoá trước thì build
        // này có thể mang theo metadata, mapping.txt hay BUILD_INFO của build
        // trước (khi Unity hỏng trước khi kịp ghi, hoặc lần này không sinh
        // ra file đó) và báo/upload sai phiên bản.
        sh '''
            set +e

            for stale_file in "${METADATA_PATH:-}" "${MAPPING_PATH:-}" "${BUILD_INFO_PATH:-}"; do
                if [ -n "$stale_file" ]; then
                    rm -f "$stale_file"
                fi
            done

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
