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

export type GroupLayout = 1 | 2 | 3 | 4;

type Props = {
  tasks: TaskDto[];
  groups: TaskGroupDto[];
  /** Click a tile → open the full detail page. The tile preview is
   *  read-only; per-tile send boxes are deferred until we have a good
   *  story for managing N independent conversations on one screen. */
  onOpen: (taskId: string) => void;
  /** Reassign a task to another group (or null to unpin). The page
   *  parent persists the change and refreshes. */
  onMoveGroup: (taskId: string, groupId: string | null) => void | Promise<void>;
  /** Stop an active task from its tile. The page parent serialises
   *  the call and refreshes once the row flips to a terminal state. */
  onStop: (taskId: string) => void | Promise<void>;
  /** ID of the task whose Stop is currently in flight, so the tile
   *  can render a busy state and disable the button. */
  busyId: string | null;
  /** Number of fixed slots to show — 1 (full), 2 (left|right),
   *  3 (two on top, one centred below), 4 (2x2). Tasks past the
   *  visible slot count are hidden until the user reorders them
   *  into a visible slot via drag-and-drop. */
  layout: GroupLayout;
};

const TILE_PREVIEW_LIMIT = 8;
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
  tasks, groups, onOpen, onMoveGroup, onStop, busyId, layout,
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
  onDragStart: () => void;
  onDragEnter: () => void;
  onDragEnd: () => void;
  onDrop: () => void;
}) {
  const isTerminal = task.status === 'COMPLETED' || task.status === 'ERRORED';
  const recent = useMemo(
    () => messages.slice(-TILE_PREVIEW_LIMIT).filter(visibleInTile),
    [messages]);
  return (
    <article
      style={{
        ...tileStyle,
        ...(dragging ? tileDraggingStyle : null),
      }}
      onClick={onOpen}
      role="button"
      tabIndex={0}
      onKeyDown={e => { if (e.key === 'Enter') onOpen(); }}
      draggable
      onDragStart={e => {
        // text/plain payload satisfies browsers that refuse to start a
        // drag without one; the actual swap is driven by the parent's
        // dragFrom/dragOver state, not the payload.
        e.dataTransfer.effectAllowed = 'move';
        e.dataTransfer.setData('text/plain', task.id);
        onDragStart();
      }}
      onDragEnter={onDragEnter}
      onDragOver={e => { e.preventDefault(); e.dataTransfer.dropEffect = 'move'; }}
      onDrop={e => { e.preventDefault(); onDrop(); }}
      onDragEnd={onDragEnd}
    >
      <header style={tileHeaderStyle}>
        <span style={dragHandleStyle} aria-hidden title="Drag to reorder">⋮⋮</span>
        <div style={tileTitleWrapStyle}>
          <span style={{ ...tileStripeStyle, background: stripeColor(task.status) }} />
          <div style={tileTitleStyle}>{task.title}</div>
        </div>
        <div style={tileHeaderRightStyle}>
          <StatusBadge status={task.status} />
          <GroupMenu task={task} groups={groups} onChange={onMoveGroup} />
        </div>
      </header>
      <div style={tileBodyStyle}>
        {recent.length === 0 && (
          <div style={emptyPreviewStyle}>
            Waiting for the first turn…
          </div>
        )}
        {recent.map(m => (
          <MessageLine key={m.id} message={m} />
        ))}
      </div>
      <footer style={tileFooterStyle}>
        <span style={footerMetaStyle}>{task.model}</span>
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
            onClick={e => { e.stopPropagation(); void onStop(); }}
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

function MessageLine({ message }: { message: TaskMessageDto }) {
  const parsed = useMemo(() => safeParseContent(message), [message]);
  if (message.type === 'tool_call') {
    const tool = String(parsed?.toolName ?? 'tool');
    const summary = summariseToolInput(tool, parsed?.input);
    return (
      <div style={lineStyle}>
        <span style={{ ...lineGlyphStyle, color: toolColor(tool) }}>{tool}</span>
        <span style={lineBodyStyle}>{summary}</span>
      </div>
    );
  }
  if (message.type === 'tool_result') {
    const ok = parsed?.isError !== true;
    return (
      <div style={lineStyle}>
        <span style={{ ...lineGlyphStyle, color: ok ? '#047857' : '#b91c1c' }}>
          {ok ? '✓' : '✗'}
        </span>
        <span style={lineBodyStyle}>{summariseResult(parsed)}</span>
      </div>
    );
  }
  if (message.type === 'text' && message.role === 'assistant') {
    const text = String(parsed?.text ?? '');
    return (
      <div style={lineStyle}>
        <span style={{ ...lineGlyphStyle, color: 'var(--text-3)' }}>›</span>
        <span style={{ ...lineBodyStyle, color: 'var(--text-1)' }}>{truncate(text, 160)}</span>
      </div>
    );
  }
  if (message.type === 'text' && message.role === 'user') {
    const text = String(parsed?.text ?? '');
    return (
      <div style={lineStyle}>
        <span style={{ ...lineGlyphStyle, color: 'var(--accent)' }}>you</span>
        <span style={{ ...lineBodyStyle, color: 'var(--text-2)' }}>{truncate(text, 160)}</span>
      </div>
    );
  }
  if (message.type === 'permission_request') {
    const tool = String(parsed?.toolName ?? 'tool');
    return (
      <div style={lineStyle}>
        <span style={{ ...lineGlyphStyle, color: '#d97706' }}>?</span>
        <span style={lineBodyStyle}>
          awaiting approval for <strong>{tool}</strong>
        </span>
      </div>
    );
  }
  return null;
}

function StatusBadge({ status }: { status: TaskStatusDto }) {
  const palette: Record<TaskStatusDto, { fg: string; bg: string; label: string }> = {
    RUNNING:   { fg: '#fff', bg: '#10b981', label: 'RUN' },
    AWAITING:  { fg: '#fff', bg: '#d97706', label: 'WAIT' },
    PENDING:   { fg: '#374151', bg: '#e5e7eb', label: 'QUEUED' },
    IDLE:      { fg: '#374151', bg: '#e5e7eb', label: 'IDLE' },
    COMPLETED: { fg: '#fff', bg: '#047857', label: 'DONE' },
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

/** Keep the tile preview to entries that carry signal — drop the
 *  bookkeeping events that bloat the message log but don't help a
 *  reader skim what's happening. */
function visibleInTile(m: TaskMessageDto): boolean {
  return m.type === 'tool_call'
      || m.type === 'tool_result'
      || m.type === 'text'
      || m.type === 'permission_request';
}

function safeParseContent(m: TaskMessageDto): Record<string, unknown> | null {
  try {
    return JSON.parse(m.contentJson) as Record<string, unknown>;
  }
  catch {
    return null;
  }
}

function summariseToolInput(tool: string, input: unknown): string {
  if (!input || typeof input !== 'object') return '';
  const obj = input as Record<string, unknown>;
  // Best-effort prettifier covering the most common Claude Code tools.
  if (typeof obj.file_path === 'string') return obj.file_path;
  if (typeof obj.path === 'string') return obj.path;
  if (typeof obj.pattern === 'string') return obj.pattern;
  if (typeof obj.command === 'string') return truncate(obj.command, 120);
  if (typeof obj.description === 'string') return truncate(obj.description, 120);
  return '';
}

function summariseResult(parsed: Record<string, unknown> | null): string {
  if (!parsed) return '';
  if (typeof parsed.summary === 'string') return truncate(parsed.summary, 140);
  if (typeof parsed.output === 'string') return truncate(parsed.output, 140);
  return '';
}

function toolColor(tool: string): string {
  switch (tool.toLowerCase()) {
    case 'read':  return '#2563eb';
    case 'write': return '#7c3aed';
    case 'edit':  return '#9333ea';
    case 'bash':  return '#0f766e';
    case 'grep':  return '#0891b2';
    case 'glob':  return '#0891b2';
    default:      return '#374151';
  }
}

function stripeColor(s: TaskStatusDto): string {
  switch (s) {
    case 'RUNNING':   return '#10b981';
    case 'AWAITING':  return '#d97706';
    case 'IDLE':      return '#9ca3af';
    case 'PENDING':   return '#9ca3af';
    case 'COMPLETED': return '#047857';
    case 'ERRORED':   return '#dc2626';
  }
}

function truncate(s: string, n: number): string {
  return s.length > n ? `${s.slice(0, n - 1)}…` : s;
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
const tileBodyStyle: React.CSSProperties = {
  flex: 1,
  padding: '10px 14px',
  display: 'flex',
  flexDirection: 'column',
  gap: 6,
  overflowY: 'auto',
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  fontSize: 12,
  background: 'var(--bg-elevated)',
};
const emptyPreviewStyle: React.CSSProperties = {
  fontFamily: 'inherit',
  fontSize: 12,
  color: 'var(--text-4)',
  fontStyle: 'italic',
  padding: '20px 0',
  textAlign: 'center',
};
const lineStyle: React.CSSProperties = {
  display: 'flex',
  gap: 8,
  alignItems: 'baseline',
  lineHeight: 1.4,
};
const lineGlyphStyle: React.CSSProperties = {
  fontSize: 11,
  fontWeight: 700,
  textTransform: 'uppercase',
  flexShrink: 0,
  minWidth: 36,
};
const lineBodyStyle: React.CSSProperties = {
  color: 'var(--text-2)',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
  flex: 1,
  minWidth: 0,
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
