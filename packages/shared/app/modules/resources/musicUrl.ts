import { LX_SOURCE_LOADER } from '@any-listen/common/command'

import { extensionEvent } from '../extension'
import { probeMusicUrl } from './probeMusicUrl'
import { resourceState, services, withResourceRequest } from './shared'
import { raceSourcesWithHedge, SOURCE_REQUEST_TIMEOUT_MS, SourceHealthTracker, UnsupportedSourceError } from './sourceRace'
import { findMusic } from './tools'
import { allowedUrl } from './utils'

const health = new SourceHealthTracker()
extensionEvent.on('extensionEvent', (event) => {
  if (event.action === 'crash') health.clear()
  else if (event.action === 'stoping' || event.action === 'listRemove') health.clear(event.data)
  else if (event.action === 'extenstionSettingUpdated') health.clear(event.data.id)
})

export const getMusicUrlByExtensionSource = async ({
  extensionId,
  source,
  musicInfo,
  quality,
  type,
  signal,
}: {
  extensionId: string
  source: string
  musicInfo: AnyListen.Music.MusicInfoOnline
  quality?: string
  type?: AnyListen.Music.FileType
  signal?: AbortSignal
}): Promise<AnyListen.IPCExtension.MusicUrlInfo> => {
  const result = await withResourceRequest(signal, async (id) =>
    services.extensionSerive.requestMusicUrl({ extensionId, source, musicInfo, quality, type }, id)
  )
  if ('skipped' in result) throw new UnsupportedSourceError(result.skipped)
  if (!result.url || !allowedUrl(result.url)) throw new Error('Get music url failed, url not allowed')
  if (signal && /^https?:\/\//i.test(result.url)) await probeMusicUrl(result.url, signal)
  return result
}

const handleGetMusicUrl = async ({
  musicInfo,
  quality,
  type,
  signal,
}: {
  musicInfo: AnyListen.Music.MusicInfoOnline
  quality?: string
  type?: AnyListen.Music.FileType
  signal?: AbortSignal
}): Promise<AnyListen.IPCExtension.MusicUrlInfo> => {
  const platform = musicInfo.meta.source
  const candidates = (resourceState.resources.musicUrl ?? []).filter((source) => !platform || source.id === platform)
  if (!candidates.length) throw new Error('Get url failed, no source')
  const execute = async (source: (typeof candidates)[number], signal?: AbortSignal) =>
    getMusicUrlByExtensionSource({
      extensionId: source.extensionId,
      source: source.id,
      musicInfo,
      quality,
      type,
      signal,
    })
  const getKey = (source: (typeof candidates)[number]) => `${source.extensionId}::${source.id}`
  return raceSourcesWithHedge({
    candidates: health.rankCandidates(candidates, getKey),
    getKey,
    execute,
    healthTracker: health,
    signal,
    // Desktop LX bounds each script and quality separately. Keep its extension in
    // the race across quality fallbacks, until another extension wins or playback stops.
    perAttemptTimeoutMs: (candidate) =>
      import.meta.env.VITE_IS_DESKTOP && candidate.extensionId === LX_SOURCE_LOADER ? null : SOURCE_REQUEST_TIMEOUT_MS,
  })
}

export const getMusicUrl = async (data: {
  musicInfo: AnyListen.Music.MusicInfo
  quality?: string
  type?: AnyListen.Music.FileType
  signal?: AbortSignal
}): Promise<AnyListen.IPCExtension.MusicUrlInfo> => {
  return findMusic(
    data.musicInfo,
    async (musicInfo) => {
      return handleGetMusicUrl({
        musicInfo,
        quality: data.quality,
        type: data.type,
        signal: data.signal,
      })
    },
    data.signal
  )
}
