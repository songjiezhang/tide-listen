import { getSourceId } from '@any-listen/common/tools'
import { derived, fromStore } from 'svelte/store'

import { resourceList } from '@/modules/extension/reactive.svelte'
import { useSettingValue } from '@/modules/setting/reactive.svelte'
import { query } from '@/plugins/routes'

export const viewTypes = ['search', 'songlist', 'topSongs', 'album', 'singer'] as const
export type ViewType = (typeof viewTypes)[number]

export const viewResourceMap = {
  search: ['musicSearch', 'songlistSearch', 'albumSearch', 'singerSearch'] satisfies AnyListen.Extension.ResourceAction[],
  songlist: ['songlist'] satisfies AnyListen.Extension.ResourceAction[],
  topSongs: ['topSongs'] satisfies AnyListen.Extension.ResourceAction[],
  album: ['album'] satisfies AnyListen.Extension.ResourceAction[],
  singer: ['singer'] satisfies AnyListen.Extension.ResourceAction[],
} as const
export type ViewResourceMap = typeof viewResourceMap

export const tabIcons: Record<(typeof viewTypes)[number], string> = {
  search: 'search',
  songlist: 'music_library',
  topSongs: 'increase',
  album: 'albums',
  singer: 'dj',
}

export type ResourceListType = StoresValues<typeof resourceList>['resources']
export type ResourceType = AnyListen.Extension.ResourceItem
export { getSourceId }

export const urlParamKeyMap = {
  query: 'q',
  queryType: 'qt',
  type: 't',
  source: 's',
  page: 'p',
}

export const availableOnlineViews = derived(resourceList, ({ resources }) =>
  viewTypes.filter((view) => viewResourceMap[view].some((action) => resources[action]?.length))
)

export const resolveOnlineView = (type: string | undefined, available: readonly ViewType[]) =>
  available.find((view) => view === type) ?? available[0]

export const useOnlineResourceAvailable = () => {
  const enabled = useSettingValue('onlineResource.enable')
  const views = fromStore(availableOnlineViews)
  return {
    get val() {
      return enabled.val && views.current.length > 0
    },
  }
}

export interface SourceType extends ResourceType {
  sId: string
}

export const useActiveType = <T extends readonly string[]>(queryType: T) => {
  const q = fromStore(query)
  const activeType = $derived<T[number] | undefined>(
    q.current[urlParamKeyMap.queryType] ? queryType.find((t) => t == q.current[urlParamKeyMap.queryType]) : undefined
  )

  return {
    get val() {
      return activeType
    },
  }
}

export const useResourceList = <T extends keyof ViewResourceMap>(type: T) => {
  const resList = fromStore(resourceList)
  let resource = $derived.by(() => {
    const searchRes: Partial<Record<ViewResourceMap[T][number], ResourceListType[keyof ResourceListType]>> = {}
    for (const r of viewResourceMap[type]) {
      // @ts-expect-error
      searchRes[r] = []
    }
    const resourceListEntries = Object.entries(resList.current.resources) as EntriesObject<ResourceListType>
    for (const [key, source] of resourceListEntries) {
      if (key in searchRes) {
        searchRes[key as keyof typeof searchRes] = source
      }
    }
    return searchRes
  })

  return {
    get val() {
      return resource
    },
  }
}

export const useActiveSource = <T extends keyof ViewResourceMap>(type: T, action: ViewResourceMap[T][number]) => {
  const q = fromStore(query)
  const resource = useResourceList(type)
  let sList = $derived(resource.val[action]!.map((s) => ({ ...s, sId: getSourceId(s) })))
  let activeSource = $derived(
    q.current.sid ? sList.find((s) => s.sId == q.current.sid) : q.current.s ? sList.find((s) => s.id == q.current.s) : undefined
  )
  return {
    get val() {
      return activeSource
    },
  }
}
