import { appEvent } from '@/modules/app/store/event'

export const sortable = (options: { onupdate: (id: string, position: number) => void }) => {
  return (dom: HTMLElement) => {
    let destroyed = false
    let dispose: (() => void) | undefined
    void import('sortablejs').then(({ default: Sortable }) => {
      if (destroyed) return
      const sortable = Sortable.create(dom, {
        animation: 150,
        forceFallback: true,
        fallbackTolerance: 5,
        draggable: '.draggable-item',
        onStart: () => appEvent.drag(false),
        onMove: ({ related }) => related === dom || related.classList.contains('draggable-item'),
        onEnd: ({ item, oldIndex, newIndex, oldDraggableIndex, newDraggableIndex }) => {
          appEvent.drag(true)
          if (oldIndex == null || newIndex == null || oldDraggableIndex === newDraggableIndex || newDraggableIndex == null) return
          // Svelte owns the keyed DOM. Restore it before committing the new data order.
          item.remove()
          dom.insertBefore(item, dom.children[oldIndex] ?? null)
          if (item.dataset.id) options.onupdate(item.dataset.id, newDraggableIndex)
        },
      })
      dispose = () => sortable.destroy()
    })
    return () => {
      destroyed = true
      dispose?.()
    }
  }
}
