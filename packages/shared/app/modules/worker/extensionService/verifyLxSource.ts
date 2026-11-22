import { createMessage2Call } from 'message2call'

import { request, type Options } from '@any-listen/nodejs/request'

import { lxScriptErrors } from './errorHandler'
import { contextState } from './vm/hostContext/state'
import { i18n } from './i18n'
import { createIsolateFuncs } from './vm/hostContext/apis/isolateContext'

type Platforms = Record<string, string[]>
interface LxRemote {
  setupEnv: (info: { name: string }, code: string) => Promise<void>
  setEnabledSourceLogout: (enabled: boolean) => Promise<void>
  getMusicUrl: (song: Record<string, unknown>, quality: string) => Promise<string>
}

export async function openLxSource(extension: AnyListen.Extension.Extension, info: { name: string }, code: string,
  log = false, showUpdateAlert?: (message: string) => void) {
  if (!extension.grant.includes('internet') || !extension.grant.includes('isolate_context')) throw new Error(i18n.t('lx_link_loader_unavailable'))
  const controller = new AbortController()
  const ready = Promise.withResolvers<Platforms>()
  const failure = Promise.withResolvers<Error>()
  const fail = (reason: unknown) => {
    const message = typeof reason === 'string' ? reason : (reason as Error | null)?.message
    failure.resolve(new Error(message || i18n.t('lx_link_init_failed')))
  }
  const guarded = <T>(task: () => Promise<T>) => Promise.race([
    lxScriptErrors.run(fail, task), failure.promise.then((error) => { throw error }),
  ])
  const ignore = () => {}
  const api = {
    log: log ? console.log : ignore,
    debug: log ? console.debug : ignore,
    warn: log ? console.warn : ignore,
    error: log ? console.error : ignore,
    showUpdateAlert: showUpdateAlert ?? ignore,
    inited(sources: Platforms | null, ok?: boolean, message?: string) {
      if (ok === false || !sources || !Object.values(sources).some((v) => Array.isArray(v) && v.length)) {
        fail(message)
      } else ready.resolve(sources)
    },
    async request({
      url,
      method,
      headers,
      query,
      json,
      form,
      formdata,
      binary,
      text,
      xml,
      needRaw,
      timeout,
      maxRedirect,
    }: Options & { url: string }) {
      const res = await request(url, {
        method,
        headers,
        query,
        json,
        form,
        formdata,
        binary,
        text,
        xml,
        needRaw,
        timeout,
        maxRedirect,
        signal: controller.signal,
      })
      return { statusCode: res.statusCode, headers: res.headers, body: res.body, raw: res.raw }
    },
  }
  const isolate = createIsolateFuncs(extension, {
    onMessage(serialized) {
      const packet: unknown = JSON.parse(serialized)
      if (!Array.isArray(packet) || typeof packet[1] !== 'string') return
      // message2call v2 requests are [0, id, path, args, callbacks]. Only flat LX methods are exposed.
      if (
        packet[0] === 0 &&
        !(
          Array.isArray(packet[2]) &&
          packet[2].length === 1 &&
          typeof packet[2][0] === 'string' &&
          Object.hasOwn(api, packet[2][0]) &&
          Array.isArray(packet[3]) &&
          Array.isArray(packet[4]) &&
          !packet[4].length
        )
      ) {
        return
      }
      if (packet[0] === 0 || packet[0] === 1) rpc.message(packet)
    },
  })
  const id = await isolate.createIsolateContext()
  const rpc = createMessage2Call<LxRemote>({
    exposeObj: api,
    timeout: 25000,
    sendMessage(data) {
      if (!controller.signal.aborted) {
        void isolate.sendMessageToIsolateContext(id, JSON.stringify(data)).catch(fail)
      }
    },
  })
  const hooks = contextState.vmContexts.get(extension.id)?.unsubscribeEvents
  const onUnload = () => { fail(i18n.t('lx_link_loader_unavailable')); controller.abort() }
  hooks?.push(onUnload)
  const close = async () => {
    const index = hooks?.indexOf(onUnload) ?? -1
    if (index >= 0) hooks!.splice(index, 1)
    controller.abort()
    rpc.destroy()
    await isolate.destroyIsolateContext(id)
  }
  const timer = setTimeout(() => fail(i18n.t('lx_source_timeout')), 15000)
  try {
    const platforms = await guarded(async () => {
      await isolate.runFileInIsolateContext(id, '@root/resources/isolate-preload.js')
      if (log) await rpc.remote.setEnabledSourceLogout(true)
      await rpc.remote.setupEnv(info, code)
      await isolate.runInIsolateContext(id, code)
      return ready.promise
    })
    return { platforms, close, getMusicUrl: (song: Record<string, unknown>, quality: string) =>
      guarded(() => rpc.remote.getMusicUrl(song, quality)) }
  } catch (error) {
    await close()
    throw error
  } finally {
    clearTimeout(timer)
  }
}

export async function verifyLxSource(extension: AnyListen.Extension.Extension, info: { name: string }, code: string) {
  const session = await openLxSource(extension, info, code)
  await session.close()
}
