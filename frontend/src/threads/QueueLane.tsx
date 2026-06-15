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
import { useState } from 'react';
import type { BranchBaseDto, QueuedTaskDto } from '../types';

/**
 * Trunk queue lane — the planned future tasks the trunk lined up, shown
 * below the "Tasks in this thread" lane. Dashed cards (vs the active
 * tasks' solid border); the head carries an amber QUEUED pill, the rest
 * a gray PENDING pill. PENDING entries can be reordered (↑↓), edited
 * (✎), or dropped (✕); a materialized head is pinned. Mirrors
 * docs/mockups/design/tasks/thread-trunk.png — faithful to layout and
 * hierarchy, not pixel-perfect.
 */
export function QueueLane(props: {
  threadId: string;
  queue: QueuedTaskDto[];
  parallelSlots: number;
  slotsInUse: number;
  onChanged: () => void;
}): React.ReactElement | null {
  const { threadId, queue, parallelSlots, slotsInUse, onChanged } = props;
  const [editing, setEditing] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Only live entries appear in the lane; completed / dropped ones drop
  // out (they stay in the row for audit but aren't actionable).
  const live = queue
    .filter((q) => q.status === 'PENDING' || q.status === 'MATERIALIZED')
    .slice()
    .sort((a, b) => a.position - b.position);
  const pendingPositions = live.filter((q) => q.status === 'PENDING').map((q) => q.position);
  const pendingCount = pendingPositions.length;

  // The trunk plans the queue (queue_task tool); the lane appears only
  // once there's something planned, and is a manage surface
  // (reorder / edit / drop) over it.
  if (live.length === 0) {
    return null;
  }

  async function run(fn: () => Promise<unknown>): Promise<void> {
    setBusy(true);
    setError(null);
    try {
      await fn();
      setEditing(null);
      onChanged();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  // Move a PENDING entry one slot earlier/later among the PENDING
  // positions; materialized entries stay pinned. The reorder API takes
  // the desired order of the current PENDING positions.
  function move(position: number, delta: -1 | 1): void {
    const order = [...pendingPositions];
    const idx = order.indexOf(position);
    const swap = idx + delta;
    if (idx < 0 || swap < 0 || swap >= order.length) {
      return;
    }
    [order[idx], order[swap]] = [order[swap], order[idx]];
    void run(() => window.bridge.queueReorder(threadId, order));
  }

  return (
    <div style={SEC}>
      <div style={SEC_H}>
        <span>Queue</span>
        <span style={SEC_R}>
          {pendingCount} pending · slot {slotsInUse}/{parallelSlots} in use
        </span>
      </div>

      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
        {live.map((entry, i) => (
          <QueueCard
            key={entry.position}
            entry={entry}
            dim={Math.max(0.62, 0.9 - i * 0.06)}
            isFirstPending={entry.status === 'PENDING' && pendingPositions[0] === entry.position}
            isLastPending={
              entry.status === 'PENDING'
              && pendingPositions[pendingPositions.length - 1] === entry.position
            }
            busy={busy}
            onEdit={() => { setError(null); setEditing(entry.position); }}
            onUp={() => move(entry.position, -1)}
            onDown={() => move(entry.position, 1)}
            onDrop={() => run(() => window.bridge.queueDrop(threadId, entry.position))}
          />
        ))}
      </div>

      {editing !== null && (
        <QueueEditor
          initial={live.find((q) => q.position === editing) ?? null}
          busy={busy}
          onCancel={() => { setEditing(null); setError(null); }}
          onSave={(title, branchBase, initialPrompt) => {
            void run(() =>
              window.bridge.queueEdit(threadId, editing, title, branchBase, initialPrompt));
          }}
        />
      )}

      {error && <div style={ERR}>{error}</div>}

      <div style={LANE_HINT}>
        ↑↓ reorder · ✎ edit plan · ✕ drop · trunk auto-runs <strong>pos 1</strong> when
        the current task ships
      </div>
    </div>
  );
}

function QueueCard(props: {
  entry: QueuedTaskDto;
  dim: number;
  isFirstPending: boolean;
  isLastPending: boolean;
  busy: boolean;
  onEdit: () => void;
  onUp: () => void;
  onDown: () => void;
  onDrop: () => void;
}): React.ReactElement {
  const { entry, dim, isFirstPending, isLastPending, busy, onEdit, onUp, onDown, onDrop } = props;
  const [hover, setHover] = useState(false);
  const materialized = entry.status === 'MATERIALIZED';
  const base = entry.branchBase === 'STACKED_ON_PREVIOUS' ? 'stacked on previous' : 'off main';

  return (
    <div
      style={{ ...TK, borderLeft: '3px dashed var(--border-strong)', opacity: dim }}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
    >
      <span style={MARK}>◻</span>
      <div style={{ minWidth: 0 }}>
        <div style={TK_TITLE}>{entry.title}</div>
        <div style={TK_SUB}>
          <span style={{ color: 'var(--text-muted)' }}>{base}</span>
          <span style={materialized ? PILL_QUEUED : PILL_PENDING}>
            {materialized ? 'QUEUED' : 'PENDING'} · pos {entry.position}
          </span>
        </div>
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 4, opacity: hover ? 1 : 0.25 }}>
        {!materialized && (
          <>
            <button
              style={ICON_BTN}
              disabled={busy || isFirstPending}
              title="Move up"
              onClick={onUp}
            >↑</button>
            <button
              style={ICON_BTN}
              disabled={busy || isLastPending}
              title="Move down"
              onClick={onDown}
            >↓</button>
            <button style={ICON_BTN} disabled={busy} title="Edit plan" onClick={onEdit}>✎</button>
            <button style={ICON_BTN} disabled={busy} title="Drop" onClick={onDrop}>✕</button>
          </>
        )}
        {materialized && <span style={{ fontSize: 10, color: 'var(--text-subtle)' }}>pinned</span>}
      </div>
    </div>
  );
}

function QueueEditor(props: {
  initial: QueuedTaskDto | null;
  busy: boolean;
  onCancel: () => void;
  onSave: (title: string, branchBase: BranchBaseDto, initialPrompt: string | null) => void;
}): React.ReactElement {
  const { initial, busy, onCancel, onSave } = props;
  const [title, setTitle] = useState(initial?.title ?? '');
  const [branchBase, setBranchBase] = useState<BranchBaseDto>(initial?.branchBase ?? 'MAIN');
  const [prompt, setPrompt] = useState(initial?.initialPrompt ?? '');

  return (
    <div style={EDITOR}>
      <input
        style={INPUT}
        placeholder="Task title"
        value={title}
        onChange={(e) => setTitle(e.target.value)}
        autoFocus
      />
      <select
        style={INPUT}
        value={branchBase}
        onChange={(e) => setBranchBase(e.target.value as BranchBaseDto)}
      >
        <option value="MAIN">off main</option>
        <option value="STACKED_ON_PREVIOUS">stacked on previous</option>
      </select>
      <textarea
        style={{ ...INPUT, minHeight: 56, resize: 'vertical', fontFamily: 'inherit' }}
        placeholder="Opening prompt (optional) — the agent's first turn"
        value={prompt}
        onChange={(e) => setPrompt(e.target.value)}
      />
      <div style={{ display: 'flex', gap: 7, justifyContent: 'flex-end' }}>
        <button style={CANCEL_BTN} disabled={busy} onClick={onCancel}>Cancel</button>
        <button
          style={SAVE_BTN}
          disabled={busy || title.trim() === ''}
          onClick={() => onSave(title.trim(), branchBase, prompt.trim() === '' ? null : prompt)}
        >{initial ? 'Save plan' : 'Add to queue'}</button>
      </div>
    </div>
  );
}

const SEC: React.CSSProperties = { display: 'flex', flexDirection: 'column', gap: 6 };
const SEC_H: React.CSSProperties = {
  fontSize: 9.5, fontWeight: 700, color: 'var(--text-muted)', textTransform: 'uppercase',
  letterSpacing: '.06em', padding: '0 2px', display: 'flex', alignItems: 'baseline',
};
const SEC_R: React.CSSProperties = {
  marginLeft: 'auto', color: 'var(--text-subtle)', fontWeight: 500, letterSpacing: 0,
  textTransform: 'none', fontSize: 10,
};
const TK: React.CSSProperties = {
  position: 'relative', display: 'grid', gridTemplateColumns: '18px 1fr auto', gap: 10,
  alignItems: 'center', padding: '10px 12px 10px 15px', borderRadius: 13,
  background: 'linear-gradient(157deg, rgba(255,255,255,0.96), rgba(255,255,255,0.62))',
  border: '1px solid var(--border-soft)', overflow: 'hidden',
};
const MARK: React.CSSProperties = {
  width: 18, height: 18, display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
  fontSize: 11, color: '#9ca3af',
};
const TK_TITLE: React.CSSProperties = {
  fontSize: 12.5, fontWeight: 700, color: 'var(--text-secondary)', letterSpacing: '-0.01em',
  overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
};
const TK_SUB: React.CSSProperties = {
  fontSize: 10, color: 'var(--text-muted)', marginTop: 4, display: 'flex', gap: 6,
  alignItems: 'center',
};
const PILL_BASE: React.CSSProperties = {
  fontSize: 9.5, padding: '1px 7px', borderRadius: 999, fontWeight: 700, letterSpacing: '.04em',
};
const PILL_QUEUED: React.CSSProperties = {
  ...PILL_BASE, background: 'rgba(245,158,11,0.10)', color: '#92400e', border: '1px solid #fcd34d',
};
const PILL_PENDING: React.CSSProperties = {
  ...PILL_BASE, background: 'var(--surface-soft)', color: 'var(--text-muted)',
  border: '1px solid var(--border-soft)',
};
const ICON_BTN: React.CSSProperties = {
  border: '1px solid var(--border-soft)', background: 'rgba(255,255,255,0.7)', borderRadius: 6,
  width: 20, height: 20, fontSize: 11, color: 'var(--text-muted)', cursor: 'pointer', padding: 0,
};
const LANE_HINT: React.CSSProperties = {
  fontSize: 10, color: 'var(--text-subtle)', padding: '3px 2px 0',
};
const EDITOR: React.CSSProperties = {
  display: 'flex', flexDirection: 'column', gap: 7, padding: 10,
  background: 'rgba(124,92,255,0.04)', border: '1px solid var(--primary-border)', borderRadius: 12,
};
const INPUT: React.CSSProperties = {
  border: '1px solid var(--border)', borderRadius: 8, padding: '6px 9px', fontSize: 12,
  color: 'var(--text-primary)', background: 'rgba(255,255,255,0.9)',
};
const CANCEL_BTN: React.CSSProperties = {
  border: '1px solid var(--border)', background: 'rgba(255,255,255,0.7)', borderRadius: 8,
  padding: '5px 12px', fontSize: 11.5, color: 'var(--text-secondary)', cursor: 'pointer',
};
const SAVE_BTN: React.CSSProperties = {
  border: 0, background: 'linear-gradient(135deg,#8b6cff,#7c5cff)', color: '#fff', borderRadius: 8,
  padding: '5px 12px', fontSize: 11.5, fontWeight: 700, cursor: 'pointer',
};
const ERR: React.CSSProperties = {
  fontSize: 11, color: '#b91c1c', background: 'rgba(239,68,68,0.08)',
  border: '1px solid rgba(239,68,68,0.25)', borderRadius: 8, padding: '6px 9px',
};
