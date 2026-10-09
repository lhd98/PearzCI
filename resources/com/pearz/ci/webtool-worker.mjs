// Kho level của web-tool: _worker.js của site tool trên Cloudflare Pages (PearzCI sinh ra khi
// WebTool/pearz-tool.json có "levels"). Lưu trong D1 (binding DB) vì D1 đọc lại được ngay sau khi ghi.
//
// Kho không hiểu nội dung level: mỗi level là một khối byte có tên, kèm revision, thứ tự, bật/tắt và
// "meta" (một giá trị JSON nhỏ do game tự quy ước, hiện trong danh mục để khỏi phải tải cả level).
//
// Đọc — công khai, CORS mở (game trong iframe và bản build mobile đều gọi được):
//   GET  /api/levels                      danh mục { version, levels: [...] }
//   GET  /api/levels/<id>[?revision=N]    nội dung thô; revision ở header ETag / X-Pearz-Revision
//   GET  /api/bundle[?after=<id>&limit=N] nhiều level một lần, nội dung base64
// Ghi — /api/edit/*, phải nằm sau Cloudflare Access; JWT của Access được kiểm tra lại ở đây vì site còn
// địa chỉ *.pages.dev không qua Access:
//   GET    /api/edit/me                       { email }
//   GET    /api/edit/login[?return=/duong-dan] đích của cửa sổ đăng nhập
//   GET    /api/edit/levels                   danh mục kèm người sửa và level đã xoá
//   PUT    /api/edit/levels/<id>              lưu; If-Match: "<revision>" để sửa, If-None-Match: * để tạo
//   PATCH  /api/edit/levels/<id>              { enabled?, meta? } không tạo revision
//   DELETE /api/edit/levels/<id>              If-Match: "<revision>"; xoá mềm, lịch sử còn nguyên
//   GET    /api/edit/levels/<id>/revisions    lịch sử
//   POST   /api/edit/levels/<id>/restore      { revision } → revision mới có nội dung cũ
//   PUT    /api/edit/order                    { ids: [...] }

const CONFIG = {{STORE_CONFIG_JSON}};
// Một dòng D1 tối đa 2 MB; chừa chỗ cho các cột khác.
const MAX_LEVEL_BYTES = 1500000;
const MAX_META_BYTES = 4096;
const MAX_BUNDLE_BYTES = 4000000;
const LEVEL_ID = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/;
const PUBLIC_HEADERS = {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Expose-Headers': 'ETag, X-Pearz-Revision, X-Pearz-Meta',
    'Cache-Control': 'no-cache'
};

class HttpError extends Error {
    constructor(status, code, message, extra) {
        super(message);
        this.status = status;
        this.code = code;
        this.extra = extra || {};
    }
}

export default {
    async fetch(request, env) {
        const url = new URL(request.url);
        if (!url.pathname.startsWith('/api/')) {
            return env.ASSETS.fetch(request);
        }
        const editing = url.pathname.startsWith('/api/edit/');
        try {
            return await route(request, env, url, editing);
        } catch (error) {
            const known = error instanceof HttpError;
            if (!known) {
                console.error(error);
            }
            return json(
                known ? error.status : 500,
                {
                    error: known ? error.code : 'internal',
                    message: String((error && error.message) || error),
                    ...(known ? error.extra : {})
                },
                editing ? {} : PUBLIC_HEADERS
            );
        }
    }
};

function json(status, body, headers) {
    return new Response(JSON.stringify(body), {
        status,
        headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', ...headers }
    });
}

async function route(request, env, url, editing) {
    if (!env.DB) {
        throw new HttpError(503, 'unavailable', 'The level store has no database binding (DB).');
    }
    await prepare(env.DB);
    const method = request.method;
    // /api/<scope>/<a>/<b>/<c>; với /api/edit/... thì a là tài nguyên, b là id level.
    let parts;
    try {
        parts = url.pathname.split('/').filter(Boolean).map((part) => decodeURIComponent(part));
    } catch {
        throw new HttpError(400, 'badpath', 'The path is not valid.');
    }
    const [, scope, a, b, c] = parts;

    if (!editing) {
        if (method === 'OPTIONS') {
            return new Response(null, { status: 204, headers: PUBLIC_HEADERS });
        }
        if (method !== 'GET' && method !== 'HEAD') {
            throw new HttpError(405, 'method', 'The public API is read-only; write through /api/edit/.');
        }
        if (scope === 'levels' && a === undefined) return catalog(env.DB, request, false);
        if (scope === 'levels' && b === undefined) return levelData(env.DB, request, url, levelId(a));
        if (scope === 'bundle' && a === undefined) return bundle(env.DB, url);
        throw new HttpError(404, 'notfound', 'Unknown API path.');
    }

    const email = await authenticate(request);
    if (method === 'GET') {
        if (a === 'me' && b === undefined) return json(200, { email });
        if (a === 'login' && b === undefined) return loginPage(url);
        if (a === 'levels' && b === undefined) return catalog(env.DB, request, true);
        if (a === 'levels' && c === 'revisions') return revisions(env.DB, levelId(b));
        throw new HttpError(404, 'notfound', 'Unknown API path.');
    }
    // Trình duyệt không cho trang khác gửi header tuỳ ý mà không hỏi CORS, và API này không cấp CORS:
    // header bắt buộc này chặn một trang lạ mượn phiên đăng nhập của GD để ghi.
    if (request.headers.get('X-Pearz-Tool') !== '1') {
        throw new HttpError(403, 'forbidden', 'Writes need the X-Pearz-Tool: 1 header.');
    }
    if (a === 'order' && b === undefined && method === 'PUT') return reorder(env.DB, request);
    if (a === 'levels' && b !== undefined) {
        const id = levelId(b);
        if (c === undefined && method === 'PUT') return save(env.DB, request, id, email);
        if (c === undefined && method === 'PATCH') return patch(env.DB, request, id);
        if (c === undefined && method === 'DELETE') return remove(env.DB, request, id, email);
        if (c === 'restore' && method === 'POST') return restore(env.DB, request, id, email);
    }
    throw new HttpError(404, 'notfound', 'Unknown API path.');
}

function levelId(value) {
    if (!LEVEL_ID.test(value || '')) {
        throw new HttpError(400, 'badid', 'A level id is 1-64 characters: letters, digits, dot, dash, underscore.');
    }
    return value;
}

// ---------------------------------------------------------------- lược đồ

let prepared = null;

function prepare(db) {
    // Một lần mỗi isolate. Bảng levels là trạng thái hiện tại, level_revisions giữ nội dung từng lần lưu.
    // token đổi ở mỗi lần ghi: các câu lệnh sau trong cùng batch chỉ chạy khi câu đầu thật sự ghi được.
    prepared = prepared || db.batch([
        db.prepare(`CREATE TABLE IF NOT EXISTS levels (
            id TEXT PRIMARY KEY,
            revision INTEGER NOT NULL,
            position REAL NOT NULL,
            enabled INTEGER NOT NULL DEFAULT 1,
            deleted INTEGER NOT NULL DEFAULT 0,
            meta TEXT NOT NULL DEFAULT 'null',
            size INTEGER NOT NULL,
            content_type TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            updated_by TEXT NOT NULL,
            token TEXT NOT NULL)`),
        db.prepare(`CREATE TABLE IF NOT EXISTS level_revisions (
            id TEXT NOT NULL,
            revision INTEGER NOT NULL,
            data BLOB NOT NULL,
            content_type TEXT NOT NULL,
            meta TEXT NOT NULL,
            size INTEGER NOT NULL,
            saved_at TEXT NOT NULL,
            saved_by TEXT NOT NULL,
            PRIMARY KEY (id, revision))`),
        db.prepare('CREATE TABLE IF NOT EXISTS state (key TEXT PRIMARY KEY, value INTEGER NOT NULL)'),
        db.prepare("INSERT OR IGNORE INTO state (key, value) VALUES ('version', 0)")
    ]).catch((error) => {
        prepared = null;
        throw error;
    });
    return prepared;
}

const written = (db, id, token) =>
    db.prepare('SELECT 1 FROM levels WHERE id = ?1 AND token = ?2').bind(id, token);

// Tăng version của danh mục nếu câu ghi trước đó (đánh dấu bằng token) thành công.
const bumpVersion = (db, id, token) => db.prepare(
    "UPDATE state SET value = value + 1 WHERE key = 'version' " +
    'AND EXISTS (SELECT 1 FROM levels WHERE id = ?1 AND token = ?2)'
).bind(id, token);

const pruneHistory = (db, id, token) => db.prepare(
    'DELETE FROM level_revisions WHERE id = ?1 AND revision <= ' +
    '(SELECT revision FROM levels WHERE id = ?1 AND token = ?2) - ?3'
).bind(id, token, Math.max(1, Number(CONFIG.history) || 20));

// ---------------------------------------------------------------- đọc

function parseMeta(text) {
    try {
        return JSON.parse(text);
    } catch {
        return null;
    }
}

async function catalog(db, request, editing) {
    const [state, rows] = await db.batch([
        db.prepare("SELECT value FROM state WHERE key = 'version'"),
        db.prepare(
            'SELECT id, revision, position, enabled, deleted, meta, size, content_type, updated_at, updated_by ' +
            `FROM levels ${editing ? '' : 'WHERE deleted = 0 '}ORDER BY position, id`
        )
    ]);
    const version = state.results[0] ? state.results[0].value : 0;
    const etag = `"v${version}"`;
    const headers = editing ? {} : { ...PUBLIC_HEADERS, ETag: etag };
    if (!editing && request.headers.get('If-None-Match') === etag) {
        return new Response(null, { status: 304, headers });
    }
    return json(200, {
        version,
        levels: rows.results.map((row) => ({
            id: row.id,
            revision: row.revision,
            enabled: row.enabled === 1,
            meta: parseMeta(row.meta),
            size: row.size,
            contentType: row.content_type,
            updatedAt: row.updated_at,
            // Email người sửa không lộ ra API công khai.
            ...(editing ? { updatedBy: row.updated_by, deleted: row.deleted === 1 } : {})
        }))
    }, headers);
}

// D1 trả BLOB dưới dạng mảng số (hoặc ArrayBuffer tuỳ phiên bản runtime).
const toBytes = (blob) => blob instanceof ArrayBuffer ? new Uint8Array(blob) : Uint8Array.from(blob);

async function levelData(db, request, url, id) {
    const wanted = url.searchParams.get('revision');
    const row = await db.prepare(
        'SELECT r.revision, r.data, r.content_type, r.meta FROM levels l ' +
        'JOIN level_revisions r ON r.id = l.id AND r.revision = COALESCE(?2, l.revision) ' +
        'WHERE l.id = ?1 AND (l.deleted = 0 OR ?2 IS NOT NULL)'
    ).bind(id, wanted === null ? null : Number(wanted)).first();
    if (!row) {
        throw new HttpError(404, 'notfound', `No level '${id}'${wanted === null ? '' : ` at revision ${wanted}`}.`);
    }
    const etag = `"${row.revision}"`;
    const headers = {
        ...PUBLIC_HEADERS,
        ETag: etag,
        'X-Pearz-Revision': String(row.revision),
        'X-Pearz-Meta': encodeURIComponent(row.meta),
        'Content-Type': row.content_type
    };
    if (request.headers.get('If-None-Match') === etag) {
        return new Response(null, { status: 304, headers });
    }
    return new Response(request.method === 'HEAD' ? null : toBytes(row.data), { status: 200, headers });
}

function toBase64(bytes) {
    let binary = '';
    for (let i = 0; i < bytes.length; i += 0x8000) {
        binary += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
    }
    return btoa(binary);
}

// Cho CI kéo cả kho và cho game tải nhiều level một lượt thay vì mỗi level một request.
async function bundle(db, url) {
    const limit = Math.min(200, Math.max(1, Number(url.searchParams.get('limit')) || 50));
    const rows = await db.prepare(
        'SELECT l.id, l.revision, l.enabled, l.meta, r.content_type, r.data FROM levels l ' +
        'JOIN level_revisions r ON r.id = l.id AND r.revision = l.revision ' +
        'WHERE l.deleted = 0 AND l.id > ?1 ORDER BY l.id LIMIT ?2'
    ).bind(url.searchParams.get('after') || '', limit).all();
    const levels = [];
    let total = 0;
    for (const row of rows.results) {
        const bytes = toBytes(row.data);
        // Luôn trả ít nhất một level, kể cả khi nó lớn hơn ngưỡng.
        if (levels.length && total + bytes.length > MAX_BUNDLE_BYTES) break;
        total += bytes.length;
        levels.push({
            id: row.id,
            revision: row.revision,
            enabled: row.enabled === 1,
            meta: parseMeta(row.meta),
            contentType: row.content_type,
            data: toBase64(bytes)
        });
    }
    const more = levels.length < rows.results.length || rows.results.length === limit;
    return json(200, { levels, next: more && levels.length ? levels[levels.length - 1].id : null }, PUBLIC_HEADERS);
}

async function revisions(db, id) {
    const rows = await db.prepare(
        'SELECT revision, size, saved_at, saved_by FROM level_revisions WHERE id = ?1 ORDER BY revision DESC'
    ).bind(id).all();
    return json(200, {
        id,
        revisions: rows.results.map((row) => ({
            revision: row.revision, size: row.size, savedAt: row.saved_at, savedBy: row.saved_by
        }))
    });
}

// ---------------------------------------------------------------- ghi

// meta gửi lên là văn bản JSON; trả về chuỗi đã chuẩn hoá, hoặc null khi không gửi.
function readMeta(text) {
    if (text === null || text === undefined) return null;
    if (new TextEncoder().encode(text).length > MAX_META_BYTES) {
        throw new HttpError(413, 'toolarge', `meta is limited to ${MAX_META_BYTES} bytes.`);
    }
    try {
        return JSON.stringify(JSON.parse(text));
    } catch {
        throw new HttpError(400, 'badmeta', 'meta must be JSON.');
    }
}

function revisionFrom(header) {
    const match = /^\s*"?(\d+)"?\s*$/.exec(header || '');
    return match ? Number(match[1]) : null;
}

async function current(db, id) {
    return db.prepare('SELECT revision, deleted FROM levels WHERE id = ?1').bind(id).first();
}

async function conflict(db, id, message) {
    const row = await current(db, id);
    const exists = row && row.deleted === 0;
    throw new HttpError(exists ? 412 : 404, exists ? 'conflict' : 'notfound', exists ? message : `No level '${id}'.`, {
        revision: exists ? row.revision : null
    });
}

async function save(db, request, id, email) {
    const expected = revisionFrom(request.headers.get('If-Match'));
    const creating = (request.headers.get('If-None-Match') || '').trim() === '*';
    if (expected === null && !creating) {
        throw new HttpError(
            428, 'precondition',
            'Send If-Match: "<revision>" to update a level or If-None-Match: * to create one.'
        );
    }
    const data = await request.arrayBuffer();
    if (data.byteLength > MAX_LEVEL_BYTES) {
        throw new HttpError(413, 'toolarge', `A level is limited to ${MAX_LEVEL_BYTES} bytes.`);
    }
    const header = request.headers.get('X-Pearz-Meta');
    const meta = readMeta(header === null ? null : decodeURIComponent(header));
    const type = request.headers.get('Content-Type') || 'application/octet-stream';
    const now = new Date().toISOString();
    const token = crypto.randomUUID();

    const write = creating
        // Tạo mới, hoặc hồi sinh một id đã xoá (lịch sử cũ nối tiếp). Id đang tồn tại thì không ghi gì.
        ? db.prepare(
            'INSERT INTO levels (id, revision, position, enabled, deleted, meta, size, content_type, ' +
            'updated_at, updated_by, token) VALUES (?1, 1, (SELECT COALESCE(MAX(position), 0) + 1 FROM levels), ' +
            '1, 0, ?2, ?3, ?4, ?5, ?6, ?7) ON CONFLICT(id) DO UPDATE SET revision = levels.revision + 1, ' +
            'deleted = 0, enabled = 1, meta = excluded.meta, size = excluded.size, ' +
            'content_type = excluded.content_type, updated_at = excluded.updated_at, ' +
            'updated_by = excluded.updated_by, token = excluded.token WHERE levels.deleted = 1'
        ).bind(id, meta === null ? 'null' : meta, data.byteLength, type, now, email, token)
        : db.prepare(
            'UPDATE levels SET revision = revision + 1, meta = COALESCE(?2, meta), size = ?3, content_type = ?4, ' +
            'updated_at = ?5, updated_by = ?6, token = ?7 WHERE id = ?1 AND revision = ?8 AND deleted = 0'
        ).bind(id, meta, data.byteLength, type, now, email, token, expected);

    const results = await db.batch([
        write,
        db.prepare(
            'INSERT INTO level_revisions (id, revision, data, content_type, meta, size, saved_at, saved_by) ' +
            'SELECT id, revision, ?3, content_type, meta, size, updated_at, updated_by FROM levels ' +
            'WHERE id = ?1 AND token = ?2'
        ).bind(id, token, data),
        bumpVersion(db, id, token),
        pruneHistory(db, id, token),
        written(db, id, token)
    ]);
    if (!results[4].results.length) {
        if (creating) {
            const row = await current(db, id);
            throw new HttpError(412, 'conflict', `Level '${id}' already exists.`, { revision: row ? row.revision : null });
        }
        await conflict(db, id, `Level '${id}' was changed by someone else since revision ${expected}.`);
    }
    const row = await current(db, id);
    return json(creating ? 201 : 200, { id, revision: row.revision });
}

async function readJson(request) {
    try {
        const body = await request.json();
        if (body && typeof body === 'object') return body;
    } catch {
        // rơi xuống lỗi chung bên dưới
    }
    throw new HttpError(400, 'badbody', 'The request body must be a JSON object.');
}

async function patch(db, request, id) {
    const body = await readJson(request);
    const enabled = typeof body.enabled === 'boolean' ? (body.enabled ? 1 : 0) : null;
    const meta = body.meta === undefined ? null : readMeta(JSON.stringify(body.meta));
    const token = crypto.randomUUID();
    const results = await db.batch([
        db.prepare(
            'UPDATE levels SET enabled = COALESCE(?2, enabled), meta = COALESCE(?3, meta), token = ?4 ' +
            'WHERE id = ?1 AND deleted = 0'
        ).bind(id, enabled, meta, token),
        bumpVersion(db, id, token),
        written(db, id, token)
    ]);
    if (!results[2].results.length) {
        throw new HttpError(404, 'notfound', `No level '${id}'.`);
    }
    return json(200, { id });
}

async function remove(db, request, id, email) {
    const expected = revisionFrom(request.headers.get('If-Match'));
    if (expected === null) {
        throw new HttpError(428, 'precondition', 'Send If-Match: "<revision>" to delete a level.');
    }
    const token = crypto.randomUUID();
    const results = await db.batch([
        db.prepare(
            'UPDATE levels SET deleted = 1, updated_at = ?2, updated_by = ?3, token = ?4 ' +
            'WHERE id = ?1 AND revision = ?5 AND deleted = 0'
        ).bind(id, new Date().toISOString(), email, token, expected),
        bumpVersion(db, id, token),
        written(db, id, token)
    ]);
    if (!results[2].results.length) {
        await conflict(db, id, `Level '${id}' was changed by someone else since revision ${expected}.`);
    }
    return json(200, { id });
}

async function restore(db, request, id, email) {
    const body = await readJson(request);
    const from = Number(body.revision);
    if (!Number.isInteger(from) || from < 1) {
        throw new HttpError(400, 'badbody', 'restore needs { "revision": <number> }.');
    }
    const token = crypto.randomUUID();
    const old = 'FROM level_revisions WHERE id = ?1 AND revision = ?2';
    const results = await db.batch([
        db.prepare(
            `UPDATE levels SET revision = revision + 1, deleted = 0, meta = (SELECT meta ${old}), ` +
            `size = (SELECT size ${old}), content_type = (SELECT content_type ${old}), updated_at = ?3, ` +
            `updated_by = ?4, token = ?5 WHERE id = ?1 AND EXISTS (SELECT 1 ${old})`
        ).bind(id, from, new Date().toISOString(), email, token),
        db.prepare(
            'INSERT INTO level_revisions (id, revision, data, content_type, meta, size, saved_at, saved_by) ' +
            'SELECT l.id, l.revision, r.data, r.content_type, r.meta, r.size, l.updated_at, l.updated_by ' +
            'FROM levels l JOIN level_revisions r ON r.id = l.id AND r.revision = ?2 ' +
            'WHERE l.id = ?1 AND l.token = ?3'
        ).bind(id, from, token),
        bumpVersion(db, id, token),
        pruneHistory(db, id, token),
        written(db, id, token)
    ]);
    if (!results[4].results.length) {
        throw new HttpError(404, 'notfound', `Level '${id}' has no revision ${from}.`);
    }
    const row = await current(db, id);
    return json(200, { id, revision: row.revision });
}

async function reorder(db, request) {
    const body = await readJson(request);
    if (!Array.isArray(body.ids) || body.ids.some((id) => !LEVEL_ID.test(id))) {
        throw new HttpError(400, 'badbody', 'order needs { "ids": [<level id>, ...] }.');
    }
    // Một câu lệnh cho cả danh sách: D1 giới hạn số tham số và số câu lệnh mỗi request.
    // Level không có trong danh sách giữ nguyên vị trí cũ (xếp sau theo position rồi id).
    await db.batch([
        db.prepare(
            'UPDATE levels SET position = (SELECT key FROM json_each(?1) WHERE value = levels.id) + 1 ' +
            'WHERE id IN (SELECT value FROM json_each(?1))'
        ).bind(JSON.stringify(body.ids)),
        db.prepare("UPDATE state SET value = value + 1 WHERE key = 'version'")
    ]);
    return json(200, { count: body.ids.length });
}

// ---------------------------------------------------------------- đăng nhập (Cloudflare Access)

let accessKeys = null;

function fromBase64Url(text) {
    const binary = atob(text.replace(/-/g, '+').replace(/_/g, '/'));
    return Uint8Array.from(binary, (char) => char.charCodeAt(0));
}

async function accessKey(issuer, kid) {
    // Access xoay khoá ký vài tuần một lần: giữ bản tải về một giờ, và tải lại khi gặp kid lạ.
    const fresh = accessKeys && Date.now() - accessKeys.loadedAt < 3600000;
    if (!fresh || !accessKeys.keys[kid]) {
        const response = await fetch(`${issuer}/cdn-cgi/access/certs`);
        if (!response.ok) {
            throw new HttpError(503, 'unavailable', `Cannot load the Access signing keys (${response.status}).`);
        }
        const keys = {};
        for (const jwk of (await response.json()).keys || []) {
            keys[jwk.kid] = await crypto.subtle.importKey(
                'jwk', jwk, { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['verify']
            );
        }
        accessKeys = { keys, loadedAt: Date.now() };
    }
    return accessKeys.keys[kid];
}

// Trả email của người đã đăng nhập, hoặc ném 401.
async function authenticate(request) {
    const access = CONFIG.access;
    if (!access || !access.team || !access.aud) {
        throw new HttpError(
            503, 'unconfigured',
            'Editing is off: add "access": { "team", "aud" } under "levels" in WebTool/pearz-tool.json.'
        );
    }
    const denied = (reason) => new HttpError(401, 'login', `Not signed in (${reason}).`);
    const cookie = /(?:^|;\s*)CF_Authorization=([^;]+)/.exec(request.headers.get('Cookie') || '');
    const jwt = request.headers.get('Cf-Access-Jwt-Assertion') || (cookie && cookie[1]);
    if (!jwt) throw denied('no Access token');
    const parts = jwt.split('.');
    if (parts.length !== 3) throw denied('malformed token');

    let header, claims;
    try {
        header = JSON.parse(new TextDecoder().decode(fromBase64Url(parts[0])));
        claims = JSON.parse(new TextDecoder().decode(fromBase64Url(parts[1])));
    } catch {
        throw denied('malformed token');
    }
    const issuer = /^https:\/\//.test(access.team)
        ? access.team.replace(/\/+$/, '')
        : `https://${access.team.replace(/\.cloudflareaccess\.com$/, '')}.cloudflareaccess.com`;
    if (header.alg !== 'RS256') throw denied('unexpected algorithm');
    const key = await accessKey(issuer, header.kid);
    if (!key) throw denied('unknown signing key');
    const valid = await crypto.subtle.verify(
        'RSASSA-PKCS1-v1_5', key, fromBase64Url(parts[2]), new TextEncoder().encode(`${parts[0]}.${parts[1]}`)
    );
    if (!valid) throw denied('bad signature');
    if (claims.iss !== issuer) throw denied('wrong issuer');
    if (![].concat(claims.aud || []).includes(access.aud)) throw denied('wrong application');
    if (!(claims.exp * 1000 > Date.now())) throw denied('expired');
    if (!claims.email) throw denied('no email');
    return String(claims.email).toLowerCase();
}

// Tới được đây nghĩa là Access đã cho qua. Mở trong cửa sổ con (pearzTool.levels.login) thì báo cho trang
// tool rồi tự đóng; mở thẳng thì quay về trang trước đó. Báo qua cả BroadcastChannel vì trang đăng nhập của
// Access có thể cắt liên kết window.opener.
function loginPage(url) {
    const target = url.searchParams.get('return') || '/';
    const back = /^\/(?!\/)/.test(target) ? target : '/';
    return new Response(
        '<!DOCTYPE html><meta charset="utf-8"><title>Signed in</title>' +
        '<p style="font-family: system-ui, sans-serif">Signed in. You can close this window.</p><script>' +
        'var done = { pearzTool: 1, type: "login" };' +
        'try { new BroadcastChannel("pearz-login").postMessage(done); } catch (error) {}' +
        'if (window.opener) { window.opener.postMessage(done, location.origin); window.close(); }' +
        'else if (window.name === "pearz-login") { window.close(); }' +
        `else { location.replace(${JSON.stringify(back).replace(/</g, '\\u003c')}); }` +
        '</script>',
        { headers: { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' } }
    );
}
