import { AsyncLocalStorage } from 'node:async_hooks'

/** LX's wire protocol does not attach a song ID to script-initiated HTTP. */
export class LxRequestScopes {
  private readonly current = new AsyncLocalStorage<AbortSignal>()
  private readonly active = new Set<AbortSignal>()
  private readonly pendingHttp = new Map<AbortController, Set<AbortSignal>>()

  async run<T>(signal: AbortSignal, task: () => Promise<T>): Promise<T> {
    signal.throwIfAborted()
    this.active.add(signal)
    const release = () => {
      this.active.delete(signal)
      for (const [http, owners] of this.pendingHttp) {
        owners.delete(signal)
        if (!owners.size) {
          this.pendingHttp.delete(http)
          http.abort(signal.reason)
        }
      }
    }
    signal.addEventListener('abort', release, { once: true })
    try {
      return await this.current.run(signal, task)
    } finally {
      release()
      signal.removeEventListener('abort', release)
    }
  }

  async http<T>(request: (signal: AbortSignal) => Promise<T>): Promise<T> {
    const owner = this.current.getStore()
    // A continuation belonging to a canceled song must never borrow the next song's signal.
    owner?.throwIfAborted()
    if (!this.active.size) throw new Error('No active LX request')
    // A shared script callback (e.g. a timer installed during setup) has no song ID.
    // Only calls active at dispatch may still need this HTTP response.
    const controller = new AbortController()
    this.pendingHttp.set(controller, new Set(owner && this.active.has(owner) ? [owner] : this.active))
    try {
      return await request(controller.signal)
    } finally {
      this.pendingHttp.delete(controller)
    }
  }
}
