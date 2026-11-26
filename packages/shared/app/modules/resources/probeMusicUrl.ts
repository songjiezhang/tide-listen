import { request, type NeedBodyType, type Options } from '@any-listen/nodejs/request'

import { SOURCE_REQUEST_TIMEOUT_MS } from './sourceRace'

export async function probeMusicUrl(url: string, signal: AbortSignal, options: Options = {}) {
  const headers = Object.fromEntries(Object.entries(options.headers ?? {}).filter(([key]) => key.toLowerCase() !== 'range'))
  const res = await request<NeedBodyType>(url, {
    ...options,
    headers: { ...headers, range: 'bytes=0-1' },
    retryNum: 0,
    needBody: true,
    timeout: Math.min(options.timeout || SOURCE_REQUEST_TIMEOUT_MS, SOURCE_REQUEST_TIMEOUT_MS),
    signal,
  })
  try {
    if (!res.statusCode || res.statusCode < 200 || res.statusCode >= 300) throw new Error(`HTTP ${res.statusCode}`)
    if (/^(text\/html|application\/json)\b/i.test(String(res.headers['content-type'] ?? '').trim())) {
      throw new Error('Music source returned an error page')
    }
    signal.throwIfAborted()
    return res.history.at(-1) || url
  } finally {
    // undici emits an abort error when closing an unread body.
    res.body.on('error', () => {}).destroy()
  }
}
