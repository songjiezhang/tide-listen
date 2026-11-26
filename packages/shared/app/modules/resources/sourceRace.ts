export const HEDGE_DELAY_MS = 1000
export const MAX_CONCURRENT_SOURCES = 3
export const SOURCE_REQUEST_TIMEOUT_MS = 8000
const EWMA_ALPHA = 0.4
const LATENCY_SWAP_THRESHOLD_MS = 300
const FAILURE_COOLDOWNS_MS = [0, 0, 60_000, 180_000, 300_000]

interface Health {
  ewmaLatencyMs: number | null
  consecutiveFailures: number
  cooldownUntil: number
}

export class SourceHealthTracker {
  private readonly sources = new Map<string, Health>()
  private readonly now: () => number

  constructor(now = Date.now) {
    this.now = now
  }

  private entry(key: string): Health {
    let health = this.sources.get(key)
    if (!health) {
      health = { ewmaLatencyMs: null, consecutiveFailures: 0, cooldownUntil: 0 }
      this.sources.set(key, health)
    }
    return health
  }

  recordSuccess(key: string, latencyMs: number) {
    const health = this.entry(key)
    health.ewmaLatencyMs =
      health.ewmaLatencyMs === null ? latencyMs : health.ewmaLatencyMs * (1 - EWMA_ALPHA) + latencyMs * EWMA_ALPHA
    health.consecutiveFailures = 0
    health.cooldownUntil = 0
  }

  recordFailure(key: string) {
    const health = this.entry(key)
    health.consecutiveFailures++
    health.cooldownUntil =
      this.now() + FAILURE_COOLDOWNS_MS[Math.min(health.consecutiveFailures, FAILURE_COOLDOWNS_MS.length - 1)]
  }

  track(key: string) {
    const health = this.entry(key)
    return {
      success: (latencyMs: number) => {
        if (this.sources.get(key) === health) this.recordSuccess(key, latencyMs)
      },
      failure: () => {
        if (this.sources.get(key) === health) this.recordFailure(key)
      },
    }
  }

  rankCandidates<T>(candidates: readonly T[], getKey: (candidate: T) => string): T[] {
    const now = this.now()
    const healthy: T[] = []
    const degraded: T[] = []
    const cooling: Array<{ candidate: T; until: number }> = []
    const sampled: Array<{ candidate: T; latency: number }> = []
    const samplePositions: number[] = []
    for (const candidate of candidates) {
      const health = this.sources.get(getKey(candidate))
      if (health && now < health.cooldownUntil) {
        cooling.push({ candidate, until: health.cooldownUntil })
      } else if (health?.consecutiveFailures) {
        degraded.push(candidate)
      } else {
        if (health?.ewmaLatencyMs != null) {
          const latency = health.ewmaLatencyMs
          let index = sampled.length
          while (index > 0 && sampled[index - 1].latency - latency >= LATENCY_SWAP_THRESHOLD_MS) index--
          sampled.splice(index, 0, { candidate, latency })
          samplePositions.push(healthy.length)
        }
        healthy.push(candidate)
      }
    }
    samplePositions.forEach((position, index) => {
      healthy[position] = sampled[index].candidate
    })
    cooling.sort((a, b) => a.until - b.until)
    return [...healthy, ...degraded, ...cooling.map(({ candidate }) => candidate)]
  }

  clear(prefixOrKey?: string) {
    if (prefixOrKey === undefined) this.sources.clear()
    else {
      for (const key of this.sources.keys()) {
        if (key === prefixOrKey || key.startsWith(`${prefixOrKey}::`)) this.sources.delete(key)
      }
    }
  }
}

export class UnsupportedSourceError extends Error {}

export class SourceAttemptTimeoutError extends Error {
  constructor() {
    super('Music source request timed out')
  }
}

export async function waitForSource<T>(task: Promise<T>, signal: AbortSignal): Promise<T> {
  const canceled = Promise.withResolvers<never>()
  const onAbort = () => {
    canceled.reject(signal.reason)
  }
  if (signal.aborted) onAbort()
  else signal.addEventListener('abort', onAbort, { once: true })
  try {
    return await Promise.race([canceled.promise, task])
  } finally {
    signal.removeEventListener('abort', onAbort)
  }
}

export async function raceSourcesWithHedge<T, R>({
  candidates,
  getKey,
  execute,
  healthTracker,
  signal,
  hedgeDelayMs = HEDGE_DELAY_MS,
  maxConcurrent = MAX_CONCURRENT_SOURCES,
  perAttemptTimeoutMs = SOURCE_REQUEST_TIMEOUT_MS,
}: {
  candidates: readonly T[]
  getKey: (candidate: T) => string
  execute: (candidate: T, signal: AbortSignal) => Promise<R>
  healthTracker?: SourceHealthTracker
  signal?: AbortSignal
  hedgeDelayMs?: number
  maxConcurrent?: number
  perAttemptTimeoutMs?: number | ((candidate: T) => number | null)
}): Promise<R> {
  return new Promise((resolve, reject) => {
    const raceController = new AbortController()
    let next = 0
    let inFlight = 0
    let lastLaunch = 0
    let hedgeTimer: ReturnType<typeof setTimeout> | undefined
    let lastError: Error | undefined
    const finish = () => {
      clearTimeout(hedgeTimer)
      signal?.removeEventListener('abort', cancel)
      raceController.abort()
    }
    const cancel = () => {
      finish()
      reject(signal?.reason instanceof Error ? signal.reason : new Error('Music source race canceled', { cause: signal?.reason }))
    }
    const schedule = () => {
      clearTimeout(hedgeTimer)
      if (raceController.signal.aborted) return
      if (next === candidates.length) {
        if (!inFlight) {
          finish()
          reject(lastError ?? new UnsupportedSourceError('No matching music source'))
        }
        return
      }
      if (!inFlight) launch()
      else if (inFlight < Math.max(1, maxConcurrent)) {
        hedgeTimer = setTimeout(launch, Math.max(0, hedgeDelayMs - (performance.now() - lastLaunch)))
      }
    }
    const launch = () => {
      const candidate = candidates[next++]
      const health = healthTracker?.track(getKey(candidate))
      const controller = new AbortController()
      const startedAt = performance.now()
      lastLaunch = startedAt
      inFlight++
      const cleanup = () => {
        clearTimeout(timer)
        raceController.signal.removeEventListener('abort', cancelAttempt)
        inFlight--
      }
      const cancelAttempt = () => {
        cleanup()
        controller.abort(raceController.signal.reason)
      }
      const fail = (error: unknown) => {
        if (controller.signal.aborted) return
        cleanup()
        controller.abort(error)
        if (!(error instanceof UnsupportedSourceError)) {
          health?.failure()
          lastError = error instanceof Error ? error : new Error(String(error))
        }
        schedule()
      }
      const timeout = typeof perAttemptTimeoutMs === 'function' ? perAttemptTimeoutMs(candidate) : perAttemptTimeoutMs
      const timer = timeout === null ? undefined : setTimeout(() => fail(new SourceAttemptTimeoutError()), timeout)
      raceController.signal.addEventListener('abort', cancelAttempt, { once: true })
      void Promise.resolve()
        .then(async () => {
          controller.signal.throwIfAborted()
          return execute(candidate, controller.signal)
        })
        .then((result) => {
          if (controller.signal.aborted) return
          cleanup()
          health?.success(performance.now() - startedAt)
          finish()
          resolve(result)
        })
        .catch(fail)
      schedule()
    }
    if (signal?.aborted) cancel()
    else {
      signal?.addEventListener('abort', cancel, { once: true })
      schedule()
    }
  })
}
