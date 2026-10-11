// Ghi một file đóng gói trong resources/com/pearz/ci vào thư mục @tmp của
// workspace và trả về đường dẫn tuyệt đối. @tmp nằm ngoài workspace nên file
// không làm bẩn project Unity và không cần dọn sau build.
def resource(String name) {
    def path = "${env.WORKSPACE}@tmp/pearz-ci-${name}"
    writeFile(
        file: path,
        encoding: 'UTF-8',
        text: libraryResource("com/pearz/ci/${name}")
    )
    return path
}

// Chạy một script shell đóng gói trong resources/com/pearz/ci.
def run(String name, boolean returnStatus = false) {
    def path = resource(name)

    if (returnStatus) {
        return sh(script: "bash -x '${path}'", returnStatus: true)
    }

    sh "bash -x '${path}'"
}
