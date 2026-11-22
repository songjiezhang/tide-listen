import { LX_SOURCE_LOADER } from '@any-listen/common/command'
import { cloneData } from '@any-listen/common/utils'

import { updateExtensionSettings } from '../shared'
import { getConfig } from '../shared/configStore'

type WithUndefined<T extends readonly unknown[]> = {
  [K in keyof T]: T[K] | undefined
}
export const createConfigurationStore = (extension: AnyListen.Extension.Extension) => {
  return {
    async getConfigs<T extends unknown[] = []>(keys: string[]) {
      const store = await getConfig(extension)
      // The desktop host resolves LX sources on demand. The loader still manages their files and settings.
      const lazy = import.meta.env.VITE_IS_DESKTOP && extension.id === LX_SOURCE_LOADER
      return cloneData(keys.map((k) => lazy && k === 'enabledScripts' ? [] : store[k])) as WithUndefined<T>
    },
    async setConfigs(datas: Record<string, unknown>) {
      await updateExtensionSettings(extension.id, cloneData(datas))
    },
  }
}
