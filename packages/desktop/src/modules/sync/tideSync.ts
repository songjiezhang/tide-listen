import fs from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { createHash, randomUUID } from 'node:crypto'
import { hostname } from 'node:os'
import { safeStorage } from 'electron'
import { extensionEvent } from '@any-listen/app/modules/extension'
import { LX_SOURCE_LOADER } from '@any-listen/common/command'
import { getSyncWebDAVState, syncWebDAVEvent } from '@any-listen/app/modules/sync'
import { applySyncedMusicList, onMusicListAction } from '@any-listen/app/modules/musicList'
import { workers } from '@any-listen/app/modules/worker'

import { appState, updateSetting } from '@/app'

import { runSyncWebDAV } from './index'
import { mergeLibrary, sameValue } from './mergeLibrary'

type Library = AnyListen.List.ListDataFull
type Source = AnyListen.IPCSync.TideSource
type Kind = 'lists' | 'sources'
type Revisions = Partial<Record<Kind, number>>
interface Snapshot<T> { revision: number; data: T | null }
interface Connection {
  url: string
  token: string
  account: string
  library: string
  started: boolean
  sources: boolean
  listRevision?: number
  sourceRevision?: number
  sourceHash?: string
}
interface Preview {
  id: string
  data: Library
  backup: string
}

let connection: Connection | undefined
let base: Library | undefined
let preview: Preview | undefined
let timer: ReturnType<typeof setTimeout> | undefined
let message = ''
let pendingSources: Array<{ name: string; enabled: boolean }> | undefined
let pendingRevision: number | undefined
let inProgress: Promise<unknown> | undefined
let watching: AbortController | undefined
const dirty = new Set<Kind>()
const retries = new Map<Kind, ReturnType<typeof setTimeout>>()

const fileName = () => join(appState.dataPath, 'tidelisten-sync.json')
const digest = (sources: Source[]) => {
  return createHash('sha256').update(JSON.stringify(sources.map(({ script, name, url, enabled }) => [script.trim(), name, url, enabled]))).digest('hex')
}
class SyncError extends Error {
  constructor(readonly status: number, text: string) { super(text) }
}

function serverUrl(text: string) {
  const url = new URL(text.trim())
  if ((url.protocol !== 'https:' && !(url.protocol === 'http:' && ['127.0.0.1', 'localhost'].includes(url.hostname))) ||
      url.username || url.password || url.hash || url.search || !/^\/(?:login\/?|admin\/?)?$/.test(url.pathname)) {
    throw Error('请输入同步服务的 HTTPS 地址')
  }
  return url.origin
}

async function request<T>(
  url: string, path: string, method = 'GET', token?: string, body?: unknown, signal = AbortSignal.timeout(18000)
): Promise<T> {
  const response = await fetch(url + '/api/v1/' + path, {
    method,
    redirect: 'error',
    signal,
    headers: {
      ...(token ? { authorization: 'Bearer ' + token } : {}),
      ...(body ? { 'content-type': 'application/json' } : {}),
    },
    ...(body ? { body: JSON.stringify(body) } : {}),
  }).catch(() => { throw Error('无法连接同步服务，请检查地址和网络') })
  const result = await response.json().catch(() => {
    throw Error('同步服务响应无效')
  }) as T & { error?: string }
  if (!response.ok) throw new SyncError(response.status, result.error || '同步请求失败')
  return result
}
const get = <T>(scope: Connection, kind: string) => request<Snapshot<T>>(scope.url, kind, 'GET', scope.token)
const put = (scope: Connection, kind: string, revision: number, data: unknown) =>
  request<{ revision: number }>(scope.url, kind, 'PUT', scope.token, { revision, data, operation: randomUUID() })

async function save() {
  const path = fileName()
  const value = connection
    ? { connection: { ...connection, token: safeStorage.encryptString(connection.token).toString('base64') }, base }
    : {}
  await fs.writeFile(path + '.tmp', JSON.stringify(value), { mode: 0o600 })
  await fs.rename(path + '.tmp', path)
}

function stopWatching() {
  watching?.abort()
  watching = undefined
  if (timer) clearTimeout(timer)
}

function schedule(delay = 0) {
  stopWatching()
  if (!connection?.started || inProgress) return
  timer = setTimeout(() => { void syncInBackground() }, delay)
}

function queue(kind: Kind) {
  if (!connection?.started || (kind === 'sources' && !connection.sources)) return
  clearRetry(kind)
  dirty.add(kind)
  schedule(800)
}

function clearRetry(kind: Kind) {
  const retry = retries.get(kind)
  if (retry) clearTimeout(retry)
  retries.delete(kind)
}

function retryKind(scope: Connection, kind: Kind) {
  clearRetry(kind)
  retries.set(kind, setTimeout(() => {
    if (connection === scope) queue(kind)
  }, 15000))
}

async function syncInBackground() {
  const scope = connection
  if (!scope?.started || inProgress) return
  if (dirty.size) {
    let failed = false
    const kinds = [...dirty]
    inProgress = (async () => {
      for (const kind of kinds) {
        dirty.delete(kind)
        try {
          if (kind === 'lists') await syncLists(scope)
          else if (scope.sources) await syncSources(scope)
        } catch (error) {
          if (!dirty.has(kind)) retryKind(scope, kind)
          message = error instanceof Error ? error.message : String(error)
          failed = true
        }
      }
    })()
    try {
      await inProgress
      if (!failed) message = '同步已完成'
    } finally {
      inProgress = undefined
      schedule()
    }
    return
  }

  const since = new URLSearchParams()
  if (!retries.has('lists')) since.set('lists', String(scope.listRevision ?? -1))
  if (scope.sources && !retries.has('sources')) since.set('sources', String(pendingRevision ?? scope.sourceRevision ?? -1))
  if (!since.size) return
  const controller = new AbortController()
  watching = controller
  let delay = 0
  try {
    const latest = await request<Revisions>(scope.url, 'changes?' + since.toString(), 'GET', scope.token,
      undefined, AbortSignal.any([controller.signal, AbortSignal.timeout(28000)]))
    if (watching !== controller) return
    for (const kind of ['lists', 'sources'] as const) {
      if (typeof latest[kind] === 'number' && latest[kind] !== Number(since.get(kind))) dirty.add(kind)
    }
  } catch (error) {
    if (watching !== controller) return
    message = error instanceof SyncError && error.status === 404
      ? '请更新同步服务以接收远端变更'
      : error instanceof Error ? error.message : String(error)
    delay = 15000
  } finally {
    if (watching === controller) schedule(delay)
  }
}

export async function startTideSync() {
  try {
    const saved = JSON.parse(await fs.readFile(fileName(), 'utf8')) as { connection?: Connection; base?: Library }
    if (saved.connection) connection = {
      ...saved.connection, token: safeStorage.decryptString(Buffer.from(saved.connection.token, 'base64')),
    }
    base = saved.base
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== 'ENOENT') message = '读取配对凭据失败，请重新配对'
  }
  if (connection?.started && appState.appSetting['sync.webdav.enable']) updateSetting({ 'sync.webdav.enable': false })
  onMusicListAction(async (action, isRemote) => {
    if (!isRemote && !(action.action === 'list_update' && action.data.sync)) queue('lists')
  })
  extensionEvent.on('extensionEvent', (event) => {
    if (event.action === 'extenstionSettingUpdated' && event.data.id === LX_SOURCE_LOADER &&
        event.data.keys.some((key) => key === 'importedScriptSources' || key === 'enabledScripts')) queue('sources')
  })
  if (connection?.started) {
    if (!base || !sameValue(base, await workers.dbService.getAllListData())) dirty.add('lists')
    if (connection.sources) {
      try {
        if (connection.sourceHash !== digest(await workers.extensionService.exportTideSources())) dirty.add('sources')
      } catch { /* Keep playlist sync available while the extension is disabled. */ }
    }
    schedule()
  }
}

async function syncLists(scope: Connection, migrating?: Library) {
  for (let attempt = 0; attempt < 5; attempt++) {
    const remote = await get<Library>(scope, 'lists')
    const local = await workers.dbService.getAllListData()
    if (migrating && !sameValue(local, migrating)) throw Error('歌单已变更，请重新预览迁移')
    const merged = mergeLibrary(base, local, remote.data)
    let revision = remote.revision
    if (!sameValue(merged, remote.data)) {
      try { revision = (await put(scope, 'lists', revision, merged)).revision }
      catch (error) {
        if (error instanceof SyncError && error.status === 409) continue
        throw error
      }
    }
    // Keep edits made while the remote request was in flight.
    for (let round = 0; round < 5; round++) {
      const latest = await workers.dbService.getAllListData()
      const applied = mergeLibrary(local, latest, merged)
      if (!sameValue(latest, applied) && !(await applySyncedMusicList(latest, applied))) continue
      base = merged
      scope.listRevision = revision
      if (!sameValue(applied, merged)) dirty.add('lists')
      await save()
      return
    }
    throw Error('本机歌单正在编辑，请稍后重试')
  }
  throw Error('歌单正在被其他设备修改，请稍后重试')
}

async function syncSources(scope: Connection, approve?: number) {
  const remote = await get<Source[]>(scope, 'sources')
  const local = await workers.extensionService.exportTideSources()
  const localHash = digest(local)
  let result = remote
  if (approve !== undefined) {
    if (approve !== remote.revision || pendingRevision !== remote.revision) throw Error('服务器音源已变更，请重新查看')
    await workers.extensionService.installTideSources(remote.data ?? [])
    scope.sourceHash = digest(await workers.extensionService.exportTideSources())
  } else if (remote.data && digest(remote.data) === localHash) {
    scope.sourceHash = localHash
  } else if (remote.revision === 0 || scope.sourceRevision === remote.revision) {
    if (scope.sourceHash !== localHash && (local.length || remote.data)) {
      result = { ...(await put(scope, 'sources', remote.revision, local)), data: local }
    }
    scope.sourceHash = localHash
  } else {
    pendingRevision = remote.revision
    pendingSources = remote.data?.map(({ name, enabled }) => ({ name, enabled })) ?? []
    return
  }
  scope.sourceRevision = result.revision
  pendingRevision = undefined
  pendingSources = undefined
  await save()
}

export const isTideSyncActive = () => Boolean(connection?.started)
const state = (): AnyListen.IPCSync.TideState => ({
  url: connection?.url, account: connection?.account, started: connection?.started ?? false,
  sources: connection?.sources ?? false, message, pendingSources, pendingRevision,
})

async function finishWebDAV() {
  await new Promise<void>((resolve, reject) => {
    let syncing = getSyncWebDAVState().status === 'syncing'
    const done = (error?: Error) => {
      clearTimeout(timeout)
      syncWebDAVEvent.off('statusChanged', change)
      if (error) reject(error)
      else resolve()
    }
    const change = (status: AnyListen.IPCSync.SyncState['webdav']) => {
      if (status.status === 'syncing') syncing = true
      if (status.status === 'error') done(Error(status.error || 'WebDAV 同步失败'))
      if (status.status === 'idle') done(syncing ? undefined : Error('WebDAV 同步繁忙，请稍后重试'))
    }
    const timeout = setTimeout(() => done(Error('等待 WebDAV 同步超时')), 90000)
    syncWebDAVEvent.on('statusChanged', change)
    const state = getSyncWebDAVState()
    if (state.status === 'idle' || state.status === 'error') {
      void runSyncWebDAV(async () => 'merge_local_remote', async () => 'cancel').catch(done)
    }
  })
}

async function operation(options: AnyListen.IPCSync.TideOptions): Promise<AnyListen.IPCSync.TideState> {
  const scope = connection
  switch (options.action) {
    case 'status': return state()
    case 'pair': {
      if (scope) throw Error('请先断开当前同步账号')
      if (!safeStorage.isEncryptionAvailable()) throw Error('系统凭据加密不可用')
      const url = serverUrl(options.url)
      const paired = await request<Pick<Connection, 'token' | 'account' | 'library'>>(url, 'pair', 'POST', undefined, {
        code: options.code, name: '汐听桌面 · ' + hostname(),
      })
      connection = { ...paired, url, started: false, sources: false }
      base = undefined
      message = '已配对，请选择怎样同步现有歌单'
      await save()
      break
    }
    case 'disconnect':
      connection = undefined
      dirty.clear()
      clearRetry('lists')
      clearRetry('sources')
      base = undefined
      preview = undefined
      pendingSources = undefined
      pendingRevision = undefined
      message = '已断开同步服务'
      await save()
      break
    case 'preview': {
      if (!scope || scope.started) throw Error('请先配对新的同步账号')
      if (appState.appSetting['sync.webdav.url'] && appState.appSetting['sync.webdav.syncEnable.list']) {
        await finishWebDAV()
      }
      const data = await workers.dbService.getAllListData()
      const backup = join(appState.dataPath, 'sync-migration-backups', 'webdav-' + Date.now() + '.json')
      preview = { id: randomUUID(), data, backup }
      return {
        ...state(), preview: {
          id: preview.id,
          lists: [data.loveList, data.defaultList, ...data.userList].map(({ id, name, list }) => ({
            name: id === 'love' ? '我喜欢' : id === 'default' ? '试听列表' : name, count: list.length,
          })),
        },
      }
    }
    case 'start': {
      if (!scope || scope.started) throw Error('请先配对新的同步账号')
      if (options.preview && preview?.id !== options.preview) throw Error('请重新预览迁移')
      const migrating = options.preview ? preview : undefined
      const syncWasOn = appState.appSetting['sync.webdav.enable']
      if (syncWasOn) updateSetting({ 'sync.webdav.enable': false })
      try {
        if (migrating) {
          await fs.mkdir(dirname(migrating.backup), { recursive: true })
          await fs.writeFile(migrating.backup, JSON.stringify({ songlist: { version: 1, data: migrating.data } }), { flag: 'wx', mode: 0o600 })
        }
        await syncLists(scope, migrating?.data)
      } catch (error) {
        if (syncWasOn) updateSetting({ 'sync.webdav.enable': true })
        throw error
      }
      scope.started = true
      preview = undefined
      message = migrating ? '迁移完成。备份：' + migrating.backup : '歌单已同步'
      await save()
      break
    }
    case 'sources':
      if (!scope?.started) throw Error('请先开启歌单同步')
      if (options.enabled) await workers.extensionService.exportTideSources()
      scope.sources = options.enabled
      if (!options.enabled) {
        dirty.delete('sources')
        clearRetry('sources')
        scope.sourceRevision = undefined
        scope.sourceHash = undefined
        pendingSources = undefined
        pendingRevision = undefined
      }
      await save()
      if (scope.sources) await syncSources(scope)
      break
    case 'approve':
      if (!scope?.started || !scope.sources) throw Error('请先启用音源同步')
      await syncSources(scope, options.revision)
      message = '音源已更新'
      break
    case 'sync':
      if (!scope?.started) return state()
      dirty.delete('lists')
      clearRetry('lists')
      await syncLists(scope).catch((error) => { retryKind(scope, 'lists'); throw error })
      message = '歌单已同步'
      if (scope.sources) {
        dirty.delete('sources')
        clearRetry('sources')
        await syncSources(scope).catch((error) => { retryKind(scope, 'sources'); throw error })
      }
      break
  }
  return state()
}

export async function tideSync(options: AnyListen.IPCSync.TideOptions): Promise<AnyListen.IPCSync.TideState> {
  if (options.action === 'status') return state()
  if (inProgress) await inProgress.catch(() => {})
  if (inProgress) throw Error('同步正在进行，请稍后再试')
  stopWatching()
  inProgress = operation(options)
  try { return await inProgress as AnyListen.IPCSync.TideState }
  catch (error) { message = error instanceof Error ? error.message : String(error); throw error }
  finally { inProgress = undefined; schedule(dirty.size ? 800 : 0) }
}
