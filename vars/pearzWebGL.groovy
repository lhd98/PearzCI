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
        // Package không chạy được trên web (SDK native Android/iOS): gỡ khỏi
        // Packages/manifest.json của workspace trước khi build WebGL. Cách
        // nhau bằng dấu phẩy/khoảng trắng/xuống dòng.
        excludePackages: config.get(
            'webExcludePackages', params.WEBGL_EXCLUDE_PACKAGES ?: ''
        ).toString().split(/[\s,;]+/).collect { it.trim() }.findAll { it },
        // Thư mục web-tool của game, tính từ thư mục project Unity. Xem
        // resolveToolDir.
        toolDir: config.get('webToolDir', 'WebTool').toString().trim()
            .replaceAll(/^\/+|\/+$/, ''),
        toolDirConfigured: config.containsKey('webToolDir'),
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

    if (web.toolDir &&
        (web.toolDir.contains('..') ||
            !(web.toolDir ==~ /[A-Za-z0-9._-]+(\/[A-Za-z0-9._-]+)*/))) {
        error(
            'webToolDir must be a folder inside the Unity project, such as ' +
            "WebTool (got '${web.toolDir}')."
        )
    }

    return web
}

// Game có thư mục web-tool (nhận diện bằng panel.html) thì trang deploy ra
// layout tool: khung game căn trái, panel của game bên phải. Không có thì giữ
// khung 9:16 như cũ. Trả về đường dẫn tuyệt đối, hoặc '' khi không dùng.
// Cần workspace đã checkout nên không gọi được trong readConfig.
def resolveToolDir(Map web) {
    if (!web.toolDir) {
        return ''
    }
    def toolDir = "${env.UNITY_PROJECT_PATH}/${web.toolDir}"
    if (fileExists("${toolDir}/panel.html")) {
        echo "WebGL: web-tool panel found in ${web.toolDir}."
        return toolDir
    }
    // Thư mục mặc định vắng mặt là chuyện thường; thư mục tự khai mà thiếu
    // thì là cấu hình sai.
    if (web.toolDirConfigured) {
        error("webToolDir '${web.toolDir}' has no panel.html (looked in ${toolDir}).")
    }
    return ''
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

// Chỉ sửa file trong workspace; lần checkout sau (git checkout -f) trả
// manifest về như trong repo, nên build Android/iOS không bị ảnh hưởng.
def removeExcludedPackages(List packages) {
    if (!packages) {
        return
    }
    if (packages.any { !(it ==~ /[a-z0-9][a-z0-9._-]*/) }) {
        error("WEBGL_EXCLUDE_PACKAGES contains an invalid package name: ${packages}")
    }
    withEnv(["PEARZ_EXCLUDE_PACKAGES=${packages.join(',')}"]) {
        sh '''
            set -eu
            export PATH="$PATH:/opt/homebrew/bin:/usr/local/bin"
            node - <<'NODE'
const fs = require('fs');
const path = require('path');
const names = process.env.PEARZ_EXCLUDE_PACKAGES.split(',');
const dir = path.join(process.env.UNITY_PROJECT_PATH, 'Packages');
for (const file of ['manifest.json', 'packages-lock.json']) {
  const full = path.join(dir, file);
  if (!fs.existsSync(full)) continue;
  const json = JSON.parse(fs.readFileSync(full, 'utf8'));
  for (const name of names) {
    if (json.dependencies && name in json.dependencies) {
      delete json.dependencies[name];
      console.log(`WebGL: removed ${name} from Packages/${file}`);
    }
  }
  fs.writeFileSync(full, JSON.stringify(json, null, 2) + '\\n');
}
for (const name of names) {
  const embedded = path.join(dir, name);
  if (fs.existsSync(embedded)) {
    console.log(`WARNING: ${name} is embedded in Packages/; it stays in the WebGL build.`);
  }
}
NODE
        '''
    }
}

def buildUnity(Map web) {
    def buildStartedAt = System.currentTimeMillis()
    // Báo cấu hình web-tool sai trước khi tốn thời gian build Unity.
    resolveToolDir(web)
    removeExcludedPackages(web.excludePackages)

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

    def toolDir = resolveToolDir(web)
    def status
    withCredentials([
        string(credentialsId: web.credentialsId, variable: 'CLOUDFLARE_API_TOKEN')
    ]) {
        withEnv([
            "CLOUDFLARE_ACCOUNT_ID=${web.accountId}",
            "WEB_DOMAIN=${web.domain}",
            "WEB_RESOLUTION=${web.resolution}",
            "WEB_SITE_DIR=${env.OUTPUT_PATH}",
            "WEB_TOOL_DIR=${toolDir}",
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
