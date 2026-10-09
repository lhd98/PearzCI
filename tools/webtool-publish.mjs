#!/usr/bin/env node
// Đẩy riêng web-tool của game (thư mục WebTool) lên Cloudflare Pages từ máy dev,
// không build lại game. Chạy trong thư mục project Unity:
//
//   node <PearzCI>/tools/webtool-publish.mjs [--domain pg07.pearz.space]
//        [--tool-dir WebTool] [--watch]
//
// --domain   domain của dự án; bỏ trống thì đọc "domain" trong <tool-dir>/pearz-tool.json
// --watch    đẩy lại mỗi khi file trong thư mục tool đổi (Ctrl+C để dừng)
//
// Cần biến môi trường CLOUDFLARE_API_TOKEN (quyền Account: Cloudflare Pages: Edit
// và Account: Account Settings: Read) và
// domain đã được job WebGL của Jenkins deploy ít nhất một lần: lệnh này chỉ
// thay trang khung + tool/, không tạo project, không đụng DNS hay bản build.

import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const resources = fileURLToPath(new URL('../resources/com/pearz/ci/', import.meta.url));

function stop(message) {
    console.error(`ERROR: ${message}`);
    process.exit(1);
}

function readArguments() {
    const options = { domain: '', toolDir: 'WebTool', watch: false };
    const args = process.argv.slice(2);
    for (let i = 0; i < args.length; i++) {
        const value = () => args[++i] ?? stop(`${args[i - 1]} needs a value.`);
        if (args[i] === '--domain') options.domain = value();
        else if (args[i] === '--tool-dir') options.toolDir = value();
        else if (args[i] === '--watch') options.watch = true;
        else stop(`Unknown argument '${args[i]}'. Use --domain, --tool-dir, --watch.`);
    }
    return options;
}

const options = readArguments();
const toolDir = path.resolve(options.toolDir);
if (!fs.existsSync(path.join(toolDir, 'panel.html'))) {
    stop(`No panel.html in ${toolDir}. Run this from the Unity project folder or pass --tool-dir.`);
}

function readDomain() {
    if (options.domain) return options.domain;
    const configFile = path.join(toolDir, 'pearz-tool.json');
    if (!fs.existsSync(configFile)) {
        stop(`Pass --domain or put { "domain": "pgXX.pearz.space" } in ${configFile}.`);
    }
    try {
        return JSON.parse(fs.readFileSync(configFile, 'utf8').replace(/^﻿/, '')).domain || '';
    } catch (error) {
        stop(`Cannot read ${configFile}: ${error.message}`);
    }
}

const domain = String(readDomain()).trim().toLowerCase()
    .replace(/^https?:\/\//, '').replace(/\/+$/, '');
if (!/^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,}$/.test(domain)) {
    stop(`The domain must look like pg07.pearz.space (got '${domain}').`);
}
if (!(process.env.CLOUDFLARE_API_TOKEN || '').trim()) {
    stop(
        'Set CLOUDFLARE_API_TOKEN to a Cloudflare API token with ' +
        '"Account: Cloudflare Pages: Edit" and "Account: Account Settings: Read".'
    );
}

// Tiêu đề trang giống bản Jenkins deploy: lấy productName của project Unity.
function readProductName() {
    const settings = path.join(toolDir, '..', 'ProjectSettings', 'ProjectSettings.asset');
    try {
        const match = /^\s*productName:\s*(.+?)\s*$/m.exec(fs.readFileSync(settings, 'utf8'));
        return match ? match[1].replace(/^(['"])(.*)\1$/, '$2') : '';
    } catch {
        return '';
    }
}

const workDir = path.join(os.tmpdir(), 'pearz-webtool', domain);
fs.mkdirSync(workDir, { recursive: true });

function publish() {
    const started = Date.now();
    const run = spawnSync(process.execPath, [path.join(resources, 'cloudflare-pages-deploy.mjs')], {
        stdio: 'inherit',
        env: {
            ...process.env,
            WEB_DEPLOY_PARTS: 'tool',
            WEB_DOMAIN: domain,
            WEB_TOOL_DIR: toolDir,
            WEB_TOOL_TEMPLATE: path.join(resources, 'webgl-tool-index.html'),
            WEB_TOOL_WORKER: path.join(resources, 'webtool-worker.mjs'),
            WEB_TOOL_SITE_DIR: path.join(workDir, 'site'),
            WEB_RESULT_FILE: path.join(workDir, 'result.txt'),
            PEARZ_PRODUCT_NAME: process.env.PEARZ_PRODUCT_NAME || readProductName() || domain
        }
    });
    if (run.status !== 0) {
        return false;
    }
    console.log(`Published in ${((Date.now() - started) / 1000).toFixed(1)}s.`);
    warnUncommitted();
    return true;
}

// Job WebGL của Jenkins deploy lại trang khung từ git: tool chưa commit sẽ bị bản trong git thay thế.
function warnUncommitted() {
    const status = spawnSync('git', ['status', '--porcelain', '--', toolDir], { encoding: 'utf8' });
    if (status.status === 0 && status.stdout.trim()) {
        console.log(
            `NOTE: ${path.basename(toolDir)}/ has uncommitted changes. Commit and push them, ` +
            'or the next Jenkins WebGL build will publish the older committed tool.'
        );
    }
}

if (!options.watch) {
    process.exit(publish() ? 0 : 1);
}

publish();
console.log(`Watching ${toolDir} — save a file to publish again, Ctrl+C to stop.`);
// Gom các lần lưu sát nhau thành một lần publish; publish chạy đồng bộ nên không bao giờ chồng nhau.
let timer = null;
fs.watch(toolDir, { recursive: true }, () => {
    clearTimeout(timer);
    timer = setTimeout(publish, 1000);
});
