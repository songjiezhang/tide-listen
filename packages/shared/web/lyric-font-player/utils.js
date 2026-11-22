export const getNow =
  typeof performance === 'object' ? performance.now.bind(performance) : Date.now

export class TimeoutTools {
  constructor(thresholdTime = 80) {
    /** @type {import('./types').TimerFunctions['setTimeout']} */
    this.setTimeout = window.setTimeout.bind(window)
    /** @type {import('./types').TimerFunctions['clearTimeout']} */
    this.clearTimeout = window.clearTimeout.bind(window)
    /** @type {import('./types').TimerFunctions['nextTick']} */
    this.nextTick = window.requestAnimationFrame.bind(window)
    /** @type {import('./types').TimerFunctions['cancelNextTick']} */
    this.cancelNextTick = window.cancelAnimationFrame.bind(window)
    this.invokeTime = 0
    this.animationFrameId = null
    this.timeoutId = null
    /** @type {((drift: number) => void) | null} */
    this.callback = null
    this.thresholdTime = thresholdTime
  }

  run() {
    this.animationFrameId = this.nextTick(() => {
      this.animationFrameId = null
      let diff = this.invokeTime - getNow()
      // console.log('diff', diff)
      if (diff > 0) {
        if (diff < this.thresholdTime) return this.run()
        // console.log('run timeout', diff, diff - this.thresholdTime)
        this.timeoutId = this.setTimeout(() => {
          this.timeoutId = null
          this.run()
        }, diff - this.thresholdTime)
        return this.timeoutId
      }

      // console.log('diff', diff)
      this.callback?.(diff)
    })
  }

  /** @param {(drift: number) => void} callback */
  start(callback = () => {}, timeout = 0) {
    // console.log(timeout)
    this.callback = callback
    this.invokeTime = getNow() + timeout

    this.run()
  }

  clear() {
    if (this.animationFrameId) {
      this.cancelNextTick(this.animationFrameId)
      this.animationFrameId = null
    }
    if (this.timeoutId) {
      this.clearTimeout(this.timeoutId)
      this.timeoutId = null
    }
  }
}
