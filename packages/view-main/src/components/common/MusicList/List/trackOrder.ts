/** Resolve drops against the full list even when its virtualized rows scroll out of view. */
export function trackOrder(move: (id: string, index: number) => void, itemHeight: () => number, count: () => number) {
  return (host: HTMLElement) => {
    let active: { id: string; pointer: number; y: number; x: number; start: number; moved: boolean; list: HTMLElement } | undefined
    let frame = 0
    const marker = document.createElement('div')
    marker.className = 'track-drop-line'
    const stop = () => {
      if (!active) return
      const pointer = active.pointer
      active = undefined
      cancelAnimationFrame(frame)
      marker.remove()
      if (host.hasPointerCapture(pointer)) host.releasePointerCapture(pointer)
    }
    const position = () => {
      if (!active) return -1
      const rect = active.list.getBoundingClientRect()
      if (active.x < rect.left || active.x > rect.right || active.y < rect.top || active.y > rect.bottom) return -1
      return Math.max(0, Math.min(count(), Math.round((active.y - rect.top + active.list.scrollTop) / itemHeight())))
    }
    const update = () => {
      if (!active) return
      const rect = active.list.getBoundingClientRect()
      const edge = Math.min(44, rect.height / 4)
      const speed = Math.max(0, active.y - rect.bottom + edge) - Math.max(0, rect.top + edge - active.y)
      if (speed) active.list.scrollTop += Math.sign(speed) * Math.min(16, Math.ceil(Math.abs(speed) / 4))
      const index = position()
      marker.hidden = index < 0
      marker.style.top = `${rect.top - host.getBoundingClientRect().top + index * itemHeight() - active.list.scrollTop}px`
      frame = requestAnimationFrame(update)
    }
    const down = (event: PointerEvent) => {
      const handle = (event.target as Element).closest<HTMLElement>('[data-reorder-track]')
      const list = handle?.closest<HTMLElement>('.my-list')
      if (!handle?.dataset.reorderTrack || !list || !event.isPrimary || event.button !== 0) return
      event.preventDefault()
      active = { id: handle.dataset.reorderTrack, pointer: event.pointerId, start: event.clientY,
        x: event.clientX, y: event.clientY, moved: false, list }
      host.setPointerCapture(event.pointerId)
    }
    const motion = (event: PointerEvent) => {
      if (active?.pointer !== event.pointerId) return
      active.x = event.clientX
      active.y = event.clientY
      if (!active.moved && Math.abs(active.y - active.start) > 3) {
        active.moved = true
        host.append(marker)
        update()
      }
    }
    const up = (event: PointerEvent) => {
      if (active?.pointer !== event.pointerId) return
      active.x = event.clientX
      active.y = event.clientY
      const index = active.moved ? position() : -1
      const { id } = active
      stop()
      if (index >= 0) move(id, index)
    }
    host.addEventListener('pointerdown', down)
    host.addEventListener('pointermove', motion)
    host.addEventListener('pointerup', up)
    host.addEventListener('pointercancel', stop)
    host.addEventListener('lostpointercapture', stop)
    return () => {
      stop()
      host.removeEventListener('pointerdown', down)
      host.removeEventListener('pointermove', motion)
      host.removeEventListener('pointerup', up)
      host.removeEventListener('pointercancel', stop)
      host.removeEventListener('lostpointercapture', stop)
    }
  }
}
