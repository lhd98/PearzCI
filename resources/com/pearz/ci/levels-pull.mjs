#!/usr/bin/env node
// Kéo level từ kho level của dự án (webtool-worker.mjs) về project Unity, để bản build đóng gói
// đúng những gì GD đã lưu trên web. Chỉ dùng API đọc công khai nên không cần token.
//
//   node levels-pull.mjs [--project-dir .] [--tool-dir WebTool] [--out <thư mục>]
//                        [--domain pg07.pearz.space | --url https://...] [--on-build] [--allow-empty]
//
// Cấu hình đọc từ <tool-dir>/pearz-tool.json:
//   { "domain": "pg07.pearz.space",
//     "levels": { "pull": "Assets/GameData/LevelStore", "pullOnBuild": true } }
// "pull" là thư mục đích tính từ thư mục project Unity. --on-build là cách Jenkins gọi: chỉ chạy khi
// "pullOnBuild" là true, còn không thì thoát êm.
//
// Kết quả trong thư mục đích: mỗi level một file <id> + đuôi theo kiểu nội dung (.json, .txt, .bytes — các
// đuôi Unity nhận là TextAsset), và _catalog.json ghi thứ tự chơi, revision, enabled, meta. Kho không hiểu
// nội dung level, nên việc biến các file này thành dữ liệu game là của bước build riêng từng game.
//
// File không đổi thì không ghi lại. Chỉ xoá những file do chính lệnh này từng ghi (theo _catalog.json cũ)
// mà nay không còn trong kho; file lạ trong thư mục được để nguyên.

import fs from 'node:fs';
import path from 'node:path';

const LEVEL_ID = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/;
const CATALOG = '_catalog.json';

// Không gọi process.exit sau khi đã fetch: trên Windows Node sập với lỗi UV_HANDLE_CLOSING.
function Stop() {}
function stop(message) {
    console.error(`ERROR: ${message}`);
    process.exitCode = 1;
    throw new Stop();
}

function readArguments() {
    const options = {
        projectDir: '.', toolDir: 'WebTool', out: '', domain: '', url: '', onBuild: false, allowEmpty: false
    };
    const names = { '--project-dir': 'projectDir', '--tool-dir': 'toolDir', '--out': 'out', '--domain': 'domain', '--url': 'url' };
    const args = process.argv.slice(2);
    for (let i = 0; i < args.length; i++) {
        if (names[args[i]]) options[names[args[i]]] = args[++i] ?? stop(`${args[i - 1]} needs a value.`);
        else if (args[i] === '--on-build') options.onBuild = true;
        else if (args[i] === '--allow-empty') options.allowEmpty = true;
        else stop(`Unknown argument '${args[i]}'.`);
    }
    return options;
}

function readConfig(configFile) {
    if (!fs.existsSync(configFile)) return {};
    try {
        return JSON.parse(fs.readFileSync(configFile, 'utf8').replace(/^﻿/, ''));
    } catch (error) {
        stop(`Cannot read ${configFile}: ${error.message}`);
    }
}

async function getJson(url) {
    let response;
    try {
        response = await fetch(url, { headers: { 'Cache-Control': 'no-cache' } });
    } catch (error) {
        stop(`Cannot reach the level store (${url}): ${error.message}`);
    }
    const text = await response.text();
    if (!response.ok) {
        stop(`The level store answered ${response.status} for ${url}: ${text.slice(0, 200)}`);
    }
    try {
        return JSON.parse(text);
    } catch {
        stop(`The level store did not answer with JSON for ${url}. Is the level store deployed on this domain?`);
    }
}

// Danh mục cho thứ tự, bundle cho nội dung. Kho có thể đổi giữa hai lần gọi, nên lấy lại danh mục sau cùng
// và làm lại nếu version đã khác: thứ kéo về luôn là một trạng thái có thật của kho.
async function download(base) {
    for (let attempt = 1; attempt <= 3; attempt++) {
        const catalog = await getJson(`${base}/api/levels`);
        const data = new Map();
        let after = '';
        do {
            const page = await getJson(`${base}/api/bundle?limit=200&after=${encodeURIComponent(after)}`);
            for (const level of page.levels) {
                data.set(level.id, level);
            }
            after = page.next;
        } while (after);
        const check = await getJson(`${base}/api/levels`);
        if (check.version === catalog.version &&
            catalog.levels.every((level) => data.get(level.id)?.revision === level.revision)) {
            return { catalog, data };
        }
        console.log(`The level store changed while downloading (attempt ${attempt}); starting over.`);
    }
    stop('The level store kept changing while downloading. Try again in a moment.');
}

function extensionFor(contentType) {
    const type = (contentType || '').split(';')[0].trim().toLowerCase();
    if (type === 'application/json' || type.endsWith('+json')) return '.json';
    if (type.startsWith('text/')) return '.txt';
    return '.bytes';
}

function fileNameFor(level) {
    if (!LEVEL_ID.test(level.id)) {
        stop(`The level store returned an id that is not a safe file name: '${level.id}'.`);
    }
    const extension = extensionFor(level.contentType);
    return level.id.toLowerCase().endsWith(extension) ? level.id : level.id + extension;
}

function writeIfChanged(file, content) {
    if (fs.existsSync(file) && fs.readFileSync(file).equals(content)) {
        return false;
    }
    fs.writeFileSync(file, content);
    return true;
}

async function main() {
    const options = readArguments();
    const projectDir = path.resolve(options.projectDir);
    const configFile = path.join(projectDir, options.toolDir, 'pearz-tool.json');
    const config = readConfig(configFile);
    const levels = (config.levels && typeof config.levels === 'object') ? config.levels : {};

    if (options.onBuild && levels.pullOnBuild !== true) {
        console.log('Level pull: "levels.pullOnBuild" is not true in pearz-tool.json; skipped.');
        return;
    }
    const target = options.out || levels.pull;
    if (typeof target !== 'string' || !target.trim()) {
        stop(`Pass --out or set "levels": { "pull": "Assets/..." } in ${configFile}.`);
    }
    const outDir = path.resolve(projectDir, target);
    const inside = path.relative(projectDir, outDir);
    if (!inside || inside.startsWith('..') || path.isAbsolute(inside)) {
        stop(`The pull folder must be inside the Unity project (got '${target}').`);
    }
    const domain = String(options.domain || config.domain || '').trim().toLowerCase()
        .replace(/^https?:\/\//, '').replace(/\/+$/, '');
    const base = (options.url || (domain ? `https://${domain}` : '')).replace(/\/+$/, '');
    if (!base) {
        stop(`Pass --domain or set "domain" in ${configFile}.`);
    }

    const { catalog, data } = await download(base);
    const catalogFile = path.join(outDir, CATALOG);
    const previous = readConfig(catalogFile);
    const previousFiles = Array.isArray(previous.levels) ? previous.levels.map((level) => level.file) : [];

    // Kho rỗng mà lần trước có level thường là trỏ nhầm domain hoặc kho vừa bị dựng lại, không phải ý muốn xoá hết.
    if (!catalog.levels.length && previousFiles.length && !options.allowEmpty) {
        stop(
            `The level store at ${base} is empty but ${previousFiles.length} levels were pulled before. ` +
            'Nothing was changed; pass --allow-empty to really remove them.'
        );
    }

    fs.mkdirSync(outDir, { recursive: true });
    const entries = [];
    let written = 0;
    for (const level of catalog.levels) {
        const item = data.get(level.id);
        const file = fileNameFor({ id: level.id, contentType: item.contentType });
        if (writeIfChanged(path.join(outDir, file), Buffer.from(item.data, 'base64'))) {
            written++;
        }
        entries.push({
            id: level.id,
            file,
            revision: level.revision,
            enabled: level.enabled,
            meta: level.meta,
            contentType: item.contentType,
            size: level.size
        });
    }

    const kept = new Set(entries.map((entry) => entry.file));
    let removed = 0;
    for (const file of previousFiles) {
        // Tên file lấy từ _catalog.json cũ: chỉ nhận tên trần, không cho đường dẫn ra ngoài thư mục.
        if (typeof file !== 'string' || kept.has(file) || path.basename(file) !== file) continue;
        for (const stale of [file, `${file}.meta`]) {
            const full = path.join(outDir, stale);
            if (fs.existsSync(full)) fs.rmSync(full);
        }
        removed++;
    }

    // Không ghi thời điểm kéo vào danh mục: file chỉ đổi khi kho đổi, nên diff trong git có nghĩa.
    writeIfChanged(catalogFile, Buffer.from(JSON.stringify({
        source: base, version: catalog.version, levels: entries
    }, null, 2) + '\n'));
    console.log(
        `Level pull: ${entries.length} levels from ${base} (store version ${catalog.version}) into ${outDir}: ` +
        `${written} written, ${entries.length - written} unchanged, ${removed} removed.`
    );
}

main().catch((error) => {
    if (!(error instanceof Stop)) {
        console.error(error);
        process.exitCode = 1;
    }
});
