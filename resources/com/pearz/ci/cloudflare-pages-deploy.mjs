// Deploy một bản build Unity WebGL lên Cloudflare Pages.
//
// Đầu vào (biến môi trường):
//   CLOUDFLARE_API_TOKEN   token của dev (bắt buộc)
//   CLOUDFLARE_ACCOUNT_ID  tuỳ chọn; để trống thì lấy tài khoản duy nhất token thấy
//   WEB_DOMAIN             domain người chơi mở, ví dụ pg05.pearz.space (bắt buộc)
//   WEB_SITE_DIR           thư mục site Unity xuất ra (có Build/)
//   WEB_INDEX_TEMPLATE     file webgl-index.html của PearzCI
//   WEB_RESOLUTION         720x1280 | 1080x1920 | 1440x2560
//   WEB_TOOL_DIR           tuỳ chọn; thư mục web-tool của game (có panel.html)
//   WEB_RESULT_FILE        file ghi kết quả KEY=VALUE cho Jenkins
//   PEARZ_PRODUCT_NAME, BUILD_VERSION, GIT_COMMIT_SHORT, GIT_COMMIT_MESSAGE
//   WRANGLER_VERSION       mặc định 4
//
// Các bước: copy web-tool (nếu có) -> sinh index.html -> kiểm tra giới hạn 25 MiB -> tìm account ->
// tìm project Pages đang gắn domain (không có thì tạo) -> wrangler deploy ->
// gắn domain + CNAME nếu còn thiếu.

import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const API = 'https://api.cloudflare.com/client/v4';
const MAX_FILE_BYTES = 25 * 1024 * 1024;
const RESOLUTIONS = ['720x1280', '1080x1920', '1440x2560'];

const token = required('CLOUDFLARE_API_TOKEN');
const domain = required('WEB_DOMAIN').trim().toLowerCase();
const siteDir = required('WEB_SITE_DIR');
const resultFile = required('WEB_RESULT_FILE');

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
    process.exit(1);
}

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

// Web-tool của game: copy cả thư mục vào <site>/tool và trả về các đoạn HTML
// chèn vào template. panel.html nằm thẳng trong index.html nên đường dẫn
// tương đối trong đó tính từ gốc site (tool/anh.png). Không có WEB_TOOL_DIR
// thì mọi đoạn đều rỗng và trang là khung 9:16 căn giữa.
function prepareTool(buildId) {
    const publishDir = path.join(siteDir, 'tool');
    // Site là thư mục Unity ghi đè chứ không dọn: bỏ bản tool của lần trước.
    fs.rmSync(publishDir, { recursive: true, force: true });

    const toolDir = (process.env.WEB_TOOL_DIR || '').trim();
    if (!toolDir) {
        return { BODY_CLASS: '', TOOL_HEAD: '', TOOL_PANEL: '', TOOL_SCRIPT: '' };
    }
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

    fs.cpSync(toolDir, publishDir, { recursive: true });
    const has = (name) => fs.existsSync(path.join(toolDir, name));
    console.log(
        `Web-tool panel from ${toolDir}` +
        ` (tool.css: ${has('tool.css') ? 'yes' : 'no'}, tool.js: ${has('tool.js') ? 'yes' : 'no'}).`
    );
    return {
        BODY_CLASS: 'tool',
        TOOL_HEAD: has('tool.css')
            ? `<link rel="stylesheet" href="tool/tool.css?v=${buildId}">`
            : '',
        TOOL_PANEL: `<aside id="tool-panel">\n${panel}\n</aside>`,
        TOOL_SCRIPT: has('tool.js')
            ? `<script src="tool/tool.js?v=${buildId}"></script>`
            : ''
    };
}

function writeIndexHtml() {
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
    const loader = pick('loader', (f) => f.endsWith('.loader.js'));
    const data = pick('data', (f) => /\.data(\.|$)/.test(f));
    const framework = pick('framework', (f) => /\.framework\.js(\.|$)/.test(f));
    const wasm = pick('wasm', (f) => /\.wasm(\.|$)/.test(f));

    const resolution = (process.env.WEB_RESOLUTION || '1080x1920').trim();
    if (!RESOLUTIONS.includes(resolution)) {
        fail(`WEB_RESOLUTION must be one of ${RESOLUTIONS.join(', ')} (got '${resolution}').`);
    }
    const [width, height] = resolution.split('x');
    const productName = process.env.PEARZ_PRODUCT_NAME || 'Game';
    const version = process.env.BUILD_VERSION || '';
    const html = (text) => text
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
    // JSON nằm trong <script>: chặn "</script>" thoát khỏi thẻ.
    const js = (value) => JSON.stringify(value).replace(/</g, '\\u003c');
    const buildId = encodeURIComponent(version || String(Date.now()));
    const values = {
        PRODUCT_NAME: html(productName),
        PRODUCT_NAME_JSON: js(productName),
        COMPANY_NAME_JSON: js(process.env.PEARZ_COMPANY_NAME || 'DefaultCompany'),
        VERSION: html(version),
        VERSION_JSON: js(version),
        BUILD_ID: buildId,
        WIDTH: width,
        HEIGHT: height,
        LOADER: encodeURIComponent(loader),
        DATA: encodeURIComponent(data),
        FRAMEWORK: encodeURIComponent(framework),
        WASM: encodeURIComponent(wasm),
        ...prepareTool(buildId)
    };
    const template = fs.readFileSync(required('WEB_INDEX_TEMPLATE'), 'utf8');
    const output = template.replace(/\{\{([A-Z_]+)\}\}/g, (token, key) =>
        Object.prototype.hasOwnProperty.call(values, key) ? values[key] : token
    );
    fs.writeFileSync(path.join(siteDir, 'index.html'), output);
    console.log(
        `index.html written (${resolution}, loader ${loader}, ` +
        `${values.BODY_CLASS === 'tool' ? 'web-tool layout' : '9:16 layout'}).`
    );
}

function checkFileSizes() {
    const tooLarge = [];
    const walk = (dir) => {
        for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
            const full = path.join(dir, entry.name);
            if (entry.isDirectory()) {
                walk(full);
            } else {
                const size = fs.statSync(full).size;
                if (size > MAX_FILE_BYTES) {
                    tooLarge.push(`${path.relative(siteDir, full)} (${(size / 1048576).toFixed(1)} MiB)`);
                }
            }
        }
    };
    walk(siteDir);
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
        fail('The Cloudflare token cannot see any account. Give it the "Cloudflare Pages: Edit" permission.');
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

async function resolveProject(accountId) {
    const projects = await listProjects(accountId);
    const byDomain = projects.find((p) =>
        (p.domains || []).map((d) => d.toLowerCase()).includes(domain) ||
        (p.subdomain || '').toLowerCase() === domain
    );
    if (byDomain) {
        console.log(`Pages project '${byDomain.name}' serves ${domain}.`);
        return { project: byDomain, created: false };
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

function deploy(accountId, project) {
    const wranglerVersion = (process.env.WRANGLER_VERSION || '4').trim();
    const branch = project.production_branch || 'main';
    const commitMessage = (process.env.GIT_COMMIT_MESSAGE || '').slice(0, 300);
    const args = [
        '--yes', `wrangler@${wranglerVersion}`,
        'pages', 'deploy', siteDir,
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
    console.log(`Deploying ${siteDir} to Pages project '${project.name}' (branch ${branch})...`);
    const run = spawnSync('npx', args, {
        stdio: 'inherit',
        env: {
            ...process.env,
            CLOUDFLARE_ACCOUNT_ID: accountId,
            WRANGLER_SEND_METRICS: 'false'
        }
    });
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
    const target = `${project.subdomain || `${project.name}.pages.dev`}`;
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
    writeIndexHtml();
    checkFileSizes();

    const accountId = await resolveAccountId();
    const { project } = await resolveProject(accountId);
    deploy(accountId, project);
    await ensureDomain(accountId, project);

    const url = `https://${domain}`;
    fs.appendFileSync(resultFile, `WEB_URL=${url}\nWEB_PAGES_PROJECT=${project.name}\n`);
    console.log(`Deployed. Reload ${url} to play the new build.`);
}

main().catch((error) => fail(error.message));
