import { AsyncLocalStorage } from 'node:async_hooks'

import { extensionEvent } from './event'

// Associate an unhandled promise in an LX isolate with its request or manual test.
export const lxScriptErrors = new AsyncLocalStorage<(reason: unknown) => void>()

export const registerErrorHandler = () => {
  let prevError: Error
  process.on('uncaughtException', (err) => {
    if (err === prevError) return
    prevError = err
    console.error('An uncaught error occurred!')
    console.error(err)
    extensionEvent.error(err.stack ?? err.message)
    // log.error(err)
  })
  process.on('unhandledRejection', (reason, p) => {
    const failSource = lxScriptErrors.getStore()
    if (failSource) return failSource(reason)
    if (import.meta.env.DEV) console.error('Unhandled Rejection at: Promise ', p)
    throw reason instanceof Error ? reason : new Error(String(reason))
  })
}
