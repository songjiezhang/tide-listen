import { appState } from '@/app'
import { getLyricInfo, getMusicPic, getMusicUrl } from '@/modules/music'
import { workers } from '@/worker'

import type { ExposeFunctions } from '.'

interface PendingUrl {
  controller: AbortController
  result: Promise<AnyListen.IPCMusic.MusicUrlInfo>
}

const urls = new Map<string, PendingUrl>()
let playback: PendingUrl | undefined

export const cancelPlaybackUrl = () => {
  playback?.controller.abort()
  playback = undefined
}

// A foreground request can take over the existing next-song preload.
export const createExposeMusic = () => {
  return {
    async getMusicUrl(event, info) {
      const key = JSON.stringify([info.musicInfo.id, info.quality, !!info.isRefresh])
      let pending = urls.get(key)
      if (info.playback && playback !== pending) cancelPlaybackUrl()
      if (!pending || pending.controller.signal.aborted) {
        const controller = new AbortController()
        const signal = AbortSignal.any([controller.signal, AbortSignal.timeout(100_000)])
        const result = getMusicUrl({ ...info, signal }).finally(() => {
          if (urls.get(key)?.controller === controller) urls.delete(key)
          if (playback?.controller === controller) playback = undefined
        })
        pending = { controller, result }
        urls.set(key, pending)
      }
      if (info.playback) playback = pending
      return pending.result
    },
    async getMusicUrlCount(event) {
      return workers.dbService.musicUrlCount()
    },
    async clearMusicUrl(event) {
      return workers.dbService.musicUrlClear()
    },

    async getMusicPic(event, info) {
      return getMusicPic(info)
    },

    async getMusicLyric(event, info) {
      return getLyricInfo(info)
    },
    async setMusicLyric(event, id, info) {
      return workers.dbService.editedLyricSave(id, info)
    },
    async removeMusicLyric(event, id) {
      return workers.dbService.editedLyricRemove([id])
    },
    async getMusicLyricCount(event) {
      return workers.dbService.rawLyricCount()
    },
    async clearMusicLyric(event) {
      return workers.dbService.rawLyricClear()
    },

    async createLocalMusicInfos(event, paths) {
      return workers.utilService.createLocalMusicInfos(paths, appState.machineId, true)
    },
  } satisfies Partial<ExposeFunctions>
}
