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
import { useEffect, useMemo, useState } from 'react';
import type { TaskDto, TaskGroupDto, TaskMessageDto, TaskStatusDto } from '../types';
import GroupMenu from './GroupMenu';
import { type PendingPermission } from './ConversationPane';
import { StructuredConversation } from './StructuredConversation';
import RepoAvatar from './RepoAvatar';

export type GroupLayout = 1 | 2 | 3 | 4;

type Props = {
  tasks: TaskDto[];
  groups: TaskGroupDto[];
  /** Open the full detail page for a task. The tile title
   *  becomes the click target so clicks inside the tile body
   *  (typing a reply, scrolling the history) don't navigate. */
  onOpen: (taskId: string) => void;
  /** Reassign a task to another group (or null to unpin). The page
   *  parent persists the change and refreshes. */
  onMoveGroup: (taskId: string, groupId: string | null) => void | Promise<void>;
  /** Stop an active task from its tile. The page parent serialises
   *  the call and refreshes once the row flips to a terminal state. */
  onStop: (taskId: string) => void | Promise<void>;
  /** Send a follow-up turn to one of the tiles' tasks. */
  onSend: (taskId: string, input: string) => void | Promise<void>;
  /** Cancel the in-flight turn on one of the tiles' tasks. */
  onInterrupt: (taskId: string) => void | Promise<void>;
  /** Reply to a pending permission_request surfaced in a tile.
   *  Optional {@code preApprove} grants an auto-allow budget for the
   *  same tool — used by the "Allow next 5/10/50/Always" buttons. */
  onDecide: (
    taskId: string,
    callId: string,
    decision: 'ALLOW' | 'DENY',
    preApprove?: { toolName: string; count: number },
  ) => void | Promise<void>;
  /** ID of the task whose Stop is currently in flight, so the tile
   *  can render a busy state and disable the button. */
  busyId: string | null;
  /** Number of fixed slots to show — 1 (full), 2 (left|right),
   *  3 (two on top, one centred below), 4 (2x2). Tasks past the
   *  visible slot count are hidden until the user reorders them
   *  into a visible slot via drag-and-drop. */
  layout: GroupLayout;
};

const POLL_MS = 4000;

/**
 * Fixed-slot grid of large task tiles, one per group member.
 * Each tile mirrors the structured-detail layout in miniature:
 * status header, scrollable recent-activity bullets, footer with
 * runtime/cost. Click anywhere to jump to the full detail page.
 *
 * <p>The {@code layout} prop picks how many tiles are visible:
 * 1 (full pane), 2 (left | right), 3 (two on top + one centred
 * below), or 4 (2×2). Tiles can be dragged onto another slot to
 * swap positions — useful for re-arranging which task gets the
 * larger / more central slot. Tasks past the visible slot count
 * are hidden until the user swaps one in.
 *
 * <p>Faithfully follows {@code docs/mockups/design/tasks/tasks-group.png}
 * minus the per-tile send box, which would require running N
 * conversations in parallel and is out of scope for this slice.
 */
export default function GroupTaskGrid({
  tasks, groups, onOpen, onMoveGroup, onStop, onSend, onInterrupt, onDecide,
  busyId, layout,
}: Props) {
  const [previews, setPreviews] = useState<Record<string, TaskMessageDto[]>>({});

  // User-overridden slot order — sticky across renders so a drag swap
  // doesn't get undone by the next status poll. Stored as a list of
  // task ids whose position in the array IS the slot index. Tasks not
  // listed here fall through to the natural ordering.
  const [order, setOrder] = useState<string[]>([]);
  const [dragFrom, setDragFrom] = useState<number | null>(null);
  const [dragOver, setDragOver] = useState<number | null>(null);

  // Garbage-collect order entries pointing at tasks that left the
  // group (deleted / un-grouped / moved). Stops the slot from going
  // blank or pointing at a stale row.
  useEffect(() => {
    setOrder(prev => prev.filter(id => tasks.some(t => t.id === id)));
  }, [tasks]);

  // Final visible order: user-pinned ids first (preserving their
  // chosen slot), then unpinned tasks fill remaining slots in the
  // parent's already-sorted order (active-first).
  const ordered = useMemo(() => {
    const pinned = order
      .map(id => tasks.find(t => t.id === id))
      .filter((t): t is TaskDto => t != null);
    const rest = tasks.filter(t => !order.includes(t.id));
    return [...pinned, ...rest];
  }, [tasks, order]);

  // Fan-out: pull recent messages for each visible tile in parallel.
  // Hidden tasks (past the layout cap) don't get their previews
  // refreshed — saves polling work and they're invisible anyway.
  const visible = ordered.slice(0, layout);
  const hiddenCount = Math.max(0, ordered.length - layout);
  // Cache key for the polling effect: a stable join of visible task
  // ids so re-renders (status polls, hover state, etc.) don't tear
  // down the interval just because the array identity changed.
  const visibleIdsKey = visible.map(t => t.id).join('|');

  useEffect(() => {
    let cancelled = false;
    const ids = visibleIdsKey ? visibleIdsKey.split('|') : [];
    async function refresh() {
      const results = await Promise.all(ids.map(async id => {
        try {
          const ms = await window.bridge.getTaskMessages(id);
          return [id, ms] as const;
        }
        catch {
          return [id, [] as TaskMessageDto[]] as const;
        }
      }));
      if (cancelled) return;
      const next: Record<string, TaskMessageDto[]> = {};
      for (const [id, ms] of results) next[id] = ms;
      setPreviews(next);
    }
    void refresh();
    const handle = window.setInterval(() => { void refresh(); }, POLL_MS);
    return () => { cancelled = true; window.clearInterval(handle); };
  }, [visibleIdsKey]);

  if (tasks.length === 0) {
    return (
      <div style={emptyStyle}>
        <div style={emptyTitleStyle}>No tasks in this group yet</div>
        <div style={mutedStyle}>
          Use <strong>+ Add task</strong> above to start one — it'll be
          pinned to this group automatically.
        </div>
      </div>
    );
  }

  function performSwap(fromIdx: number, toIdx: number) {
    if (fromIdx === toIdx) return;
    const next = [...ordered];
    const tmp = next[fromIdx];
    next[fromIdx] = next[toIdx];
    next[toIdx] = tmp;
    // Persist the swap by writing the new ids in slot order. Only the
    // visible slots matter — tasks past the layout cap stay hidden,
    // but we keep their ids in the trailing positions so re-expanding
    // the layout preserves their existing order.
    setOrder(next.map(t => t.id));
  }

  return (
    <>
      <div style={layoutGridStyle(layout)}>
        {visible.map((t, idx) => (
          <div
            key={t.id}
            style={{
              ...slotStyle(layout, idx),
              ...(dragOver === idx && dragFrom !== idx ? slotDropTargetStyle : null),
            }}
          >
            <TaskTile
              task={t}
              groups={groups}
              messages={previews[t.id] ?? []}
              busy={busyId === t.id}
              dragging={dragFrom === idx}
              onOpen={() => onOpen(t.id)}
              onMoveGroup={onMoveGroup}
              onStop={() => onStop(t.id)}
              onSend={input => onSend(t.id, input)}
              onInterrupt={() => onInterrupt(t.id)}
              onDecide={(callId, decision, preApprove) =>
                onDecide(t.id, callId, decision, preApprove)}
              onDragStart={() => setDragFrom(idx)}
              onDragEnter={() => setDragOver(idx)}
              onDragEnd={() => { setDragFrom(null); setDragOver(null); }}
              onDrop={() => {
                if (dragFrom != null) performSwap(dragFrom, idx);
                setDragFrom(null);
                setDragOver(null);
              }}
            />
          </div>
        ))}
      </div>
      {hiddenCount > 0 && (
        <div style={hiddenHintStyle}>
          +{hiddenCount} more task{hiddenCount === 1 ? '' : 's'} hidden —
          bump the layout above or drag one of the visible tiles to
          rearrange.
        </div>
      )}
    </>
  );
}

function TaskTile({
  task, groups, messages, busy, dragging,
  onOpen, onMoveGroup, onStop,
  onSend, onInterrupt, onDecide,
  onDragStart, onDragEnter, onDragEnd, onDrop,
}: {
  task: TaskDto;
  groups: TaskGroupDto[];
  messages: TaskMessageDto[];
  busy: boolean;
  dragging: boolean;
  onOpen: () => void;
  onMoveGroup: (taskId: string, groupId: string | null) => void | Promise<void>;
  onStop: () => void | Promise<void>;
  onSend: (input: string) => void | Promise<void>;
  onInterrupt: () => void | Promise<void>;
  onDecide: (
    callId: string,
    decision: 'ALLOW' | 'DENY',
    preApprove?: { toolName: string; count: number },
  ) => void | Promise<void>;
  onDragStart: () => void;
  onDragEnter: () => void;
  onDragEnd: () => void;
  onDrop: () => void;
}) {
  const isTerminal = task.status === 'COMPLETED' || task.status === 'ERRORED';
  const isRunning = task.status === 'RUNNING';
  const [draft, setDraft] = useState('');
  const [sending, setSending] = useState(false);
  const pendingPermission = useMemo(() => findPendingPermission(messages), [messages]);

  async function submit() {
    const text = draft.trim();
    if (!text || sending) return;
    setSending(true);
    try {
      await onSend(text);
      setDraft('');
    }
    finally {
      setSending(false);
    }
  }

  return (
    <article
      style={{
        ...tileStyle,
        ...(dragging ? tileDraggingStyle : null),
      }}
      onDragEnter={onDragEnter}
      onDragOver={e => { e.preventDefault(); e.dataTransfer.dropEffect = 'move'; }}
      onDrop={e => { e.preventDefault(); onDrop(); }}
      onDragEnd={onDragEnd}
    >
      <header
        style={tileHeaderStyle}
        draggable
        onDragStart={e => {
          // text/plain payload satisfies browsers that refuse to
          // start a drag without one; the actual swap is driven by
          // the parent's dragFrom/dragOver state, not the payload.
          e.dataTransfer.effectAllowed = 'move';
          e.dataTransfer.setData('text/plain', task.id);
          onDragStart();
        }}
      >
        <span style={dragHandleStyle} aria-hidden title="Drag header to reorder">⋮⋮</span>
        <div style={tileTitleWrapStyle}>
          <span style={{ ...tileStripeStyle, background: stripeColor(task.status) }} />
          <RepoAvatar workingDir={task.workingDir} size={18} />
          <button
            type="button"
            onClick={onOpen}
            style={tileTitleBtnStyle}
            title="Open in full detail view"
          >
            <span style={tileTitleStyle}>{task.title}</span>
          </button>
        </div>
        <div style={tileHeaderRightStyle}>
          <StatusBadge status={task.status} />
          <GroupMenu task={task} groups={groups} onChange={onMoveGroup} />
        </div>
      </header>

      <div style={tileConversationStyle}>
        <StructuredConversation
          messages={messages}
          pendingPermission={pendingPermission}
          onDecide={onDecide}
          modelName={task.model}
        />
      </div>

      {!isTerminal && (
        <div style={tileReplyStyle}>
          <textarea
            value={draft}
            onChange={e => setDraft(e.target.value)}
            placeholder={isRunning
              ? 'message — will queue for after current turn…'
              : 'send a follow-up turn…'}
            disabled={sending}
            rows={1}
            onKeyDown={e => {
              if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
                e.preventDefault();
                void submit();
              }
            }}
            style={tileReplyTextareaStyle}
          />
          <div style={tileReplyActionsStyle}>
            {isRunning && (
              <button
                type="button"
                onClick={() => void onInterrupt()}
                style={tileInterruptBtnStyle}
                title="Cancel the current turn"
              >
                ⏵ Interrupt
              </button>
            )}
            <button
              type="button"
              onClick={() => void submit()}
              disabled={!draft.trim() || sending}
              style={tileSendBtnStyle}
            >
              {sending ? 'sending…' : (isRunning ? 'Queue →' : 'Send →')}
            </button>
          </div>
        </div>
      )}

      <footer style={tileFooterStyle}>
        <span style={footerMetaStyle}>{task.model || 'unknown'}</span>
        <span style={footerSepStyle}>·</span>
        <span style={footerMetaStyle}>{formatAge(task.updatedAt)}</span>
        <div style={{ flex: 1 }} />
        <span style={footerMetricStyle}>{formatCost(task.costUsdMilli)}</span>
        <span style={footerSepStyle}>·</span>
        <span style={footerMetricStyle}>
          {formatTokens(task.tokensIn + task.tokensOut)} tok
        </span>
        {!isTerminal && (
          <button
            type="button"
            onClick={() => void onStop()}
            disabled={busy}
            style={stopBtnStyle}
            title="Stop and release the agent"
          >
            {busy ? 'Stopping…' : 'Stop'}
          </button>
        )}
      </footer>
    </article>
  );
}

/** Walk the message log backwards to find the most recent
 *  permission_request whose callId hasn't yet been answered by a
 *  permission_decision. Mirrors the detail-page helper so a tile
 *  surfaces approval prompts the same way the full page does. */
function findPendingPermission(messages: TaskMessageDto[]): PendingPermission | null {
  const decided = new Set<string>();
  for (let i = messages.length - 1; i >= 0; i--) {
    const m = messages[i];
    if (m.type === 'permission_decision') {
      try {
        const cid = (JSON.parse(m.contentJson) as { callId?: string }).callId;
        if (cid) decided.add(cid);
      }
      catch { /* ignore */ }
    }
    if (m.type === 'permission_request') {
      try {
        const p = JSON.parse(m.contentJson) as { callId?: string; toolName?: string; summary?: string };
        if (p.callId && !decided.has(p.callId)) {
          return { callId: p.callId, toolName: p.toolName ?? 'tool', summary: p.summary ?? '' };
        }
      }
      catch { /* ignore */ }
    }
  }
  return null;
}

function StatusBadge({ status }: { status: TaskStatusDto }) {
  const palette: Record<TaskStatusDto, { fg: string; bg: string; label: string }> = {
    RUNNING:   { fg: '#fff', bg: '#10b981', label: 'RUN' },
    AWAITING:  { fg: '#fff', bg: '#d97706', label: 'WAIT' },
    PENDING:   { fg: '#374151', bg: '#e5e7eb', label: 'QUEUED' },
    IDLE:      { fg: '#374151', bg: '#e5e7eb', label: 'IDLE' },
    COMPLETED: { fg: '#fff', bg: '#64748b', label: 'DONE' },
    ERRORED:   { fg: '#fff', bg: '#dc2626', label: 'ERR' },
  };
  const p = palette[status];
  return (
    <span style={{
      display: 'inline-flex',
      alignItems: 'center',
      gap: 6,
      padding: '2px 10px',
      borderRadius: 999,
      background: p.bg,
      color: p.fg,
      fontSize: 11,
      fontWeight: 700,
      letterSpacing: 0.4,
    }}>
      {status === 'RUNNING' && <span style={pulseDotStyle} />}
      {p.label}
    </span>
  );
}

// ────────────────────────────────────────────────────────────────────
// Helpers
// ────────────────────────────────────────────────────────────────────

function stripeColor(s: TaskStatusDto): string {
  switch (s) {
    case 'RUNNING':   return '#10b981';
    case 'AWAITING':  return '#d97706';
    case 'IDLE':      return '#9ca3af';
    case 'PENDING':   return '#9ca3af';
    case 'COMPLETED': return '#64748b';
    case 'ERRORED':   return '#dc2626';
  }
}

function formatAge(iso: string): string {
  const ms = Date.now() - new Date(iso).getTime();
  const s = Math.round(ms / 1000);
  if (s < 60) return `${s}s ago`;
  const m = Math.round(s / 60);
  if (m < 60) return `${m}m ago`;
  const h = Math.round(m / 60);
  if (h < 24) return `${h}h ago`;
  return `${Math.round(h / 24)}d ago`;
}

function formatCost(milli: number): string {
  if (!milli) return '$0.00';
  return `$${(milli / 1000).toFixed(milli < 100 ? 4 : 2)}`;
}

function formatTokens(n: number): string {
  if (n < 1000) return String(n);
  if (n < 1000_000) return `${(n / 1000).toFixed(1)}k`;
  return `${(n / 1000_000).toFixed(1)}M`;
}

// ────────────────────────────────────────────────────────────────────
// Styles
// ────────────────────────────────────────────────────────────────────

/** Tall, viewport-anchored grid for the group view. Heights are
 *  computed so the tiles fill the same vertical slot the conversation
 *  pane occupies on the detail page, regardless of how many slots
 *  the user picks. The {@code minmax(0, ...)} on the row tracks lets
 *  tiles shrink inside their slot instead of pushing the grid taller
 *  than the viewport. */
function layoutGridStyle(layout: GroupLayout): React.CSSProperties {
  const base: React.CSSProperties = {
    display: 'grid',
    gap: 16,
    height: 'calc(100vh - 240px)',
    minHeight: 320,
  };
  switch (layout) {
    case 1:
      return { ...base, gridTemplateColumns: '1fr', gridTemplateRows: '1fr' };
    case 2:
      return { ...base, gridTemplateColumns: '1fr 1fr', gridTemplateRows: '1fr' };
    case 3:
      // 4 col × 2 row scaffold so the bottom tile (col 2-3) sits
      // centred between the two top tiles (col 1-2 and 3-4). All
      // three render at the same effective width since each
      // visible slot spans two of the four columns.
      return { ...base,
        gridTemplateColumns: 'repeat(4, 1fr)',
        gridTemplateRows: 'minmax(0, 1fr) minmax(0, 1fr)' };
    case 4:
      return { ...base,
        gridTemplateColumns: '1fr 1fr',
        gridTemplateRows: 'minmax(0, 1fr) minmax(0, 1fr)' };
  }
}

function slotStyle(layout: GroupLayout, idx: number): React.CSSProperties {
  // 3-slot layout's bottom tile centres on the 4-col scaffold.
  if (layout === 3) {
    if (idx === 0) return { gridColumn: '1 / span 2', gridRow: '1' };
    if (idx === 1) return { gridColumn: '3 / span 2', gridRow: '1' };
    if (idx === 2) return { gridColumn: '2 / span 2', gridRow: '2' };
  }
  // 1/2/4 layouts: tiles flow naturally — the grid template handles
  // the placement, so each slot is just a plain container.
  return { display: 'flex', minHeight: 0, minWidth: 0 };
}

const slotDropTargetStyle: React.CSSProperties = {
  outline: '2px dashed var(--accent)',
  outlineOffset: -2,
  borderRadius: 10,
};

const tileStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  background: 'var(--bg-card)',
  border: '1px solid var(--border)',
  borderRadius: 10,
  overflow: 'hidden',
  cursor: 'pointer',
  transition: 'border-color 0.12s ease, box-shadow 0.12s ease, opacity 0.12s ease',
  width: '100%',
  height: '100%',
  minHeight: 0,
  minWidth: 0,
};
const tileDraggingStyle: React.CSSProperties = {
  opacity: 0.5,
};
const dragHandleStyle: React.CSSProperties = {
  color: 'var(--text-4)',
  fontSize: 14,
  cursor: 'grab',
  userSelect: 'none',
  flexShrink: 0,
  lineHeight: 1,
  letterSpacing: -2,
};
const hiddenHintStyle: React.CSSProperties = {
  marginTop: 12,
  padding: '8px 12px',
  background: 'var(--bg-elevated)',
  border: '1px dashed var(--border)',
  borderRadius: 6,
  fontSize: 12,
  color: 'var(--text-3)',
  textAlign: 'center',
};
const tileHeaderStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'flex-start',
  gap: 12,
  padding: '12px 14px',
  borderBottom: '1px solid var(--border-light)',
};
const tileHeaderRightStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 4,
  flexShrink: 0,
};
const tileTitleWrapStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'flex-start',
  gap: 10,
  minWidth: 0,
  flex: 1,
};
const tileStripeStyle: React.CSSProperties = {
  width: 3,
  alignSelf: 'stretch',
  borderRadius: 2,
  flexShrink: 0,
};
const tileTitleStyle: React.CSSProperties = {
  fontSize: 14,
  fontWeight: 600,
  color: 'var(--text-1)',
  lineHeight: 1.35,
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  display: '-webkit-box',
  WebkitLineClamp: 2,
  WebkitBoxOrient: 'vertical',
};
const tileTitleBtnStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
  textAlign: 'left',
  background: 'transparent',
  border: 'none',
  padding: 0,
  cursor: 'pointer',
  color: 'inherit',
  font: 'inherit',
};
const tileConversationStyle: React.CSSProperties = {
  flex: 1,
  minHeight: 0,
  display: 'flex',
  flexDirection: 'column',
};
const tileReplyStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'flex-end',
  gap: 6,
  padding: '8px 10px',
  borderTop: '1px solid var(--border-light)',
  background: 'var(--bg-elevated)',
  flexShrink: 0,
};
const tileReplyTextareaStyle: React.CSSProperties = {
  flex: 1,
  minHeight: 28,
  maxHeight: 120,
  resize: 'none',
  padding: '6px 8px',
  fontFamily: 'inherit',
  fontSize: 12,
  background: 'var(--bg-input)',
  color: 'var(--text-1)',
  border: '1px solid var(--border-input)',
  borderRadius: 6,
  outline: 'none',
};
const tileReplyActionsStyle: React.CSSProperties = {
  display: 'flex',
  gap: 4,
  flexShrink: 0,
};
const tileInterruptBtnStyle: React.CSSProperties = {
  padding: '4px 8px',
  background: 'transparent',
  color: 'var(--accent-dark)',
  border: '1px solid var(--accent-a40)',
  borderRadius: 5,
  fontSize: 11,
  fontWeight: 600,
  cursor: 'pointer',
};
const tileSendBtnStyle: React.CSSProperties = {
  padding: '4px 12px',
  background: 'var(--accent)',
  color: '#fff',
  border: 'none',
  borderRadius: 5,
  fontSize: 11.5,
  fontWeight: 600,
  cursor: 'pointer',
};
const tileFooterStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 6,
  padding: '8px 14px',
  borderTop: '1px solid var(--border-light)',
  background: 'var(--bg-card)',
  fontSize: 11,
};
const footerMetaStyle: React.CSSProperties = { color: 'var(--text-3)' };
const footerSepStyle: React.CSSProperties = { color: 'var(--text-4)' };
const footerMetricStyle: React.CSSProperties = {
  color: 'var(--text-2)',
  fontWeight: 600,
  fontVariantNumeric: 'tabular-nums',
};
const stopBtnStyle: React.CSSProperties = {
  marginLeft: 8,
  padding: '3px 10px',
  background: 'transparent',
  color: '#dc2626',
  border: '1px solid #fca5a5',
  borderRadius: 4,
  fontSize: 11,
  fontWeight: 600,
  cursor: 'pointer',
};
const pulseDotStyle: React.CSSProperties = {
  width: 6,
  height: 6,
  borderRadius: '50%',
  background: '#fff',
};
const emptyStyle: React.CSSProperties = {
  padding: '40px 24px',
  textAlign: 'center',
  border: '1px dashed #d1d5db',
  borderRadius: 8,
};
const emptyTitleStyle: React.CSSProperties = { fontSize: 16, fontWeight: 600, marginBottom: 4 };
const mutedStyle: React.CSSProperties = { color: '#6b7280', fontSize: 13 };
