import { isDeepStrictEqual } from 'node:util'

type Library = AnyListen.List.ListDataFull
type Playlist = Library['defaultList'] | Library['loveList'] | Library['userList'][number]

export const sameValue = isDeepStrictEqual

const mapById = <Row extends { id: string | number }>(rows: Row[]) => new Map(rows.map((row) => [String(row.id), row]))

function mergeRows<Row extends { id: string | number }>(
  base: Row[],
  local: Row[],
  remote: Row[],
  merge: (before: Row | undefined, here: Row, there: Row) => Row
): Row[] {
  const before = mapById(base)
  const here = mapById(local)
  const there = mapById(remote)
  const resolve = (id: string) => {
    const old = before.get(id)
    const l = here.get(id)
    const r = there.get(id)
    if (!l) return old ? undefined : r
    if (!r) return old ? undefined : l
    return merge(old, l, r)
  }

  const common = (row: Row) => before.has(String(row.id)) && here.has(String(row.id)) && there.has(String(row.id))
  const order = (rows: Row[]) => rows.filter(common).map((row) => String(row.id))
  const localOrderChanged = !sameValue(order(base), order(local))
  const [primary, secondary] = localOrderChanged || !base.length ? [local, remote] : [remote, local]
  const keys = [...new Set(primary.map((row) => String(row.id)))].filter((id) => resolve(id))
  const included = new Set(keys)
  // Insert additions next to the preceding retained row in their source order.
  let anchor: string | undefined
  for (const row of secondary) {
    const id = String(row.id)
    if (included.has(id)) {
      anchor = id
    } else if (resolve(id)) {
      keys.splice(anchor == null ? 0 : keys.indexOf(anchor) + 1, 0, id)
      included.add(id)
      anchor = id
    }
  }
  return keys.map((id) => resolve(id)!)
}

function mergeList<T extends Playlist>(base: T | undefined, local: T, remote: T): T {
  const list = mergeRows(base?.list ?? [], local.list, remote.list, (before, here, there) =>
    before && !sameValue(before, here) ? here : there
  )
  const changed = <K extends keyof Playlist>(key: K) => base && !sameValue(base[key], local[key])
  const meta = { ...remote.meta }
  if (base) {
    const old: Record<string, unknown> = { ...base.meta }
    const destination: Record<string, unknown> = meta
    for (const [key, value] of Object.entries(local.meta)) {
      if (!sameValue(value, old[key])) destination[key] = value
    }
  }
  meta.songCount = list.length
  return {
    ...remote,
    type: changed('type') ? local.type : remote.type,
    name: changed('name') ? local.name : remote.name,
    parentId: changed('parentId') ? local.parentId : remote.parentId,
    meta,
    list,
  }
}

export function mergeLibrary(base: Library | undefined, local: Library, remote: Library | null): Library {
  if (!remote || sameValue(local, remote)) return local
  if (base && sameValue(local, base)) return remote
  return {
    defaultList: mergeList(base?.defaultList, local.defaultList, remote.defaultList),
    loveList: mergeList(base?.loveList, local.loveList, remote.loveList),
    userList: mergeRows(base?.userList ?? [], local.userList, remote.userList, mergeList),
  }
}
