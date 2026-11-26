import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { performance } from 'node:perf_hooks'
import { test } from 'node:test'
import { setTimeout } from 'node:timers'
import { setTimeout as delay } from 'node:timers/promises'
import { runInNewContext } from 'node:vm'

import {
  HEDGE_DELAY_MS,
  MAX_CONCURRENT_SOURCES,
  SourceAttemptTimeoutError,
  SourceHealthTracker,
  UnsupportedSourceError,
  raceSourcesWithHedge,
} from '../modules/resources/sourceRace.ts'
import { LxRequestScopes } from '../modules/worker/extensionService/lxRequestScopes.ts'
import { LxSessionPool } from '../modules/worker/extensionService/lxSessionPool.ts'

const getKey = (candidate) => `${candidate}::wy`

test('a fast first candidate wins without a hedge', async () => {
  const started = []
  const result = await raceSourcesWithHedge({
    candidates: ['first', 'second'],
    getKey,
    async execute(candidate) {
      started.push(candidate)
      return candidate
    },
  })
  assert.equal(result, 'first')
  assert.deepEqual(started, ['first'])
})

test('a slow first candidate is hedged after one second and canceled without a failure', { timeout: 5000 }, async () => {
  const health = new SourceHealthTracker()
  let canceled = false
  let startedAt
  let hedgeAge
  const result = await raceSourcesWithHedge({
    candidates: ['slow', 'fast'],
    getKey,
    healthTracker: health,
    async execute(candidate, signal) {
      if (candidate === 'slow') {
        startedAt = performance.now()
        signal.addEventListener(
          'abort',
          () => {
            canceled = true
          },
          { once: true }
        )
        await delay(3000, undefined, { signal })
      } else hedgeAge = performance.now() - startedAt
      return candidate
    },
  })
  assert.equal(result, 'fast')
  assert.ok(hedgeAge >= HEDGE_DELAY_MS - 25)
  assert.equal(canceled, true)
  assert.deepEqual(health.rankCandidates(['slow', 'fast'], getKey), ['slow', 'fast'])
})

test('an empty flight advances immediately and all failures report the last error', { timeout: 2000 }, async () => {
  const started = []
  await assert.rejects(
    raceSourcesWithHedge({
      candidates: ['first', 'last'],
      getKey,
      hedgeDelayMs: 60_000,
      async execute(candidate) {
        started.push(candidate)
        throw new Error(candidate)
      },
    }),
    /last/
  )
  assert.deepEqual(started, ['first', 'last'])
})

test('per-attempt timeout aborts work and records a failure', async () => {
  const health = new SourceHealthTracker()
  let reason
  await assert.rejects(
    raceSourcesWithHedge({
      candidates: ['slow'],
      getKey,
      healthTracker: health,
      perAttemptTimeoutMs: 25,
      async execute(_candidate, signal) {
        signal.addEventListener(
          'abort',
          () => {
            reason = signal.reason
          },
          { once: true }
        )
        await delay(500, undefined, { signal })
      },
    }),
    SourceAttemptTimeoutError
  )
  assert.ok(reason instanceof SourceAttemptTimeoutError)
  assert.deepEqual(health.rankCandidates(['slow', 'new'], getKey), ['new', 'slow'])
})

test('the race caps concurrency and cancels every active candidate on external abort', { timeout: 2000 }, async () => {
  const controller = new globalThis.AbortController()
  const started = []
  const canceled = []
  await assert.rejects(
    raceSourcesWithHedge({
      candidates: ['1', '2', '3', '4'],
      getKey,
      hedgeDelayMs: 10,
      signal: controller.signal,
      async execute(candidate, signal) {
        started.push(candidate)
        signal.addEventListener('abort', () => canceled.push(candidate), { once: true })
        if (candidate === '3') setTimeout(() => controller.abort(), 50)
        await delay(1000, undefined, { signal })
      },
    }),
    { name: 'AbortError' }
  )
  assert.equal(started.length, MAX_CONCURRENT_SOURCES)
  assert.deepEqual(canceled, started)
})

test('health ranks sampled sources, preserves new slots and falls back to cooling sources', () => {
  let now = 10_000
  const health = new SourceHealthTracker(() => now)
  const rank = (...sources) => health.rankCandidates(sources, getKey)
  const success = (key, latency) => health.recordSuccess(getKey(key), latency)
  const failure = (key) => health.recordFailure(getKey(key))
  success('slow', 900)
  success('fast', 400)
  success('similar', 200)
  assert.deepEqual(rank('slow', 'new', 'fast', 'similar'), ['fast', 'new', 'similar', 'slow'])
  failure('fast')
  assert.deepEqual(rank('fast', 'new', 'slow'), ['new', 'slow', 'fast'])
  failure('slow')
  failure('slow')
  failure('fast')
  failure('fast')
  assert.deepEqual(rank('fast', 'slow'), ['slow', 'fast'])
  now += 60_000
  assert.deepEqual(rank('fast', 'slow', 'new'), ['new', 'slow', 'fast'])
  success('fast', 500)
  assert.deepEqual(rank('fast', 'slow', 'new'), ['fast', 'new', 'slow'])
  failure('fast')
  assert.deepEqual(rank('fast', 'slow', 'new'), ['new', 'fast', 'slow'])
})

test('skips and stale observations do not penalize a source', async () => {
  const health = new SourceHealthTracker()
  const old = health.track(getKey('old'))
  health.clear('old')
  old.failure()
  await assert.rejects(
    raceSourcesWithHedge({
      candidates: ['old'],
      getKey,
      healthTracker: health,
      async execute() {
        throw new UnsupportedSourceError()
      },
    }),
    UnsupportedSourceError
  )
  assert.deepEqual(health.rankCandidates(['old', 'new'], getKey), ['old', 'new'])
})

test('an extension can finish its bounded quality fallback after its peer times out', async () => {
  const result = await raceSourcesWithHedge({
    candidates: ['loader', 'other'],
    getKey,
    hedgeDelayMs: 0,
    perAttemptTimeoutMs: (id) => (id === 'loader' ? null : 5),
    async execute(id, signal) {
      await delay(id === 'loader' ? 30 : 1000, undefined, { signal })
      return id
    },
  })
  assert.equal(result, 'loader')
})

test('pool shares opening, disposes only the final idle lease, and rebuilds after two timeouts', async () => {
  const broken = []
  const pool = new LxSessionPool((id) => broken.push(id), 20)
  let opens = 0
  let closes = 0
  const open = async () => {
    opens++
    return {
      async close() {
        closes++
      },
    }
  }
  const first = pool.acquire('one', open)
  const second = pool.acquire('one', open)
  assert.equal(await first.opening, await second.opening)
  first.release('timeout')
  await delay(40)
  assert.equal(closes, 0)
  second.release('timeout')
  assert.deepEqual(broken, ['one'])
  assert.equal(closes, 1)
  const replacement = pool.acquire('one', open)
  await replacement.opening
  replacement.release()
  await delay(40)
  assert.equal(closes, 2)
  assert.equal(opens, 2)
})

test('a rejected or evicted opening cannot evict its replacement', async () => {
  const pool = new LxSessionPool(() => {})
  const old = Promise.withResolvers()
  const first = pool.acquire('one', async () => old.promise)
  await Promise.resolve()
  pool.evict('one')
  const replacement = pool.acquire('one', async () => ({ async close() {} }))
  const expected = await replacement.opening
  old.reject(new Error('old script'))
  await assert.rejects(first.opening, /old script/)
  first.release()
  assert.equal(pool.peek('one'), expected)
  replacement.release()
  pool.evict('one')
})

test('concurrent VM promise and timer chains abort only their own HTTP socket', { timeout: 5000 }, async () => {
  const opened = Promise.withResolvers()
  const closed = Promise.withResolvers()
  const server = createServer((req, res) => {
    if (req.url === '/slow') {
      res.on('close', closed.resolve)
      opened.resolve()
    } else res.end('ok')
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  const base = `http://127.0.0.1:${server.address().port}`
  const scopes = new LxRequestScopes()
  const request = async (path) => scopes.http(async (signal) => (await globalThis.fetch(base + path, { signal })).text())
  const script = runInNewContext(
    '(async (path) => { await request("/first"); await new Promise(r => setTimeout(r, 5)); return request(path) })',
    { request, setTimeout }
  )
  const slow = new globalThis.AbortController()
  const fast = new globalThis.AbortController()
  const gate = Promise.withResolvers()
  try {
    const pending = scopes.run(slow.signal, async () => script('/slow'))
    const winner = scopes.run(fast.signal, async () => {
      await gate.promise
      return script('/fast')
    })
    await opened.promise
    slow.abort()
    await assert.rejects(pending)
    await closed.promise
    gate.resolve()
    assert.equal(await winner, 'ok')
  } finally {
    slow.abort()
    fast.abort()
    gate.resolve()
    server.closeAllConnections()
    await new Promise((resolve) => server.close(resolve))
  }
})

test('detached script HTTP is released with its original owners even when a new song starts', async () => {
  const scopes = new LxRequestScopes()
  const end = Promise.withResolvers()
  const first = new globalThis.AbortController()
  const second = new globalThis.AbortController()
  const one = scopes.run(first.signal, async () => end.promise)
  const detached = scopes.http(async (signal) => delay(1000, undefined, { signal }))
  const two = scopes.run(second.signal, async () => end.promise)
  first.abort()
  await assert.rejects(detached, { name: 'AbortError' })
  assert.equal(second.signal.aborted, false)
  end.resolve()
  await Promise.all([one, two])
  await assert.rejects(
    scopes.http(async () => {}),
    /No active LX request/
  )
})
