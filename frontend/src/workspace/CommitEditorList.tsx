/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import { useCallback, useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react';
import type { EditableCommit } from './commitRewrite';
import { CommitAuthorAvatar, GripIcon, PencilIcon, commitDate, dayLabel } from './CommitEditorUi';
import { relative } from './WorkspaceRepoUi';

/** Where a drag would land: against a row's top/bottom edge to move, or
 *  over its middle to squash into it. */
type Drop = { id: string; mode: 'before' | 'after' | 'squash' } | null;

/** Pointer travel before a press counts as a drag rather than a click.
 *  Small enough to feel immediate, large enough that a shaky click on a
 *  row still selects instead of reordering it. */
const DRAG_THRESHOLD_PX = 5;
const EDGE_ZONE = 0.28;
const AUTOSCROLL_MARGIN_PX = 46;
const AUTOSCROLL_STEP_PX = 14;
/** How close to the bottom counts as "scrolled to the end". */
const LOAD_MORE_MARGIN_PX = 320;

type Props = {
  commits: EditableCommit[];
  /** Subset of `commits` passing the filter, same order. */
  visible: EditableCommit[];
  selectedIds: string[];
  /** Commits above this index are the LOCAL group — derived, never stored. */
  localCount: number;
  trackingRef: string | null;
  /** Reordering is off while a filter hides rows: the drop position would
   *  be ambiguous against the commits the user cannot see. */
  filtering: boolean;
  editable: boolean;
  editingId: string | null;
  onPick: (id: string, shiftKey: boolean) => void;
  onToggle: (id: string, shiftKey: boolean) => void;
  onStartEdit: (id: string) => void;
  onCommitEdit: (id: string, subject: string) => void;
  onCancelEdit: () => void;
  onMove: (ids: string[], targetId: string, mode: 'before' | 'after') => void;
  onSquashDrop: (ids: string[], targetId: string) => void;
  /** Fired as the scroll nears the bottom, to append the next page. */
  onReachEnd: () => void;
  loadingMore: boolean;
  hasMore: boolean;
};

export default function CommitEditorList({
  commits,
  visible,
  selectedIds,
  localCount,
  trackingRef,
  filtering,
  editable,
  editingId,
  onPick,
  onToggle,
  onStartEdit,
  onCommitEdit,
  onCancelEdit,
  onMove,
  onSquashDrop,
  onReachEnd,
  loadingMore,
  hasMore,
}: Props) {
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const ghostRef = useRef<HTMLDivElement | null>(null);
  const [drop, setDrop] = useState<Drop>(null);
  const [dragIds, setDragIds] = useState<string[]>([]);
  const [dragging, setDragging] = useState(false);
  // A drag ends with a click event on the row underneath; this swallows
  // it so dropping doesn't also re-select.
  const suppressClick = useRef(false);
  const gesture = useRef<{ ids: string[]; x: number; y: number } | null>(null);

  const dragEnabled = editable && !filtering;

  const stopDrag = useCallback(() => {
    gesture.current = null;
    document.body.style.userSelect = '';
    setDragging(false);
    setDragIds([]);
    setDrop(null);
  }, []);

  useEffect(() => () => { document.body.style.userSelect = ''; }, []);

  useEffect(() => {
    if (dragIds.length === 0) return undefined;
    const onMovePointer = (event: globalThis.PointerEvent) => {
      const start = gesture.current;
      if (start === null) return;
      const travel = Math.abs(event.clientX - start.x) + Math.abs(event.clientY - start.y);
      if (!dragging) {
        if (travel < DRAG_THRESHOLD_PX) return;
        document.body.style.userSelect = 'none';
        setDragging(true);
      }
      if (ghostRef.current !== null) {
        ghostRef.current.style.transform =
          `translate(${event.clientX + 14}px, ${event.clientY + 12}px)`;
      }
      autoScroll(scrollRef.current, event.clientY);
      setDrop(hitTest(event.clientY, start.ids));
    };
    const onUp = () => {
      const ids = gesture.current?.ids ?? [];
      const landed = drop;
      const wasDragging = dragging;
      stopDrag();
      if (wasDragging) {
        suppressClick.current = true;
        window.setTimeout(() => { suppressClick.current = false; }, 0);
      }
      if (landed === null || ids.length === 0) return;
      if (landed.mode === 'squash') onSquashDrop(ids, landed.id);
      else onMove(ids, landed.id, landed.mode);
    };
    window.addEventListener('pointermove', onMovePointer);
    window.addEventListener('pointerup', onUp);
    return () => {
      window.removeEventListener('pointermove', onMovePointer);
      window.removeEventListener('pointerup', onUp);
    };
  }, [dragIds, dragging, drop, onMove, onSquashDrop, stopDrag]);

  const armDrag = (id: string, event: ReactPointerEvent, fromGrip: boolean) => {
    if (!dragEnabled || event.button !== 0) return;
    const target = event.target as HTMLElement | null;
    if (!fromGrip && target !== null && target.closest('input, textarea, button') !== null) return;
    if (fromGrip) event.preventDefault();
    // Dragging any row of a multi-selection moves the whole set.
    const ids = selectedIds.includes(id) && selectedIds.length > 1
      ? commits.filter(c => selectedIds.includes(c.id)).map(c => c.id)
      : [id];
    gesture.current = { ids, x: event.clientX, y: event.clientY };
    setDragIds(ids);
    if (fromGrip) {
      document.body.style.userSelect = 'none';
      setDragging(true);
    }
  };

  // Paging is off while a filter is on: the visible rows are a subset, so
  // "near the bottom" says nothing about how much history is loaded.
  const onScroll = () => {
    const box = scrollRef.current;
    if (box === null || filtering || !hasMore || loadingMore) return;
    if (box.scrollTop + box.clientHeight >= box.scrollHeight - LOAD_MORE_MARGIN_PX) onReachEnd();
  };

  const localIds = new Set(commits.slice(0, localCount).map(c => c.id));
  const localRows = visible.filter(c => localIds.has(c.id));
  const pushedRows = visible.filter(c => !localIds.has(c.id));
  const rewritingPushed = commits.some(c => c.pushed && c.rewritten);

  const row = (commit: EditableCommit) => (
    <CommitRow
      key={commit.id}
      commit={commit}
      isLocal={localIds.has(commit.id)}
      selected={selectedIds.includes(commit.id)}
      editing={editingId === commit.id}
      dragged={dragging && dragIds.includes(commit.id)}
      drop={drop?.id === commit.id ? drop.mode : null}
      dragEnabled={dragEnabled}
      editable={editable}
      onPick={event => {
        if (suppressClick.current) return;
        onPick(commit.id, event.shiftKey);
      }}
      onToggle={shiftKey => onToggle(commit.id, shiftKey)}
      onStartEdit={() => onStartEdit(commit.id)}
      onCommitEdit={subject => onCommitEdit(commit.id, subject)}
      onCancelEdit={onCancelEdit}
      onPointerDownRow={event => armDrag(commit.id, event, false)}
      onPointerDownGrip={event => armDrag(commit.id, event, true)}
    />
  );

  let currentDay: string | null = null;
  return (
    <>
      <div className="wu-ce-list" ref={scrollRef} role="listbox" aria-multiselectable
        aria-label="Commits" onScroll={onScroll}>
        {localRows.length > 0 && (
          <div className="wu-ce-group wu-ce-group--local" role="presentation">
            <span>{localRows.length} LOCAL {localRows.length === 1 ? 'COMMIT' : 'COMMITS'}</span>
            <small>{rewritingPushed
              ? 'includes rewritten pushed commits'
              : `ahead of ${trackingRef ?? 'the remote'} · safe to rewrite`}</small>
            <i />
          </div>
        )}
        {localRows.map(row)}
        {localRows.length > 0 && pushedRows.length > 0 && (
          <div className="wu-ce-origin" role="presentation">
            <span>{(trackingRef ?? 'ORIGIN').toUpperCase()}</span>
            <i />
            <small>pushed history</small>
          </div>
        )}
        {pushedRows.map(commit => {
          const day = dayLabel(commitDate(commit));
          const heading = day === currentDay ? null : day;
          currentDay = day;
          return (
            <div key={`g-${commit.id}`} role="presentation">
              {heading !== null && (
                <div className="wu-ce-group" role="presentation"><span>{heading}</span><i /></div>
              )}
              {row(commit)}
            </div>
          );
        })}
        {loadingMore && (
          <p className="wu-ce-note" role="status">Loading more commits…</p>
        )}
        {!hasMore && !filtering && commits.length > 0 && (
          <p className="wu-ce-note">End of history.</p>
        )}
        {visible.length === 0 && (
          <div className="wu-ce-empty" role="presentation">
            <strong>No commits match this filter</strong>
            <span>Try a shorter word, or clear the author filter.</span>
          </div>
        )}
      </div>
      {dragging && (
        <div className="wu-ce-ghost" ref={ghostRef} aria-hidden>
          <GripIcon />
          {dragIds.length > 1
            ? `${dragIds.length} commits`
            : commits.find(c => c.id === dragIds[0])?.shortSha ?? ''}
          <b className={drop?.mode === 'squash' ? 'squash' : drop === null ? 'idle' : 'move'}>
            {drop?.mode === 'squash'
              ? 'squash'
              : drop === null ? 'drop on a commit to squash' : 'move'}
          </b>
        </div>
      )}
    </>
  );
}

function CommitRow({
  commit,
  isLocal,
  selected,
  editing,
  dragged,
  drop,
  dragEnabled,
  editable,
  onPick,
  onToggle,
  onStartEdit,
  onCommitEdit,
  onCancelEdit,
  onPointerDownRow,
  onPointerDownGrip,
}: {
  commit: EditableCommit;
  isLocal: boolean;
  selected: boolean;
  editing: boolean;
  dragged: boolean;
  drop: 'before' | 'after' | 'squash' | null;
  dragEnabled: boolean;
  editable: boolean;
  onPick: (event: { shiftKey: boolean }) => void;
  onToggle: (shiftKey: boolean) => void;
  onStartEdit: () => void;
  onCommitEdit: (subject: string) => void;
  onCancelEdit: () => void;
  onPointerDownRow: (event: ReactPointerEvent) => void;
  onPointerDownGrip: (event: ReactPointerEvent) => void;
}) {
  const [draft, setDraft] = useState(commit.subject);
  // Enter/Escape both tear the input down, and the blur that follows
  // would otherwise stage the edit a second time — or, after Escape,
  // stage the edit the user just cancelled.
  const settled = useRef(false);
  useEffect(() => {
    if (editing) {
      setDraft(commit.subject);
      settled.current = false;
    }
  }, [editing, commit.subject]);
  const settle = (action: () => void) => {
    if (settled.current) return;
    settled.current = true;
    action();
  };

  const badge = commit.squashedFrom > 0
    ? `squashed ${commit.squashedFrom}`
    : commit.reworded ? 'reworded' : null;
  const landedAt = commitDate(commit);

  return (
    <div className="wu-ce-rowwrap" role="presentation">
      {drop === 'before' && <span className="wu-ce-insert wu-ce-insert--before" aria-hidden />}
      <div
        data-commit-row={commit.id}
        className={`wu-ce-row${selected ? ' is-selected' : ''}${
          drop === 'squash' ? ' is-squash-target' : ''}${dragged ? ' is-dragged' : ''}`}
        role="option"
        aria-selected={selected}
        tabIndex={0}
        onClick={onPick}
        onKeyDown={event => {
          if (event.key === 'Enter' || event.key === ' ') {
            event.preventDefault();
            onPick(event);
          }
        }}
        onPointerDown={onPointerDownRow}
      >
        <span
          className={`wu-ce-check${selected ? ' is-on' : ''}`}
          role="checkbox"
          aria-checked={selected}
          aria-label={`Select ${commit.shortSha}`}
          tabIndex={0}
          title="Select · shift-click for a range"
          onClick={event => { event.stopPropagation(); onToggle(event.shiftKey); }}
          onKeyDown={event => {
            if (event.key === 'Enter' || event.key === ' ') {
              event.preventDefault();
              event.stopPropagation();
              onToggle(event.shiftKey);
            }
          }}
        >
          {selected && (
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3.6"
              strokeLinecap="round" strokeLinejoin="round" aria-hidden>
              <path d="M5 12.5 10 17.5 19 7" />
            </svg>
          )}
        </span>
        <span
          className={`wu-ce-grip${dragEnabled ? '' : ' is-off'}`}
          title={dragEnabled
            ? 'Drag to reorder · drop on a commit to squash'
            : 'Clear the filter to reorder'}
          onPointerDown={onPointerDownGrip}
          aria-hidden
        >
          <GripIcon />
        </span>
        <code className={isLocal ? 'is-local' : ''}>{commit.shortSha}</code>
        <span className="wu-ce-title">
          {editing ? (
            <>
              <input
                autoFocus
                value={draft}
                aria-label={`Rename ${commit.shortSha}`}
                onChange={event => setDraft(event.target.value)}
                onClick={event => event.stopPropagation()}
                onBlur={() => settle(() => onCommitEdit(draft))}
                onKeyDown={event => {
                  if (event.key === 'Enter') settle(() => onCommitEdit(draft));
                  if (event.key === 'Escape') settle(onCancelEdit);
                }}
              />
              <small>⏎ save · esc</small>
            </>
          ) : (
            <>
              <strong onDoubleClick={editable ? onStartEdit : undefined}
                title={editable ? 'Double-click to rename' : undefined}>{commit.subject}</strong>
              {selected && editable && (
                <button type="button" className="wu-ce-rename" title="Rename this commit"
                  onClick={event => { event.stopPropagation(); onStartEdit(); }}>
                  <PencilIcon />
                </button>
              )}
              {badge !== null && (
                <i className={commit.squashedFrom > 0 ? 'wu-ce-badge squash' : 'wu-ce-badge reword'}>
                  {badge}
                </i>
              )}
            </>
          )}
        </span>
        <span className="wu-ce-meta">
          <span className="wu-ce-stat">
            <b>+{commit.additions}</b> <em>−{commit.deletions}</em>
          </span>
          <CommitAuthorAvatar commit={commit} size={18} />
          <span className="wu-ce-author">{commit.authorName}</span>
          <time>{landedAt === null ? '' : relative(landedAt)}</time>
        </span>
      </div>
      {drop === 'squash' && (
        <span className="wu-ce-squash-flag">SQUASH INTO {commit.shortSha}</span>
      )}
      {drop === 'after' && <span className="wu-ce-insert wu-ce-insert--after" aria-hidden />}
    </div>
  );
}

/** Which row the pointer is over, and whether it's near an edge (move)
 *  or over the middle (squash). Rows already being dragged never accept
 *  their own drop. */
function hitTest(clientY: number, dragIds: string[]): Drop {
  for (const element of document.querySelectorAll('[data-commit-row]')) {
    const box = element.getBoundingClientRect();
    if (clientY < box.top || clientY > box.bottom) continue;
    const id = element.getAttribute('data-commit-row') ?? '';
    if (dragIds.includes(id)) return null;
    const ratio = (clientY - box.top) / Math.max(box.height, 1);
    return {
      id,
      mode: ratio < EDGE_ZONE ? 'before' : ratio > 1 - EDGE_ZONE ? 'after' : 'squash',
    };
  }
  return null;
}

function autoScroll(container: HTMLDivElement | null, clientY: number) {
  if (container === null) return;
  const box = container.getBoundingClientRect();
  if (clientY < box.top + AUTOSCROLL_MARGIN_PX) container.scrollTop -= AUTOSCROLL_STEP_PX;
  else if (clientY > box.bottom - AUTOSCROLL_MARGIN_PX) container.scrollTop += AUTOSCROLL_STEP_PX;
}
