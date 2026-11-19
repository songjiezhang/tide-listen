import { getList as getKg, getListDetail as getKgDetail, getTags as kgTags, getSorts as kgSorts } from './src/onlineResource/kg/songlist'
import { getList as getKw, getListDetail as getKwDetail, getTags as kwTags, getSorts as kwSorts } from './src/onlineResource/kw/songlist'
import { getList as getWy, getListDetail as getWyDetail, getTags as wyTags, getSorts as wySorts } from './src/onlineResource/wy/songlist'
import { getList as getTx, getListDetail as getTxDetail, getTags as txTags, getSorts as txSorts } from './src/onlineResource/tx/songlist'
import { getList as getMg, getListDetail as getMgDetail, getTags as mgTags, getSorts as mgSorts } from './src/onlineResource/mg/songlist'

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
const lyrics = { kg: kgLyric, kw: kwLyric, wy: wyLyric, tx: txLyric, mg: mgLyric }

function lyricInput(platform, info) {
  const meta = { ...info.meta }
  if (platform === 'tx' && !/^\d+$/.test(String(meta.songId ?? ''))) delete meta.songId
  if (platform === 'kg' && !meta.hash) meta.hash = meta.qualitys?.['128k']?.hash || ''
  return { ...info, id: platform === 'kw' ? String(meta.musicId) : info.id, meta }
}

export async function catalog({action, platform, id, sort, tag, musicInfo, page = 1}) {
  const [list, detail, tags, sorts, defaultSort] = platforms[platform]
  switch (action) {
    case 'lyric':
      return lyrics[platform](lyricInput(platform, musicInfo))
    case 'list':
      return list(sort || defaultSort, tag || '', page, 24)
    case 'detail':
      return detail(id, page, 100)
    case 'options':
      return { sorts: await sorts(), ...await tags().catch(() => ({ tags: [], hotTags: [] })) }
    default:
      throw Error('未知歌单操作')
  }
}

