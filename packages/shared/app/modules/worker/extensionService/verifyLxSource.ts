import { createMessage2Call } from 'message2call'

import { request, type Options } from '@any-listen/nodejs/request'

import { i18n } from './i18n'
import { createIsolateFuncs } from './vm/hostContext/apis/isolateContext'

// Use the installed loader's own LX preload. The probe cannot add a provider to the active playback list.
export async function verifyLxSource(extension: AnyListen.Extension.Extension, info: { name: string }, code: string) {
  const controller = new AbortController()
  const ready = Promise.withResolvers<boolean>()
  const ignore = () => {}
  const api = {
    log: ignore,
    debug: ignore,
    warn: ignore,
    error: ignore,
    showUpdateAlert: ignore,
    inited(sources: Record<string, string[]> | null, ok: boolean) {
      ready.resolve(ok && !!sources && Object.values(sources).some((v) => Array.isArray(v) && v.length > 0))
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
  const rpc = createMessage2Call<{ setupEnv: (info: { name: string }, code: string) => Promise<void> }>({
    exposeObj: api,
    timeout: 15000,
    sendMessage(data) {
      if (!controller.signal.aborted) {
        void isolate.sendMessageToIsolateContext(id, JSON.stringify(data)).catch(() => ready.resolve(false))
      }
    },
  })
  const timer = setTimeout(() => ready.resolve(false), 15000)
  try {
    await isolate.runFileInIsolateContext(id, '@root/resources/isolate-preload.js')
    await rpc.remote.setupEnv(info, code)
    await isolate.runInIsolateContext(id, code)
    if (!(await ready.promise)) throw new Error(i18n.t('lx_link_init_failed'))
  } finally {
    clearTimeout(timer)
    controller.abort()
    rpc.destroy()
    await isolate.destroyIsolateContext(id)
  }
}
