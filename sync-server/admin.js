const $ = (id) => document.getElementById(id)
const status = (message = '') => { $('status').textContent = message }
let account
let tab = 'devices'

async function api(path, method = 'GET', body) {
  const response = await fetch('/admin/api/' + path, {
    method,
    ...(body ? { headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) } : {}),
  })
  const result = await response.json()
  if (!response.ok) throw Object.assign(new Error(result.error), { status: response.status })
  return result
}

function element(tag, text, className) {
  const node = document.createElement(tag)
  if (text) node.textContent = text
  if (className) node.className = className
  return node
}

async function work(button, callback) {
  button.disabled = true
  status()
  try { await callback() }
  catch (error) { status(error.message) }
  finally { button.disabled = false }
}

function button(label, fn) {
  const node = element('button', label)
  node.type = 'button'
  node.onclick = () => work(node, fn)
  return node
}

function section(label) {
  const panel = element('section', '', 'panel')
  panel.append(element('h2', label))
  $('content').append(panel)
  return panel
}

function field(form, label, name, type = 'text', value = '') {
  const wrapper = element('label', label)
  const input = element('input')
  input.name = name
  input.type = type
  input.value = value
  wrapper.append(input)
  form.append(wrapper)
  return input
}

function submit(form, label, fn) {
  const action = element('button', label)
  form.append(action)
  form.onsubmit = (event) => {
    event.preventDefault()
    void work(action, () => fn(Object.fromEntries(new FormData(form))))
  }
}

function entry(parent, text, ...buttons) {
  const row = element('div', '', 'row entry')
  row.append(element('span', text), ...buttons)
  parent.append(row)
}

const date = (value) => new Date(value * 1000).toLocaleString()
const put = (kind, revision, data) => api(kind, 'PUT', { revision, data, operation: crypto.randomUUID() })

async function devices() {
  const panel = section('生成一次性配对码')
  const form = element('form')
  if (account.admin) {
    const label = element('label', '所属账号')
    const select = element('select')
    select.name = 'uid'
    for (const user of account.users.filter((item) => item.active)) {
      const option = element('option', user.name)
      option.value = user.id
      option.selected = user.id === account.id
      select.append(option)
    }
    label.append(select)
    form.append(label)
  }
  const expiry = field(form, '有效期（分钟）', 'minutes', 'number', account.minutes)
  expiry.min = '1'
  expiry.max = '1440'
  const result = element('output')
  submit(form, '生成配对码', async (values) => {
    const created = await api('code', 'POST', { uid: Number(values.uid), minutes: Number(values.minutes) })
    result.textContent = created.code + ' · ' + date(created.expires) + ' 到期'
    account = await api('account')
    drawCodes()
  })
  panel.append(form, result)
  const codes = section('待使用的配对码')
  function drawCodes() {
    codes.replaceChildren(element('h2', '待使用的配对码'))
    if (!account.codes.length) codes.append(element('p', '暂无待使用的配对码', 'dim'))
    for (const code of account.codes) {
      entry(codes, (code.account ?? account.account) + ' · ' + date(code.expires) + ' 到期',
        button('作废', async () => { await api('revoke-code', 'POST', { id: code.id }); account = await api('account'); drawCodes() }))
    }
  }
  drawCodes()
  const paired = section('已配对设备')
  if (!account.devices.length) paired.append(element('p', '暂无配对设备', 'dim'))
  for (const device of account.devices) {
    entry(paired, device.name + ' · ' + (device.account ?? account.account) + ' · ' + date(device.seen),
      button('撤销', async () => {
        if (!confirm('撤销「' + device.name + '」的同步权限？')) return
        await api('revoke-device', 'POST', { id: device.id })
        await load()
      }))
  }
}

async function users() {
  const panel = section('修改我的密码')
  const form = element('form')
  field(form, '当前密码', 'current', 'password').autocomplete = 'current-password'
  field(form, '新密码（至少 12 个字符）', 'password', 'password').autocomplete = 'new-password'
  submit(form, '保存密码', async (data) => { await api('password', 'POST', data); form.reset(); status('密码已更新') })
  panel.append(form)
  if (!account.admin) return
  const create = section('创建用户')
  const newUser = element('form')
  field(newUser, '账号', 'name').required = true
  field(newUser, '初始密码（至少 12 个字符）', 'password', 'password').autocomplete = 'new-password'
  submit(newUser, '创建', async (data) => { await api('create-user', 'POST', data); await load(); status('账号已创建') })
  create.append(newUser)
  const listing = section('用户')
  for (const user of account.users.filter((item) => item.id !== account.id)) {
    entry(listing, user.name + ' · ' + (user.active ? '已启用' : '已停用'),
      button(user.active ? '停用' : '启用', async () => {
        if (user.active && !confirm('停用该用户并撤销其设备和配对码？')) return
        await api('user-status', 'POST', { uid: user.id, active: !user.active })
        await load()
      }))
    const reset = element('form')
    field(reset, '为 ' + user.name + ' 设置新密码', 'password', 'password').autocomplete = 'new-password'
    submit(reset, '重置密码', async (data) => { await api('password', 'POST', { ...data, uid: user.id }); reset.reset(); status('密码已重置') })
    listing.append(reset)
  }
  const defaults = section('默认配对有效期')
  const config = element('form')
  const expiry = field(config, '有效期（分钟）', 'minutes', 'number', account.minutes)
  expiry.min = '1'
  expiry.max = '1440'
  submit(config, '保存', async (data) => { await api('default-expiry', 'POST', { minutes: Number(data.minutes) }); status('默认有效期已更新') })
  defaults.append(config)
}

async function lists() {
  const state = await api('lists')
  const panel = section('我的列表')
  if (!state.data) return panel.append(element('p', '配对桌面端并同步后，歌单会显示在这里。', 'dim'))
  const data = state.data
  async function save() { await put('lists', state.revision, data); await load() }
  for (const list of [data.loveList, data.defaultList, ...data.userList]) {
    const label = list.id === 'love' ? '我喜欢' : list.id === 'default' ? '试听列表' : list.name
    const detail = element('details')
    detail.className = 'entry'
    detail.append(element('summary', label + ' · ' + list.list.length + ' 首'))
    if (list.type === 'general') {
      const form = element('form')
      field(form, '歌单名称', 'name', 'text', list.name).maxLength = 100
      submit(form, '保存名称', async ({ name }) => { if (!name.trim()) return; list.name = name.trim(); list.meta.updateTime = Date.now(); await save() })
      detail.append(form, button('删除歌单', async () => {
        if (!confirm('删除歌单「' + label + '」？')) return
        data.userList = data.userList.filter((row) => row.id !== list.id)
        await save()
      }))
    }
    for (const song of list.list) {
      const actions = list.type === 'general' || list.type === 'default'
        ? [button('移除', async () => { list.list = list.list.filter((row) => row.id !== song.id); list.meta.songCount = list.list.length; await save() })]
        : []
      entry(detail, song.name + ' · ' + song.singer, ...actions)
    }
    panel.append(detail)
  }
}

async function sources() {
  const state = await api('sources')
  const data = state.data || []
  const panel = section('洛雪 JS 音源')
  const form = element('form')
  field(form, '名称', 'name').required = true
  field(form, '更新链接（可选）', 'url', 'url')
  const script = field(form, '上传 JS 文件', 'script', 'file')
  script.accept = '.js'
  script.required = true
  async function save() { await put('sources', state.revision, data); await load() }
  submit(form, '添加音源', async ({ name, url, script: file }) => {
    if (file.size > 4 * 1024 * 1024) throw Error('音源文件过大')
    data.push({ name, url, script: await file.text(), enabled: true })
    await save()
  })
  panel.append(form)
  for (const [index, row] of data.entries()) {
    const item = element('div', '', 'entry')
    item.append(element('strong', row.name))
    const update = element('form')
    field(update, '更新链接', 'url', 'url', row.url)
    const replacement = field(update, '替换 JS 文件（可选）', 'script', 'file')
    replacement.accept = '.js'
    const enabled = field(update, '启用', 'enabled', 'checkbox', '1')
    enabled.checked = row.enabled
    submit(update, '保存音源', async ({ url, enabled, script: file }) => {
      if (file.size > 4 * 1024 * 1024) throw Error('音源文件过大')
      if (file.size) row.script = await file.text()
      row.url = url
      row.enabled = Boolean(enabled)
      await save()
    })
    item.append(update)
    const actions = []
    for (const [offset, label] of [[-1, '上移'], [1, '下移']]) {
      if (data[index + offset]) actions.push(button(label, async () => {
        ;[data[index], data[index + offset]] = [data[index + offset], data[index]]
        await save()
      }))
    }
    actions.push(button('删除音源', async () => {
      if (!confirm('删除音源「' + row.name + '」？')) return
      data.splice(index, 1)
      await save()
    }))
    entry(item, '优先级 ' + (index + 1), ...actions)
    panel.append(item)
  }
}

async function backups() {
  const state = await api('lists')
  const panel = section('歌单备份与恢复')
  const download = button('下载歌单备份', () => {
    const backup = { songlist: { version: 1, data: state.data } }
    const url = URL.createObjectURL(new Blob([JSON.stringify(backup)], { type: 'application/json' }))
    const link = element('a')
    link.href = url
    link.download = 'tidelisten-playlists-' + new Date().toISOString().slice(0, 10) + '.json'
    link.click()
    setTimeout(() => URL.revokeObjectURL(url), 30000)
  })
  download.disabled = !state.data
  panel.append(download, element('p', '备份包含当前账号的歌单，可在这里恢复，也可导入汐听桌面版。', 'dim'))
  const form = element('form')
  const file = field(form, '选择歌单备份', 'backup', 'file')
  file.accept = '.json,application/json'
  file.required = true
  submit(form, '恢复歌单', async ({ backup }) => {
    if (backup.size > 24 * 1024 * 1024) throw Error('备份文件过大')
    let data
    try { data = JSON.parse(await backup.text())?.songlist?.data }
    catch { throw Error('备份文件格式有误') }
    if (!data || !Array.isArray(data.userList)) throw Error('备份文件格式有误')
    if (!confirm('用这份备份替换当前账号的歌单？此改动将同步到配对设备。')) return
    await put('lists', state.revision, data)
    await load()
    status('歌单已恢复')
  })
  panel.append(form)
}

async function load() {
  try { account = await api('account') }
  catch (error) {
    if (error.status !== 401) throw error
    account = undefined
  }
  $('login').hidden = Boolean(account)
  $('logout').hidden = !account
  $('dashboard').hidden = !account
  if (!account) return
  $('content').replaceChildren()
  for (const node of document.querySelectorAll('[data-tab]')) node.setAttribute('aria-current', String(node.dataset.tab === tab))
  await { devices, users, lists, sources, backups }[tab]()
}

for (const node of document.querySelectorAll('[data-tab]')) {
  node.onclick = () => work(node, async () => { tab = node.dataset.tab; await load() })
}
submit($('login'), '登录', async (data) => { await api('login', 'POST', data); $('login').reset(); await load() })
$('logout').onclick = () => work($('logout'), async () => { await api('logout', 'POST', {}); await load() })
load().catch((error) => status(error.message))
