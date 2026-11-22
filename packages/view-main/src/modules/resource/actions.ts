import { getLocation, push, replace, type Params } from '@/plugins/routes'
import { urlParamKeyMap } from '@/views/Online/shared.svelte'

const historys: Array<[string, Params]> = []
export const setLastHistory = (path: string, query: Params) => {
  const last = historys[historys.length - 1] as [string, Params] | undefined
  if (last && path === last[0] && (path !== '/online' || query[urlParamKeyMap.type] === last[1][urlParamKeyMap.type])) {
    last[1] = query
    return
  }
  historys.push([path, query])
}
export const pushRoute = (path: string, query: Params) => {
  historys.push([path, query])
  void push(path, query)
}
export const replaceRoute = (path: string, query: Params) => {
  const last = historys[historys.length - 1] as [string, Params] | undefined
  if (last) {
    last[0] = path
    last[1] = query
  } else {
    historys.push([path, query])
  }
  void replace(path, query)
}

export const back = () => {
  const loc = getLocation()
  if (loc.location.startsWith('/online')) historys.pop()
  const previous = historys[historys.length - 1]
  if (previous) {
    void replace(previous[0], previous[1])
  } else if (loc.location === '/online/songlist' || loc.location === '/online/topSongs') {
    void replace('/online', {
      [urlParamKeyMap.type]: loc.location.slice('/online/'.length),
      [urlParamKeyMap.source]: loc.query.sid,
    })
  } else {
    void replace('/online')
  }
}

export const toOnlineSearch = async (text?: string) => {
  const loc = getLocation()
  const lastLoc = historys[historys.length - 1] as [string, Params] | undefined
  if (text != null) {
    const searchLocation = loc.location === '/online' && loc.query[urlParamKeyMap.type] === 'search'
    const previous = searchLocation ? loc.query : historys.slice().reverse().find(([path, params]) =>
      path === '/online' && params[urlParamKeyMap.type] === 'search'
    )?.[1]
    const params = {
      [urlParamKeyMap.type]: 'search',
      [urlParamKeyMap.query]: text || undefined,
      [urlParamKeyMap.source]: previous?.[urlParamKeyMap.source],
      [urlParamKeyMap.queryType]: previous?.[urlParamKeyMap.queryType],
    }
    if (searchLocation) await replace('/online', params)
    else await push('/online', params)
  } else if (loc.location.startsWith('/online')) {
    await replace('/online', loc.query)
  } else {
    const [path, query] = lastLoc || ['/online', {}]
    await push(path, query)
  }
}
