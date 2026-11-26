import { request, type Options } from '@any-listen/nodejs/request'
import { createMessage2Call } from 'message2call'

import { SOURCE_REQUEST_TIMEOUT_MS, SourceAttemptTimeoutError, waitForSource } from '../../resources/sourceRace'
import { lxScriptErrors } from './errorHandler'
import { i18n } from './i18n'
import { LxRequestScopes } from './lxRequestScopes'
import { createIsolateFuncs } from './vm/hostContext/apis/isolateContext'
import { contextState } from './vm/hostContext/state'

type Platforms = Partial<Record<string, string[]>>
interface LxRemote {
  setupEnv: (info: { name: string }, code: string) => Promise<void>
  setEnabledSourceLogout: (enabled: boolean) => Promise<void>
  getMusicUrl: (song: Record<string, unknown>, quality: string) => Promise<string>
}

export async function openLxSource(
  extension: AnyListen.Extension.Extension,
  info: { name: string },
  code: string,
  {
    log = false,
    showUpdateAlert,
    openSignal,
    onInvalidated,
  }: {
    log?: boolean
    showUpdateAlert?: (message: string) => void
    openSignal?: AbortSignal
    onInvalidated?: (error: Error) => void
  } = {}
) {
  if (!extension.grant.includes('internet') || !extension.grant.includes('isolate_context')) {
    throw new Error(i18n.t('lx_link_loader_unavailable'))
  }
  openSignal?.throwIfAborted()
  const sessionController = new AbortController()
  const requests = new LxRequestScopes()
  const ready = Promise.withResolvers<Platforms>()
  const initController = new AbortController()
  const scriptError = (reason: unknown) =>
    new Error((typeof reason === 'string' ? reason : (reason as Error | null)?.message) || i18n.t('lx_link_init_failed'))
  let initialized = false
  const onScriptError = (reason: unknown) => {
    const error = scriptError(reason)
    if (initialized) void close(error)
    else initController.abort(error)
  }
  const ignore = () => {}
  const api = {
    log: log ? console.log : ignore,
    debug: log ? console.debug : ignore,
    warn: log ? console.warn : ignore,
    error: log ? console.error : ignore,
    showUpdateAlert: showUpdateAlert ?? ignore,
    inited(sources: Platforms | null, ok?: boolean, message?: string) {
      if (ok === false || !sources || !Object.values(sources).some((v) => Array.isArray(v) && v.length)) {
        onScriptError(message)
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
      const res = await requests.http(async (signal) =>
        request(url, {
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
          timeout: Math.max(1, Math.min(timeout ?? SOURCE_REQUEST_TIMEOUT_MS, 15_000)),
          maxRedirect,
          retryNum: 0,
          signal,
        })
      )
      return { statusCode: res.statusCode, headers: res.headers, body: res.body, raw: res.raw }
    },
  }
  // Give each outbound RPC its own message2call pending table. Destroying one call
  // clears its timers and waiters without disturbing other songs in this isolate.
  const replies = new Map<string, (packet: unknown[]) => void>()
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
      if (packet[0] === 0) rpc.message(packet)
      else if (packet[0] === 1) replies.get(packet[1])?.(packet)
    },
  })
  const id = await isolate.createIsolateContext()
  const send = (data: unknown) => {
    if (sessionController.signal.aborted) return
    void isolate.sendMessageToIsolateContext(id, JSON.stringify(data)).catch(onScriptError)
  }
  const rpc = createMessage2Call({ exposeObj: api, sendMessage: send })
  const call = async <T>(signal: AbortSignal, invoke: (remote: LxRemote) => Promise<T>) => {
    let rpcId: string | undefined
    const outgoing = createMessage2Call<LxRemote>({
      exposeObj: {},
      timeout: 0,
      sendMessage(data) {
        if (!Array.isArray(data) || typeof data[1] !== 'string') throw new Error('Invalid LX request')
        rpcId = data[1]
        replies.set(data[1], outgoing.message)
        send(data)
      },
    })
    try {
      signal.throwIfAborted()
      return await waitForSource(invoke(outgoing.remote), signal)
    } finally {
      if (rpcId) replies.delete(rpcId)
      outgoing.destroy()
    }
  }
  const hooks = contextState.vmContexts.get(extension.id)?.unsubscribeEvents
  const onUnload = () => {
    void close(new Error(i18n.t('lx_link_loader_unavailable')), true)
  }
  hooks?.push(onUnload)
  const close = async (reason?: Error, unloading = false) => {
    if (sessionController.signal.aborted) return
    const index = hooks?.indexOf(onUnload) ?? -1
    if (!unloading && index >= 0) hooks?.splice(index, 1)
    sessionController.abort(reason)
    if (reason) onInvalidated?.(reason)
    rpc.destroy()
    await isolate.destroyIsolateContext(id)
  }
  const timer = setTimeout(() => onScriptError(i18n.t('lx_source_timeout')), 15000)
  const initSignal = AbortSignal.any([sessionController.signal, initController.signal, ...(openSignal ? [openSignal] : [])])
  try {
    const platforms = await requests.run(initSignal, async () =>
      waitForSource(
        lxScriptErrors.run(onScriptError, async () => {
          await isolate.runFileInIsolateContext(id, '@root/resources/isolate-preload.js')
          initSignal.throwIfAborted()
          if (log) await call(initSignal, async (remote) => remote.setEnabledSourceLogout(true))
          await call(initSignal, async (remote) => remote.setupEnv(info, code))
          initSignal.throwIfAborted()
          await isolate.runInIsolateContext(id, code)
          return ready.promise
        }),
        initSignal
      )
    )
    initSignal.throwIfAborted()
    initialized = true
    return {
      platforms,
      close,
      get closed() {
        return sessionController.signal.aborted
      },
      async getMusicUrl(
        song: Record<string, unknown>,
        quality: string,
        options: { signal?: AbortSignal; timeoutMs?: number } = {}
      ) {
        const requestController = new AbortController()
        const { signal } = options
        const requestSignal = AbortSignal.any([sessionController.signal, requestController.signal, ...(signal ? [signal] : [])])
        // The pool's attempt covers opening, resolution and the media probe.
        const deadline =
          signal && options.timeoutMs == null
            ? undefined
            : setTimeout(
                () => requestController.abort(new SourceAttemptTimeoutError()),
                options.timeoutMs ?? SOURCE_REQUEST_TIMEOUT_MS
              )
        try {
          return await requests.run(requestSignal, async () =>
            lxScriptErrors.run(
              (reason) => {
                if (!requestSignal.aborted) onScriptError(reason)
              },
              async () => call(requestSignal, async (remote) => remote.getMusicUrl(song, quality))
            )
          )
        } finally {
          clearTimeout(deadline)
          requestController.abort()
        }
      },
    }
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
