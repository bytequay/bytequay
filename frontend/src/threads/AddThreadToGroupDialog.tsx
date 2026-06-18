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
import { useEffect, useMemo, useRef, useState } from 'react';
import type { ThreadDto, ThreadGroupDto } from '../types';

type Props = {
  group: ThreadGroupDto;
  /** Every thread in the workspace. The dialog filters out threads
   *  already in this group (and any not-yet-pinnable terminal
   *  ones) on its own. */
  allTasks: ThreadDto[];
  /** Group IDs each thread belongs to — used to hide threads that are
   *  already members of this group. */
  groupIdsByTaskId: Map<string, string[]>;
  onClose: () => void;
  /** Caller routes to the create-thread page with this group pre-
   *  selected. The dialog closes itself before invoking so the
   *  navigation isn't competing with a fading overlay. */
  onCreateNew: () => void;
  /** Add an existing thread into the group. Returning a promise
   *  lets the dialog flip into a busy state while the membership
   *  POST is in flight. */
  onAddExisting: (threadId: string) => Promise<void>;
};

/**
 * Two-path picker shown when the user clicks "+ Add thread" in the
 * group rail. The top row jumps to the create-thread page; the list
 * below lets the user pull in a thread that already exists. Single-
 * pick: tap a row, the thread is added, the dialog closes.
 */
export default function AddThreadToGroupDialog({
  group, allTasks, groupIdsByTaskId,
  onClose, onCreateNew, onAddExisting,
}: Props) {
  const [search, setSearch] = useState('');
  const [busyId, setBusyId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const searchRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    searchRef.current?.focus();
  }, []);

  // Esc closes — covers the case where the user opened the dialog
  // by accident and wants out without a click.
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') {
        e.preventDefault();
        onClose();
      }
    }
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  const eligible = useMemo(() => {
    return allTasks.filter(t => {
      const ids = groupIdsByTaskId.get(t.id) ?? [];
      return !ids.includes(group.id);
    });
  }, [allTasks, groupIdsByTaskId, group.id]);

  const matched = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (q === '') return eligible;
    return eligible.filter(t => t.title.toLowerCase().includes(q));
  }, [eligible, search]);

  async function pick(threadId: string) {
    setBusyId(threadId);
    setError(null);
    try {
      await onAddExisting(threadId);
      onClose();
    }
    catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setBusyId(null);
    }
  }

  return (
    <div style={overlayStyle} onClick={onClose}>
      <div
        style={dialogStyle}
        onClick={e => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-label={`Add a thread to ${group.name}`}
      >
        <header style={headerStyle}>
          <h2 style={titleStyle}>Add a thread to {group.name}</h2>
          <p style={subtitleStyle}>
            Start a new thread, or pull an existing one in.
          </p>
        </header>

        <button
          type="button"
          onClick={() => { onClose(); onCreateNew(); }}
          style={createBtnStyle}
        >
          <span style={createGlyphStyle}>＋</span>
          <span style={createLabelStyle}>
            <span style={createPrimaryStyle}>Create a new thread</span>
            <span style={createHintStyle}>Opens the new-thread form with this group pre-filled.</span>
          </span>
        </button>

        <div style={dividerStyle}>
          <span style={dividerLineStyle} />
          <span style={dividerLabelStyle}>or add existing</span>
          <span style={dividerLineStyle} />
        </div>

        <input
          ref={searchRef}
          type="search"
          value={search}
          onChange={e => setSearch(e.target.value)}
          placeholder="Search threads…"
          style={inputStyle}
        />

        <div style={listStyle}>
          {eligible.length === 0 ? (
            <div style={emptyStyle}>
              No other threads available — every thread you have is already
              in this group.
            </div>
          ) : matched.length === 0 ? (
            <div style={emptyStyle}>
              No threads match “{search.trim()}”.
            </div>
          ) : (
            matched.map(t => (
              <button
                key={t.id}
                type="button"
                onClick={() => void pick(t.id)}
                disabled={busyId !== null}
                style={{
                  ...rowStyle,
                  opacity: busyId !== null && busyId !== t.id ? 0.5 : 1,
                  cursor: busyId !== null ? 'wait' : 'pointer',
                }}
              >
                <span style={rowTitleStyle} title={t.title}>{t.title}</span>
                <span style={rowMetaStyle}>
                  <StatusBadge status={t.status} />
                  {busyId === t.id ? 'Adding…' : '+ Add'}
                </span>
              </button>
            ))
          )}
        </div>

        {error && <div style={errorStyle}>{error}</div>}

        <div style={actionsStyle}>
          <button type="button" onClick={onClose} style={secondaryBtnStyle}>
            Cancel
          </button>
        </div>
      </div>
    </div>
  );
}

function StatusBadge({ status }: { status: ThreadDto['status'] }) {
  const tone = STATUS_TONES[status] ?? STATUS_TONES.IDLE;
  return (
    <span style={{
      ...statusBadgeStyle,
      color: tone.fg,
      background: tone.bg,
      borderColor: tone.border,
    }}>
      {status.toLowerCase()}
    </span>
  );
}

const STATUS_TONES: Record<ThreadDto['status'], { fg: string; bg: string; border: string }> = {
  RUNNING:         { fg: '#047857', bg: 'rgba(4,120,87,0.10)',  border: 'rgba(4,120,87,0.25)' },
  AWAITING:        { fg: '#b45309', bg: 'rgba(217,119,6,0.10)', border: 'rgba(217,119,6,0.28)' },
  AWAITING_REVIEW: { fg: '#b45309', bg: 'rgba(245,158,11,0.14)', border: 'rgba(245,158,11,0.30)' },
  NEEDS_ATTENTION: { fg: '#b91c1c', bg: 'rgba(220,38,38,0.10)',  border: 'rgba(220,38,38,0.24)' },
  PENDING:         { fg: 'var(--text-2)', bg: 'var(--bg-card)', border: 'var(--border-hairline)' },
  IDLE:            { fg: 'var(--text-2)', bg: 'var(--bg-card)', border: 'var(--border-hairline)' },
  COMPLETED:       { fg: '#1d4ed8', bg: 'rgba(29,78,216,0.08)', border: 'rgba(29,78,216,0.22)' },
  ARCHIVED:        { fg: 'var(--text-3)', bg: 'var(--bg-card)', border: 'var(--border-hairline)' },
  ERRORED:         { fg: '#b91c1c', bg: 'rgba(185,28,28,0.08)', border: 'rgba(185,28,28,0.22)' },
};

const overlayStyle: React.CSSProperties = {
  position: 'fixed',
  inset: 0,
  background: 'rgba(15, 23, 42, 0.35)',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  zIndex: 50,
};
const dialogStyle: React.CSSProperties = {
  width: 460,
  maxWidth: '92vw',
  maxHeight: '80vh',
  background: 'var(--bg-panel)',
  color: 'var(--text-1)',
  borderRadius: 10,
  padding: 20,
  boxShadow: '0 24px 56px rgba(15, 23, 42, 0.2)',
  display: 'flex',
  flexDirection: 'column',
  gap: 12,
};
const headerStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 4,
};
const titleStyle: React.CSSProperties = {
  margin: 0, fontSize: 16, fontWeight: 700, color: 'var(--text-1)',
};
const subtitleStyle: React.CSSProperties = {
  margin: 0, color: 'var(--text-3)', fontSize: 12.5,
};
const createBtnStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 12,
  padding: '12px 14px',
  background: 'var(--accent-a10, rgba(124,92,255,0.08))',
  border: '1px dashed var(--accent)',
  color: 'var(--text-1)',
  borderRadius: 8,
  cursor: 'pointer',
  textAlign: 'left',
  width: '100%',
};
const createGlyphStyle: React.CSSProperties = {
  fontSize: 20,
  fontWeight: 600,
  color: 'var(--accent)',
  lineHeight: 1,
  flexShrink: 0,
};
const createLabelStyle: React.CSSProperties = {
  display: 'flex', flexDirection: 'column', gap: 2,
};
const createPrimaryStyle: React.CSSProperties = {
  fontSize: 13, fontWeight: 600, color: 'var(--text-1)',
};
const createHintStyle: React.CSSProperties = {
  fontSize: 11.5, color: 'var(--text-3)', fontWeight: 400,
};
const dividerStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  margin: '4px 0',
};
const dividerLineStyle: React.CSSProperties = {
  flex: 1,
  height: 1,
  background: 'var(--border-hairline)',
};
const dividerLabelStyle: React.CSSProperties = {
  fontSize: 10.5,
  color: 'var(--text-3)',
  textTransform: 'uppercase',
  letterSpacing: '0.08em',
  fontWeight: 600,
};
const inputStyle: React.CSSProperties = {
  padding: '8px 10px',
  background: 'var(--bg-input)',
  color: 'var(--text-1)',
  border: '1px solid var(--border-input)',
  borderRadius: 6,
  fontSize: 13,
  fontFamily: 'inherit',
};
const listStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 4,
  maxHeight: 280,
  overflowY: 'auto',
  paddingRight: 4,
  // Visual scrollbar hint — long lists are common.
  scrollbarWidth: 'thin',
};
const rowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 10,
  padding: '8px 10px',
  background: 'var(--bg-card)',
  border: '1px solid var(--border-hairline)',
  borderRadius: 6,
  textAlign: 'left',
  width: '100%',
  fontSize: 12.5,
  color: 'var(--text-1)',
};
const rowTitleStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
  fontWeight: 500,
};
const rowMetaStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 8,
  fontSize: 11,
  color: 'var(--text-3)',
  fontWeight: 500,
  flexShrink: 0,
};
const statusBadgeStyle: React.CSSProperties = {
  padding: '2px 6px',
  borderRadius: 999,
  border: '1px solid',
  fontSize: 10,
  textTransform: 'uppercase',
  letterSpacing: '0.04em',
  fontWeight: 600,
};
const emptyStyle: React.CSSProperties = {
  padding: '12px 10px',
  color: 'var(--text-3)',
  fontSize: 12.5,
  textAlign: 'center',
  border: '1px dashed var(--border-hairline)',
  borderRadius: 6,
};
const errorStyle: React.CSSProperties = {
  padding: '8px 10px',
  background: 'rgba(220, 38, 38, 0.08)',
  color: '#b91c1c',
  border: '1px solid rgba(220, 38, 38, 0.2)',
  borderRadius: 6,
  fontSize: 12,
};
const actionsStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'flex-end',
  gap: 8,
};
const secondaryBtnStyle: React.CSSProperties = {
  padding: '6px 14px',
  background: 'transparent',
  color: 'var(--text-2)',
  border: '1px solid var(--border)',
  borderRadius: 6,
  cursor: 'pointer',
  fontSize: 12.5,
  fontWeight: 500,
};
