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
 * tasks' solid border) with a gray PENDING pill. Entries can be
 * reordered (↑↓), edited (✎), or dropped (✕). The lane holds only
 * not-yet-started plans: once an entry materialises into a task it
 * leaves the queue and lives in the task list. Faithful to layout and
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

  // Only PENDING entries appear in the lane. Materialised entries have
  // become tasks and left the queue; completed / dropped ones stay in the
  // row for audit but aren't actionable here.
  const live = queue
    .filter((q) => q.status === 'PENDING')
    .slice()
    .sort((a, b) => a.position - b.position);
  const pendingPositions = live.map((q) => q.position);
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

  // Move an entry one slot earlier/later among the PENDING positions.
  // The reorder API takes the desired order of those positions.
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
            isFirstPending={pendingPositions[0] === entry.position}
            isLastPending={pendingPositions[pendingPositions.length - 1] === entry.position}
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
        ↑↓ reorder · ✎ edit plan · ✕ drop · the trunk starts the <strong>next plan</strong> when
        a slot frees
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
  const base = entry.branchBase === 'STACKED_ON_PREVIOUS' ? 'stacked on previous' : 'off main';

  return (
    <div
      style={{ ...TK, opacity: dim }}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
    >
      <div style={CARD_TOP}>
        <span style={MARK}>◻</span>
        <div style={TK_TITLE}>{entry.title}</div>
      </div>
      <div style={TK_SUB}>
        <span style={BASE_TAG}>{base}</span>
        <span style={PILL_PENDING}>PENDING · pos {entry.position}</span>
      </div>
      <div style={{ ...ACTIONS, opacity: hover ? 1 : 0.45 }}>
        <button style={ICON_BTN} disabled={busy || isFirstPending} title="Move up" onClick={onUp}>
          ↑
        </button>
        <button style={ICON_BTN} disabled={busy || isLastPending} title="Move down" onClick={onDown}>
          ↓
        </button>
        <span style={ACTIONS_SPACER} />
        <button style={ICON_BTN} disabled={busy} title="Edit plan" onClick={onEdit}>✎</button>
        <button style={ICON_BTN_DANGER} disabled={busy} title="Drop" onClick={onDrop}>✕</button>
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
  position: 'relative', display: 'flex', flexDirection: 'column', gap: 7,
  padding: '9px 11px', borderRadius: 12,
  background: 'rgba(255,255,255,0.72)',
  border: '1px solid var(--border-soft)', borderLeft: '3px dashed var(--border-strong)',
};
const CARD_TOP: React.CSSProperties = {
  display: 'flex', alignItems: 'flex-start', gap: 8,
};
const MARK: React.CSSProperties = {
  flexShrink: 0, marginTop: 1, display: 'inline-flex', alignItems: 'center',
  justifyContent: 'center', fontSize: 12, color: '#9ca3af',
};
const TK_TITLE: React.CSSProperties = {
  flex: 1, minWidth: 0, fontSize: 12.5, fontWeight: 700, color: 'var(--text-secondary)',
  letterSpacing: '-0.01em', lineHeight: 1.3, wordBreak: 'break-word',
};
const TK_SUB: React.CSSProperties = {
  fontSize: 10, color: 'var(--text-muted)', display: 'flex', gap: 6,
  alignItems: 'center', flexWrap: 'wrap', paddingLeft: 20,
};
const BASE_TAG: React.CSSProperties = {
  color: 'var(--text-muted)', fontFamily: 'var(--font-mono)', fontSize: 9.5,
};
const ACTIONS: React.CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 5, paddingLeft: 20,
  transition: 'opacity .12s ease',
};
const ACTIONS_SPACER: React.CSSProperties = { flex: 1 };
const PILL_BASE: React.CSSProperties = {
  fontSize: 9.5, padding: '1px 7px', borderRadius: 999, fontWeight: 700, letterSpacing: '.04em',
};
const PILL_PENDING: React.CSSProperties = {
  ...PILL_BASE, background: 'var(--surface-soft)', color: 'var(--text-muted)',
  border: '1px solid var(--border-soft)',
};
const ICON_BTN: React.CSSProperties = {
  border: '1px solid var(--border-soft)', background: 'rgba(255,255,255,0.8)', borderRadius: 7,
  width: 22, height: 22, fontSize: 11, color: 'var(--text-secondary)', cursor: 'pointer',
  padding: 0, lineHeight: 1,
};
const ICON_BTN_DANGER: React.CSSProperties = {
  border: '1px solid rgba(239,68,68,0.28)', background: 'rgba(239,68,68,0.06)', borderRadius: 7,
  width: 22, height: 22, fontSize: 11, color: '#b91c1c', cursor: 'pointer', padding: 0, lineHeight: 1,
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
