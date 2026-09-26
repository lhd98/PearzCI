// Chạy một script shell đóng gói trong resources/com/pearz/ci. Script được
// ghi vào thư mục @tmp của workspace nên không làm bẩn project Unity.
def run(String name, boolean returnStatus = false) {
    def path = "${env.WORKSPACE}@tmp/pearz-ci-${name}"
    writeFile(
        file: path,
        encoding: 'UTF-8',
        text: libraryResource("com/pearz/ci/${name}")
    )

    if (returnStatus) {
        return sh(script: "bash -x '${path}'", returnStatus: true)
    }

    sh "bash -x '${path}'"
}
