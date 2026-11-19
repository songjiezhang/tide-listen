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
  const { method = 'GET', body, cookie, token, status = 200, origin = url, signal } = settings
  const response = await fetch(url + path, {
    method, signal, headers: {
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
const login = async (portal, name, password, status = 200) =>
  (await post(portal + '/api/login', { name, password }, { status })).cookie
const addCode = async (portal, cookie, body = {}) =>
  (await post(portal + '/api/code', body, { cookie, status: 201 })).data
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
  const home = await fetch(url, { redirect: 'manual' })
  assert.equal(home.status, 303)
  assert.equal(home.headers.get('location'), '/login')
  assert.match((await request('/login')).data, /汐听/)
  assert.match((await request('/admin')).data, /汐听/)
  await request('/admin/api/account', { status: 401 })
  await request('/login/api/account', { status: 401 })

  const ownerPassword = 'Test-Owner-Password-001'
  const memberPassword = 'Test-Member-Password-002'
  await post('/admin/api/login', { name: 'owner', password: ownerPassword }, { status: 403, origin: 'http://invalid.example' })
  const owner = await login('/admin', 'owner', ownerPassword)
  await post('/admin/api/create-user', { name: 'member', password: memberPassword }, { cookie: owner, status: 201 })
  const adminAccount = () => request('/admin/api/account', { cookie: owner })
  const memberId = (await adminAccount()).data.users.find((user) => user.name === 'member').id
  assert.equal(await login('/login', 'owner', ownerPassword, 403), undefined)
  assert.equal(await login('/admin', 'member', memberPassword, 403), undefined)
  const user = await login('/login', 'member', memberPassword)
  assert.deepEqual((await request('/login/api/account', { cookie: user })).data.users, [])
  await request('/admin/api/account', { cookie: user, status: 401 })
  await request('/login/api/account', { cookie: owner, status: 401 })
  await post('/login/api/default-expiry', { expiry: { unit: 'never' } }, { cookie: user, status: 403 })

  const db = new DatabaseSync(join(folder, 'sync.sqlite'))
  db.prepare('INSERT INTO settings VALUES(?,?)').run('pairMinutes', 27)
  assert.deepEqual((await adminAccount()).data.expiry, { unit: 'minutes', amount: 27 })
  const desktopCode = await addCode('/admin', owner, { uid: memberId })
  const createdForMember = (await adminAccount()).data.codes.find((row) => row.code === desktopCode.code)
  assert.equal(createdForMember.uid, memberId)
  assert.equal(createdForMember.account, 'member')
  assert.equal((await request('/login/api/account', { cookie: user })).data.codes[0].code, desktopCode.code)
  const ciphertext = db.prepare('SELECT value FROM code_secrets WHERE code_id=?').get(createdForMember.id).value
  assert(!ciphertext.includes(desktopCode.code))
  const desktop = await pair(desktopCode.code, 'Windows')
  assert.equal(db.prepare('SELECT value FROM code_secrets WHERE code_id=?').get(createdForMember.id), undefined)
  assert.equal((await adminAccount()).data.codes.some((row) => row.id === createdForMember.id), false)
  assert.equal(desktop.account, 'member')
  assert(desktopCode.expires - Date.now() / 1000 > 26 * 60)
  await pair(desktopCode.code, 'Reuse', 400)
  const expiring = await addCode('/admin', owner, { uid: memberId, minutes: 1 })
  db.prepare('UPDATE codes SET expires=0 WHERE code=?').run(createHash('sha256').update(expiring.code).digest('hex'))
  await pair(expiring.code, 'Expired', 400)

  for (const [unit, min, max] of [
    ['minutes', 60, 60], ['hours', 3600, 3600], ['days', 86400, 86400],
    ['months', 28 * 86400, 31 * 86400], ['years', 365 * 86400, 366 * 86400],
  ]) {
    const before = Math.floor(Date.now() / 1000)
    const { expires } = await addCode('/admin', owner, { expiry: { unit, amount: 1 } })
    const after = Math.floor(Date.now() / 1000)
    assert(expires >= before + min && expires <= after + max, 'expiry for ' + unit)
  }
  for (const expiry of [{ unit: 'weeks', amount: 1 }, { unit: 'days', amount: 0 }, { unit: 'hours', amount: 1.5 }]) {
    await post('/admin/api/code', { expiry }, { cookie: owner, status: 400 })
  }
  const newDefault = { unit: 'never' }
  await post('/admin/api/default-expiry', { expiry: newDefault }, { cookie: owner })
  assert.deepEqual((await adminAccount()).data.expiry, newDefault)
  const pending = await addCode('/login', user, { uid: (await adminAccount()).data.id })
  assert.equal(pending.expires, null)
  const active = (await request('/login/api/account', { cookie: user })).data.codes
  assert.equal(active.length, 1)
  assert.equal(active[0].expires, null)
  assert.equal(active[0].uid, memberId)
  assert.equal(active[0].code, pending.code)
  assert.equal((await adminAccount()).data.codes.find((row) => row.id === active[0].id).code, undefined)
  const phone = await pair(pending.code, 'Android')
  await pair(pending.code, 'Reuse unlimited', 400)

  const unneeded = await addCode('/login', user)
  const unused = (await request('/login/api/account', { cookie: user })).data.codes
  assert.equal(unused.length, 1)
  await post('/login/api/revoke-code', { id: unused[0].id }, { cookie: user })
  await pair(unneeded.code, 'Revoked code', 400)
  assert.equal(db.prepare('SELECT value FROM code_secrets WHERE code_id=?').get(unused[0].id), undefined)
  const car = await pair((await addCode('/login', user, { expiry: { unit: 'hours', amount: 2 } })).code, 'Android-Car')
  assert.equal(phone.library, desktop.library)
  assert.equal((await info(car.token, 'lists')).data.revision, 0)
  await request('/api/v1/changes?lists=0', { status: 401 })
  await request('/api/v1/changes?lists=invalid', { token: car.token, status: 400 })
  assert.deepEqual((await info(car.token, 'changes?lists=-1')).data, { lists: 0 })
  const wrongAccount = await pair((await addCode('/admin', owner)).code, 'Admin device')
  const controller = new AbortController()
  const otherAccount = request('/api/v1/changes?lists=0', { token: wrongAccount.token, signal: controller.signal })
  const changing = info(car.token, 'changes?lists=0&sources=0')
  await sleep(75)
  const operation = randomUUID()
  assert.equal((await put('lists', desktop.token, 0, library, 200, operation)).data.revision, 1)
  assert.deepEqual((await changing).data, { lists: 1, sources: 0 })
  assert.equal(await Promise.race([otherAccount.then(() => false), sleep(120, true)]), true)
  controller.abort()
  await assert.rejects(otherAccount, { name: 'AbortError' })
  assert.equal((await put('lists', desktop.token, 0, library, 200, operation)).data.revision, 1)
  assert.equal((await put('lists', desktop.token, 1, library)).data.revision, 1)
  assert.deepEqual((await info(phone.token, 'lists')).data.data, library)
  assert.deepEqual((await info(car.token, 'lists')).data.data, library)
  const amended = structuredClone(library)
  amended.loveList.list.push(amended.userList[0].list[1])
  await put('lists', phone.token, 1, amended)
  await put('lists', car.token, 1, library, 409)
  assert.deepEqual((await info(car.token, 'lists')).data.data, amended)
  assert.equal((await request('/admin/api/lists', { cookie: owner })).data.data, null)
  const sources = [{ name: '测试', script: '/* test-fixture-js */\nconst unrelated = 1', url: 'https://example.com/test.js', enabled: true }]
  const sourceChange = info(phone.token, 'changes?lists=2&sources=0')
  await sleep(75)
  await put('sources', desktop.token, 0, sources)
  assert.deepEqual((await sourceChange).data, { lists: 2, sources: 1 })
  assert.equal((await info(car.token, 'sources')).data.data[0].script, sources[0].script)
  const encrypted = db.prepare("SELECT data FROM libraries WHERE kind='sources'").get().data
  assert(!encrypted.includes('test-fixture-js') && !encrypted.includes('example.com'))
  db.close()
  await post('/login/api/revoke-device', { id: car.device }, { cookie: user })
  await request('/api/v1/lists', { token: car.token, status: 401 })
  await post('/admin/api/user-status', { uid: memberId, active: false }, { cookie: owner })
  await request('/api/v1/lists', { token: desktop.token, status: 401 })
  await request('/login/api/account', { cookie: user, status: 401 })
  console.log('PASS 登录及权限隔离、代用户配对、配对码加密与单次使用、按账号推送歌单和音源版本')
} finally {
  child.kill()
  await once(child, 'exit')
  rmSync(folder, { recursive: true, force: true })
}
