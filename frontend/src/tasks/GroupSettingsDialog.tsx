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
import type { TaskGroupDto } from '../types';

type Props = {
  group: TaskGroupDto;
  /** Tasks currently pinned to this group — count is shown in the
   *  delete confirmation so the user knows what they're un-pinning. */
  pinnedTaskCount: number;
  onClose: () => void;
  onSaved: (group: TaskGroupDto) => void;
  /** Fired after the group is removed on the backend; caller is
   *  responsible for navigating away from the group view. */
  onDeleted: () => void;
};

const COLOR_SWATCHES: Array<{ value: string; bg: string }> = [
  { value: 'slate',  bg: 'linear-gradient(135deg, #64748b, #334155)' },
  { value: 'violet', bg: 'linear-gradient(135deg, #7c3aed, #4c1d95)' },
  { value: 'amber',  bg: 'linear-gradient(135deg, #d97706, #92400e)' },
  { value: 'green',  bg: 'linear-gradient(135deg, #10b981, #047857)' },
  { value: 'blue',   bg: 'linear-gradient(135deg, #2563eb, #1e3a8a)' },
  { value: 'rose',   bg: 'linear-gradient(135deg, #e11d48, #9f1239)' },
];

export default function GroupSettingsDialog({
  group, pinnedTaskCount, onClose, onSaved, onDeleted,
}: Props) {
  const [name, setName] = useState(group.name);
  const [glyph, setGlyph] = useState(group.glyph || '•');
  const [color, setColor] = useState(group.color || 'slate');
  const [busy, setBusy] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const nameRef = useRef<HTMLInputElement>(null);

  useEffect(() => { nameRef.current?.focus(); }, []);

  async function save(e: React.FormEvent) {
    e.preventDefault();
    if (!name.trim()) {
      setError('Name is required.');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const next = await window.bridge.updateTaskGroup(group.id, {
        name: name.trim(),
        glyph: glyph.trim() || '•',
        color,
      });
      onSaved(next);
    }
    catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    }
    finally {
      setBusy(false);
    }
  }

  async function doDelete() {
    setBusy(true);
    setError(null);
    try {
      await window.bridge.deleteTaskGroup(group.id);
      onDeleted();
    }
    catch (err) {
      setError(err instanceof Error ? err.message : String(err));
      setBusy(false);
    }
  }

  return (
    <div style={overlayStyle} onClick={onClose}>
      <form
        style={dialogStyle}
        onClick={e => e.stopPropagation()}
        onSubmit={save}
      >
        <h2 style={titleStyle}>Group settings</h2>

        <label style={labelStyle}>
          Name
          <input
            ref={nameRef}
            type="text"
            value={name}
            onChange={e => setName(e.target.value)}
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

        {error && <div style={errorStyle}>{error}</div>}

        {confirmDelete ? (
          <div style={dangerZoneStyle}>
            <div style={{ fontSize: 13, color: '#991b1b' }}>
              Delete <strong>{group.name}</strong>? The{' '}
              {pinnedTaskCount} pinned task{pinnedTaskCount === 1 ? '' : 's'}{' '}
              will become ungrouped — their history is kept.
            </div>
            <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
              <button
                type="button"
                onClick={() => setConfirmDelete(false)}
                style={secondaryBtnStyle}
                disabled={busy}
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={() => void doDelete()}
                style={dangerBtnStyle}
                disabled={busy}
              >
                {busy ? 'Deleting…' : 'Delete group'}
              </button>
            </div>
          </div>
        ) : (
          <div style={actionsStyle}>
            <button
              type="button"
              onClick={() => setConfirmDelete(true)}
              style={dangerLinkStyle}
              disabled={busy}
            >
              Delete group
            </button>
            <div style={{ flex: 1 }} />
            <button type="button" onClick={onClose} style={secondaryBtnStyle}>
              Cancel
            </button>
            <button type="submit" disabled={busy} style={primaryBtnStyle}>
              {busy ? 'Saving…' : 'Save changes'}
            </button>
          </div>
        )}
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
  width: 460,
  maxWidth: '92vw',
  background: '#fff',
  borderRadius: 10,
  padding: 24,
  boxShadow: '0 24px 56px rgba(15, 23, 42, 0.2)',
  display: 'flex',
  flexDirection: 'column',
  gap: 14,
};
const titleStyle: React.CSSProperties = { margin: 0, fontSize: 18, fontWeight: 700 };
const labelStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 6,
  fontSize: 12,
  fontWeight: 600,
  color: '#374151',
};
const inputStyle: React.CSSProperties = {
  padding: '8px 10px',
  border: '1px solid #d1d5db',
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
  alignItems: 'center',
  gap: 8,
};
const dangerZoneStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 10,
  padding: 12,
  background: '#fef2f2',
  border: '1px solid #fecaca',
  borderRadius: 8,
};
const secondaryBtnStyle: React.CSSProperties = {
  padding: '8px 14px',
  background: 'transparent',
  color: '#374151',
  border: '1px solid #d1d5db',
  borderRadius: 6,
  cursor: 'pointer',
};
const primaryBtnStyle: React.CSSProperties = {
  padding: '8px 14px',
  background: '#7c3aed',
  color: '#fff',
  border: 'none',
  borderRadius: 6,
  fontWeight: 600,
  cursor: 'pointer',
};
const dangerBtnStyle: React.CSSProperties = {
  padding: '8px 14px',
  background: '#dc2626',
  color: '#fff',
  border: 'none',
  borderRadius: 6,
  fontWeight: 600,
  cursor: 'pointer',
};
const dangerLinkStyle: React.CSSProperties = {
  background: 'transparent',
  border: 'none',
  color: '#dc2626',
  fontWeight: 600,
  fontSize: 13,
  cursor: 'pointer',
  padding: '8px 4px',
};
