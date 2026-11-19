import { i18n } from '@/i18n'

import { appEvent, appState } from '../app'
import { checkUpdate, downloadUpdate, initUpdate, restartUpdate } from './electronUpdate'
import { sleep } from './utils'

interface EventTypes {
  checking_for_update: never
  update_available: AnyListen.UpdateInfo
  update_not_available: AnyListen.UpdateInfo
  download_progress: AnyListen.DownloadProgressInfo
  update_downloaded: never
  error: Error
  ignore_version: string | null
}

type Listener = (event: unknown) => void
class UpdateEvent {
  private readonly listeners: Map<keyof EventTypes, Listener[]>
  constructor() {
    this.listeners = new Map()
  }
  off<T extends keyof EventTypes>(eventName: T, listener: (event: EventTypes[T]) => void) {
    let targetListeners = this.listeners.get(eventName)
    if (!targetListeners) return
    const index = targetListeners.indexOf(listener as Listener)
    if (index < 0) return
    targetListeners.splice(index, 1)
  }
  on<T extends keyof EventTypes>(eventName: T, listener: (event: EventTypes[T]) => void) {
    let targetListeners = this.listeners.get(eventName)
    if (!targetListeners) this.listeners.set(eventName, (targetListeners = []))
    targetListeners.push(listener as Listener)
    return () => {
      this.off(eventName, listener)
    }
  }
  emit<T extends keyof EventTypes>(eventName: T, ...[event]: EventTypes[T] extends never ? [] : [event: EventTypes[T]]) {
    setImmediate(() => {
      let targetListeners = this.listeners.get(eventName)
      if (!targetListeners) return
      for (const listener of Array.from(targetListeners)) {
        listener(event)
      }
    })
  }
}

export class Update extends UpdateEvent {
  initUpdate() {
    initUpdate(this)
    appEvent.on('updated_config', (keys, settings) => {
      if (keys.includes('common.allowPreRelease')) {
        void this.checkForUpdates(appState.appSetting['common.tryAutoUpdate'])
      }
    })
  }
  async checkForUpdates(isAutoUpdate: boolean, reportFailure = false) {
    this.emit('checking_for_update')
    try {
      const result = await checkUpdate(appState.appSetting['common.allowPreRelease'])
      if (!result) throw new Error('Update checking requires an installed build')
      const { version, releaseNotes, releaseDate } = result.updateInfo
      const info = {
        version,
        desc: typeof releaseNotes === 'string' ? releaseNotes : (releaseNotes ?? []).map((entry) => entry.note).join('\n'),
        time: releaseDate,
        history: [],
      }
      if (result.isUpdateAvailable) {
        this.emit('update_available', info)
        if (isAutoUpdate) await this.downloadUpdate()
      } else {
        this.emit('update_not_available', info)
      }
      return result.isUpdateAvailable
    } catch (err) {
      this.emit('error', err as Error)
      if (reportFailure) throw err
      return false
    }
  }
  async downloadUpdate() {
    if (import.meta.env.VITE_IS_MAC) {
      this.emit('error', new Error(i18n.t('update.not_supported_on_mac')))
      return
    }
    if (import.meta.env.VITE_IS_WINDOWS_LEGACY) {
      this.emit('error', new Error(i18n.t('update.not_supported_on_windows_legacy')))
      return
    }
    await downloadUpdate()
  }
  async isUpdateAvailable() {
    return this.checkForUpdates(false)
  }
  // eslint-disable-next-line @typescript-eslint/class-methods-use-this
  async quitAndInstall() {
    await restartUpdate()
  }
}
export const update = new Update()

export const startCheckUpdateTimeout = async (): Promise<void> => {
  await update.checkForUpdates(appState.appSetting['common.tryAutoUpdate']).catch(() => {})
  await sleep(86400_000)
  return startCheckUpdateTimeout()
}
