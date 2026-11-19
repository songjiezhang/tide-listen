'use strict'

function parseTimedLyrics(text) {
  const offsetTag = /^\s*\[offset:\s*([+-]?\d+)\s*\]/im.exec(text)
  const offset = offsetTag ? Number(offsetTag[1]) : 0
  const timestamps = /^((?:\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?\])+)(.*)$/
  const repeated = /\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]/g
  const entries = new Map()
  text.split(/\r\n|\n|\r/).forEach((line) => {
    const row = timestamps.exec(line.trim())
    if (!row) return
    const content = row[2].trim()
    let timestamp
    while ((timestamp = repeated.exec(row[1]))) {
      const ms = (Number(timestamp[1]) * 60 + Number(timestamp[2])) * 1000 +
        Number((timestamp[3] || '').padEnd(3, '0'))
      const time = Math.max(0, ms - offset)
      if (content || !entries.has(time)) entries.set(time, content)
    }
  })
  const rows = Array.from(entries, ([time, text]) => ({ time, text })).sort((a, b) => a.time - b.time)
  return rows.some((row) => row.text) ? rows : []
}

function createLyricsView(request, cancel) {
  const viewport = document.getElementById('lyricsViewport')
  const linesNode = document.getElementById('lyricsLines')
  const statusNode = document.getElementById('lyricsStatus')
  const statusText = document.getElementById('lyricsStatusText')
  const retry = document.getElementById('lyricsRetry')
  const cache = new Map()
  let rows = [], active = -1, id = null, token = 0, loading = false
  let position = 0

  function center() {
    const line = linesNode.children[Math.max(0, active)]
    if (!line || !viewport.clientHeight) return
    const shift = viewport.clientHeight / 2 - line.offsetTop - line.offsetHeight / 2
    linesNode.style.transform = 'translateY(' + Math.round(shift) + 'px)'
  }

  function highlight(force) {
    let low = 0, high = rows.length
    while (low < high) {
      const middle = (low + high) >> 1
      if (rows[middle].time <= position) low = middle + 1
      else high = middle
    }
    const index = low - 1
    if (index === active && !force) return
    if (active >= 0 && linesNode.children[active]) {
      linesNode.children[active].removeAttribute('aria-current')
    }
    active = index
    if (active >= 0) linesNode.children[active].setAttribute('aria-current', 'true')
    center()
  }

  function showStatus(message, mayRetry = false) {
    rows = []
    active = -1
    linesNode.textContent = ''
    linesNode.style.transform = ''
    viewport.hidden = true
    statusNode.hidden = false
    statusText.textContent = message
    retry.hidden = !mayRetry
  }

  function showLyrics(newRows) {
    if (!newRows.length) {
      showStatus('暂无同步歌词')
      return
    }
    rows = newRows
    active = -1
    linesNode.textContent = ''
    const fragment = document.createDocumentFragment()
    for (const row of rows) {
      const line = document.createElement('li')
      line.textContent = row.text || ' '
      if (!row.text) line.className = 'lyricBreak'
      fragment.appendChild(line)
    }
    linesNode.appendChild(fragment)
    viewport.hidden = false
    statusNode.hidden = true
    highlight(true)
  }

  function load() {
    showStatus('正在获取歌词…')
    loading = true
    request({ id, token: ++token })
  }

  retry.addEventListener('click', () => { if (id && !loading) load() })
  window.addEventListener('resize', center)

  return {
    show(nextId) {
      if (nextId === id) return
      if (loading) cancel()
      loading = false
      token++
      id = nextId
      position = 0
      if (!id) showStatus('播放歌曲后显示歌词')
      else if (cache.has(id)) showLyrics(cache.get(id))
      else load()
    },
    hide() {
      if (loading) cancel()
      loading = false
      token++
      id = null
    },
    update(ms) {
      position = ms
      if (rows.length) highlight(false)
    },
    accept(reply) {
      if (!loading || reply.token !== token || reply.id !== id) return
      loading = false
      if (reply.status === 'error') {
        showStatus('歌词获取失败', true)
      } else if (reply.status === 'unavailable') {
        showStatus('暂无同步歌词')
      } else {
        const result = parseTimedLyrics(reply.lyric || '')
        if (result.length) {
          cache.set(id, result)
          if (cache.size > 30) cache.delete(cache.keys().next().value)
        }
        showLyrics(result)
      }
    },
  }
}

