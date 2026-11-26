import { findMusic as findMusicByExt } from './search/music'
import { buildExtSourceId, getExtSource, getSourceAllExtSourceIds } from './utils'

const findSourceMusic = async <T>(
  info: {
    name: string
    singer: string
    albumName: string
    interval: string | null
  },
  handler: (info: AnyListen.Music.MusicInfoOnline) => Promise<T>,
  excludeList: string[] = [],
  signal?: AbortSignal
): Promise<T> => {
  signal?.throwIfAborted()
  const source = getExtSource('musicSearch', excludeList)
  // console.log('excludeList', excludeList, source)
  if (!source) throw new Error('Get url failed, no source')
  const music = await findMusicByExt({ extensionId: source.extensionId, source: source.id, ...info, signal })
  if (music) {
    try {
      return await handler(music)
    } catch (e) {
      signal?.throwIfAborted()
      console.error(e)
    }
  }
  excludeList.push(buildExtSourceId(source.extensionId, source.id))
  return findSourceMusic(info, handler, excludeList, signal)
}

const handleFindMusic = async <T>(
  musicInfo: {
    name: string
    singer: string
    albumName: string
    interval: string | null
    rawName?: string
    source?: string
  },
  handler: (info: AnyListen.Music.MusicInfoOnline) => Promise<T>,
  signal?: AbortSignal
) => {
  const excludeList: string[] = musicInfo.source ? getSourceAllExtSourceIds('musicSearch', musicInfo.source) : []
  try {
    return await findSourceMusic<T>(
      {
        name: musicInfo.name,
        singer: musicInfo.singer,
        albumName: musicInfo.albumName,
        interval: musicInfo.interval,
      },
      handler,
      [...excludeList],
      signal
    )
  } catch {
    signal?.throwIfAborted()
  }
  if (musicInfo.name.includes('-')) {
    const [name, singer] = musicInfo.name.split('-').map((val) => val.trim())
    try {
      return await findSourceMusic<T>(
        {
          name,
          singer,
          albumName: musicInfo.albumName,
          interval: musicInfo.interval,
        },
        handler,
        [...excludeList],
        signal
      )
    } catch {
      signal?.throwIfAborted()
    }
    try {
      return await findSourceMusic<T>(
        {
          name: singer,
          singer: name,
          albumName: musicInfo.albumName,
          interval: musicInfo.interval,
        },
        handler,
        [...excludeList],
        signal
      )
    } catch {
      signal?.throwIfAborted()
    }
  }
  let fileName = musicInfo.rawName
  if (fileName) {
    fileName = fileName.substring(0, fileName.lastIndexOf('.'))
    if (fileName != musicInfo.name) {
      if (fileName.includes('-')) {
        const [name, singer] = fileName.split('-').map((val) => val.trim())
        try {
          return await findSourceMusic<T>(
            {
              name,
              singer,
              albumName: musicInfo.albumName,
              interval: musicInfo.interval,
            },
            handler,
            [...excludeList],
            signal
          )
        } catch {
          signal?.throwIfAborted()
        }
        try {
          return await findSourceMusic<T>(
            {
              name: singer,
              singer: name,
              albumName: musicInfo.albumName,
              interval: musicInfo.interval,
            },
            handler,
            [...excludeList],
            signal
          )
        } catch {
          signal?.throwIfAborted()
        }
      } else {
        try {
          return await findSourceMusic<T>(
            {
              name: fileName,
              singer: '',
              albumName: musicInfo.albumName,
              interval: musicInfo.interval,
            },
            handler,
            [...excludeList],
            signal
          )
        } catch {
          signal?.throwIfAborted()
        }
      }
    }
  }

  throw new Error('source not found')
}

const findMusicByLocal = async <T>(
  musicInfo: AnyListen.Music.MusicInfoLocal,
  handler: (info: AnyListen.Music.MusicInfoOnline) => Promise<T>,
  signal?: AbortSignal
) => {
  return handleFindMusic(
    {
      name: musicInfo.name,
      singer: musicInfo.singer,
      albumName: musicInfo.meta.albumName,
      interval: musicInfo.interval,
      rawName: musicInfo.meta.filePath.split(/\/|\\/).at(-1),
    },
    handler,
    signal
  )
}

const findMusicByOnline = async <T>(
  musicInfo: AnyListen.Music.MusicInfoOnline,
  handler: (info: AnyListen.Music.MusicInfoOnline) => Promise<T>,
  signal?: AbortSignal
) => {
  return handleFindMusic(
    {
      name: musicInfo.name,
      singer: musicInfo.singer,
      albumName: musicInfo.meta.albumName,
      interval: musicInfo.interval,
      rawName: musicInfo.meta.fileName,
      source: musicInfo.meta.source,
    },
    handler,
    signal
  )
}

export const findMusic = async <T>(
  musicInfo: AnyListen.Music.MusicInfo,
  handler: (info: AnyListen.Music.MusicInfoOnline) => Promise<T>,
  signal?: AbortSignal
) => {
  if (musicInfo.isLocal) {
    return findMusicByLocal(musicInfo, handler, signal)
  }
  try {
    return await handler(musicInfo)
  } catch {
    signal?.throwIfAborted()
  }
  return findMusicByOnline(musicInfo, handler, signal)
}
