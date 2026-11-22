import { Script } from 'node:vm'

import { CANCELED_ERROR_MSG } from '@any-listen/common/constants'
import { LX_SOURCE_COMMANDS, LX_SOURCE_LOADER } from '@any-listen/common/command'
import { toMD5 } from '@any-listen/nodejs'
import { request, type NeedBodyType } from '@any-listen/nodejs/request'

import { createCommon } from './extensionApis/common'
import { createStore } from './extensionApis/storage'
import { i18n } from './i18n'
import { createBoxs } from './internalExtension/shared'
import { getConfig } from './shared/configStore'
import { updateExtensionSettings } from './shared'
import { extensionState } from './state'
import { openLxSource, verifyLxSource } from './verifyLxSource'

interface Source {
  id: string
  name: string
  fileName: string
  fileDesc: string
  version?: string
  sourceUrl?: string
  allowShowUpdateAlert?: boolean
}

const MAX_SCRIPT_BYTES = 4 * 1024 * 1024

const fetchScript = async (url: string) => {
  const { statusCode, body } = await request<NeedBodyType>(url, {
    needBody: true,
    retryNum: 0,
    signal: AbortSignal.timeout(25000),
    headers: { 'cache-control': 'no-cache' },
  })
  try {
    if (statusCode !== 200) throw new Error(`HTTP ${statusCode}`)
    const parts: Buffer[] = []
    let size = 0
    for await (const part of body as AsyncIterable<Uint8Array>) {
      size += part.length
      if (size > MAX_SCRIPT_BYTES) throw new Error(i18n.t('lx_link_too_large'))
      parts.push(Buffer.from(part))
    }
    return Buffer.concat(parts)
      .toString('utf8')
      .replace(/^\uFEFF/, '')
  } finally {
    if (!body.destroyed) body.destroy()
  }
}

const parseSource = (raw: string, url: URL, validate: boolean) => {
  const header = /^\s*\/\*([\s\S]*?)\*\//.exec(raw)
  if (validate) {
    if (!header || !raw.slice(header[0].length).trim()) throw new Error(i18n.t('lx_link_invalid_script'))
    // eslint-disable-next-line no-new -- Manual imports check syntax without executing the script.
    new Script(raw)
  }
  const fields = new Map<string, string>()
  for (const [, key, value] of (header?.[1] ?? '').matchAll(/^[ \t]*\*[ \t]*@(\w+)[ \t]+(.+?)[ \t]*$/gm)) fields.set(key, value.trim())
  const fileName = url.pathname.split('/').pop() || 'remote_source.js'
  const version = fields.get('version')?.slice(0, 36) || ''
  const author = fields.get('author')?.slice(0, 56) || ''
  const description = fields.get('description')?.slice(0, 36) || ''
  const label = [version && (version.startsWith('v') ? version : `v${version}`), author && `@${author}`].join('')
  return {
    id: toMD5(raw.trim()),
    name: fields.get('name')?.slice(0, 24) || fileName,
    version,
    author,
    description,
    homepage: fields.get('homepage')?.slice(0, 1024) || '',
    fileName: fileName.endsWith('.js') ? fileName : `${fileName}.js`,
    fileDesc: [label && `[${label}]`, description].filter(Boolean).join(' '),
    allowShowUpdateAlert: true,
    sourceUrl: url.href,
  }
}

const readSources = async (ext: AnyListen.Extension.Extension) => {
  const config = await getConfig(ext)
  return {
    sources: (config.importedScriptSources ?? []) as Source[],
    enabled: (config.enabledScripts ?? []) as string[],
  }
}

type Command = (typeof LX_SOURCE_COMMANDS)[keyof typeof LX_SOURCE_COMMANDS]
const activeCommands = new Set<string>()

export const manageLinkedLxSource = async (command: Command, sourceId?: string) => {
  const ext = extensionState.extensions.find((e) => e.id === LX_SOURCE_LOADER && e.loaded && !e.removed)
  if (!ext?.grant.includes('internet')) throw new Error(i18n.t('lx_link_loader_unavailable'))
  const ui = createBoxs(createCommon(ext))
  if (activeCommands.size) {
    void ui.showMessage(i18n.t('lx_link_busy'))
    return
  }
  activeCommands.add(command)
  try {
    const before = await readSources(ext)
    const selected = before.sources.find((s) => s.id === sourceId)
    if (command !== LX_SOURCE_COMMANDS.add && !selected) throw new Error(i18n.t('lx_link_stale'))
    if (command === LX_SOURCE_COMMANDS.test && selected) {
      try {
        const code = await createStore(ext.dataDirectory).readFile('scripts/' + selected.id, 'utf-8')
        await verifyLxSource(ext, selected, code)
        void ui.showMessage(i18n.t('lx_source_test_ok', { name: selected.name }))
      } catch (error) {
        void ui.showMessage(i18n.t('lx_source_test_failed', { name: selected.name, message: (error as Error).message }), { type: 'error' })
      }
      return
    }
    if (command === LX_SOURCE_COMMANDS.up || command === LX_SOURCE_COMMANDS.down) {
      const index = before.enabled.indexOf(sourceId ?? '')
      const dest = index + (command === LX_SOURCE_COMMANDS.up ? -1 : 1)
      if (index < 0 || dest < 0 || dest >= before.enabled.length) return
      const enabled = [...before.enabled]
      ;[enabled[index], enabled[dest]] = [enabled[dest], enabled[index]]
      await updateExtensionSettings(ext.id, { enabledScripts: enabled })
      return
    }
    const input =
      command === LX_SOURCE_COMMANDS.update && selected?.sourceUrl
        ? selected.sourceUrl
        : await ui.showInputBox({
            title: selected ? i18n.t('lx_link_edit_title', { name: selected.name }) : i18n.t('lx_link_add_title'),
            placeholder: 'https://…/source.js',
            value: selected?.sourceUrl || '',
          })
    if (!input.trim()) return
    let url: URL
    try {
      url = new URL(input.trim())
    } catch (cause) {
      throw new Error(i18n.t('lx_link_invalid_url'), { cause })
    }
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) {
      throw new Error(i18n.t('lx_link_invalid_url'))
    }
    url.hash = ''
    const raw = await fetchScript(url.href)
    const next = parseSource(raw, url, true)
    const byUrl = before.sources.find((s) => s.sourceUrl === url.href)
    if (selected && byUrl && selected.id !== byUrl.id) throw new Error(i18n.t('lx_link_duplicate'))
    const sameContent = before.sources.find((s) => s.id === next.id)
    const previous = selected ?? byUrl ?? sameContent
    if (
      sameContent &&
      (sameContent.id !== previous?.id || (!selected && sameContent.sourceUrl && sameContent.sourceUrl !== url.href))
    ) {
      throw new Error(i18n.t('lx_link_duplicate'))
    }
    if (previous?.id === next.id && previous.sourceUrl === url.href) {
      void ui.showMessage(i18n.t('lx_link_current', { name: next.name }))
      return
    }
    const changed = previous?.id !== next.id
    const storage = createStore(ext.dataDirectory)
    if (changed) {
      if (previous && before.enabled.includes(previous.id)) await verifyLxSource(ext, next, raw)
      await storage.writeFile(`scripts/${next.id}`, raw)
    }
    // Re-read after network, validation and file I/O, so a removed source cannot reappear.
    const { sources, enabled } = await readSources(ext)
    if (
      !ext.loaded ||
      ext.removed ||
      extensionState.extensions.find((e) => e.id === ext.id) !== ext ||
      (previous && !sources.some((s) => s.id === previous.id && s.sourceUrl === previous.sourceUrl)) ||
      sources.some((s) => s.id !== previous?.id && (s.id === next.id || s.sourceUrl === url.href)) ||
      (previous && enabled.includes(previous.id) !== before.enabled.includes(previous.id))
    ) {
      throw new Error(i18n.t('lx_link_stale'))
    }
    const updated = { ...previous, ...next, allowShowUpdateAlert: previous?.allowShowUpdateAlert ?? true }
    const settings: Record<string, unknown> = {
      importedScriptSources: previous ? sources.map((s) => (s.id === previous.id ? updated : s)) : [...sources, updated],
    }
    const reload = !!previous && changed && enabled.includes(previous.id)
    if (reload) settings.enabledScripts = enabled.map((id) => (id === previous.id ? next.id : id))
    await updateExtensionSettings(ext.id, settings)
    const message = previous ? (changed ? 'lx_link_updated' : 'lx_link_bound') : 'lx_link_added'
    void ui.showMessage(i18n.t(message, { name: next.name }))
  } catch (err) {
    if ((err as Error).message === CANCELED_ERROR_MSG) return
    void ui.showMessage(i18n.t('lx_link_failed', { message: (err as Error).message }), { type: 'error' })
  } finally {
    activeCommands.delete(command)
  }
}

const getTideLoader = () => {
  const extension = extensionState.extensions.find((item) => item.id === LX_SOURCE_LOADER && item.loaded && !item.removed)
  if (!extension?.grant.includes('internet')) throw new Error('请先安装并启用 LX API Source Loader 扩展')
  return extension
}

export const exportTideSources = async (): Promise<AnyListen.IPCSync.TideSource[]> => {
  const extension = getTideLoader()
  const { sources, enabled } = await readSources(extension)
  const storage = createStore(extension.dataDirectory)
  const byId = new Map(sources.map((source) => [source.id, source]))
  const ordered = [...new Set([...enabled, ...sources.map((source) => source.id)])]
  const result: AnyListen.IPCSync.TideSource[] = []
  for (const id of ordered) {
    const source = byId.get(id)
    if (!source) continue
    const script = await storage.readFile('scripts/' + id, 'utf-8')
    result.push({ name: source.name, url: source.sourceUrl || '', enabled: enabled.includes(id), script })
  }
  return result
}

export const installTideSources = async (input: AnyListen.IPCSync.TideSource[]) => {
  const extension = getTideLoader()
  const before = await readSources(extension)
  const storage = createStore(extension.dataDirectory)
  const sources: Source[] = []
  const enabled: string[] = []
  for (const row of input) {
    const url = row.url ? new URL(row.url) : new URL('https://invalid.example/imported.js')
    if (url.protocol !== 'https:' && url.protocol !== 'http:') throw Error('音源更新链接无效')
    const parsed = parseSource(row.script, url, false)
    const source = { ...parsed, name: row.name, sourceUrl: row.url || undefined }
    if (!(await storage.fileExists('scripts/' + source.id))) await storage.writeFile('scripts/' + source.id, row.script)
    sources.push(source)
    if (row.enabled) enabled.push(source.id)
  }
  const current = await readSources(extension)
  if (getTideLoader() !== extension || JSON.stringify(before) !== JSON.stringify(current)) {
    throw Error('本机音源已变更，请重新确认')
  }
  await updateExtensionSettings(extension.id, { importedScriptSources: sources, enabledScripts: enabled })
}

const qualities = ['128k', '192k', '320k', 'wav', 'flac', 'flac24bit', 'dolby', 'master']

// The installed loader's isolate preload accepts LX Music's song fields.
const toLxSong = ({ name, singer, interval, meta }: AnyListen.Music.MusicInfoOnline) => ({
  name, singer, interval, source: meta.source, songmid: meta.musicId, albumName: meta.albumName,
  albumId: meta.albumId, hash: meta.hash, strMediaMid: meta.strMediaMid, albumMid: meta.albumMid,
  songId: meta.songId, copyrightId: meta.copyrightId, lrcUrl: meta.lrcUrl, mrcUrl: meta.mrcUrl, trcUrl: meta.trcUrl,
  typeUrl: {}, types: Object.fromEntries(Object.entries(meta.qualitys ?? {}).map(([quality, { sizeStr, ...rest }]) =>
    [quality, sizeStr == null ? rest : { ...rest, size: sizeStr }])),
})

export async function resolveLxMusicUrl({ quality = '128k', musicInfo }: AnyListen.IPCExtension.MusicUrlParams) {
  const extension = getTideLoader()
  const config = await getConfig(extension)
  const byId = new Map(((config.importedScriptSources ?? []) as Source[]).map((s) => [s.id, s]))
  const ids = new Set((config.enabledScripts ?? []) as string[])
  const sessions = new Map<string, Awaited<ReturnType<typeof openLxSource>>>()
  const storage = createStore(extension.dataDirectory)
  const song = toLxSong(musicInfo)
  const available = qualities.slice(0, qualities.indexOf(quality) + 1).reverse()
  let lastError = i18n.t('lx_source_no_match')
  try {
    for (const type of available.length ? available : [quality]) {
      for (const id of ids) {
        const info = byId.get(id)
        if (!info) continue
        try {
          let session = sessions.get(id)
          if (!session) {
            const script = await storage.readFile('scripts/' + id, 'utf-8')
            session = await openLxSource(extension, info, script, config.enabledSourceLogout === true, (message) => {
              if (info.allowShowUpdateAlert !== false) void createBoxs(createCommon(extension)).showMessage(info.name + ' · ' + message)
            })
            sessions.set(id, session)
          }
          if (!session.platforms[musicInfo.meta.source]?.includes(type)) continue
          const url = new URL(await session.getMusicUrl(song, type))
          if (!['http:', 'https:'].includes(url.protocol)) throw new Error(i18n.t('lx_link_invalid_url'))
          const res = await request<NeedBodyType>(url.href, {
            headers: { range: 'bytes=0-1' }, retryNum: 0, needBody: true, signal: AbortSignal.timeout(15000),
          })
          res.body.destroy()
          if (!res.statusCode || res.statusCode < 200 || res.statusCode >= 300) throw new Error('HTTP ' + res.statusCode)
          const playable = await extensionState.remoteFuncs.createProxyUrl(res.history.at(-1) || url.href, {}, config.enabledCache === true)
          return { url: playable, quality: type }
        } catch (error) {
          ids.delete(id)
          lastError = info.name + ' · ' + (error as Error).message
        }
      }
    }
    throw new Error(lastError)
  } finally {
    await Promise.all([...sessions.values()].map(async (session) => session.close()))
  }
}
