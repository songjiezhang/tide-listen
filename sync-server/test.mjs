import assert from 'node:assert/strict'
import { spawn, spawnSync } from 'node:child_process'
import { createHash, randomUUID } from 'node:crypto'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { DatabaseSync } from 'node:sqlite'
import { setTimeout as sleep } from 'node:timers/promises'
import { once } from 'node:events'

const folder = mkdtempSync(join(tmpdir(), 'tide-sync-'))
const port = 17900 + Math.floor(Math.random() * 1000)
const url = 'http://127.0.0.1:' + port
const env = {
  ...process.env, SYNC_LOCAL_TEST: '1', SYNC_PORT: String(port), SYNC_BIND: '127.0.0.1',
  SYNC_PUBLIC_URL: url, SYNC_DATA: folder, SYNC_KEY_FILE: join(folder, 'secret', 'key'),
}
const app = fileURLToPath(new URL('server.mjs', import.meta.url))
const setup = spawnSync(process.execPath, [app, 'init', 'owner'], {
  env, input: 'Test-Owner-Password-001\n', encoding: 'utf8',
})
assert.equal(setup.status, 0, setup.stderr)
const child = spawn(process.execPath, [app], { env, stdio: 'inherit' })

async function request(path, settings = {}) {
  const { method = 'GET', body, cookie, token, status = 200, origin = url } = settings
  const response = await fetch(url + path, {
    method, headers: {
      ...(cookie ? { cookie } : {}), ...(token ? { authorization: 'Bearer ' + token } : {}),
      ...(body ? { 'content-type': 'application/json', origin } : {}),
    }, ...(body ? { body: JSON.stringify(body) } : {}),
  })
  const text = await response.text()
  assert.equal(response.status, status, path + ' ' + text)
  return { data: response.headers.get('content-type')?.startsWith('application/json') ? JSON.parse(text) : text,
    cookie: response.headers.get('set-cookie')?.split(';')[0] }
}
const post = (path, body, options = {}) => request(path, { ...options, method: 'POST', body })
const login = async (name, password) => (await post('/admin/api/login', { name, password })).cookie
const addCode = async (cookie, uid, minutes) => (await post('/admin/api/code', { uid, minutes }, { cookie, status: 201 })).data
const pair = async (code, name, status = 201) => (await post('/api/v1/pair', { code, name }, { status })).data
const put = (kind, token, revision, data, status = 200, operation = randomUUID()) =>
  request('/api/v1/' + kind, { method: 'PUT', token, body: { revision, data, operation }, status })
const info = (token, kind) => request('/api/v1/' + kind, { token })
const library = {
  defaultList: { id: 'default', name: 'default', type: 'default', meta: {}, list: [] },
  loveList: { id: 'love', name: 'love', type: 'default', meta: {}, list: [] },
  userList: [{ id: 'commute', name: '沿海公路', type: 'general', meta: {}, list: [
    { id: 'tx_1', name: '海潮', singer: '示例', meta: { source: 'tx' } },
    { id: 'tx_2', name: '晚风', singer: '示例', meta: { source: 'tx' } },
  ] }],
}
try {
  let started = false
  for (let attempt = 0; attempt < 60; attempt++) {
    try { await request('/health'); started = true; break } catch { await sleep(100) }
  }
  assert(started)
  assert.match((await request('/admin')).data, /汐听/)
  await request('/admin/api/account', { status: 401 })
  await post('/admin/api/login', { name: 'owner', password: 'Test-Owner-Password-001' }, { status: 403, origin: 'http://invalid.example' })
  const owner = await login('owner', 'Test-Owner-Password-001')
  await post('/admin/api/create-user', { name: 'member', password: 'Test-Member-Password-002' }, { cookie: owner, status: 201 })
  const users = (await request('/admin/api/account', { cookie: owner })).data.users
  const memberId = users.find((user) => user.name === 'member').id
  const user = await login('member', 'Test-Member-Password-002')
  assert.deepEqual((await request('/admin/api/account', { cookie: user })).data.users, [])
  await post('/admin/api/default-expiry', { minutes: 27 }, { cookie: owner })
  const desktopCode = await addCode(owner, memberId)
  const desktop = await pair(desktopCode.code, 'Windows')
  assert.equal(desktop.account, 'member')
  assert(desktopCode.expires - Date.now() / 1000 > 26 * 60)
  await pair(desktopCode.code, 'Reuse', 400)
  const expiring = await addCode(owner, memberId, 1)
  const db = new DatabaseSync(join(folder, 'sync.sqlite'))
  db.prepare('UPDATE codes SET expires=0 WHERE code=?').run(createHash('sha256').update(expiring.code).digest('hex'))
  await pair(expiring.code, 'Expired', 400)
  const phone = await pair((await addCode(user)).code, 'Android')
  const car = await pair((await addCode(user)).code, 'Android-Car')
  assert.equal(phone.library, desktop.library)
  assert.equal((await info(car.token, 'lists')).data.revision, 0)
  const operation = randomUUID()
  assert.equal((await put('lists', desktop.token, 0, library, 200, operation)).data.revision, 1)
  assert.equal((await put('lists', desktop.token, 0, library, 200, operation)).data.revision, 1)
  assert.deepEqual((await info(phone.token, 'lists')).data.data, library)
  assert.deepEqual((await info(car.token, 'lists')).data.data, library)
  const amended = structuredClone(library)
  amended.loveList.list.push(amended.userList[0].list[1])
  await put('lists', phone.token, 1, amended)
  await put('lists', car.token, 1, library, 409)
  assert.deepEqual((await info(car.token, 'lists')).data.data, amended)
  assert.equal((await request('/admin/api/lists', { cookie: owner })).data.data, null)
  const sources = [{ name: '测试', script: '/* test-fixture-js */\nconst unrelated = 1', url: 'https://example.com/test.js', enabled: true }]
  await put('sources', desktop.token, 0, sources)
  assert.equal((await info(car.token, 'sources')).data.data[0].script, sources[0].script)
  const encrypted = db.prepare("SELECT data FROM libraries WHERE kind='sources'").get().data
  assert(!encrypted.includes('test-fixture-js') && !encrypted.includes('example.com'))
  db.close()
  await post('/admin/api/revoke-device', { id: car.device }, { cookie: user })
  await request('/api/v1/lists', { token: car.token, status: 401 })
  await post('/admin/api/user-status', { uid: memberId, active: false }, { cookie: owner })
  await request('/api/v1/lists', { token: desktop.token, status: 401 })
  await request('/admin/api/account', { cookie: user, status: 401 })
  console.log('PASS 登录、权限隔离、一次性配对、过期、撤销、并发版本检查、歌单和音源加密')
} finally {
  child.kill()
  await once(child, 'exit')
  rmSync(folder, { recursive: true, force: true })
}
