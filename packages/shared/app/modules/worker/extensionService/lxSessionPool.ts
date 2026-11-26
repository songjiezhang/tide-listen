interface Session {
  close: () => Promise<void>
}

interface Entry<T> {
  controller: AbortController
  opening: Promise<T>
  session?: T
  users: number
  timeouts: number
  idle?: NodeJS.Timeout
}

export class LxSessionPool<T extends Session> {
  private readonly entries = new Map<string, Entry<T>>()
  private readonly idleMs: number
  private readonly onBroken: (id: string) => void

  constructor(onBroken: (id: string) => void, idleMs = 600_000) {
    this.onBroken = onBroken
    this.idleMs = idleMs
  }

  peek(id: string) {
    return this.entries.get(id)?.session
  }

  evict(id: string, reason?: Error) {
    const entry = this.entries.get(id)
    if (!entry) return
    this.entries.delete(id)
    clearTimeout(entry.idle)
    entry.controller.abort(reason)
    if (entry.session) void entry.session.close()
  }

  acquire(id: string, open: (signal: AbortSignal, broken: (error: Error) => void) => Promise<T>) {
    let entry = this.entries.get(id)
    if (!entry) {
      const controller = new AbortController()
      const broken = (error: Error) => {
        if (this.entries.get(id)?.controller !== controller) return
        this.onBroken(id)
        this.evict(id, error)
      }
      const opening = Promise.resolve().then(async () => {
        controller.signal.throwIfAborted()
        const session = await open(controller.signal, broken)
        if (controller.signal.aborted) {
          await session.close()
          controller.signal.throwIfAborted()
        }
        created.session = session
        return session
      })
      const created: Entry<T> = { controller, opening, users: 0, timeouts: 0 }
      this.entries.set(id, created)
      void opening.catch(() => {
        clearTimeout(created.idle)
        if (this.entries.get(id) === created) this.entries.delete(id)
      })
      entry = created
    }
    const leased = entry
    clearTimeout(leased.idle)
    leased.users++
    let released = false
    return {
      opening: leased.opening,
      signal: leased.controller.signal,
      release: (outcome?: 'completed' | 'timeout') => {
        if (released) return
        released = true
        leased.users--
        if (this.entries.get(id) !== leased) return
        if (outcome === 'completed') leased.timeouts = 0
        if (outcome === 'timeout' && ++leased.timeouts >= 2) {
          this.onBroken(id)
          this.evict(id, new Error('LX source timed out twice'))
        } else if (!leased.users) {
          if (leased.session) {
            leased.idle = setTimeout(() => this.evict(id), this.idleMs)
            leased.idle.unref()
          } else this.evict(id)
        }
      },
    }
  }
}
