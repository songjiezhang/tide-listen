'use strict'

const el = (id) => document.getElementById(id)
const view = el('view')
const app = (name, value = '') => App.action(name, typeof value === 'string' ? value : JSON.stringify(value))
const icons = (name) => '<svg aria-hidden="true"><use href="#glyph-' + name + '"></use></svg>'
const esc = (value) => String(value == null ? '' : value).replace(/[&<>"']/g, (c) => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
})[c])
const platforms = { kg: '酷狗音乐', kw: '酷我音乐', wy: '网易音乐', tx: '企鹅音乐', mg: '咪咕音乐' }
const modes = { order: '列表播放', loop: '列表循环', one: '单曲循环', shuffle: '随机播放' }
const sorts = { hot: '最热', new: '最新', recommend: '推荐', hot_collect: '热藏', soaring: '飙升' }
const catalogLabels = { list: '在线歌单', charts: '排行榜' }
const tabs = Array.from(document.querySelectorAll('[data-tab]'))
const catalog = new Map()
const details = new Map()
const filters = new Map()
const requests = new Map()
const recentCovers = new Map()
const coverObserver = typeof IntersectionObserver === 'function' ? new IntersectionObserver((entries, observer) => {
  for (const entry of entries) {
    if (!entry.isIntersecting) continue
    const img = entry.target
    observer.unobserve(img)
    if (document.documentElement.contains(img)) img.src = img.dataset.cover
  }
}, { root: view, rootMargin: '160px 0px' }) : null
let data = { playlists: [], sources: [] }
let viewMarkup = '', filterMarkup = '', catalogMarkup = ''
let favoriteKeys = new Set()
let playing = { id: '', name: '', artist: '', cover: '', position: 0, duration: 0 }
let tab = 'home', platform = 'kg', sort = '', tag = '', listId = '', online = null, token = 0
let catalogMode = 'list', sorting = false
let query = '', searchSource = 'kg', searchDraft = '', lastPage = ''
let phoneBrowseScroll = 0
let artworkId = '', resolvedArtwork = '', pictureRequested = false
const searches = new Map()
function catalogTab() { return tab === 'home' || tab === 'list' || tab === 'charts' }
function searchKey() { return JSON.stringify([searchSource, query]) }
function rememberPage() {
  if (!nativeReady || !data.car) return
  const page = JSON.stringify({ tab, listId, query, searchSource,
    online: online && { kind: online.kind, platform: online.platform, id: online.id, name: online.name, cover: online.cover },
    now: !el('nowScreen').hidden })
  if (page !== lastPage) { lastPage = page; app('carPage', page) }
}
function restorePage() {
  let saved
  try { saved = JSON.parse(data.carPage || '{}') || {} } catch (_) { saved = {} }
  tab = ['list', 'charts', 'search', 'lists', 'settings'].includes(saved.tab) ? saved.tab : 'list'
  catalogMode = tab === 'charts' ? 'charts' : 'list'
  listId = tab === 'lists' && typeof saved.listId === 'string' ? saved.listId : ''
  query = searchDraft = typeof saved.query === 'string' ? saved.query.slice(0,128) : ''
  if (Object.prototype.hasOwnProperty.call(platforms, saved.searchSource)) searchSource = saved.searchSource
  const ref = saved.online
  if (catalogTab() && ref && ref.kind === catalogMode && ref.id &&
      Object.prototype.hasOwnProperty.call(platforms, ref.platform)) {
    online = ref
    platform = ref.platform
  }
  if (saved.now) showNow(true)
}
function requestPicture() {
  if (!playing.id || pictureRequested) return
  pictureRequested = true
  app('picture', playing.id)
}
window.pictureReply = ({ id, url }) => {
  if (playing.id !== id || !url) return
  resolvedArtwork = url
  drawTransport()
}
const grip = '<button type="button" class="grip" data-grip aria-label="拖动排序">☰</button>'
const controls = playlistControls(view,
  (moved, anchor, after) => app('moveItem', { listId, moved, anchor, after }),
  (properties) => app('listProperties', properties))
function canRefresh(item) {
  const ref = item && item.online
  return !!ref && ref.extensionId === 'online-metadata' && Object.prototype.hasOwnProperty.call(platforms, ref.source) &&
    ['songlist', 'topSongs'].includes(ref.sourceType) && !!ref.syncId
}
function refreshList(item) {
  const ref = item.online
  app('saveCatalog', { target: item.id, action: ref.sourceType === 'topSongs' ? 'chart-detail' : 'detail',
    platform: ref.source, id: ref.syncId, date: ref.date, name: item.name })
}
function sortButton() { return button('sort', sorting ? '完成排序' : '排序') }
let visibleTracks = [], seekHeld = false, nativeReady = false
let positionSample = 0, fullClock = 0
const lyrics = createLyricsView((request) => app('lyrics', request), () => app('cancelLyrics'))
window.lyricsReply = (reply) => lyrics.accept(reply)

function coverContents(url, glyph, fallback, eager) {
  const src = url || fallback
  return icons(glyph) + (src ? '<img decoding="async" data-cover="' + esc(src) + '" alt=""' +
    (eager || !coverObserver ? ' src="' + esc(src) + '"' : '') +
    (!eager && !coverObserver ? ' loading="lazy"' : '') +
    (url && fallback && url !== fallback ? ' data-fallback="' + esc(fallback) + '"' : '') + '>' : '')
}
function cover(url, glyph = 'disc', cls = '', fallback, eager) {
  return '<span class="cover ' + cls + '">' + coverContents(url, glyph, fallback, eager) + '</span>'
}
document.addEventListener('error', ({ target }) => {
  if (!(target instanceof HTMLImageElement) || !target.closest('.cover')) return
  if (target.dataset.fallback) {
    target.src = target.dataset.fallback
    delete target.dataset.fallback
  } else {
    const current = target.parentNode
    target.remove()
    if (current && (current.id === 'miniCover' || current.id === 'fullCover') &&
        current.dataset.art === (resolvedArtwork || playing.cover || '')) requestPicture()
  }
}, true)
function setArtwork(node, url) {
  if (node.dataset.art !== url) {
    node.dataset.art = url
    node.innerHTML = coverContents(url, 'disc', '', true)
  }
}
function replaceCoverMarkup(node, markup) {
  for (const image of node.querySelectorAll('img[data-cover]')) {
    if (coverObserver) coverObserver.unobserve(image)
    if (!image.naturalWidth) continue
    const key = image.dataset.cover
    recentCovers.delete(key)
    recentCovers.set(key, image)
  }
  while (recentCovers.size > 16) recentCovers.delete(recentCovers.keys().next().value)
  const template = document.createElement('template')
  template.innerHTML = markup
  for (const image of template.content.querySelectorAll('img[data-cover]')) {
    const key = image.dataset.cover
    const retained = recentCovers.get(key)
    if (!retained || (retained.isConnected && !node.contains(retained))) continue
    recentCovers.delete(key)
    if (retained.getAttribute('src') === key) {
      if (image.dataset.fallback) retained.dataset.fallback = image.dataset.fallback
      else delete retained.dataset.fallback
    }
    image.parentNode.replaceChild(retained, image)
  }
  node.textContent = ''
  node.appendChild(template.content)
  if (coverObserver) {
    for (const image of node.querySelectorAll('img[data-cover]:not([src])')) coverObserver.observe(image)
  }
}
function button(key, name, glyph, cls = 'secondary') {
  return '<button type="button" class="' + cls + '" data-do="' + key + '">' +
    (glyph ? icons(glyph) : '') + name + '</button>'
}
function options(list, selected) {
  return list.map((option) => '<option value="' + esc(option.id) + '"' +
    (String(option.id) === selected ? ' selected' : '') + '>' + esc(option.name) + '</option>').join('')
}
function browseKey() {
  return JSON.stringify(catalogMode === 'charts' ? [catalogMode, platform] : [catalogMode, platform, sort, tag])
}
function detailKey() { return online && JSON.stringify([online.kind, online.platform, online.id]) }
function detailAction() { return online.kind === 'charts' ? 'chart-detail' : 'detail' }
function isDetailAction(kind) { return kind === 'detail' || kind === 'chart-detail' }

function loadCatalog(kind, page = 1) {
  const isDetail = isDetailAction(kind)
  const searching = kind === 'search'
  const key = searching ? searchKey() : kind === 'options' ? platform : isDetail ? detailKey() : browseKey()
  const states = searching ? searches : kind === 'options' ? filters : isDetail ? details : catalog
  const previous = states.get(key)
  if (!key || (previous && previous.loading)) return
  const state = previous || { rows: [] }
  if (page > 1 && !previous) return
  state.checkedAt = Date.now()
  state.loading = true
  state.error = ''
  state.failedPage = page
  states.set(key, state)
  const next = ++token
  requests.set(next, { kind, key, states, state, page })
  app('catalog', {
    token: next, action: kind, platform: searching ? searchSource : isDetail ? online.platform : platform,
    query: searching ? query : undefined,
    sort, tag, id: isDetail ? online.id : '', page,
  })
}
window.catalogReply = ({ token, result, error, cached }) => {
  const call = requests.get(token)
  if (!call) return
  if (!cached) requests.delete(token)
  const { kind, key, states, state, page } = call
  if (states.get(key) !== state) return
  if (cached && (state.rows.length || state.tags)) return
  state.loading = Boolean(cached)
  state.error = error || ''
  if (result) {
    if (kind === 'options') {
      state.sorts = result.sorts || []
      const categories = new Map()
      ;(result.hotTags || []).forEach((item) => categories.set(String(item.id), item))
      ;(result.tags || []).forEach((group) => (group.list || []).forEach((item) => categories.set(String(item.id), item)))
      if (categories.size || !state.tags) state.tags = Array.from(categories.values())
    } else {
      const rows = result.list || []
      if (page === 1) state.rows = []
      const seen = new Set(state.rows.map((item) => String(item.id)))
      rows.forEach((row) => {
        const id = String(row.id)
        if (!seen.has(id)) { state.rows.push(row); seen.add(id) }
      })
      state.next = rows.length && page * Math.max(1, result.limit || rows.length) < result.total ? page + 1 : 0
      state.info = result.info || state.info
      state.total = result.total
    }
  }
  const active = isDetailAction(kind) ? key === detailKey() :
    !online && (kind === 'options' ? catalogMode === 'list' && key === platform : key === browseKey())
  if (kind === 'search' ? tab === 'search' && key === searchKey() : catalogTab() && active) draw()
}

function openCatalog() {
  const now = Date.now()
  const page = catalog.get(browseKey())
  const categories = filters.get(platform)
  if (!page || now - page.checkedAt > 300000) loadCatalog(catalogMode)
  if (catalogMode === 'list' && (!categories || now - categories.checkedAt > 300000)) loadCatalog('options')
}
function selectTab(next) {
  const returningFromSearch = !data.car && tab === 'search' && next === 'home'
  tab = next
  sorting = false
  listId = ''
  online = null
  if (data.car && catalogTab()) catalogMode = tab === 'charts' ? 'charts' : 'list'
  if (catalogTab() && nativeReady) openCatalog()
  if (tab === 'search' && query && !searches.has(searchKey())) loadCatalog('search')
  view.scrollTop = 0
  draw()
  if (returningFromSearch) view.scrollTop = phoneBrowseScroll
}

function card(item, id, subtitle, eager) {
  return '<button type="button" class="playlistCard" data-open="' + esc(id) + '">' +
    cover(item.cover, id === 'love' ? 'heart' : 'disc', id === 'love' ? 'lovedCover' : '', item.coverFallback, eager) +
    '<span class="cardWords"><strong>' + esc(item.name) + '</strong>' + (subtitle ? '<small>' + esc(subtitle) + '</small>' : '') + '</span></button>'
}
function loading(state, kind, noRows) {
  const subject = kind === 'search' ? '搜索结果' : kind === 'charts' ? '排行榜' : kind === 'chart-detail' ? '榜单歌曲' : '歌单'
  if (state && state.loading) return noRows ? '<div class="empty" role="status">正在读取' + subject + '…</div>' : ''
  if (state && state.error) return '<div class="empty" role="status">' +
    (noRows ? '当前来源暂时无法获取' : '刷新失败 · 正在显示上次的') + subject +
    '<small>' + esc(state.error) + '</small>' + button('retry-' + kind, '重试', 'sync') + '</div>'
  if (noRows) return '<div class="empty">当前没有可显示的' + subject + '</div>'
  return state && state.next ? '<div class="loadMore">' + button('more-' + kind, '加载更多') + '</div>' : ''
}
function homeFilters() {
  const source = '<select id="sourceFilter" aria-label="在线资源来源">' +
    options(Object.keys(platforms).map((id) => ({ id, name: platforms[id] })), platform) + '</select>'
  if (catalogMode === 'charts') return source
  const choices = filters.get(platform) || {}
  const sortChoices = (choices.sorts || []).map((item) => {
    const name = item.name || ''
    const match = /^\{songlist\.sort\.(.+)\}$/.exec(name)
    return { id: item.id, name: match ? sorts[match[1]] || match[1] : name }
  })
  return source +
    ((choices.tags || []).length || !choices.error ?
      '<select id="tagFilter" aria-label="歌单分类"' + ((choices.tags || []).length ? '' : ' disabled') + '>' +
      options([{ id: '', name: '全部分类' }].concat(choices.tags || []), tag) + '</select>' :
      button('retry-options', '重试分类')) +
    (sortChoices.length > 1 ? '<select id="sortFilter" aria-label="歌单排序">' +
      options([{ id: '', name: '默认排序' }].concat(sortChoices), sort) + '</select>' : '')
}
function homeLists() {
  const state = catalog.get(browseKey())
  const rows = state ? state.rows : []
  const compact = !data.car && catalogMode === 'list'
  const gridClass = catalogMode === 'list' ? 'playlistGrid onlinePlaylists' : 'playlistGrid'
  return '<div class="' + gridClass + (compact ? ' compactPlaylists' : '') + '">' + rows.map((item, i) =>
    card(item, item.id, compact ? '' : [platforms[platform], item.total != null ? item.total + ' 首' : item.author].filter(Boolean).join(' · '), i < (compact ? 9 : 4))
  ).join('') + '</div>' + loading(state, catalogMode, rows.length === 0)
}
function showPlaylist(id) {
  return id !== 'default' || data.showListeningList
}
function myLists() {
  return '<div class="pageTitle"><h1>我的列表</h1>' + (data.playlists.filter((l) => l.id !== 'love' && l.id !== 'default').length > 1 ? sortButton() : '') + button('new', '新建', 'plus') + '</div>' +
    (data.playlistReady ? '<div class="playlistGrid">' +
      data.playlists.filter((item) => showPlaylist(item.id)).map((item, i) => {
        const tile = card(item, item.id, item.count + ' 首', i < 4)
        return sorting && item.id !== 'love' && item.id !== 'default'
          ? '<div class="sortCard" data-sort-id="' + esc(item.id) + '">' + tile + grip + '</div>' : tile
      }).join('') +
      '</div>' : '<div class="empty" role="status">正在读取歌单…</div>')
}
function play(id) {
  const ids = visibleTracks.filter((t) => t.playable !== false).map((t) => t.id)
  if (id && ids.includes(id)) app('play', { id, ids })
}
function isLiked(row) {
  return favoriteKeys.has(row.key)
}
function trackRows(rows, remove) {
  return '<ol class="tracks">' + rows.map((t, i) => {
    const liked = isLiked(t)
    const current = Boolean(playing.id) && t.id === playing.id
    return '<li class="track' + (current ? ' playing' : '') + '"' +
      (current ? ' aria-current="true"' : '') +
      (sorting ? ' data-sort-id="' + esc(t.key) + '"' : '') + '>' +
      (sorting ? grip : '') + '<button class="trackMain" type="button" data-track="' + esc(t.id) + '"' +
      (t.playable === false ? ' disabled' : '') + '><span class="trackIndex">' + (current ? '♫' : String(i + 1)) + '</span>' +
      '<span class="trackCopy"><strong>' + esc(t.name) + '</strong><small>' +
      esc([t.artist, t.platform, t.duration].filter(Boolean).join(' · ')) + '</small></span></button>' +
      '<button type="button" class="plain heart' + (liked ? ' liked' : '') + '" data-fav="' + esc(t.id) +
      '" aria-pressed="' + liked + '" aria-label="' + (liked ? '从我喜欢移除' : '加入我喜欢') + '">' + icons('heart') + '</button>' +
      (remove ? '<button type="button" class="plain" data-remove="' + esc(t.id) + '" aria-label="从当前歌单移除">' + icons('trash') + '</button>' : '') + '</li>'
  }).join('') + '</ol>'
}
function detail() {
  const remote = catalogTab()
  const state = remote ? details.get(detailKey()) : null
  const own = !remote && showPlaylist(listId) && data.playlists.find((item) => item.id === listId)
  if (!remote && !own) return myLists()
  const item = remote ? online : own
  const rows = remote ? state ? state.rows : [] : JSON.parse(App.songs(listId))
  visibleTracks = rows
  const count = remote ? state && state.total || online.total : own.count
  const detailCover = state && state.info && state.info.cover
  const artwork = item.cover || detailCover
  const songWithCover = remote && rows.find((row) => row.cover && row.cover !== artwork)
  const fallback = remote ? (detailCover !== artwork && detailCover) ||
    (songWithCover && songWithCover.cover) : own.coverFallback
  return button('back', remote ? catalogLabels[online.kind] : '我的列表', 'back', 'back') +
    '<div class="detailHeader">' + cover(artwork, listId === 'love' ? 'heart' : 'disc', listId === 'love' ? 'lovedCover' : '', fallback, true) +
    '<div><h1>' + esc(item.name) + '</h1><p class="muted">' +
    esc([remote ? platforms[item.platform] : '', count == null ? '' : count + ' 首歌曲'].filter(Boolean).join(' · ')) +
    '</p></div></div><div class="actions">' +
    (rows.some((t) => t.playable !== false) ? button('play-all', '播放全部', 'play', 'primary') : '') +
    (remote ? (rows.length ? button('save-online', online.kind === 'charts' ? '收藏榜单' : '收藏歌单', 'bookmark') +
        (data.playlists.some((list) => list.type === 'general') ? button('bind-existing', '关联已有列表') : '') : '') :
      (rows.length > 1 ? sortButton() : '') + (canRefresh(own) ? button('refresh', '更新列表', 'sync') : '') +
      (own.type === 'online' ? '' : button('add', '添加歌曲', 'plus')) +
      (own.editable ? button('edit', '编辑列表', 'pencil') + button('delete', '删除歌单', 'trash', 'plain') : '')) +
    '</div>' + (rows.length ? trackRows(rows, !remote && own.type !== 'online' && listId !== 'love') : '') +
    (remote ? loading(state, detailAction(), rows.length === 0) :
      rows.length ? '' : '<div class="empty">这个歌单还没有歌曲</div>')
}

function toggleOption(id, title, hint) {
  return '<label class="setting pref"><span><strong>' + title + '</strong>' +
    (hint ? '<small>' + hint + '</small>' : '') + '</span><input type="checkbox" data-pref="' +
    id + '"' + (data[id] ? ' checked' : '') + '></label>'
}
function selectOption(id, title, table) {
  return '<label class="setting pref"><span><strong>' + title + '</strong></span><select data-pref="' + id + '">' +
    options(Object.keys(table).map((key) => ({ id: key, name: table[key] })), data[id]) + '</select></label>'
}
function sourceCards() {
  const actions = (item, name, action) => '<button type="button" class="secondary" data-source-action="' + action +
    '" data-source-id="' + esc(item.id) + '"' + (data.busy ? ' disabled' : '') + '>' + name + '</button>'
  const enabled = data.sources.filter((item) => item.enabled)
  const ordered = enabled.concat(data.sources.filter((item) => !item.enabled))
  return ordered.map((item, i) => '<article class="sourceCard"><div><strong>' + esc(item.name) +
    '</strong><small>' + esc([item.enabled ? '优先级 ' + (i + 1) : '', item.version, (item.platforms || []).map((p) => platforms[p] || p).join(' / ')].filter(Boolean).join(' · ')) +
    '</small></div><div class="actions">' + actions(item, item.enabled ? '已启用' : '已停用', 'enableSource') +
    actions(item, '测试音源', 'testSource') +
    (item.linked ? actions(item, '从链接更新', 'updateSource') : '') +
    actions(item, item.linked ? '修改更新地址' : '绑定更新地址', 'editLink') +
    (item.enabled && i > 0 ? actions(item, '上移', 'raiseSource') : '') +
    (i + 1 < enabled.length ? actions(item, '下移', 'lowerSource') : '') +
    actions(item, '移除', 'removeSource') + '</div></article>').join('')
}
function settings() {
  const paired = data.tide && data.tide.account
  const syncTime = data.lastSync ? '上次同步 · ' + new Date(data.lastSync).toLocaleString() : data.syncConfigured ? '等待同步' : '尚未配置';
  return '<div class="pageTitle"><h1>设置</h1></div><div class="settingGroups"><section>' +
    '<h2>同步</h2><div class="setting"><div><strong>汐听同步服务</strong><small>' +
    esc(paired ? data.tide.account + ' · ' + syncTime : '使用服务器地址和一次性配对码') +
    '</small></div>' + (paired ? button('tideDisconnect', '断开') : button('tidePair', '配对')) + '</div>' +
    (paired
      ? '<div class="setting"><div><strong>同步音源</strong><small>' +
        (data.tide.sources ? '已开启' : '默认关闭') + '</small></div>' +
        button('tideSources:' + String(!data.tide.sources), data.tide.sources ? '关闭' : '开启') + '</div>' +
        (data.tide.hasReview ? button('tideApprove', '审核服务器音源') : '')
      : '<div class="setting"><div><strong>WebDAV</strong><small>' + esc(syncTime) +
        '</small></div>' + button('dav', '配置') + '</div>') +
    (data.syncConfigured ? button('sync', data.syncing ? '正在同步…' : '立即同步', 'sync') : '') +
    (paired && data.changeWatchError ?
      '<p class="muted" role="status">' + esc('自动同步监听 · ' + data.changeWatchError + '，正在重试') + '</p>' : '') +
    '<p id="status" role="status">' + esc(data.status) + '</p></section><section><h2>播放与显示</h2>' +
    selectOption('playMode', '播放顺序', modes) +
    selectOption('quality', '首选音质', { auto: '音源默认', '128k': '标准 128k', '320k': '高品质 320k', flac: '无损 FLAC' }) +
    toggleOption('unmetered', '仅在非计费网络播放') +
    toggleOption('keepScreen', '播放时保持亮屏', '播放器位于前台时生效') +
    toggleOption('showListeningList', '显示试听列表') +
    (data.car ? toggleOption('resumeOnOpen', '打开应用时自动续播', '从上次保存的歌曲和进度继续播放') +
      toggleOption('largeControls', '加大文字与触控区') : '') + '</section><section><h2>音源</h2>' +
    '<div class="setting sourceSetting"><div><strong>音源管理</strong><small>' +
    esc(data.sources.length ? '已启用 ' + data.sources.filter((item) => item.enabled).length + ' 个 · 同音质按优先级逐个尝试' : '尚未导入') + '</small></div>' +
    '<span class="sourceButtons">' +
    '<button type="button" class="secondary" data-do="source"' + (data.busy ? ' disabled' : '') + '>导入音源</button>' +
    '<button type="button" class="secondary" data-do="updateSources"' + (data.busy ? ' disabled' : '') + '>更新音源</button>' +
    '</span></div><p class="sourceStatus" role="status">' + esc(data.sourceUpdateStatus) + '</p>' +
    sourceCards() + '</section><section><h2>关于</h2><div class="setting"><div><strong>' + esc(data.appName || '汐听') + ' · TideListen</strong><small>当前版本 ' +
    esc(data.appVersion || '') + ' · Midnight 主题</small></div>' + button('checkAppUpdate', '检查更新') + '</div>' +
    toggleOption('includeBetaUpdates', '测试版', '更新渠道 · 勾选后也会检查 Beta 版本') +
    '<div class="setting"><div><strong>项目源码与许可</strong><small>基于 Any Listen 的非商业改版</small></div>' +
    button('projectSource', '查看') + '</div></section></div>'
}

function searchView() {
  const sources = '<select aria-label="搜索来源" id="searchSource">' +
    options(Object.keys(platforms).map((id) => ({id, name: platforms[id]})), searchSource) + '</select>'
  const field = '<input type="search" maxlength="128" enterkeyhint="search" aria-label="搜索歌曲或歌手" placeholder="搜索歌曲或歌手" required>'
  const form = data.car
    ? '<div class="pageTitle"><h1>搜索</h1></div><form class="carSearch" id="carSearch">' + sources + field +
      '<button type="submit" class="primary">搜索</button></form>'
    : '<form class="phoneSearch" id="carSearch"><label class="searchBox">' + icons('search') + field + '</label>' +
      '<button type="submit" class="searchSubmit">搜索</button>' + button('cancel-search', '取消') + '</form>' +
      '<div class="filters">' + sources + '</div>'
  return form + '<div id="searchResults"></div>'
}

function draw() {
  const previousTop = view.scrollTop
  visibleTracks = []
  const selectedTab = !data.car && tab === 'search' ? 'home' : tab
  tabs.forEach((btn) => {
    if (btn.dataset.tab === selectedTab) btn.setAttribute('aria-current', 'page')
    else btn.removeAttribute('aria-current')
  })
  const showHome = catalogTab() && !online
  const markup = showHome ? (data.car ? '' : button('home-search', '搜索歌曲或歌手', 'search', 'homeSearch') +
    '<div class="catalogSwitch" role="group" aria-label="在线资源">' +
    Object.entries(catalogLabels).map(([mode, label]) => '<button type="button" data-catalog-mode="' + mode + '">' + label + '</button>').join('') +
    '</div>') + '<div class="filters"></div><div id="catalogResults"></div>' :
    tab === 'search' ? searchView() :
    tab === 'settings' ? settings() : listId || online ? detail() : myLists()
  if (markup !== viewMarkup) {
    replaceCoverMarkup(view, markup)
    if (tab === 'search') el('carSearch').querySelector('input').value = searchDraft
    viewMarkup = markup
    filterMarkup = catalogMarkup = ''
  }
  if (showHome) {
    for (const button of view.querySelectorAll('[data-catalog-mode]')) {
      button.setAttribute('aria-pressed', String(button.dataset.catalogMode === catalogMode))
    }
    const choices = homeFilters()
    if (choices !== filterMarkup) {
      view.querySelector('.filters').innerHTML = choices
      filterMarkup = choices
    }
    const lists = homeLists()
    if (lists !== catalogMarkup) {
      replaceCoverMarkup(el('catalogResults'), lists)
      catalogMarkup = lists
    }
  }
  if (tab === 'search') {
    const result = searches.get(searchKey())
    visibleTracks = result ? result.rows : []
    const html = query ? trackRows(visibleTracks, false) + loading(result, 'search', !visibleTracks.length) : ''
    if (catalogMarkup !== html) {
      el('searchResults').innerHTML = html
      catalogMarkup = html
    }
  }
  rememberPage()
  view.scrollTop = previousTop
  drawTransport()
}

function time(ms) {
  const s = Math.floor(ms / 1000)
  return Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0')
}
function currentPosition() {
  const ms = playing.position || 0
  if (!playing.playing || !playing.duration) return ms
  return Math.min(playing.duration, ms + Math.max(0, performance.now() - positionSample))
}

function drawFullTime() {
  const seek = el('seek')
  const ms = seekHeld ? Number(seek.value) : currentPosition()
  if (!seekHeld) seek.value = ms
  el('position').textContent = time(ms)
  seek.style.setProperty('--seek-progress', (playing.duration ? ms * 100 / playing.duration : 0) + '%')
  lyrics.update(ms)
}

function updateFullClock() {
  const open = !el('nowScreen').hidden
  if (open) {
    lyrics.show(playing.id)
    drawFullTime()
  }
  if (open && playing.playing) {
    if (!fullClock) fullClock = setInterval(drawFullTime, 80)
  } else if (fullClock) {
    clearInterval(fullClock)
    fullClock = 0
  }
}

function drawTransport() {
  const p = playing
  const mode = modes[data.playMode] ? data.playMode : 'order'
  const canPlay = Boolean(p.id || visibleTracks.some((row) => row.playable !== false))
  const pause = p.playing || p.loading
  el('nowName').textContent = p.name || '尚未播放'
  el('fullName').textContent = p.name || '尚未播放'
  el('nowArtist').textContent = p.loading ? '正在加载…' : p.artist || ''
  el('fullArtist').textContent = p.artist || ''
  if (artworkId !== p.id) {
    artworkId = p.id
    resolvedArtwork = ''
    pictureRequested = false
  }
  const picture = resolvedArtwork || p.cover || ''
  setArtwork(el('miniCover'), picture)
  setArtwork(el('fullCover'), picture)
  if (!picture) requestPicture()
  el('miniMode').hidden = !data.car
  ;['mini', 'full'].forEach((size) => {
    const heart = el(size + 'Heart')
    heart.disabled = !p.canFavorite
    heart.classList.toggle('liked', !!p.fav)
    heart.setAttribute('aria-pressed', String(!!p.fav))
    heart.setAttribute('aria-label', p.fav ? '从我喜欢移除' : '加入我喜欢')
    if (!heart.firstChild) heart.innerHTML = icons('heart')
    const toggle = el(size + 'Toggle')
    toggle.disabled = !canPlay
    toggle.setAttribute('aria-label', pause ? '暂停' : '播放')
    if (toggle.dataset.icon !== (pause ? 'pause' : 'play')) {
      toggle.dataset.icon = pause ? 'pause' : 'play'
      toggle.innerHTML = icons(toggle.dataset.icon)
    }
    const modeButton = el(size + 'Mode')
    modeButton.setAttribute('aria-label', '切换播放模式 · ' + modes[mode])
    modeButton.title = modes[mode]
    if (modeButton.dataset.icon !== mode) {
      modeButton.dataset.icon = mode
      modeButton.innerHTML = icons(mode)
    }
    el(size + 'Prev').disabled = !p.prev
    el(size + 'Next').disabled = !p.next
  })
  el('miniProgress').style.width = p.duration ? Math.min(100, p.position * 100 / p.duration) + '%' : '0%'
  const seek = el('seek')
  seek.disabled = !p.duration
  if (!seekHeld) seek.max = p.duration || 1
  el('duration').textContent = time(p.duration || 0)
  updateFullClock()
}

window.library = (update) => {
  data = update
  if (!nativeReady) {
    nativeReady = true
    if (tab === 'home' && !online) {
      try {
        const saved = JSON.parse(data.catalogSelection || '{}')
        if (Object.prototype.hasOwnProperty.call(platforms, saved.platform)) {
          platform = saved.platform
          sort = saved.sort || ''
          tag = saved.tag || ''
        }
      } catch (_) {}
    }
    if (data.car) restorePage()
    if (online) loadCatalog(detailAction())
    else if (catalogTab()) openCatalog()
    else if (tab === 'search' && query) loadCatalog('search')
  }
  favoriteKeys = new Set(data.favoriteKeys || [])
  document.body.classList.toggle('car', data.car)
  document.body.classList.toggle('largeControls', data.car && data.largeControls)
  const badge = el('syncBadge')
  badge.hidden = !data.syncConfigured
  badge.dataset.state = data.pending ? 'pending' : data.syncing ? 'sync' : ''
  badge.querySelector('span').textContent = data.syncState || (data.pending ? '修改待同步' : data.lastSync ? '歌单已同步' : '等待同步')
  if (listId && (!showPlaylist(listId) || (data.playlistReady && !data.playlists.some((list) => list.id === listId)))) listId = ''
  draw()
}
window.transport = (state) => {
  const changed = state.id !== playing.id
  if (changed) seekHeld = false
  playing = state
  positionSample = performance.now()
  if (changed && (listId || online || tab === 'search')) draw()
  else drawTransport()
}
window.openList = ({ id }) => {
  tab = 'lists'
  sorting = false
  listId = showPlaylist(id) ? id : ''
  online = null
  view.scrollTop = 0
  draw()
}

view.addEventListener('click', (event) => {
  const target = event.target.closest('button')
  if (!target) return
  const item = target.dataset
  if (item.catalogMode) {
    if (catalogMode === item.catalogMode) return
    catalogMode = item.catalogMode
    view.scrollTop = 0
    openCatalog()
    draw()
  } else if (item.do === 'home-search') {
    phoneBrowseScroll = view.scrollTop
    searchDraft = query = ''
    searchSource = platform
    selectTab('search')
    el('carSearch').querySelector('input').focus()
  } else if (item.do === 'cancel-search') {
    selectTab('home')
  } else if (item.open !== undefined) {
    sorting = false
    if (tab === 'lists') {
      listId = item.open
      const list = data.playlists.find((l) => l.id === listId)
      if (canRefresh(list) && list.autoSync && Date.now() - list.syncTime > 86400000) refreshList(list)
    } else {
      const section = catalog.get(browseKey())
      const row = section && section.rows.find((itemRow) => String(itemRow.id) === item.open)
      if (!row) return
      online = Object.assign({}, row, { platform, kind: catalogMode })
      if (!details.has(detailKey())) loadCatalog(detailAction())
    }
    view.scrollTop = 0
    draw()
  } else if (item.track) play(item.track)
  else if (item.fav) app('fav', item.fav)
  else if (item.remove) app('removeSong', { listId, song: item.remove })
  else if (item.sourceAction) app(item.sourceAction, item.sourceId)
  else if (item.do === 'back') selectTab(tab)
  else if (item.do === 'play-all') {
    const first = visibleTracks.find((track) => track.playable !== false)
    if (first) play(first.id)
  } else if (item.do === 'new') app('createList')
  else if (item.do === 'sort') { sorting = !sorting; draw() }
  else if (item.do === 'edit' || item.do === 'refresh') {
    const list = data.playlists.find((itemRow) => itemRow.id === listId)
    if (list && item.do === 'edit') controls.edit(list)
    else if (canRefresh(list)) refreshList(list)
  } else if (item.do === 'bind-existing' && online) {
    app('bindExisting', { extensionId: 'online-metadata', source: online.platform,
      sourceType: online.kind === 'charts' ? 'topSongs' : 'songlist', syncId: String(online.id) })
  } else if (item.do === 'add') app('add', listId)
  else if (item.do === 'delete') app('deleteList', listId)
  else if (item.do === 'save-online' && online) {
    app('saveCatalog', { action: detailAction(), id: String(online.id), name: online.name, platform: online.platform })
  } else if (item.do && item.do.startsWith('tideSources:')) app('tideSources', item.do.slice('tideSources:'.length))
  else if (['tidePair', 'tideDisconnect', 'tideApprove', 'dav', 'sync', 'source', 'updateSources', 'checkAppUpdate', 'projectSource'].includes(item.do)) app(item.do)
  else if (item.do === 'retry-options') {
    loadCatalog('options')
    draw()
  } else if (item.do && /^(retry|more)-(list|detail|charts|chart-detail|search)$/.test(item.do)) {
    const kind = item.do.slice(item.do.indexOf('-') + 1)
    const state = kind === 'search' ? searches.get(searchKey()) : isDetailAction(kind) ? details.get(detailKey()) : catalog.get(browseKey())
    loadCatalog(kind, item.do.startsWith('retry') ? state.failedPage : state.next)
    draw()
  }
})
view.addEventListener('input', (event) => {
  if (event.target.form && event.target.form.id === 'carSearch' && event.target.type === 'search')
    searchDraft = event.target.value
})
function submitSearch() {
  query = searchDraft.trim()
  if (query) {
    searches.clear()
    loadCatalog('search')
  }
  draw()
}
view.addEventListener('submit', (event) => {
  if (event.target.id !== 'carSearch') return
  event.preventDefault()
  searchDraft = event.target.querySelector('input').value
  event.target.querySelector('input').blur()
  submitSearch()
})
view.addEventListener('change', (event) => {
  const target = event.target
  if (target.id === 'searchSource') {
    searchSource = target.value
    submitSearch()
  } else if (target.dataset.pref) {
    app(target.dataset.pref, target.type === 'checkbox' ? String(target.checked) : target.value)
  } else if (target.id === 'sourceFilter' || target.id === 'tagFilter' || target.id === 'sortFilter') {
    if (target.id === 'sourceFilter') { platform = target.value; sort = ''; tag = '' }
    else if (target.id === 'tagFilter') tag = target.value
    else sort = target.value
    app('catalogSelection', { platform, sort, tag })
    view.scrollTop = 0
    openCatalog()
    draw()
    if (el(target.id)) el(target.id).focus()
  }
})

function cycleMode() {
  const keys = Object.keys(modes)
  app('playMode', keys[(keys.indexOf(data.playMode) + 1) % keys.length])
}
function toggle() {
  if (playing.id) app('toggle')
  else {
    const first = visibleTracks.find((row) => row.playable !== false)
    if (first) play(first.id)
  }
}
function showNow(open) {
  el('nowScreen').hidden = !open
  el('shell').hidden = open
  if (!open) { seekHeld = false; lyrics.hide() }
  updateFullClock()
  el(open ? 'closeNow' : 'nowOpen').focus()
  rememberPage()
}
el('nowOpen').onclick = () => showNow(true)
el('closeNow').onclick = () => showNow(false)
el('syncBadge').onclick = () => selectTab('settings')
;['mini', 'full'].forEach((size) => {
  el(size + 'Heart').onclick = () => { if (playing.canFavorite) app('fav', playing.id) }
  el(size + 'Mode').onclick = cycleMode
  el(size + 'Toggle').onclick = toggle
  el(size + 'Prev').onclick = () => app('prev')
  el(size + 'Next').onclick = () => app('next')
})
el('seek').oninput = () => {
  seekHeld = true
  drawFullTime()
}
el('seek').onchange = (event) => {
  seekHeld = false
  playing.position = Number(event.target.value)
  positionSample = performance.now()
  drawFullTime()
  app('seek', event.target.value)
}
el('seek').onpointercancel = () => { seekHeld = false; drawFullTime() }
tabs.forEach((button) => { button.onclick = () => selectTab(button.dataset.tab) })
window.goBack = () => {
  if (controls.close()) return true
  if (!el('nowScreen').hidden) { showNow(false); return true }
  if (online || listId) { selectTab(tab); return true }
  const first = data.car ? 'list' : 'home'
  if (tab !== first) { selectTab(first); return true }
  return false
}
selectTab('home')

