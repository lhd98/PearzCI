// Build Unity WebGL và deploy lên Cloudflare Pages. Tách khỏi
// pearzUnityMobilePipeline để hàm pipeline không vượt giới hạn kích thước
// method của Jenkins CPS ("Method too large").

// Job chỉ cần WEB_DOMAIN + CLOUDFLARE_CREDENTIAL; account và project Pages
// tự suy ra trong cloudflare-pages-deploy.mjs.
def readConfig(Map config) {
    def domain = config.get('webDomain', params.WEB_DOMAIN ?: '')
        .toString().trim().toLowerCase()
        .replaceFirst(/^https?:\/\//, '').replaceAll(/\/+$/, '')
    def web = [
        domain: domain,
        credentialsId: config.get(
            'cloudflareCredentialsId', params.CLOUDFLARE_CREDENTIAL ?: ''
        ).toString().trim(),
        accountId: config.get(
            'cloudflareAccountId', params.CLOUDFLARE_ACCOUNT_ID ?: ''
        ).toString().trim(),
        // Cố định 1080x1920 (9:16) để job bớt một tham số; khung trên trang
        // tự co theo màn hình.
        resolution: config.get('webResolution', '1080x1920').toString().trim(),
        wranglerVersion: config.get('wranglerVersion', '4').toString().trim()
    ]

    if (!(domain ==~ /^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,}$/)) {
        error("WEB_DOMAIN must be a domain such as pg05.pearz.space (got '${domain}').")
    }
    if (!web.credentialsId) {
        error(
            'CLOUDFLARE_CREDENTIAL is required for WebGL builds: choose ' +
            'the Secret text credential that holds your Cloudflare API token.'
        )
    }
    if (!(web.resolution in ['720x1280', '1080x1920', '1440x2560'])) {
        error(
            'webResolution must be 720x1280, 1080x1920 or 1440x2560 ' +
            "(got '${web.resolution}')."
        )
    }

    return web
}

def validateAgent() {
    def webGlSupport = "${env.UNITY_HUB_ROOT}/${env.UNITY_VERSION}" +
        '/PlaybackEngines/WebGLSupport'
    if (!fileExists(webGlSupport)) {
        error(
            "WebGL Build Support is not installed for Unity ${env.UNITY_VERSION}. " +
            'Add the module in Unity Hub on the agent.'
        )
    }
    def nodeStatus = sh(
        script: 'export PATH="$PATH:/opt/homebrew/bin:/usr/local/bin"; node --version && npx --version',
        returnStatus: true
    )
    if (nodeStatus != 0) {
        error('Node.js 18+ is required on the agent to deploy WebGL builds (brew install node).')
    }
}

// Gọi Unity batchmode với BuildEntry.<method>, in log Unity vào console.
def runUnity(String buildTarget, String method) {
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

def buildUnity() {
    def buildStartedAt = System.currentTimeMillis()

    try {
        withEnv([
            "OUTPUT_PATH=${env.OUTPUT_PATH}",
            "APP_VERSION=${env.PEARZ_APP_VERSION}",
            "CI_BUILD_NUMBER=${env.ARTIFACT_BUILD_NUMBER}",
            "DEVELOPMENT_BUILD=${params.DEVELOPMENT_BUILD ?: false}",
            "PRODUCT_NAME=${env.PEARZ_PRODUCT_NAME ?: ''}"
        ]) {
            runUnity('WebGL', 'BuildWebGL')
        }
    } finally {
        env.BUILD_TIME_MILLIS = (
            System.currentTimeMillis() - buildStartedAt
        ).toString()
    }
}

// Sinh index.html, kiểm tra giới hạn 25 MiB của Pages, tìm account và
// project Pages theo domain (tạo mới nếu chưa có), deploy bằng wrangler rồi
// gắn domain/CNAME. Toàn bộ logic nằm trong cloudflare-pages-deploy.mjs.
def deploy(Map web) {
    writeFile(
        file: 'cloudflare-pages-deploy.mjs',
        encoding: 'UTF-8',
        text: libraryResource('com/pearz/ci/cloudflare-pages-deploy.mjs')
    )
    writeFile(
        file: 'webgl-index.html',
        encoding: 'UTF-8',
        text: libraryResource('com/pearz/ci/webgl-index.html')
    )

    def status
    withCredentials([
        string(credentialsId: web.credentialsId, variable: 'CLOUDFLARE_API_TOKEN')
    ]) {
        withEnv([
            "CLOUDFLARE_ACCOUNT_ID=${web.accountId}",
            "WEB_DOMAIN=${web.domain}",
            "WEB_RESOLUTION=${web.resolution}",
            "WEB_SITE_DIR=${env.OUTPUT_PATH}",
            "WEB_INDEX_TEMPLATE=${env.WORKSPACE}/webgl-index.html",
            "WEB_RESULT_FILE=${env.WORKSPACE}/web-deploy-result.txt",
            "WRANGLER_VERSION=${web.wranglerVersion}"
        ]) {
            status = sh(
                script: '''
                    export PATH="$PATH:/opt/homebrew/bin:/usr/local/bin"
                    node cloudflare-pages-deploy.mjs
                ''',
                returnStatus: true
            )
        }
    }

    if (fileExists('web-deploy-result.txt')) {
        readFile(file: 'web-deploy-result.txt', encoding: 'UTF-8')
            .readLines()
            .each { line ->
                def separator = line.indexOf('=')
                if (separator > 0) {
                    env[line.substring(0, separator)] = line.substring(separator + 1)
                }
            }
    }

    if (status != 0) {
        error(env.WEB_DEPLOY_ERROR ?: 'Cloudflare Pages deploy failed.')
    }
}
