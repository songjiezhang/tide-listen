import { randomUUID } from 'node:crypto'

const url = 'http://127.0.0.1:8472'
async function send(path, body, method = 'POST', credentials = {}) {
  const response = await fetch(url + path, {
    method, body: JSON.stringify(body),
    headers: { 'content-type': 'application/json', origin: url, ...credentials },
  })
  const data = await response.json()
  if (!response.ok) throw Error(path + ': ' + data.error)
  return { data, cookie: response.headers.get('set-cookie')?.split(';')[0] }
}
const { cookie } = await send('/admin/api/login', { name: 'owner', password: 'Android-Local-QA-2026' })
const code = async () => (await send('/admin/api/code', { minutes: 25 }, 'POST', { cookie })).data.code
const fixture = await send('/api/v1/pair', { code: await code(), name: 'QA fixture' })
const headers = { authorization: 'Bearer ' + fixture.data.token }
const song = { id: 'tx_1', name: '旧日海风', singer: '示例', interval: '00:30', isLocal: false,
  meta: { source: 'tx', musicId: '1', createTime: 1, updateTime: 1, posTime: 1 } }
const playlist = (id, name, type, list) => ({
  id, name, type, parentId: null, meta: { songCount: list.length, createTime: 1, updateTime: 1 }, list,
})
const library = {
  defaultList: playlist('default', 'default', 'default', []),
  loveList: playlist('love', 'love', 'default', [song]),
  userList: [playlist('qa-list', '汐听通勤歌单', 'general', [song])],
}
await send('/api/v1/lists', { revision: 0, operation: randomUUID(), data: library }, 'PUT', headers)
const sources = ['示例音源一', '示例音源二'].map((name, index) => ({
  name, url: '', enabled: index === 0,
  script: '/**\n * @name ' + name + '\n */\n' +
    "lx.on(lx.EVENT_NAMES.request, async ()=>'https://example.com/audio.mp3');\n" +
    "lx.send(lx.EVENT_NAMES.inited,{sources:{tx:{type:'music',actions:['musicUrl'],qualitys:['128k']}}});",
}))
await send('/api/v1/sources', { revision: 0, operation: randomUUID(), data: sources }, 'PUT', headers)
console.log(await code())
console.log(await code())
