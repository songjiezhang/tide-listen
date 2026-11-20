import { createCipheriv, createDecipheriv, createHash, randomBytes, randomInt, scrypt as scryptCallback, timingSafeEqual } from 'node:crypto'
import { lookup } from 'node:dns/promises'
import { createServer, request as httpRequest } from 'node:http'
import { request as httpsRequest } from 'node:https'
import { BlockList, isIP } from 'node:net'
import { Script } from 'node:vm'
import {
  closeSync, constants, existsSync, fchmodSync, fchownSync, fstatSync,
  mkdirSync, openSync, readFileSync, writeFileSync,
} from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { DatabaseSync, backup } from 'node:sqlite'
import { isDeepStrictEqual, promisify } from 'node:util'

const folder = process.env.SYNC_DATA || '/data'
const secretFile = process.env.SYNC_KEY_FILE || '/secrets/sources.key'
const local = process.env.SYNC_LOCAL_TEST === '1'
const domain = process.env.SYNC_DOMAIN
const publicUrl = new URL(
  process.env.SYNC_PUBLIC_URL || (domain ? 'https://' + domain : local ? 'http://127.0.0.1:8472' : 'https://sync.example.com')
)
if (publicUrl.protocol !== 'https:' && !local) throw Error('SYNC_PUBLIC_URL requires HTTPS')
const origin = publicUrl.origin
const scrypt = promisify(scryptCallback)
const random = () => randomBytes(32).toString('base64url')
const hash = (value) => createHash('sha256').update(value).digest('hex')
const now = () => Math.floor(Date.now() / 1000)
const day = 86400
const unlimitedExpiry = -1
const limit = 24 * 1024 * 1024
const sourceLimit = 4 * 1024 * 1024
const args = process.argv.slice(2)
const container = args[0] === '--container'
const [action, argument] = container ? args.slice(1) : args

const databaseFile = join(folder, 'sync.sqlite')

// Open the exact entry before changing its metadata. This keeps symlinks in
// bind mounts from redirecting the privileged repair to another host file.
function repairContainerPath(path, directory = false) {
  const uid = 1000
  const gid = 1000
  const mode = directory ? 0o700 : 0o600
  let descriptor
  try {
    descriptor = openSync(path, constants.O_RDONLY | constants.O_NOFOLLOW |
      (directory ? constants.O_DIRECTORY : constants.O_NONBLOCK))
  } catch (error) {
    if (!directory && error.code === 'ENOENT') return
    throw error
  }
  try {
    const entry = fstatSync(descriptor)
    if (directory ? !entry.isDirectory() : !entry.isFile()) {
      throw Error('挂载目标不是预期的文件类型：' + path)
    }
    const wrongOwner = entry.uid !== uid || entry.gid !== gid
    const wrongMode = (entry.mode & 0o7777) !== mode
    if (!directory && entry.nlink > 1 && (wrongOwner || wrongMode)) {
      throw Error('请使用独立的数据库或密钥文件：' + path)
    }
    if (wrongOwner) fchownSync(descriptor, uid, gid)
    if (wrongMode) fchmodSync(descriptor, mode)
  } finally {
    closeSync(descriptor)
  }
}

if (container && process.getuid?.() === 0) {
  if (resolve(folder) === '/data') {
    repairContainerPath('/data', true)
    for (const suffix of ['', '-wal', '-shm', '-journal']) {
      repairContainerPath('/data/sync.sqlite' + suffix)
    }
  }
  if (resolve(secretFile) === '/secrets/sources.key') {
    try {
      repairContainerPath('/secrets', true)
      repairContainerPath('/secrets/sources.key')
    } catch (error) {
      // A separately mounted, readable key can remain read-only.
      if (error.code !== 'EROFS') throw error
    }
  }
  // All database operations and the HTTP server run without root privileges.
  process.setgroups([])
  process.setgid(1000)
  process.setuid(1000)
}
mkdirSync(folder, { recursive: true, mode: 0o700 })
if (!existsSync(secretFile)) {
  if (existsSync(databaseFile)) throw Error('请将原来的 sources.key 与数据库一起恢复')
  if (action !== 'init' && (action || !process.env.SYNC_ADMIN_PASSWORD)) {
    throw Error('首次启动请设置 SYNC_ADMIN_PASSWORD，或用 init 创建管理员')
  }
  mkdirSync(dirname(secretFile), { recursive: true, mode: 0o700 })
  try {
    writeFileSync(secretFile, randomBytes(32), { flag: 'wx', mode: 0o600 })
  } catch (error) {
    if (error.code !== 'EEXIST') throw error
  }
}
const key = readFileSync(secretFile)
if (key.length !== 32) throw Error('Invalid sources.key')
const db = new DatabaseSync(databaseFile)
db.exec(`
  PRAGMA journal_mode = WAL;
  PRAGMA foreign_keys = ON;
  PRAGMA busy_timeout = 5000;
  CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE COLLATE NOCASE, password TEXT NOT NULL,
    admin INTEGER NOT NULL DEFAULT 0, active INTEGER NOT NULL DEFAULT 1
  );
  CREATE TABLE IF NOT EXISTS sessions (
    token TEXT PRIMARY KEY, uid INTEGER NOT NULL REFERENCES users(id), expires INTEGER NOT NULL
  );
  CREATE TABLE IF NOT EXISTS codes (
    id INTEGER PRIMARY KEY AUTOINCREMENT, code TEXT NOT NULL UNIQUE, uid INTEGER NOT NULL REFERENCES users(id), expires INTEGER NOT NULL, created INTEGER NOT NULL
  );
  CREATE TABLE IF NOT EXISTS code_secrets (
    code_id INTEGER PRIMARY KEY REFERENCES codes(id) ON DELETE CASCADE,
    issuer INTEGER NOT NULL REFERENCES users(id), value TEXT NOT NULL
  );
  CREATE TABLE IF NOT EXISTS devices (
    id INTEGER PRIMARY KEY AUTOINCREMENT, token TEXT NOT NULL UNIQUE, uid INTEGER NOT NULL REFERENCES users(id),
    name TEXT NOT NULL, seen INTEGER NOT NULL
  );
  CREATE TABLE IF NOT EXISTS libraries (
    uid INTEGER NOT NULL REFERENCES users(id), kind TEXT NOT NULL, revision INTEGER NOT NULL,
    data TEXT NOT NULL, PRIMARY KEY (uid, kind)
  );
  CREATE TABLE IF NOT EXISTS settings (name TEXT PRIMARY KEY, value INTEGER NOT NULL);
  CREATE TABLE IF NOT EXISTS writes (
    uid INTEGER NOT NULL REFERENCES users(id), kind TEXT NOT NULL, operation TEXT NOT NULL,
    revision INTEGER NOT NULL, created INTEGER NOT NULL, PRIMARY KEY (uid, kind, operation)
  );
`)
const sql = (query) => db.prepare(query)
const fail = (status, message) => { throw Object.assign(new Error(message), { status }) }
function tx(fn) {
  db.exec('BEGIN IMMEDIATE')
  try { const result = fn(); db.exec('COMMIT'); return result }
  catch (error) { db.exec('ROLLBACK'); throw error }
}

async function passwordHash(password, salt = randomBytes(16).toString('hex')) {
  const bytes = await scrypt(password, salt, 64, { N: 32768, r: 8, p: 1, maxmem: 64 * 1024 * 1024 })
  return salt + ':' + bytes.toString('hex')
}
function validPassword(password) {
  if (typeof password !== 'string' || password.length < 12 || password.length > 128) fail(400, '密码须为 12 至 128 个字符')
  return password
}
function username(name) {
  if (typeof name !== 'string' || !/^[a-zA-Z0-9_.-]{2,40}$/.test(name)) fail(400, '账号须为 2 至 40 个字母、数字或 ._-')
  return name
}
function protect(value) {
  const iv = randomBytes(12)
  const cipher = createCipheriv('aes-256-gcm', key, iv)
  const encrypted = Buffer.concat([cipher.update(value, 'utf8'), cipher.final()])
  return Buffer.concat([iv, cipher.getAuthTag(), encrypted]).toString('base64')
}
function reveal(value) {
  const bytes = Buffer.from(value, 'base64')
  const cipher = createDecipheriv('aes-256-gcm', key, bytes.subarray(0, 12))
  cipher.setAuthTag(bytes.subarray(12, 28))
  return Buffer.concat([cipher.update(bytes.subarray(28)), cipher.final()]).toString('utf8')
}
const listTypes = new Set(['default', 'general', 'online', 'local', 'remote', 'folder'])
const object = (value) => Boolean(value && typeof value === 'object' && !Array.isArray(value))
const idValue = (value) => (typeof value === 'string' && value.length > 0 && value.length <= 4096) || Number.isSafeInteger(value)
function validateList(list) {
  if (!object(list) || !idValue(list.id) || typeof list.name !== 'string' || list.name.length > 4096 ||
      !listTypes.has(list.type) || !object(list.meta) || !Array.isArray(list.list) ||
      list.list.some((song) => !object(song) || !idValue(song.id) ||
      typeof song.name !== 'string' || typeof song.singer !== 'string' || !object(song.meta))) {
    fail(400, '歌单数据格式有误')
  }
}
function scriptName(script, link) {
  const header = /^\s*\/\*([\s\S]*?)\*\//.exec(script)?.[1] ?? ''
  const name = /^[ \t]*\*?[ \t]*@name[ \t]+([^\r\n]+)/m.exec(header)?.[1]?.trim()
  if (name) return name.slice(0, 512)
  try {
    const filename = new URL(link).pathname.split('/').filter(Boolean).at(-1)
    if (filename) return decodeURIComponent(filename).replace(/\.js$/i, '').slice(0, 512) || '自定义音源'
  } catch { /* A script without a usable name can still be uploaded. */ }
  return '自定义音源'
}

const privateIp = new BlockList()
for (const [prefix, bits] of [
  ['0.0.0.0', 8], ['10.0.0.0', 8], ['100.64.0.0', 10], ['127.0.0.0', 8],
  ['168.63.129.16', 32], ['169.254.0.0', 16], ['172.16.0.0', 12], ['192.0.0.0', 24], ['192.0.2.0', 24],
  ['192.88.99.0', 24], ['192.168.0.0', 16], ['198.18.0.0', 15], ['198.51.100.0', 24],
  ['203.0.113.0', 24], ['224.0.0.0', 3],
]) privateIp.addSubnet(prefix, bits, 'ipv4')
const globalV6 = new BlockList()
globalV6.addSubnet('2000::', 3, 'ipv6')
for (const [prefix, bits] of [['2001::', 23], ['2001:db8::', 32], ['2002::', 16], ['3fff::', 20]]) {
  privateIp.addSubnet(prefix, bits, 'ipv6')
}
const sourceRedirects = new Set([301, 302, 303, 307, 308])
let activeSourceDownloads = 0

function sourceUrl(value, base) {
  let url
  try { url = new URL(value, base) }
  catch { fail(400, '请输入有效的音源链接') }
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) fail(400, '请输入公开的 HTTP 或 HTTPS 音源链接')
  url.hash = ''
  return url
}

async function resolveSourceHost(hostname, signal) {
  const host = hostname.replace(/^\[|\]$/g, '')
  const hostnameKey = host.toLowerCase().replace(/\.$/, '')
  if (hostnameKey === 'localhost' || hostnameKey.endsWith('.localhost')) {
    fail(400, '音源链接必须指向公网地址')
  }
  const family = isIP(host)
  let candidates
  if (family) candidates = [{ address: host, family }]
  else {
    let abort
    try {
      candidates = await Promise.race([
        lookup(host, { all: true, order: 'ipv4first' }),
        new Promise((_, reject) => {
          abort = () => reject(signal.reason)
          signal.addEventListener('abort', abort, { once: true })
        }),
      ])
    } finally { if (abort) signal.removeEventListener('abort', abort) }
  }
  const pinned = candidates.find(({ address, family }) => family === 4
    ? !privateIp.check(address, 'ipv4')
    : globalV6.check(address, 'ipv6') && !privateIp.check(address, 'ipv6'))
  if (!pinned) fail(400, '音源链接必须指向公网地址')
  return pinned
}

async function downloadSource(value) {
  if (typeof value !== 'string' || !value.trim() || value.length > 4096) fail(400, '请输入有效的音源链接')
  let url = sourceUrl(value.trim())
  if (activeSourceDownloads >= 8) fail(429, '音源下载忙，请稍后再试')
  activeSourceDownloads++
  const signal = AbortSignal.timeout(15000)
  try {
    for (let attempt = 0; attempt < 5; attempt++) {
      signal.throwIfAborted()
      const pinned = await resolveSourceHost(url.hostname, signal)
      const request = url.protocol === 'https:' ? httpsRequest : httpRequest
      // Pin each hop to the public address already checked above.
      const response = await new Promise((resolve, reject) => {
        const outgoing = request(url, {
          agent: false, autoSelectFamily: false, signal,
          lookup: (_host, _options, callback) => callback(null, pinned.address, pinned.family),
          headers: { 'accept-encoding': 'identity' },
        }, resolve)
        outgoing.on('error', reject)
        outgoing.end()
      })
      try {
        if (sourceRedirects.has(response.statusCode) && response.headers.location) {
          url = sourceUrl(response.headers.location, url)
          continue
        }
        if (response.statusCode !== 200) fail(502, '音源服务器返回 HTTP ' + response.statusCode)
        if (response.headers['content-encoding'] && response.headers['content-encoding'] !== 'identity') {
          fail(502, '音源服务器返回了不受支持的压缩格式')
        }
        if (Number(response.headers['content-length']) > sourceLimit) fail(413, '音源文件过大')
        const parts = []
        let total = 0
        for await (const chunk of response) {
          total += chunk.length
          if (total > sourceLimit) fail(413, '音源文件过大')
          parts.push(chunk)
        }
        const script = Buffer.concat(parts).toString('utf8').trim()
        if (!script) fail(400, '链接返回了空文件')
        try { new Script(script) }
        catch { fail(400, '链接未返回有效的 JS 脚本') }
        return script
      } finally { response.destroy() }
    }
    fail(502, '音源链接重定向次数过多')
  } catch (error) {
    if (error.status) throw error
    fail(502, signal.aborted ? '下载音源超时' : '无法下载音源，请检查链接')
  } finally { activeSourceDownloads-- }
}

function validate(kind, data) {
  if (kind === 'lists') {
    if (!object(data) || data.defaultList?.id !== 'default' || data.loveList?.id !== 'love' ||
        data.defaultList.type !== 'default' || data.loveList.type !== 'default' || !Array.isArray(data.userList)) {
      fail(400, '歌单快照格式有误')
    }
    const ids = new Set()
    for (const list of [data.defaultList, data.loveList, ...data.userList]) {
      validateList(list)
      if (ids.has(String(list.id))) fail(400, '歌单 ID 重复')
      ids.add(String(list.id))
    }
    return data
  }
  if (!Array.isArray(data) || data.length > 100) fail(400, '音源列表格式有误')
  const seen = new Set()
  return data.map((row) => {
    if (!object(row) || (row.name != null && (typeof row.name !== 'string' || row.name.length > 512)) ||
        typeof row.script !== 'string' || !row.script.trim() || Buffer.byteLength(row.script) > sourceLimit ||
        typeof row.enabled !== 'boolean' || (row.url != null && (typeof row.url !== 'string' ||
        row.url.length > 4096 || (row.url && !/^https?:\/\//i.test(row.url))))) fail(400, '音源内容或更新链接格式有误')
    const script = row.script.trim()
    const digest = hash(script)
    if (seen.has(digest)) fail(400, '列表中已有相同音源')
    seen.add(digest)
    return { name: row.name?.trim() || scriptName(script, row.url), script, url: row.url || '', enabled: row.enabled, digest }
  })
}
function library(uid, kind) {
  const row = sql('SELECT revision, data FROM libraries WHERE uid=? AND kind=?').get(uid, kind)
  if (!row) return { revision: 0, data: null }
  return { revision: row.revision, data: JSON.parse(kind === 'sources' ? reveal(row.data) : row.data) }
}
const revisionQuery = 'SELECT revision FROM libraries WHERE uid=? AND kind=?'
const libraryRevision = (uid, kind) => sql(revisionQuery).get(uid, kind)?.revision ?? 0
const listeners = new Map()

async function changes(req, res, user, query) {
  const seen = {}
  for (const kind of ['lists', 'sources']) {
    const value = query.get(kind)
    if (value === null) continue
    if (!/^(?:-1|[0-9]+)$/.test(value) || !Number.isSafeInteger(Number(value))) fail(400, '同步版本无效')
    seen[kind] = Number(value)
  }
  const kinds = Object.keys(seen)
  if (!kinds.length) fail(400, '请选择监听的同步内容')
  const revisions = () => Object.fromEntries(kinds.map((kind) => [kind, libraryRevision(user.id, kind)]))
  const current = revisions()
  if (kinds.some((kind) => current[kind] !== seen[kind])) return json(res, current)

  const waiting = listeners.get(user.id) ?? new Set()
  if (waiting.size >= 64) fail(429, '同步连接过多，请稍后再试')
  listeners.set(user.id, waiting)
  await new Promise((resolve) => {
    const done = (kind) => {
      if (kind && !kinds.includes(kind)) return
      clearTimeout(timeout)
      res.off('close', done)
      waiting.delete(done)
      if (!waiting.size) listeners.delete(user.id)
      resolve()
    }
    const timeout = setTimeout(done, 20000)
    res.on('close', done)
    waiting.add(done)
  })
  if (res.destroyed) return
  authenticate(req, true)
  return json(res, revisions())
}
function saveLibrary(uid, kind, body) {
  if (!Number.isSafeInteger(body.revision) || body.revision < 0 || !/^[0-9A-Za-z_-]{16,80}$/.test(body.operation || '')) {
    fail(400, '同步版本或操作编号无效')
  }
  const data = validate(kind, body.data)
  const result = tx(() => {
    const prior = sql('SELECT revision FROM writes WHERE uid=? AND kind=? AND operation=?').get(uid, kind, body.operation)
    if (prior) return prior
    const current = library(uid, kind)
    if (current.revision !== body.revision) fail(409, '服务器已有新的修改，请重新同步')
    const changed = !isDeepStrictEqual(current.data, data)
    const revision = current.revision + Number(changed)
    if (changed) {
      const encoded = JSON.stringify(data)
      sql(`INSERT INTO libraries(uid,kind,revision,data) VALUES(?,?,?,?)
        ON CONFLICT(uid,kind) DO UPDATE SET revision=excluded.revision,data=excluded.data`)
        .run(uid, kind, revision, kind === 'sources' ? protect(encoded) : encoded)
    }
    sql('INSERT INTO writes VALUES(?,?,?,?,?)').run(uid, kind, body.operation, revision, now())
    return { revision, changed }
  })
  if (result.changed) {
    for (const done of listeners.get(uid) ?? []) done(kind)
  }
  return { revision: result.revision }
}

const authQuery = {
  api: 'SELECT d.id AS device,u.* FROM devices d JOIN users u ON u.id=d.uid WHERE d.token=? AND u.active=1',
  web: 'SELECT u.* FROM sessions s JOIN users u ON u.id=s.uid WHERE s.token=? AND s.expires>? AND u.active=1',
}
function browserToken(req) {
  return req.headers.cookie?.match(/(?:^|;\s*)tide_session=([\w-]{43})(?:;|$)/)?.[1] || ''
}
function authenticate(req, api) {
  const token = api ? req.headers.authorization?.match(/^Bearer ([\w-]{43})$/)?.[1] : browserToken(req)
  if (!token) fail(401, api ? '设备需要重新配对' : '请登录')
  const user = api ? sql(authQuery.api).get(hash(token)) : sql(authQuery.web).get(hash(token), now())
  if (!user) fail(401, api ? '设备需要重新配对' : '请登录')
  if (api) sql('UPDATE devices SET seen=? WHERE id=? AND seen<?').run(now(), user.device, now() - 30)
  return user
}
const sessionCookie = (token, ttl) => 'tide_session=' + token + '; Path=/; HttpOnly; SameSite=Strict; Max-Age=' + ttl + (local ? '' : '; Secure')
const counters = new Map()
function throttle(req, kind, max, seconds) {
  const address = process.env.SYNC_TRUST_PROXY === '1'
    ? (req.headers['x-forwarded-for']?.split(',').at(-1)?.trim() || req.socket.remoteAddress)
    : req.socket.remoteAddress
  const id = kind + ':' + address
  const counter = counters.get(id)
  if (!counter || counter.until <= now()) {
    if (counters.size > 20000) fail(429, '请求过多，请稍后再试')
    counters.set(id, { count: 1, until: now() + seconds })
  } else if (++counter.count > max) fail(429, '尝试次数过多，请稍后再试')
}
const clean = setInterval(() => {
  for (const [id, value] of counters) if (value.until <= now()) counters.delete(id)
  sql('DELETE FROM sessions WHERE expires<=?').run(now())
  sql('DELETE FROM codes WHERE expires<>? AND expires<=?').run(unlimitedExpiry, now())
  sql('DELETE FROM writes WHERE created<?').run(now() - 30 * day)
}, 60000)
clean.unref()

function json(res, value, status = 200, headers = {}) {
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', ...headers })
  res.end(JSON.stringify(value))
}
async function payload(req, max = limit) {
  if (!/^application\/json(?:\s*;|$)/i.test(req.headers['content-type'] || '')) fail(415, '请求应为 JSON')
  if (Number(req.headers['content-length']) > max) fail(413, '内容超过大小限制')
  const parts = []
  let size = 0
  for await (const part of req) {
    size += part.length
    if (size > max) fail(413, '内容超过大小限制')
    parts.push(part)
  }
  try {
    const data = JSON.parse(Buffer.concat(parts).toString('utf8'))
    if (!object(data)) fail(400, '请求格式有误')
    return data
  } catch (error) {
    if (error.status) throw error
    fail(400, '请求格式有误')
  }
}
const chars = '23456789ABCDEFGHJKLMNPQRSTUVWXYZ'
// Unit indices are persisted in the settings table.
const expiryUnits = ['minutes', 'hours', 'days', 'months', 'years', 'never']

function expiryOption(value) {
  if (!object(value) || !expiryUnits.includes(value.unit)) fail(400, '请选择有效期单位')
  if (value.unit === 'never') return { unit: value.unit }
  if (!Number.isInteger(value.amount) || value.amount < 1 || value.amount > 9999) {
    fail(400, '有效期数量须为 1 至 9999 的整数')
  }
  return { unit: value.unit, amount: value.amount }
}

function defaultExpiry() {
  const read = (name) => sql('SELECT value FROM settings WHERE name=?').get(name)?.value
  const unit = expiryUnits[read('pairExpiryUnit')]
  if (!unit) return { unit: 'minutes', amount: read('pairMinutes') ?? 10 }
  if (unit === 'never') return { unit }
  return { unit, amount: read('pairExpiryAmount') }
}

function requestedExpiry(body, useDefault = false) {
  if (body.expiry !== undefined) return expiryOption(body.expiry)
  if (body.minutes !== undefined) return expiryOption({ unit: 'minutes', amount: body.minutes })
  if (useDefault) return defaultExpiry()
  fail(400, '请设置有效期')
}

function expiration({ unit, amount }, time) {
  if (unit === 'never') return unlimitedExpiry
  if (unit === 'minutes') return time + amount * 60
  if (unit === 'hours') return time + amount * 3600
  if (unit === 'days') return time + amount * day

  const date = new Date(time * 1000)
  const originalDay = date.getUTCDate()
  date.setUTCMonth(date.getUTCMonth() + amount * (unit === 'years' ? 12 : 1), 1)
  const lastDay = new Date(Date.UTC(date.getUTCFullYear(), date.getUTCMonth() + 1, 0)).getUTCDate()
  date.setUTCDate(Math.min(originalDay, lastDay))
  return date.getTime() / 1000
}

function pair(body) {
  const code = typeof body.code === 'string' ? body.code.toUpperCase().replace(/[\s-]/g, '') : ''
  if (!/^[A-Z2-9]{8}$/.test(code)) fail(400, '配对码无效或已过期')
  const name = typeof body.name === 'string' ? body.name.trim().slice(0, 80) : ''
  if (!name) fail(400, '请填写设备名称')
  return tx(() => {
    const found = sql(`SELECT u.id, u.name FROM codes c JOIN users u ON u.id=c.uid
      WHERE c.code=? AND (c.expires=? OR c.expires>?) AND u.active=1`).get(hash(code), unlimitedExpiry, now())
    if (!found) fail(400, '配对码无效或已过期')
    sql('DELETE FROM codes WHERE code=?').run(hash(code))
    const token = random()
    const device = sql('INSERT INTO devices(token,uid,name,seen) VALUES(?,?,?,?)').run(hash(token), found.id, name, now())
    return { token, account: found.name, device: Number(device.lastInsertRowid), library: String(found.id) }
  })
}

function requireAdmin(user) {
  if (!user.admin) fail(403, '需要管理员权限')
}
function revokeUser(uid) {
  for (const table of ['sessions', 'devices', 'codes']) sql('DELETE FROM ' + table + ' WHERE uid=?').run(uid)
}
async function webAccount(req, res, path, method, adminPortal) {
  if (method !== 'GET' && req.headers.origin !== origin) fail(403, '请求来源无效')
  if (path === '/login' && method === 'POST') {
    throttle(req, 'login', 12, 600)
    const body = await payload(req, 8192)
    const name = username(body.name)
    const password = validPassword(body.password)
    const found = sql('SELECT * FROM users WHERE name=? AND active=1').get(name)
    const expected = found?.password || '0'.repeat(32) + ':' + '0'.repeat(128)
    const candidate = await passwordHash(password, expected.split(':')[0])
    if (!timingSafeEqual(Buffer.from(candidate), Buffer.from(expected)) || !found) fail(401, '账号或密码错误')
    if (Boolean(found.admin) !== adminPortal) {
      fail(403, adminPortal ? '请在普通账号入口 /login 登录' : '请在管理员入口 /admin 登录')
    }
    const token = random()
    sql('INSERT INTO sessions VALUES(?,?,?)').run(hash(token), found.id, now() + day)
    return json(res, { account: found.name }, 200, { 'set-cookie': sessionCookie(token, day) })
  }
  const user = authenticate(req, false)
  if (Boolean(user.admin) !== adminPortal) fail(401, '请在当前入口登录')
  if (method === 'GET' && path === '/account') {
    const users = user.admin ? sql('SELECT id,name,active FROM users ORDER BY id').all() : []
    const devices = user.admin
      ? sql('SELECT d.id,d.uid,d.name,d.seen,u.name AS account FROM devices d JOIN users u ON u.id=d.uid ORDER BY d.id').all()
      : sql('SELECT id,uid,name,seen FROM devices WHERE uid=? ORDER BY id').all(user.id)
    const codes = sql(`SELECT c.id,c.uid,NULLIF(c.expires,?) AS expires,u.name AS account,
        CASE WHEN c.uid=? OR s.issuer=? THEN s.value END AS encrypted
      FROM codes c JOIN users u ON u.id=c.uid LEFT JOIN code_secrets s ON s.code_id=c.id
      WHERE (c.uid=? OR ?=1) AND (c.expires=? OR c.expires>?) ORDER BY c.id DESC`)
      .all(unlimitedExpiry, user.id, user.id, user.id, user.admin, unlimitedExpiry, now())
      .map(({ encrypted, ...code }) => ({ ...code, ...(encrypted ? { code: reveal(encrypted) } : {}) }))
    return json(res, {
      account: user.name, id: user.id, admin: Boolean(user.admin), users, devices, codes, expiry: defaultExpiry(),
    })
  }
  const kind = path.match(/^\/(lists|sources)$/)?.[1]
  if (kind && method === 'GET') return json(res, library(user.id, kind))
  if (kind && method === 'PUT') return json(res, saveLibrary(user.id, kind, await payload(req)))
  if (method !== 'POST') fail(404, '接口不存在')
  const body = await payload(req, 8192)
  switch (path) {
    case '/source-download': {
      throttle(req, 'source-download', 20, 60)
      const script = await downloadSource(body.url)
      authenticate(req, false)
      return json(res, { script })
    }
    case '/logout':
      sql('DELETE FROM sessions WHERE token=?').run(hash(browserToken(req)))
      return json(res, {}, 200, { 'set-cookie': sessionCookie('', 0) })
    case '/code': {
      const uid = user.admin && Number.isInteger(body.uid) ? body.uid : user.id
      if (!sql('SELECT id FROM users WHERE id=? AND active=1').get(uid)) fail(404, '用户不存在或已停用')
      const created = now()
      const pending = sql('SELECT COUNT(*) AS n FROM codes WHERE uid=? AND (expires=? OR expires>?)')
        .get(uid, unlimitedExpiry, created).n
      if (pending >= 30) fail(429, '待使用的配对码过多')
      const expires = expiration(requestedExpiry(body, true), created)
      const code = Array.from({ length: 8 }, () => chars[randomInt(chars.length)]).join('')
      tx(() => {
        const { lastInsertRowid } = sql('INSERT INTO codes(code,uid,expires,created) VALUES(?,?,?,?)')
          .run(hash(code), uid, expires, created)
        sql('INSERT INTO code_secrets VALUES(?,?,?)').run(lastInsertRowid, user.id, protect(code))
      })
      return json(res, { code, expires: expires === unlimitedExpiry ? null : expires }, 201)
    }
    case '/default-expiry':
      requireAdmin(user)
      const expiry = requestedExpiry(body)
      tx(() => {
        const save = sql('INSERT OR REPLACE INTO settings VALUES(?,?)')
        save.run('pairExpiryUnit', expiryUnits.indexOf(expiry.unit))
        save.run('pairExpiryAmount', expiry.amount ?? 0)
      })
      return json(res, {})
    case '/revoke-code':
    case '/revoke-device': {
      const table = path.endsWith('code') ? 'codes' : 'devices'
      if (!Number.isSafeInteger(body.id)) fail(400, '编号无效')
      sql('DELETE FROM ' + table + ' WHERE rowid=? AND (uid=? OR ?=1)').run(body.id, user.id, user.admin)
      return json(res, {})
    }
    case '/create-user': {
      requireAdmin(user)
      const name = username(body.name)
      const password = await passwordHash(validPassword(body.password))
      if (sql('SELECT id FROM users WHERE name=?').get(name)) fail(409, '账号已存在')
      sql('INSERT INTO users(name,password) VALUES(?,?)').run(name, password)
      return json(res, {}, 201)
    }
    case '/user-status':
      requireAdmin(user)
      if (!Number.isSafeInteger(body.uid) || typeof body.active !== 'boolean' || body.uid === user.id) fail(400, '用户设置无效')
      tx(() => {
        if (!sql('UPDATE users SET active=? WHERE id=? AND admin=0').run(Number(body.active), body.uid).changes) fail(404, '用户不存在')
        if (!body.active) revokeUser(body.uid)
      })
      return json(res, {})
    case '/password': {
      const uid = user.admin && Number.isInteger(body.uid) ? body.uid : user.id
      if (uid === user.id) {
        const current = await passwordHash(validPassword(body.current), user.password.split(':')[0])
        if (!timingSafeEqual(Buffer.from(current), Buffer.from(user.password))) fail(401, '当前密码错误')
      }
      const password = await passwordHash(validPassword(body.password))
      tx(() => {
        if (!sql('UPDATE users SET password=? WHERE id=? AND (?=id OR admin=0)').run(password, uid, user.id).changes) fail(404, '用户不存在')
        sql('DELETE FROM sessions WHERE uid=? AND token<>?').run(uid, hash(browserToken(req)))
        sql('DELETE FROM codes WHERE uid=?').run(uid)
      })
      return json(res, {})
    }
    default: fail(404, '接口不存在')
  }
}

const staticAssets = {
  '/favicon.png': ['favicon.png', 'image/png'],
  '/login': ['admin.html', 'text/html; charset=utf-8'],
  '/login/': ['admin.html', 'text/html; charset=utf-8'],
  '/admin': ['admin.html', 'text/html; charset=utf-8'],
  '/admin/': ['admin.html', 'text/html; charset=utf-8'],
  '/admin/site.css': ['site.css', 'text/css; charset=utf-8'],
  '/admin/admin.js': ['admin.js', 'text/javascript; charset=utf-8'],
}
async function handler(req, res) {
  res.setHeader('cache-control', 'no-store')
  res.setHeader('x-content-type-options', 'nosniff')
  res.setHeader('referrer-policy', 'no-referrer')
  res.setHeader('content-security-policy', "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'")
  const { pathname: path, searchParams } = new URL(req.url, origin)
  const method = req.method
  if (path === '/health' && method === 'GET') return json(res, { status: 'ok' })
  if (path === '/' && method === 'GET') { res.writeHead(303, { location: '/login' }); return res.end() }
  if (Object.hasOwn(staticAssets, path) && method === 'GET') {
    const [name, type] = staticAssets[path]
    res.writeHead(200, { 'content-type': type })
    return res.end(readFileSync(new URL(name, import.meta.url)))
  }
  const portal = path.match(/^\/(login|admin)\/api(\/.*)$/)
  if (portal) return webAccount(req, res, portal[2], method, portal[1] === 'admin')
  if (path === '/api/v1/pair' && method === 'POST') {
    throttle(req, 'pair', 20, 600)
    return json(res, pair(await payload(req, 8192)), 201)
  }
  if (path === '/api/v1/changes' && method === 'GET') {
    return changes(req, res, authenticate(req, true), searchParams)
  }
  const kind = path.match(/^\/api\/v1\/(lists|sources)$/)?.[1]
  if (!kind || (method !== 'GET' && method !== 'PUT')) fail(404, '接口不存在')
  const user = authenticate(req, true)
  if (method === 'GET') return json(res, library(user.id, kind))
  const body = await payload(req)
  const active = authenticate(req, true)
  if (active.id !== user.id) fail(401, '设备需要重新配对')
  return json(res, saveLibrary(user.id, kind, body))
}

function hasAdmin() {
  return Boolean(sql('SELECT id FROM users WHERE admin=1').get())
}

async function createInitialAdmin(name, password) {
  const account = username(name)
  const encoded = await passwordHash(validPassword(password))
  return tx(() => {
    if (hasAdmin()) return false
    sql('INSERT INTO users(name,password,admin) VALUES(?,?,1)').run(account, encoded)
    return true
  })
}

if (action === 'init') {
  const password = readFileSync(0, 'utf8').replace(/\r?\n$/, '')
  if (!await createInitialAdmin(argument || 'admin', password)) throw Error('管理员已经初始化')
  console.log('管理员已创建')
  db.close()
} else if (action === 'backup') {
  const destination = argument || join(folder, 'sync-backup-' + now() + '.sqlite')
  if (existsSync(destination)) throw Error('目标备份文件已存在')
  await backup(db, destination)
  db.close()
  console.log(destination)
} else if (action) {
  throw Error('用法：server.mjs [init <账号> | backup <路径>]')
} else {
  if (!hasAdmin()) {
    const password = process.env.SYNC_ADMIN_PASSWORD
    if (!password) throw Error('首次启动请设置 SYNC_ADMIN_PASSWORD，或用 init 创建管理员')
    if (await createInitialAdmin(process.env.SYNC_ADMIN_USERNAME || 'admin', password)) console.log('管理员已创建')
  }
  delete process.env.SYNC_ADMIN_PASSWORD
  const server = createServer({ requestTimeout: 45000, headersTimeout: 10000 }, (req, res) => {
    handler(req, res).catch((error) => {
      if (!error.status) console.error(error.code || error.name)
      if (!res.headersSent && !res.destroyed) json(res, { error: error.status ? error.message : '同步服务内部错误' }, error.status || 500)
      else if (!res.writableEnded) res.end()
    })
  })
  server.listen(Number(process.env.SYNC_PORT || 8472), process.env.SYNC_BIND || '0.0.0.0')
  for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => server.close(() => db.close()))
}
