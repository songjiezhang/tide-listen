import { getSyncWebDAVState, runSyncWebDAV } from '@/modules/sync'

import { tideSync } from '@/modules/sync/tideSync'

import type { ExposeFunctions } from '.'

// 暴露给前端的方法
export const createExposeSync = () => {
  return {
    async tideSync(event, options) {
      return tideSync(options)
    },
    async getSyncState() {
      const webdavState = getSyncWebDAVState()

      return {
        webdav: {
          status: webdavState.status,
          error: webdavState.error?.message,
          nextSyncTime: webdavState.nextSyncTime,
        },
      }
    },
    async runSyncWebDAV(event, getListMergeMode, getDislikeMergeMode) {
      return runSyncWebDAV(getListMergeMode, getDislikeMergeMode)
    },
  } satisfies Partial<ExposeFunctions>
}
