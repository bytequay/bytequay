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
import { relativeTime } from '../relativeTime';

type Props = {
  onClose: () => void;
  onCreated: (group: ThreadGroupDto) => void;
  /** Threads the user can pin into the new group. The 4-member cap
   *  matches the tile-grid layout in
   *  docs/mockups/design/tasks/thread-group.png. */
  availableTasks: ThreadDto[];
};

/** Cap matching ThreadService.GROUP_MAX_MEMBERS on the backend. */
const MAX_INITIAL_MEMBERS = 4;

/**
 * New-thread-group modal per
 * docs/mockups/design/tasks/create-thread-group.png — title +
 * subtitle, workspace chip, GROUP NAME field, addressable-by hint,
 * a PIN THREADS column with searchbox + threads to check, and a
 * BOARD PREVIEW 2×2 grid that mirrors the selected threads into
 * their slots (with a "+ pin a 4th" affordance in the empty cell).
 */
export default function NewThreadGroupDialog({
  onClose, onCreated, availableTasks,
}: Props) {
  const [name, setName] = useState('Launch prep');
  const [search, setSearch] = useState('');
  const [pickedIds, setPickedIds] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const nameRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    nameRef.current?.focus();
    nameRef.current?.select();
  }, []);

  const slug = useMemo(() => makeSlug(name), [name]);

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (q === '') return availableTasks;
    return availableTasks.filter(t =>
      t.title.toLowerCase().includes(q),
    );
  }, [availableTasks, search]);

  const pickedThreads = useMemo(
    () => pickedIds
      .map(id => availableTasks.find(t => t.id === id))
      .filter((t): t is ThreadDto => t != null),
    [pickedIds, availableTasks]);

  function togglePick(threadId: string) {
    setPickedIds(prev => {
      if (prev.includes(threadId)) return prev.filter(id => id !== threadId);
      if (prev.length >= MAX_INITIAL_MEMBERS) return prev;
      return [...prev, threadId];
    });
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!name.trim()) {
      setError('Name is required.');
      return;
    }
    if (pickedIds.length === 0) {
      setError('Pin at least one thread.');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const group = await window.bridge.createTaskGroup({
        name: name.trim(),
        glyph: '⊞',
        color: 'violet',
        initialTaskIds: pickedIds,
      });
      onCreated(group);
    }
    catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    }
    finally {
      setBusy(false);
    }
  }

  return (
    <div style={overlayStyle} onClick={onClose}>
      <form
        style={dialogStyle}
        onClick={e => e.stopPropagation()}
        onSubmit={submit}
      >
        <header style={headerRowStyle}>
          <div style={titleColStyle}>
            <div style={titleRowStyle}>
              <span style={titleGlyphStyle} aria-hidden>⊞</span>
              <h2 style={titleStyle}>New thread group</h2>
            </div>
            <div style={subtitleStyle}>
              A monitoring board — pin up to 4 threads to watch side by side
            </div>
          </div>
          <div style={titleRightStyle}>
            <span style={workspaceChipStyle}>
              <span style={brandSqStyle} aria-hidden>B</span>
              ByteQuay
            </span>
            <button
              type="button"
              onClick={onClose}
              style={closeBtnStyle}
              aria-label="Close"
            >
              ✕
            </button>
          </div>
        </header>

        <div style={sectionLabelStyle}>GROUP NAME</div>
        <div style={nameFieldWrapStyle}>
          <span style={nameFieldGlyphStyle} aria-hidden>⊞</span>
          <input
            ref={nameRef}
            type="text"
            value={name}
            onChange={e => setName(e.target.value)}
            placeholder="Launch prep"
            style={nameInputStyle}
            maxLength={60}
          />
        </div>
        <div style={addressableHintStyle}>
          Switch by tab · <kbd style={kbdStyle}>⌘K</kbd>
          {' · '}
          <kbd style={kbdStyle}>g b</kbd>
          {' · addressable as '}
          <code style={addressableCodeStyle}>#group-{slug}</code>
          {' · A view, not a workspace.'}
        </div>

        <div style={twoColStyle}>
          <section style={pickColStyle}>
            <div style={colHeadStyle}>
              <span>PIN THREADS</span>
              <span style={colHeadCountStyle}>
                {pickedIds.length} / {MAX_INITIAL_MEMBERS}
              </span>
            </div>
            <div style={searchWrapStyle}>
              <input
                type="text"
                value={search}
                onChange={e => setSearch(e.target.value)}
                placeholder="Find a thread by name, branch, or PR…"
                style={searchInputStyle}
              />
            </div>
            <div style={listStyle}>
              {filtered.length === 0 ? (
                <div style={emptyStyle}>
                  {availableTasks.length === 0
                    ? 'No threads yet — start one before building a group.'
                    : 'No threads match that query.'}
                </div>
              ) : (
                filtered.map(t => {
                  const picked = pickedIds.includes(t.id);
                  const capped = !picked && pickedIds.length >= MAX_INITIAL_MEMBERS;
                  return (
                    <label
                      key={t.id}
                      style={threadRowStyle(picked, capped)}
                      onClick={(e) => {
                        if (capped) e.preventDefault();
                      }}
                    >
                      <input
                        type="checkbox"
                        checked={picked}
                        disabled={capped}
                        onChange={() => togglePick(t.id)}
                        style={checkboxStyle}
                      />
                      <span style={dotStyle(t.status)} aria-hidden />
                      <div style={threadBodyStyle}>
                        <div style={threadTitleStyle}>{t.title}</div>
                        <div style={threadMetaStyle}>
                          <span style={flowChipStyle(t.flow)}>
                            {t.flow === 'review' ? 'REVIEW' : 'BUILD'}
                          </span>
                          <span style={metaItemStyle}>
                            {humanStatus(t.status)}
                          </span>
                          <span style={metaItemStyle}>
                            {relativeTime(t.updatedAt)}
                          </span>
                        </div>
                      </div>
                    </label>
                  );
                })
              )}
            </div>
          </section>

          <section style={previewColStyle}>
            <div style={colHeadStyle}>
              <span>BOARD PREVIEW</span>
              <span style={colHeadCountStyle}>
                {pickedThreads.length} / 4
              </span>
            </div>
            <div style={previewGridStyle}>
              {Array.from({ length: 4 }).map((_, i) => {
                const t = pickedThreads[i];
                if (t === undefined) {
                  return (
                    <div key={i} style={previewSlotEmptyStyle}>
                      + pin a {ordinal(i + 1)}
                    </div>
                  );
                }
                return (
                  <div key={i} style={previewSlotFilledStyle}>
                    <span style={previewSlotDotStyle(t.status)} aria-hidden />
                    <span style={previewSlotTitleStyle}>{t.title}</span>
                  </div>
                );
              })}
            </div>
            <div style={previewHintStyle}>
              2×2 keeps each tile readable. Beyond 4, use the
              {' '}<strong>List</strong> or <strong>Immersive</strong> view
              instead of a denser grid.
            </div>
          </section>
        </div>

        {error !== null && (
          <div style={errorStyle}>{error}</div>
        )}

        <footer style={footerStyle}>
          <div style={footerNoteStyle}>
            A group is a <strong>view</strong> — a thread can sit in
            several groups or none, and pinning moves nothing.
          </div>
          <div style={footerBtnsStyle}>
            <button
              type="button"
              onClick={onClose}
              style={secondaryBtnStyle}
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={busy || pickedIds.length === 0}
              style={primaryBtnStyle}
            >
              <span aria-hidden style={{ marginRight: 6 }}>⊞</span>
              {busy ? 'Creating…' : 'Create group'}
            </button>
          </div>
        </footer>
      </form>
    </div>
  );
}

/* ── Helpers ───────────────────────────────────────────────────── */

function makeSlug(name: string): string {
  return name
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
      || 'untitled';
}

function ordinal(n: number): string {
  if (n === 1) return '1st';
  if (n === 2) return '2nd';
  if (n === 3) return '3rd';
  return `${n}th`;
}

function humanStatus(status: string): string {
  if (status === 'AWAITING_REVIEW') return 'awaiting review';
  if (status === 'NEEDS_ATTENTION') return 'needs you';
  if (status === 'RUNNING') return 'running';
  if (status === 'IDLE') return 'idle';
  if (status === 'COMPLETED') return 'completed';
  if (status === 'ERRORED') return 'errored';
  return status.toLowerCase();
}

/* ── Styles ────────────────────────────────────────────────────── */

const overlayStyle: React.CSSProperties = {
  position: 'fixed',
  inset: 0,
  background: 'rgba(15, 23, 42, 0.30)',
  backdropFilter: 'blur(2px)',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  zIndex: 1100,
  padding: 24,
};

const dialogStyle: React.CSSProperties = {
  width: 'min(920px, 96vw)',
  maxHeight: '92vh',
  background: '#ffffff',
  color: 'var(--text-1)',
  borderRadius: 16,
  padding: '22px 26px',
  boxShadow: '0 30px 60px rgba(15, 23, 42, 0.25)',
  display: 'flex',
  flexDirection: 'column',
  gap: 12,
  overflow: 'auto',
};

const headerRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'flex-start',
  justifyContent: 'space-between',
  gap: 12,
};

const titleColStyle: React.CSSProperties = {
  flex: 1,
  display: 'flex',
  flexDirection: 'column',
  gap: 2,
};

const titleRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 10,
};

const titleGlyphStyle: React.CSSProperties = {
  width: 28,
  height: 28,
  borderRadius: 8,
  background: 'var(--accent)',
  color: '#fff',
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  fontSize: 14,
  fontWeight: 700,
};

const titleStyle: React.CSSProperties = {
  margin: 0,
  fontSize: 17,
  fontWeight: 700,
  color: 'var(--text-1)',
};

const subtitleStyle: React.CSSProperties = {
  marginLeft: 38,
  fontSize: 12,
  color: 'var(--text-3)',
};

const titleRightStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
};

const workspaceChipStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 6,
  padding: '4px 10px',
  background: 'var(--accent-a10)',
  border: '1px solid var(--accent-border)',
  borderRadius: 999,
  color: 'var(--accent-deep)',
  fontSize: 12,
  fontWeight: 600,
};

const brandSqStyle: React.CSSProperties = {
  width: 16,
  height: 16,
  borderRadius: 4,
  background: 'var(--accent)',
  color: '#fff',
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  fontSize: 10,
  fontWeight: 700,
};

const closeBtnStyle: React.CSSProperties = {
  width: 26,
  height: 26,
  border: '1px solid rgba(0,0,0,0.08)',
  background: '#fff',
  borderRadius: 6,
  fontSize: 13,
  color: 'var(--text-3)',
  cursor: 'pointer',
};

const sectionLabelStyle: React.CSSProperties = {
  fontSize: 10,
  fontWeight: 700,
  letterSpacing: '0.08em',
  color: 'var(--text-3)',
  marginTop: 4,
};

const nameFieldWrapStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 10,
  padding: '10px 12px',
  background: '#fff',
  border: '1px solid var(--accent-border)',
  borderRadius: 12,
  boxShadow: 'inset 0 0 0 3px var(--accent-a7)',
};

const nameFieldGlyphStyle: React.CSSProperties = {
  width: 22,
  height: 22,
  borderRadius: 6,
  background: 'var(--accent-a10)',
  color: 'var(--accent)',
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  fontSize: 12,
  fontWeight: 700,
  flexShrink: 0,
};

const nameInputStyle: React.CSSProperties = {
  flex: 1,
  border: 'none',
  outline: 'none',
  background: 'transparent',
  fontSize: 16,
  fontWeight: 600,
  color: 'var(--text-1)',
  fontFamily: 'inherit',
};

const addressableHintStyle: React.CSSProperties = {
  fontSize: 11,
  color: 'var(--text-3)',
};

const kbdStyle: React.CSSProperties = {
  fontSize: 10,
  padding: '1px 5px',
  border: '1px solid rgba(0,0,0,0.10)',
  borderRadius: 4,
  background: 'rgba(0,0,0,0.04)',
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
};

const addressableCodeStyle: React.CSSProperties = {
  color: 'var(--accent)',
  background: 'var(--accent-a10)',
  padding: '1px 6px',
  borderRadius: 4,
  fontSize: 11,
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
};

const twoColStyle: React.CSSProperties = {
  display: 'grid',
  gridTemplateColumns: '1.1fr 0.9fr',
  gap: 16,
  marginTop: 4,
};

const pickColStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 8,
  minWidth: 0,
};

const colHeadStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'space-between',
  alignItems: 'baseline',
  fontSize: 10,
  fontWeight: 700,
  letterSpacing: '0.06em',
  color: 'var(--text-2)',
};

const colHeadCountStyle: React.CSSProperties = {
  color: 'var(--text-4)',
  fontWeight: 500,
  fontVariantNumeric: 'tabular-nums',
};

const searchWrapStyle: React.CSSProperties = {
  display: 'flex',
};

const searchInputStyle: React.CSSProperties = {
  flex: 1,
  padding: '8px 12px',
  border: '1px solid rgba(0,0,0,0.10)',
  borderRadius: 8,
  fontSize: 12,
  fontFamily: 'inherit',
  background: '#fff',
  outline: 'none',
};

const listStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 6,
  maxHeight: 280,
  overflowY: 'auto',
  paddingRight: 4,
};

const emptyStyle: React.CSSProperties = {
  padding: '12px',
  background: 'rgba(0,0,0,0.03)',
  border: '1px dashed rgba(0,0,0,0.10)',
  borderRadius: 8,
  fontSize: 11,
  color: 'var(--text-3)',
};

function threadRowStyle(picked: boolean, capped: boolean): React.CSSProperties {
  return {
    display: 'flex',
    alignItems: 'flex-start',
    gap: 10,
    padding: '8px 10px',
    border: picked
        ? '1px solid var(--accent-border)'
        : '1px solid rgba(0,0,0,0.08)',
    background: picked
        ? 'var(--accent-a7)'
        : '#fff',
    borderRadius: 10,
    cursor: capped ? 'not-allowed' : 'pointer',
    opacity: capped ? 0.55 : 1,
    transition: 'background 140ms ease, border-color 140ms ease',
  };
}

const checkboxStyle: React.CSSProperties = {
  marginTop: 3,
  accentColor: 'var(--accent)',
  flexShrink: 0,
};

function dotStyle(status: string): React.CSSProperties {
  let bg = '#94a3b8';
  if (status === 'RUNNING') bg = '#22c55e';
  else if (status === 'AWAITING_REVIEW') bg = '#d97706';
  else if (status === 'NEEDS_ATTENTION') bg = '#dc2626';
  else if (status === 'COMPLETED') bg = '#0ea5e9';
  else if (status === 'ERRORED') bg = '#dc2626';
  return {
    width: 7,
    height: 7,
    borderRadius: 999,
    background: bg,
    marginTop: 7,
    flexShrink: 0,
  };
}

const threadBodyStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
};

const threadTitleStyle: React.CSSProperties = {
  fontSize: 13,
  fontWeight: 600,
  color: 'var(--text-1)',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
};

const threadMetaStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  marginTop: 3,
  fontSize: 11,
  color: 'var(--text-4)',
  flexWrap: 'wrap',
};

function flowChipStyle(flow: 'build' | 'review'): React.CSSProperties {
  const isReview = flow === 'review';
  return {
    fontSize: 9,
    fontWeight: 700,
    letterSpacing: '0.08em',
    padding: '1px 6px',
    borderRadius: 3,
    color: isReview ? '#1d4ed8' : 'var(--accent)',
    background: isReview ? 'rgba(37,99,235,0.10)' : 'var(--accent-a10)',
    border: `1px solid ${isReview ? 'rgba(37,99,235,0.30)' : 'var(--accent-border)'}`,
  };
}

const metaItemStyle: React.CSSProperties = {
  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
};

const previewColStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 8,
};

const previewGridStyle: React.CSSProperties = {
  display: 'grid',
  gridTemplateColumns: '1fr 1fr',
  gridTemplateRows: '1fr 1fr',
  gap: 4,
  background: '#fff',
  border: '1px solid rgba(0,0,0,0.08)',
  borderRadius: 10,
  padding: 4,
  height: 200,
};

const previewSlotEmptyStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  border: '1px dashed var(--accent-border)',
  borderRadius: 8,
  fontSize: 11,
  color: 'var(--accent)',
  background: 'var(--accent-a4)',
};

const previewSlotFilledStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'flex-start',
  gap: 6,
  padding: '8px 10px',
  background: '#fafafe',
  border: '1px solid rgba(0,0,0,0.08)',
  borderRadius: 8,
  overflow: 'hidden',
};

function previewSlotDotStyle(status: string): React.CSSProperties {
  let bg = '#94a3b8';
  if (status === 'RUNNING') bg = '#22c55e';
  else if (status === 'AWAITING_REVIEW') bg = '#d97706';
  else if (status === 'NEEDS_ATTENTION') bg = '#dc2626';
  return {
    width: 6,
    height: 6,
    borderRadius: 999,
    background: bg,
    marginTop: 5,
    flexShrink: 0,
  };
}

const previewSlotTitleStyle: React.CSSProperties = {
  flex: 1,
  fontSize: 11,
  fontWeight: 600,
  color: 'var(--text-1)',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
};

const previewHintStyle: React.CSSProperties = {
  fontSize: 11,
  color: 'var(--text-4)',
  lineHeight: 1.5,
};

const errorStyle: React.CSSProperties = {
  padding: '8px 12px',
  background: '#fee2e2',
  color: '#991b1b',
  border: '1px solid #fecaca',
  borderRadius: 8,
  fontSize: 12,
};

const footerStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'space-between',
  alignItems: 'center',
  marginTop: 8,
  paddingTop: 12,
  borderTop: '1px solid rgba(0,0,0,0.06)',
  gap: 12,
};

const footerNoteStyle: React.CSSProperties = {
  flex: 1,
  fontSize: 10,
  color: 'var(--text-4)',
  lineHeight: 1.5,
};

const footerBtnsStyle: React.CSSProperties = {
  display: 'flex',
  gap: 8,
};

const secondaryBtnStyle: React.CSSProperties = {
  padding: '8px 16px',
  background: '#fff',
  color: 'var(--text-2)',
  border: '1px solid rgba(0,0,0,0.10)',
  borderRadius: 8,
  fontSize: 13,
  cursor: 'pointer',
};

const primaryBtnStyle: React.CSSProperties = {
  padding: '8px 16px',
  background: 'var(--accent)',
  color: '#fff',
  border: 'none',
  borderRadius: 8,
  fontSize: 13,
  fontWeight: 600,
  cursor: 'pointer',
};
