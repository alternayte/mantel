import {
  DndContext,
  KeyboardSensor,
  PointerSensor,
  closestCenter,
  useSensor,
  useSensors,
  type DragEndEvent,
} from '@dnd-kit/core'
import {
  SortableContext,
  arrayMove,
  rectSortingStrategy,
  sortableKeyboardCoordinates,
  useSortable,
} from '@dnd-kit/sortable'
import { CSS } from '@dnd-kit/utilities'
import { useState } from 'react'
import type { Item } from '../api'
import { Button } from '../components/ui'

/**
 * The album as the creator sees it: every item in its position, with its own state on it.
 *
 * A pointer sensor with a small activation distance, so a click still opens the caption and a drag
 * still reorders — at forty items both have to work without thinking about it.
 */
export function ItemGrid({
  items,
  onReorder,
  onCaption,
  onCover,
  onDelete,
  onRetry,
  coverItemId,
}: {
  items: Item[]
  onReorder: (ids: string[]) => void
  onCaption: (id: string, caption: string) => void
  onCover: (id: string) => void
  onDelete: (id: string) => void
  onRetry: (id: string) => void
  coverItemId?: string | null
}) {
  // Keyboard as well as pointer: reordering is the one thing in this app that is impossible
  // without a mouse if nobody thinks about it. Space picks an item up, arrows move it, space drops.
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 6 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  )

  const onDragEnd = (event: DragEndEvent) => {
    const { active, over } = event
    if (!over || active.id === over.id) return
    const from = items.findIndex((item) => item.id === active.id)
    const to = items.findIndex((item) => item.id === over.id)
    onReorder(arrayMove(items, from, to).map((item) => item.id))
  }

  return (
    <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={onDragEnd}>
      <SortableContext items={items.map((item) => item.id)} strategy={rectSortingStrategy}>
        <ul className="grid grid-cols-[repeat(auto-fill,minmax(150px,1fr))] gap-3 list-none p-0 m-0">
          {items.map((item) => (
            <ItemTile
              key={item.id}
              item={item}
              isCover={item.id === coverItemId}
              onCaption={onCaption}
              onCover={onCover}
              onDelete={onDelete}
              onRetry={onRetry}
            />
          ))}
        </ul>
      </SortableContext>
    </DndContext>
  )
}

function ItemTile({
  item,
  isCover,
  onCaption,
  onCover,
  onDelete,
  onRetry,
}: {
  item: Item
  isCover: boolean
  onCaption: (id: string, caption: string) => void
  onCover: (id: string) => void
  onDelete: (id: string) => void
  onRetry: (id: string) => void
}) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({ id: item.id })
  const [caption, setCaption] = useState(item.caption ?? '')

  return (
    <li
      ref={setNodeRef}
      style={{ transform: CSS.Transform.toString(transform), transition, opacity: isDragging ? 0.4 : 1 }}
      className="flex flex-col gap-1.5 rounded-[10px] border border-line bg-[var(--work-lift)] p-2"
    >
      <div
        {...attributes}
        {...listeners}
        role="button"
        tabIndex={0}
        aria-label={`Item ${item.position + 1}${item.caption ? `, ${item.caption}` : ''}. Press space to reorder.`}
        className="relative grid aspect-[3/2] cursor-grab place-items-center overflow-hidden rounded-[6px] bg-[#0f0f11] text-xs text-muted active:cursor-grabbing"
      >
        <ItemFace item={item} />
        {isCover && (
          <span className="absolute left-1.5 top-1.5 rounded-full bg-black/70 px-2 py-0.5 text-[10px] text-ink">
            cover
          </span>
        )}
        <span className="absolute right-1.5 top-1.5 rounded-full bg-black/70 px-2 py-0.5 text-[10px] text-muted tabular-nums">
          {item.position + 1}
        </span>
      </div>

      <input
        value={caption}
        placeholder="Add a caption"
        aria-label={`Caption for item ${item.position + 1}`}
        onChange={(event) => setCaption(event.target.value)}
        onBlur={() => caption !== (item.caption ?? '') && onCaption(item.id, caption)}
        className="h-7 rounded-[6px] border border-transparent bg-transparent px-1 text-xs text-ink placeholder:text-muted hover:border-line focus:border-line"
      />

      <div className="flex gap-1">
        {item.status === 'failed' ? (
          <Button size="sm" onClick={() => onRetry(item.id)}>
            Retry
          </Button>
        ) : (
          <Button size="sm" onClick={() => onCover(item.id)} disabled={item.status !== 'shareable' || isCover}>
            Cover
          </Button>
        )}
        <Button size="sm" variant="danger" onClick={() => onDelete(item.id)}>
          Remove
        </Button>
      </div>
    </li>
  )
}

/** Every item carries its own state, which is what makes a slow one distinguishable from a dead one. */
function ItemFace({ item }: { item: Item }) {
  if (item.status === 'failed') {
    return (
      <span className="px-2 text-center text-fail" title={item.lastError ?? undefined}>
        could not be processed
      </span>
    )
  }
  // A thumbnail exists from the moment an item is backed up, whether or not the rest does.
  if (item.thumbUrl) {
    return <img src={item.thumbUrl} alt="" className="h-full w-full object-cover" loading="lazy" draggable={false} />
  }
  if (item.kind === 'file') {
    return <span className="px-2 text-center text-muted">{item.filename ?? 'kept, not rendered'}</span>
  }
  return <span className="animate-pulse text-muted">{item.status.replace('_', ' ')}</span>
}
