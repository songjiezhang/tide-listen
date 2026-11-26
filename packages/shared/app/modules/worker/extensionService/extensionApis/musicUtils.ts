import { probeMusicUrl } from '../../../resources/probeMusicUrl'
import { extensionState } from '../state'
import { resourceRequestSignal } from './request'

export const createMusicUtils = (extension: AnyListen.Extension.Extension) => {
  return {
    async createProxyUrl(url: string, options?: AnyListen.IPCExtension.RequestOptions, cacheEnabled?: boolean) {
      const signal = resourceRequestSignal.getStore()
      if (signal) {
        await probeMusicUrl(url, signal, options)
        return extensionState.remoteFuncs.createProxyUrl(url, options, cacheEnabled, true)
      }
      return extensionState.remoteFuncs.createProxyUrl(url, options, cacheEnabled)
    },
    async writeProxyCache(url: string, data: Uint8Array) {
      return extensionState.remoteFuncs.writeProxyCache(url, data)
    },
  } as const
}
