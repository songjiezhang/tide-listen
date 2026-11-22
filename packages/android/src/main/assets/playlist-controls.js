'use strict'

function playlistControls(view, move, save) {
  const form = document.getElementById('playlistForm')
  const dialog = document.getElementById('playlistDialog')
  const fields = form.elements
  let editing, drag, frame

  function toggleFields() {
    const online = fields.listType.value === 'online'
    form.querySelector('.bindingFields').hidden = !online
    fields.syncId.required = online && !fields.syncId.disabled
  }
  fields.listType.onchange = toggleFields
  form.querySelector('[data-cancel]').onclick = () => dialog.close()
  form.onsubmit = (event) => {
    event.preventDefault()
    const name = fields.listName.value.trim()
    const syncId = fields.syncId.value.trim()
    if (!name || (fields.syncId.required && !syncId)) return
    const previous = editing.online
    const online = fields.listType.value === 'online' && !fields.platform.disabled
      ? { extensionId: 'online-metadata', source: fields.platform.value, sourceType: fields.kind.value,
          syncId, date: previous && previous.syncId === syncId && previous.source === fields.platform.value &&
            previous.sourceType === fields.kind.value ? previous.date || '' : '' } : null
    save({ id: editing.id, name, online, autoSync: fields.autoSync.checked })
    dialog.close()
  }

  function clearDrag() {
    cancelAnimationFrame(frame)
    if (!drag) return
    drag.source.classList.remove('dragSource')
    if (drag.target) drag.target.classList.remove('dragTarget')
    drag = null
  }
  function findTarget() {
    if (!drag) return
    const rect = view.getBoundingClientRect()
    const inside = drag.y >= rect.top && drag.y <= rect.bottom && drag.x >= rect.left && drag.x <= rect.right
    const hit = inside && document.elementFromPoint(drag.x, drag.y)
    const row = hit && hit.closest('[data-sort-id]')
    if (drag.target) drag.target.classList.remove('dragTarget')
    drag.target = row && row !== drag.source && row.parentElement === drag.source.parentElement ? row : null
    if (drag.target) drag.target.classList.add('dragTarget')
  }
  function scroll() {
    if (!drag || !drag.source.isConnected) return clearDrag()
    const rect = view.getBoundingClientRect()
    const edge = Math.min(rect.height / 4, 55)
    const delta = Math.max(0, drag.y - rect.bottom + edge) - Math.max(0, rect.top + edge - drag.y)
    if (delta) view.scrollTop += Math.sign(delta) * Math.min(18, Math.ceil(Math.abs(delta) / 4))
    findTarget()
    frame = requestAnimationFrame(scroll)
  }
  view.addEventListener('pointerdown', (event) => {
    const handle = event.target.closest('[data-grip]')
    if (!handle || !event.isPrimary || event.button !== 0) return
    clearDrag()
    event.preventDefault()
    drag = { source: handle.closest('[data-sort-id]'), pointer: event.pointerId, x: event.clientX, y: event.clientY }
    handle.setPointerCapture(event.pointerId)
    drag.source.classList.add('dragSource')
    frame = requestAnimationFrame(scroll)
  })
  view.addEventListener('pointermove', (event) => {
    if (!drag || drag.pointer !== event.pointerId) return
    drag.x = event.clientX
    drag.y = event.clientY
  })
  view.addEventListener('pointerup', (event) => {
    if (!drag || drag.pointer !== event.pointerId) return
    drag.x = event.clientX
    drag.y = event.clientY
    findTarget()
    const { source, target } = drag
    clearDrag()
    if (target) move(source.dataset.sortId, target.dataset.sortId,
      !!(source.compareDocumentPosition(target) & Node.DOCUMENT_POSITION_FOLLOWING))
  })
  view.addEventListener('lostpointercapture', clearDrag)
  view.addEventListener('pointercancel', clearDrag)
  view.addEventListener('keydown', (event) => {
    const grip = event.target.closest('[data-grip]')
    const source = grip && grip.closest('[data-sort-id]')
    if (!source || !['ArrowDown', 'ArrowUp', 'ArrowRight', 'ArrowLeft'].includes(event.key)) return
    event.preventDefault()
    const after = event.key === 'ArrowDown' || event.key === 'ArrowRight'
    const target = after ? source.nextElementSibling : source.previousElementSibling
    if (target && target.dataset.sortId) move(source.dataset.sortId, target.dataset.sortId, after)
  })
  return {
    edit(list) {
      editing = list
      const ref = list.online
      const unsupported = !!ref && (ref.extensionId !== 'online-metadata' ||
        !Array.from(fields.platform.options).some((option) => option.value === ref.source) ||
        !['songlist', 'topSongs'].includes(ref.sourceType))
      fields.listName.value = list.name
      fields.listType.value = ref ? 'online' : 'general'
      fields.listType.disabled = !!ref
      fields.platform.value = (ref && ref.source) || 'kg'
      fields.kind.value = (ref && ref.sourceType) || 'songlist'
      fields.syncId.value = (ref && ref.syncId) || ''
      for (const input of [fields.platform, fields.kind, fields.syncId]) input.disabled = unsupported
      fields.autoSync.checked = !!list.autoSync
      toggleFields()
      dialog.showModal()
    },
    close() { if (!dialog.open) return false; dialog.close(); return true },
  }
}
