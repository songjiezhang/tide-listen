import { getList as getKg, getListDetail as getKgDetail, getTags as kgTags, getSorts as kgSorts } from './src/onlineResource/kg/songlist'
import { getList as getKw, getListDetail as getKwDetail, getTags as kwTags, getSorts as kwSorts } from './src/onlineResource/kw/songlist'
import { getList as getWy, getListDetail as getWyDetail, getTags as wyTags, getSorts as wySorts } from './src/onlineResource/wy/songlist'
import { getList as getTx, getListDetail as getTxDetail, getTags as txTags, getSorts as txSorts } from './src/onlineResource/tx/songlist'
import { getList as getMg, getListDetail as getMgDetail, getTags as mgTags, getSorts as mgSorts } from './src/onlineResource/mg/songlist'

import { getBoards as kgBoards, getList as kgChart } from './src/onlineResource/kg/topSongs'
import { getBoards as kwBoards, getList as kwChart } from './src/onlineResource/kw/topSongs'
import { getBoards as wyBoards, getList as wyChart } from './src/onlineResource/wy/topSongs'
import { getBoards as txBoards, getList as txChart } from './src/onlineResource/tx/topSongs'
import { getBoards as mgBoards, getList as mgChart } from './src/onlineResource/mg/topSongs'

import { getLyric as kgLyric } from './src/onlineResource/kg/lyric'
import { getLyric as kwLyric } from './src/onlineResource/kw/lyric'
import { getLyric as wyLyric } from './src/onlineResource/wy/lyric'
import { getLyric as txLyric } from './src/onlineResource/tx/lyric'
import { getLyric as mgLyric } from './src/onlineResource/mg/lyric'

const platforms = {
  kg: [getKg, getKgDetail, kgTags, kgSorts, '6'],
  kw: [getKw, getKwDetail, kwTags, kwSorts, 'hot'],
  wy: [getWy, getWyDetail, wyTags, wySorts, 'hot'],
  tx: [getTx, getTxDetail, txTags, txSorts, '-1'],
  mg: [getMg, getMgDetail, mgTags, mgSorts, '15127315'],
}
const charts = {
  kg: [kgBoards, kgChart],
  kw: [kwBoards, kwChart],
  wy: [wyBoards, wyChart],
  tx: [txBoards, txChart],
  mg: [mgBoards, mgChart],
}
const lyrics = { kg: kgLyric, kw: kwLyric, wy: wyLyric, tx: txLyric, mg: mgLyric }

function lyricInput(platform, info) {
  const meta = { ...info.meta }
  if (platform === 'tx' && !/^\d+$/.test(String(meta.songId ?? ''))) delete meta.songId
  if (platform === 'kg' && !meta.hash) meta.hash = meta.qualitys?.['128k']?.hash || ''
  return { ...info, id: platform === 'kw' ? String(meta.musicId) : info.id, meta }
}

export async function catalog({action, platform, id, sort, tag, date, musicInfo, page = 1}) {
  const [list, detail, tags, sorts, defaultSort] = platforms[platform]
  switch (action) {
    case 'lyric':
      return lyrics[platform](lyricInput(platform, musicInfo))
    case 'list':
      return list(sort || defaultSort, tag || '', page, 24)
    case 'detail':
      return detail(id, page, 100)
    case 'charts': {
      const boards = await charts[platform][0]()
      const list = boards.map(({ id, name, pic }) => ({ id, name, img: pic }))
      return { list, total: list.length, limit: list.length }
    }
    case 'chart-detail': {
      const result = await charts[platform][1](id, date || '', page)
      return { ...result, info: { ...result.info, img: result.info.pic } }
    }
    case 'options':
      return { sorts: await sorts(), ...await tags().catch(() => ({ tags: [], hotTags: [] })) }
    default:
      throw Error('未知在线资源操作')
  }
}

