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
import { useEffect, useRef, useState } from 'react';
import type { ThreadDto, ThreadGroupDto } from '../types';

type Props = {
  onClose: () => void;
  onCreated: (group: ThreadGroupDto) => void;
  /** Threads the user can pin into the new group. The non-empty
   *  invariant requires at least one selection; the 4-member cap
   *  matches the tile-grid layout in
   *  {@code docs/mockups/design/threads/threads-group.png}. */
  availableTasks: ThreadDto[];
};

/** Cap matching {@code ThreadService.GROUP_MAX_MEMBERS} on the
 *  backend. Kept here as a literal so the dialog doesn't reach
 *  into the bridge for a constant; if the backend ever changes,
 *  this stays in lockstep via a code-review checklist. */
const MAX_INITIAL_MEMBERS = 4;

const COLOR_SWATCHES: Array<{ value: string; bg: string }> = [
  { value: 'slate',  bg: 'linear-gradient(135deg, #64748b, #334155)' },
  { value: 'violet', bg: 'linear-gradient(135deg, #7c3aed, #4c1d95)' },
  { value: 'amber',  bg: 'linear-gradient(135deg, #d97706, #92400e)' },
  { value: 'green',  bg: 'linear-gradient(135deg, #10b981, #047857)' },
  { value: 'blue',   bg: 'linear-gradient(135deg, #2563eb, #1e3a8a)' },
  { value: 'rose',   bg: 'linear-gradient(135deg, #e11d48, #9f1239)' },
];

/**
 * Small modal for the rail's Groups section. Just name / glyph /
 * color — sort order defaults to "append last" via timestamp.
 */
export default function NewThreadGroupDialog({ onClose, onCreated, availableTasks }: Props) {
  const [name, setName] = useState('');
  const [glyph, setGlyph] = useState('•');
  const [color, setColor] = useState('violet');
  const [initialTaskIds, setInitialTaskIds] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const nameRef = useRef<HTMLInputElement>(null);

  useEffect(() => { nameRef.current?.focus(); }, []);

  function toggleTask(threadId: string) {
    setInitialTaskIds(prev => {
      if (prev.includes(threadId)) {
        return prev.filter(id => id !== threadId);
      }
      if (prev.length >= MAX_INITIAL_MEMBERS) {
        // Honor the cap silently — the disabled-checkbox cue is
        // enough; surfacing an error every click would be noisy.
        return prev;
      }
      return [...prev, threadId];
    });
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!name.trim()) {
      setError('Name is required.');
      return;
    }
    if (initialTaskIds.length === 0) {
      setError('Pick at least one thread to put in the group.');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const group = await window.bridge.createTaskGroup({
        name: name.trim(),
        glyph: glyph.trim() || '•',
        color,
        initialTaskIds,
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
        <h2 style={titleStyle}>New group</h2>
        <p style={subtitleStyle}>
          Groups pin a handful of related threads together so you can
          flip between them.
        </p>

        <label style={labelStyle}>
          Name
          <input
            ref={nameRef}
            type="text"
            value={name}
            onChange={e => setName(e.target.value)}
            placeholder="e.g. Trino refactor"
            style={inputStyle}
            maxLength={48}
          />
        </label>

        <div style={fieldRowStyle}>
          <label style={{ ...labelStyle, flex: '0 0 96px' }}>
            Glyph
            <input
              type="text"
              value={glyph}
              onChange={e => setGlyph(e.target.value)}
              maxLength={2}
              style={{ ...inputStyle, textAlign: 'center', fontSize: 16 }}
            />
          </label>
          <div style={{ ...labelStyle, flex: 1 }}>
            Color
            <div style={swatchRowStyle}>
              {COLOR_SWATCHES.map(s => (
                <button
                  key={s.value}
                  type="button"
                  onClick={() => setColor(s.value)}
                  title={s.value}
                  style={{
                    ...swatchStyle,
                    background: s.bg,
                    outline: color === s.value ? '2px solid #7c3aed' : 'none',
                    outlineOffset: 2,
                  }}
                />
              ))}
            </div>
          </div>
        </div>

        <div style={labelStyle}>
          <span>
            Threads
            <span style={{ color: 'var(--text-3)', fontWeight: 400, marginLeft: 6 }}>
              {initialTaskIds.length}/{MAX_INITIAL_MEMBERS} picked
            </span>
          </span>
          {availableTasks.length === 0 ? (
            <div style={emptyTasksHintStyle}>
              No threads to add yet. Start one from the rail first — a
              group has to contain at least one thread.
            </div>
          ) : (
            <div style={threadListStyle}>
              {availableTasks.map(t => {
                const picked = initialTaskIds.includes(t.id);
                const atCap = !picked && initialTaskIds.length >= MAX_INITIAL_MEMBERS;
                return (
                  <label
                    key={t.id}
                    style={{
                      ...threadRowStyle,
                      opacity: atCap ? 0.5 : 1,
                      cursor: atCap ? 'not-allowed' : 'pointer',
                    }}
                  >
                    <input
                      type="checkbox"
                      checked={picked}
                      disabled={atCap}
                      onChange={() => toggleTask(t.id)}
                    />
                    <span style={threadRowTitleStyle}>{t.title}</span>
                  </label>
                );
              })}
            </div>
          )}
        </div>

        {error && <div style={errorStyle}>{error}</div>}

        <div style={actionsStyle}>
          <button type="button" onClick={onClose} style={secondaryBtnStyle}>
            Cancel
          </button>
          <button
            type="submit"
            disabled={busy || initialTaskIds.length === 0}
            style={primaryBtnStyle}
          >
            {busy ? 'Creating…' : 'Create group'}
          </button>
        </div>
      </form>
    </div>
  );
}

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
  width: 420,
  maxWidth: '92vw',
  background: 'var(--bg-panel)',
  color: 'var(--text-1)',
  borderRadius: 10,
  padding: 24,
  boxShadow: '0 24px 56px rgba(15, 23, 42, 0.2)',
  display: 'flex',
  flexDirection: 'column',
  gap: 14,
};
const titleStyle: React.CSSProperties = {
  margin: 0, fontSize: 18, fontWeight: 700, color: 'var(--text-1)',
};
const subtitleStyle: React.CSSProperties = {
  margin: 0, color: 'var(--text-3)', fontSize: 13,
};
const labelStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 6,
  fontSize: 12,
  fontWeight: 600,
  color: 'var(--text-2)',
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
const fieldRowStyle: React.CSSProperties = {
  display: 'flex',
  gap: 12,
  alignItems: 'flex-start',
};
const swatchRowStyle: React.CSSProperties = {
  display: 'flex',
  gap: 8,
  flexWrap: 'wrap',
};
const swatchStyle: React.CSSProperties = {
  width: 26,
  height: 26,
  borderRadius: 6,
  border: 'none',
  cursor: 'pointer',
};
const threadListStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 4,
  maxHeight: 180,
  overflowY: 'auto',
  padding: 6,
  border: '1px solid var(--border-input)',
  borderRadius: 6,
  background: 'var(--bg-input)',
};
const threadRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  padding: '4px 6px',
  borderRadius: 4,
  fontSize: 13,
  color: 'var(--text-1)',
  fontWeight: 400,
};
const threadRowTitleStyle: React.CSSProperties = {
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
};
const emptyTasksHintStyle: React.CSSProperties = {
  padding: '8px 10px',
  background: 'var(--bg-input)',
  color: 'var(--text-3)',
  border: '1px dashed var(--border-input)',
  borderRadius: 6,
  fontSize: 12,
  fontWeight: 400,
};
const errorStyle: React.CSSProperties = {
  padding: '8px 10px',
  background: '#fef2f2',
  color: '#991b1b',
  border: '1px solid #fca5a5',
  borderRadius: 6,
  fontSize: 12,
};
const actionsStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'flex-end',
  gap: 8,
};
const secondaryBtnStyle: React.CSSProperties = {
  padding: '8px 14px',
  background: 'var(--bg-btn-secondary)',
  color: 'var(--text-2)',
  border: '1px solid var(--border-input)',
  borderRadius: 6,
  cursor: 'pointer',
};
const primaryBtnStyle: React.CSSProperties = {
  padding: '8px 14px',
  background: 'var(--accent)',
  color: '#fff',
  border: 'none',
  borderRadius: 6,
  fontWeight: 600,
  cursor: 'pointer',
};
