declare namespace AnyListen {
  namespace IPCSync {
    type SyncStatus = 'idle' | 'waiting' | 'syncing' | 'error'

    interface SyncState {
      webdav: {
        status: SyncStatus
        error?: string
        nextSyncTime: number
      }
    }

    interface TideSource {
      name: string
      url: string
      script: string
      enabled: boolean
    }

    interface TideState {
      url?: string
      account?: string
      started: boolean
      sources: boolean
      message: string
      pendingSources?: Array<{ name: string; enabled: boolean }>
      pendingRevision?: number
      preview?: { id: string; lists: Array<{ name: string; count: number }> }
    }

    type TideOptions =
      | { action: 'pair'; url: string; code: string }
      | { action: 'start'; preview?: string }
      | { action: 'sources'; enabled: boolean }
      | { action: 'approve'; revision: number }
      | { action: 'status' | 'sync' | 'preview' | 'disconnect' }

    type ServerActions = WarpPromiseRecord<{
      tideSync: (options: TideOptions) => TideState
      getSyncState: () => SyncState
      runSyncWebDAV: (
        getListMergeMode?: () => Promise<List.MergeMode>,
        getDislikeMergeMode?: () => Promise<Dislike.MergeMode>
      ) => Promise<void>
    }>
    type ServerIPCActions<Socket = undefined> = IPC.WarpIPCHandlerActions<Socket, ServerActions>

    type ClientActions = WarpPromiseRecord<{
      webdavSyncStatus: (state: SyncState['webdav']) => void
    }>
    type ClientIPCActions<Socket = undefined> = IPC.WarpIPCHandlerActions<Socket, ClientActions>
  }
}
