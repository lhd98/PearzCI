// Deploy một bản build Unity WebGL lên Cloudflare Pages.
//
// Game không có web-tool: một project Pages, gắn WEB_DOMAIN, chứa bản build.
// Game có web-tool (WEB_TOOL_DIR): hai project —
//   <project>        gắn WEB_DOMAIN: trang khung + thư mục tool/ (nhỏ, deploy lại không cần build Unity)
//   <project>-game   địa chỉ *.pages.dev: bản build, hiện trong iframe của trang khung
//
// Đầu vào (biến môi trường):
//   CLOUDFLARE_API_TOKEN   token của dev (bắt buộc)
//   CLOUDFLARE_ACCOUNT_ID  tuỳ chọn; để trống thì lấy tài khoản duy nhất token thấy
//   WEB_DOMAIN             domain người chơi mở, ví dụ pg05.pearz.space (bắt buộc)
//   WEB_DEPLOY_PARTS       all (mặc định) | tool — tool: chỉ deploy lại trang khung + tool/,
//                          không cần bản build (project game phải có sẵn từ một lần deploy đầy đủ)
//   WEB_SITE_DIR           thư mục site Unity xuất ra (có Build/); không cần khi WEB_DEPLOY_PARTS=tool
//   WEB_INDEX_TEMPLATE     file webgl-index.html của PearzCI (trang game)
//   WEB_RESOLUTION         720x1280 | 1080x1920 | 1440x2560
//   WEB_TOOL_DIR           tuỳ chọn; thư mục web-tool của game (có panel.html)
//   WEB_TOOL_TEMPLATE      file webgl-tool-index.html của PearzCI (trang khung); cần khi có WEB_TOOL_DIR
//   WEB_TOOL_SITE_DIR      thư mục tạm để dựng site tool; cần khi có WEB_TOOL_DIR
//   WEB_RESULT_FILE        file ghi kết quả KEY=VALUE cho Jenkins
//   PEARZ_PRODUCT_NAME, BUILD_VERSION, GIT_COMMIT_SHORT, GIT_COMMIT_MESSAGE
//   WRANGLER_VERSION       mặc định 4
//
// Các bước: kiểm tra đầu vào + giới hạn 25 MiB -> tìm account -> tìm project Pages đang gắn domain
// (không có thì tạo) -> [có tool: tìm/tạo project game, deploy game, dựng site tool] -> wrangler deploy ->
// gắn domain + CNAME nếu còn thiếu.

import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const API = 'https://api.cloudflare.com/client/v4';
const MAX_FILE_BYTES = 25 * 1024 * 1024;
const RESOLUTIONS = ['720x1280', '1080x1920', '1440x2560'];

// Đặt trước mọi lời gọi fail(): fail ném Stop thay vì process.exit (xem fail).
process.on('uncaughtException', (error) => {
    if (!(error instanceof Stop)) {
        console.error(error);
    }
    process.exitCode = 1;
});

const token = required('CLOUDFLARE_API_TOKEN');
const domain = required('WEB_DOMAIN').trim().toLowerCase();
const resultFile = required('WEB_RESULT_FILE');
const toolOnly = (process.env.WEB_DEPLOY_PARTS || 'all').trim().toLowerCase() === 'tool';
const toolDir = (process.env.WEB_TOOL_DIR || '').trim();
const siteDir = toolOnly ? '' : required('WEB_SITE_DIR');

class CloudflareError extends Error {
    constructor(message, status, codes) {
        super(message);
        this.status = status;
        this.codes = codes;
    }
}

function required(name) {
    const value = process.env[name];
    if (!value || !value.trim()) {
        fail(`${name} is required.`);
    }
    return value;
}

function fail(message) {
    console.error(`ERROR: ${message}`);
    try {
        fs.appendFileSync(
            process.env.WEB_RESULT_FILE || 'web-deploy-result.txt',
            `WEB_DEPLOY_ERROR=${message.replace(/\r?\n/g, ' ')}\n`
        );
    } catch {
        // Kết quả chỉ để báo lỗi gọn hơn trên Telegram.
    }
    // Không gọi process.exit: trên Windows, thoát ngay khi fetch còn dở làm Node sập với
    // "Assertion failed: !(handle->flags & UV_HANDLE_CLOSING)". Ném Stop để script tự chạy hết.
    process.exitCode = 1;
    throw new Stop();
}

// Dấu hiệu "đã báo lỗi xong, dừng lại"; là function để dùng được ở phần khởi tạo đầu file.
function Stop() {}

async function cf(method, apiPath, body) {
    const response = await fetch(API + apiPath, {
        method,
        headers: {
            Authorization: `Bearer ${token}`,
            'Content-Type': 'application/json'
        },
        body: body === undefined ? undefined : JSON.stringify(body)
    });
    let json = {};
    try {
        json = await response.json();
    } catch {
        // Cloudflare luôn trả JSON; phòng khi proxy trả HTML.
    }
    if (!response.ok || json.success === false) {
        const errors = json.errors || [];
        const detail = errors.map((e) => `${e.code}: ${e.message}`).join('; ') ||
            `HTTP ${response.status}`;
        throw new CloudflareError(
            `${method} ${apiPath} failed (${detail})`,
            response.status,
            errors.map((e) => e.code)
        );
    }
    return json;
}

const escapeHtml = (text) => text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
// JSON nằm trong <script>: chặn "</script>" thoát khỏi thẻ.
const escapeJs = (value) => JSON.stringify(value).replace(/</g, '\\u003c');

function renderTemplate(templateEnv, values, outputFile) {
    const template = fs.readFileSync(required(templateEnv), 'utf8');
    const output = template.replace(/\{\{([A-Z_]+)\}\}/g, (placeholder, key) =>
        Object.prototype.hasOwnProperty.call(values, key) ? values[key] : placeholder
    );
    fs.writeFileSync(outputFile, output);
}

function frameSize() {
    const resolution = (process.env.WEB_RESOLUTION || '1080x1920').trim();
    if (!RESOLUTIONS.includes(resolution)) {
        fail(`WEB_RESOLUTION must be one of ${RESOLUTIONS.join(', ')} (got '${resolution}').`);
    }
    const [width, height] = resolution.split('x');
    return { resolution, width, height };
}

function readPanel() {
    const panelFile = path.join(toolDir, 'panel.html');
    if (!fs.existsSync(panelFile)) {
        fail(`The web-tool folder has no panel.html: ${toolDir}`);
    }
    const panel = fs.readFileSync(panelFile, 'utf8').replace(/^﻿/, '');
    if (/<(!doctype|html|head|body)[\s>]/i.test(panel)) {
        fail(
            'panel.html must be an HTML fragment (the content of the panel only), ' +
            'without <!DOCTYPE>, <html>, <head> or <body>.'
        );
    }
    return panel;
}

// Site tool: trang khung (iframe trỏ sang gameUrl + panel của game) và cả thư mục web-tool tại tool/.
// panel.html nằm thẳng trong index.html nên đường dẫn tương đối trong đó tính từ gốc site (tool/anh.png).
function writeToolSite(gameUrl) {
    const toolSiteDir = required('WEB_TOOL_SITE_DIR');
    // Thư mục này bị xoá rồi dựng lại: chỉ xoá khi nó đúng là site tool của lần trước.
    if (fs.existsSync(toolSiteDir) &&
        fs.readdirSync(toolSiteDir).some((name) => name !== 'index.html' && name !== 'tool')) {
        fail(`WEB_TOOL_SITE_DIR holds other files and will not be overwritten: ${toolSiteDir}`);
    }
    fs.rmSync(toolSiteDir, { recursive: true, force: true });
    fs.mkdirSync(toolSiteDir, { recursive: true });

    const panel = readPanel();
    fs.cpSync(toolDir, path.join(toolSiteDir, 'tool'), { recursive: true });
    const has = (name) => fs.existsSync(path.join(toolDir, name));
    // File tool đổi mà không build lại game, nên mã chống cache theo lần deploy chứ không theo version game.
    const toolId = Date.now().toString(36);
    const productName = process.env.PEARZ_PRODUCT_NAME || 'Game';
    const { width, height } = frameSize();
    renderTemplate('WEB_TOOL_TEMPLATE', {
        PRODUCT_NAME: escapeHtml(productName),
        PRODUCT_NAME_JSON: escapeJs(productName),
        WIDTH: width,
        HEIGHT: height,
        GAME_URL_JSON: escapeJs(gameUrl),
        TOOL_HEAD: has('tool.css')
            ? `<link rel="stylesheet" href="tool/tool.css?v=${toolId}">`
            : '',
        TOOL_PANEL: panel,
        TOOL_SCRIPT: has('tool.js')
            ? `<script src="tool/tool.js?v=${toolId}"></script>`
            : ''
    }, path.join(toolSiteDir, 'index.html'));
    console.log(
        `Web-tool site written from ${toolDir} (game ${gameUrl}, ` +
        `tool.css: ${has('tool.css') ? 'yes' : 'no'}, tool.js: ${has('tool.js') ? 'yes' : 'no'}).`
    );
    return toolSiteDir;
}

function findBuildFiles() {
    const buildDir = path.join(siteDir, 'Build');
    if (!fs.existsSync(buildDir)) {
        fail(`Unity WebGL output has no Build folder: ${buildDir}`);
    }
    const files = fs.readdirSync(buildDir);
    const pick = (label, test) => {
        const match = files.find(test);
        if (!match) {
            fail(`Could not find the WebGL ${label} file in ${buildDir}.`);
        }
        return match;
    };
    return {
        loader: pick('loader', (f) => f.endsWith('.loader.js')),
        data: pick('data', (f) => /\.data(\.|$)/.test(f)),
        framework: pick('framework', (f) => /\.framework\.js(\.|$)/.test(f)),
        wasm: pick('wasm', (f) => /\.wasm(\.|$)/.test(f))
    };
}

// toolOrigins: các origin của trang khung được phép ra lệnh cho game qua postMessage; rỗng khi game không có
// web-tool và trang này là trang người chơi mở thẳng.
function writeGameIndexHtml(toolOrigins) {
    const { loader, data, framework, wasm } = findBuildFiles();
    const { resolution, width, height } = frameSize();
    const productName = process.env.PEARZ_PRODUCT_NAME || 'Game';
    const version = process.env.BUILD_VERSION || '';
    // Site là thư mục Unity ghi đè chứ không dọn: bỏ bản tool mà PearzCI cũ từng chép vào đây.
    fs.rmSync(path.join(siteDir, 'tool'), { recursive: true, force: true });
    renderTemplate('WEB_INDEX_TEMPLATE', {
        PRODUCT_NAME: escapeHtml(productName),
        PRODUCT_NAME_JSON: escapeJs(productName),
        COMPANY_NAME_JSON: escapeJs(process.env.PEARZ_COMPANY_NAME || 'DefaultCompany'),
        VERSION: escapeHtml(version),
        VERSION_JSON: escapeJs(version),
        BUILD_ID: encodeURIComponent(version || String(Date.now())),
        WIDTH: width,
        HEIGHT: height,
        LOADER: encodeURIComponent(loader),
        DATA: encodeURIComponent(data),
        FRAMEWORK: encodeURIComponent(framework),
        WASM: encodeURIComponent(wasm),
        TOOL_ORIGINS_JSON: escapeJs(toolOrigins)
    }, path.join(siteDir, 'index.html'));
    console.log(
        `index.html written (${resolution}, loader ${loader}, ` +
        `${toolOrigins.length ? `embedded by ${toolOrigins.join(', ')}` : '9:16 layout'}).`
    );
}

function checkFileSizes(rootDir) {
    const tooLarge = [];
    const walk = (dir) => {
        for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
            const full = path.join(dir, entry.name);
            if (entry.isDirectory()) {
                walk(full);
            } else {
                const size = fs.statSync(full).size;
                if (size > MAX_FILE_BYTES) {
                    tooLarge.push(`${path.relative(rootDir, full)} (${(size / 1048576).toFixed(1)} MiB)`);
                }
            }
        }
    };
    walk(rootDir);
    if (tooLarge.length) {
        fail(
            'Cloudflare Pages accepts files up to 25 MiB. Too large: ' +
            tooLarge.join(', ') +
            '. Reduce the build size (textures, audio, Addressables) and build again.'
        );
    }
}

async function resolveAccountId() {
    const configured = (process.env.CLOUDFLARE_ACCOUNT_ID || '').trim();
    if (configured) {
        return configured;
    }
    const { result } = await cf('GET', '/accounts?per_page=50');
    if (result.length === 1) {
        console.log(`Cloudflare account: ${result[0].name}`);
        return result[0].id;
    }
    if (result.length === 0) {
        fail(
            'The Cloudflare token cannot see any account. Add "Account: Account Settings: Read" ' +
            'to it (next to "Cloudflare Pages: Edit"), or set CLOUDFLARE_ACCOUNT_ID.'
        );
    }
    fail(
        'The Cloudflare token can see several accounts (' +
        result.map((a) => `${a.name} = ${a.id}`).join(', ') +
        '). Restrict the token to one account or set CLOUDFLARE_ACCOUNT_ID.'
    );
}

async function listProjects(accountId) {
    const projects = [];
    for (let page = 1; page <= 50; page++) {
        const { result, result_info: info } = await cf(
            'GET', `/accounts/${accountId}/pages/projects?page=${page}&per_page=10`
        );
        projects.push(...result);
        if (!result.length || !info || page >= (info.total_pages || page)) {
            break;
        }
    }
    return projects;
}

function projectNameFromDomain() {
    const name = domain
        .replace(/[^a-z0-9]+/g, '-')
        .replace(/^-+|-+$/g, '')
        .slice(0, 58)
        .replace(/-+$/g, '');
    if (!name) {
        fail(`Cannot derive a Pages project name from '${domain}'.`);
    }
    return name;
}

async function resolveProject(accountId, projects) {
    const byDomain = projects.find((p) =>
        (p.domains || []).map((d) => d.toLowerCase()).includes(domain) ||
        (p.subdomain || '').toLowerCase() === domain
    );
    if (byDomain) {
        console.log(`Pages project '${byDomain.name}' serves ${domain}.`);
        return { project: byDomain, created: false };
    }
    if (toolOnly) {
        fail(
            `No Pages project serves ${domain} yet. Run the WebGL job once ` +
            'before publishing the web-tool on its own.'
        );
    }
    if (domain.endsWith('.pages.dev')) {
        fail(`No Pages project in this account uses ${domain}.`);
    }

    const name = projectNameFromDomain();
    const byName = projects.find((p) => p.name === name);
    if (byName) {
        console.log(`Using existing Pages project '${name}' for ${domain}.`);
        return { project: byName, created: false };
    }

    console.log(`No Pages project uses ${domain}; creating '${name}'.`);
    const { result } = await cf('POST', `/accounts/${accountId}/pages/projects`, {
        name,
        production_branch: 'main'
    });
    return { project: result, created: true };
}

const pagesHost = (project) => project.subdomain || `${project.name}.pages.dev`;

// Project chứa bản build của game có web-tool: tên theo project gắn domain, không gắn domain riêng mà dùng
// địa chỉ *.pages.dev Cloudflare cấp sẵn.
async function resolveGameProject(accountId, projects, shell) {
    const name = `${shell.name.slice(0, 53).replace(/-+$/g, '')}-game`;
    const existing = projects.find((p) => p.name === name);
    if (existing) {
        console.log(`Pages project '${name}' holds the game build.`);
        return existing;
    }
    if (toolOnly) {
        fail(
            `Pages project '${name}' does not exist yet. Run the WebGL job once ` +
            'before publishing the web-tool on its own.'
        );
    }
    console.log(`Creating Pages project '${name}' for the game build.`);
    const { result } = await cf('POST', `/accounts/${accountId}/pages/projects`, {
        name,
        production_branch: 'main'
    });
    return result;
}

function deploy(accountId, project, directory) {
    const wranglerVersion = (process.env.WRANGLER_VERSION || '4').trim();
    const branch = project.production_branch || 'main';
    const commitMessage = (process.env.GIT_COMMIT_MESSAGE || '').slice(0, 300);
    const args = [
        '--yes', `wrangler@${wranglerVersion}`,
        'pages', 'deploy', directory,
        '--project-name', project.name,
        '--branch', branch,
        '--commit-dirty=true'
    ];
    if (process.env.GIT_COMMIT_SHORT) {
        args.push('--commit-hash', process.env.GIT_COMMIT_SHORT);
    }
    if (commitMessage) {
        args.push('--commit-message', commitMessage);
    }
    console.log(`Deploying ${directory} to Pages project '${project.name}' (branch ${branch})...`);
    // Windows: npx là file .cmd, Node chỉ chạy được qua shell, nên tham số có ký tự đặc biệt phải tự bọc nháy.
    const windows = process.platform === 'win32';
    const quote = (arg) => /[\s"&|<>^]/.test(arg) ? `"${arg.replace(/"/g, '\\"')}"` : arg;
    const options = {
        stdio: 'inherit',
        env: {
            ...process.env,
            CLOUDFLARE_ACCOUNT_ID: accountId,
            WRANGLER_SEND_METRICS: 'false'
        }
    };
    const run = windows
        ? spawnSync(['npx.cmd', ...args.map(quote)].join(' '), { ...options, shell: true })
        : spawnSync('npx', args, options);
    if (run.error) {
        fail(`Could not run npx wrangler: ${run.error.message}. Install Node.js 18+ on the agent.`);
    }
    if (run.status !== 0) {
        fail(`wrangler pages deploy exited with code ${run.status}.`);
    }
}

async function findZone(accountId) {
    const labels = domain.split('.');
    for (let i = 0; i < labels.length - 1; i++) {
        const candidate = labels.slice(i).join('.');
        const { result } = await cf(
            'GET', `/zones?name=${encodeURIComponent(candidate)}&account.id=${accountId}`
        );
        if (result.length) {
            return result[0];
        }
    }
    return null;
}

function manualDnsHint(target) {
    console.log('');
    console.log('ACTION NEEDED: add this DNS record where the domain is managed:');
    console.log(`    ${domain}  CNAME  ${target}`);
    console.log('');
}

async function ensureDomain(accountId, project) {
    if (domain.endsWith('.pages.dev')) {
        return;
    }
    const target = pagesHost(project);
    const attached = (project.domains || []).map((d) => d.toLowerCase()).includes(domain);

    if (!attached) {
        console.log(`Attaching ${domain} to Pages project '${project.name}'.`);
        try {
            await cf('POST', `/accounts/${accountId}/pages/projects/${project.name}/domains`, {
                name: domain
            });
        } catch (error) {
            // 8000018: domain đã gắn (ví dụ lần trước tạo xong nhưng chưa thấy).
            if (!(error.codes || []).includes(8000018)) {
                throw error;
            }
        }
    }

    let zone = null;
    try {
        zone = await findZone(accountId);
    } catch (error) {
        console.log(`WARNING: could not look up the DNS zone (${error.message}).`);
    }
    if (!zone) {
        manualDnsHint(target);
        return;
    }

    try {
        const { result } = await cf(
            'GET', `/zones/${zone.id}/dns_records?name=${encodeURIComponent(domain)}`
        );
        if (result.length) {
            const pointsHere = result.some((r) =>
                r.type === 'CNAME' && r.content.toLowerCase() === target.toLowerCase()
            );
            if (!pointsHere) {
                // Không ghi đè DNS dev tự cấu hình.
                console.log(`WARNING: ${domain} already has DNS records that do not point to ${target}; left unchanged.`);
                manualDnsHint(target);
            }
            return;
        }
        await cf('POST', `/zones/${zone.id}/dns_records`, {
            type: 'CNAME',
            name: domain,
            content: target,
            proxied: true,
            comment: 'Created by PearzCI for Cloudflare Pages'
        });
        console.log(`Created DNS record ${domain} CNAME ${target}.`);
    } catch (error) {
        console.log(
            `WARNING: could not create the DNS record (${error.message}). ` +
            'Give the token "Zone: DNS: Edit" or add the record by hand.'
        );
        manualDnsHint(target);
    }
}

async function main() {
    fs.writeFileSync(resultFile, '');
    // Kiểm tra mọi thứ làm được ở máy trước khi gọi Cloudflare.
    if (toolOnly && !toolDir) {
        fail('WEB_DEPLOY_PARTS=tool needs WEB_TOOL_DIR.');
    }
    if (toolDir) {
        readPanel();
        required('WEB_TOOL_TEMPLATE');
        required('WEB_TOOL_SITE_DIR');
    }
    if (!toolOnly) {
        findBuildFiles();
        checkFileSizes(siteDir);
    }

    const accountId = await resolveAccountId();
    const projects = await listProjects(accountId);
    const { project } = await resolveProject(accountId, projects);
    const url = `https://${domain}`;
    const results = [`WEB_URL=${url}`, `WEB_PAGES_PROJECT=${project.name}`];

    if (!toolDir) {
        writeGameIndexHtml([]);
        deploy(accountId, project, siteDir);
    } else {
        // Game trước, trang khung sau: trang khung không bao giờ trỏ vào một project chưa có bản build.
        const game = await resolveGameProject(accountId, projects, project);
        const gameUrl = `https://${pagesHost(game)}/`;
        if (!toolOnly) {
            writeGameIndexHtml([...new Set([url, `https://${pagesHost(project)}`])]);
            deploy(accountId, game, siteDir);
        }
        const toolSiteDir = writeToolSite(gameUrl);
        checkFileSizes(toolSiteDir);
        deploy(accountId, project, toolSiteDir);
        results.push(`WEB_GAME_URL=${gameUrl}`, `WEB_GAME_PROJECT=${game.name}`);
    }
    if (!toolOnly) {
        await ensureDomain(accountId, project);
    }

    fs.appendFileSync(resultFile, results.join('\n') + '\n');
    console.log(
        toolOnly
            ? `Web-tool published. Reload ${url} to use it.`
            : `Deployed. Reload ${url} to play the new build.`
    );
}

main().catch((error) => {
    if (!(error instanceof Stop)) {
        fail(error.message);
    }
});
