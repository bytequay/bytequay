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
import React, { useEffect, useRef, useState } from 'react';

/**
 * Inline-editable title chip: a click turns the resting label into a text
 * input; Enter (or blur) saves, Escape cancels. {@code onRename} may be
 * async — the editor stays open while it runs and, if it rejects, surfaces
 * the error inline without discarding the user's typed text so they can
 * retry. Shared by the thread detail toolbar and the PR preview header.
 */
export function EditableTitle({ title, onRename, maxDisplayWords, titleStyleOverride, inputStyleOverride }: {
  title: string;
  onRename: (next: string) => void | Promise<void>;
  /** Optional cap on how many whitespace-separated words of the title are
   *  rendered in the resting state; anything past the cap becomes `…`. The
   *  editor still operates on the full title. */
  maxDisplayWords?: number;
  /** Style merged into the inner title span — pass `{ overflow: 'visible',
   *  textOverflow: 'clip' }` to suppress the default ellipsis truncation. */
  titleStyleOverride?: React.CSSProperties;
  /** Style merged into the edit input — pass `{ fontSize: 'inherit' }` to
   *  keep the editor sized to a large host heading rather than the default
   *  toolbar size. */
  inputStyleOverride?: React.CSSProperties;
}) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(title);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const inputRef = useRef<HTMLInputElement | null>(null);

  useEffect(() => { if (!editing) setDraft(title); }, [title, editing]);
  useEffect(() => {
    if (editing && inputRef.current) {
      inputRef.current.focus();
      inputRef.current.select();
    }
  }, [editing]);

  async function commit() {
    if (saving) return;
    const trimmed = draft.trim();
    if (!trimmed || trimmed === title) { setEditing(false); setError(null); return; }
    setSaving(true);
    setError(null);
    try {
      await onRename(trimmed);
      setEditing(false);
    }
    catch (e) {
      // Keep the editor open with the typed text so the user can retry.
      setError(e instanceof Error ? e.message : 'Failed to save');
    }
    finally {
      setSaving(false);
    }
  }

  function cancel() {
    setDraft(title);
    setError(null);
    setEditing(false);
  }

  if (editing) {
    return (
      <span style={editWrapStyle}>
        <input
          ref={inputRef}
          type="text"
          value={draft}
          disabled={saving}
          onChange={e => setDraft(e.target.value)}
          onBlur={() => { void commit(); }}
          onKeyDown={e => {
            if (e.key === 'Enter') { e.preventDefault(); void commit(); }
            else if (e.key === 'Escape') { e.preventDefault(); cancel(); }
          }}
          style={{ ...titleEditInputStyle, ...inputStyleOverride }}
        />
        {error !== null && <span role="alert" style={titleEditErrorStyle}>{error}</span>}
      </span>
    );
  }
  const displayTitle = (() => {
    if (!maxDisplayWords) return title;
    const words = title.trim().split(/\s+/).filter(Boolean);
    if (words.length <= maxDisplayWords) return title;
    return `${words.slice(0, maxDisplayWords).join(' ')}…`;
  })();
  return (
    <button
      type="button"
      onClick={() => setEditing(true)}
      style={titleEditTriggerStyle}
      title={title.length > displayTitle.length
        ? `${title} — click to rename (Enter saves, Esc cancels)`
        : 'Click to rename — Enter to save, Esc to cancel'}
    >
      <span style={{ ...thTitleStyle, ...titleStyleOverride }}>{displayTitle}</span>
      <span style={titleEditPencilStyle} aria-hidden>✎</span>
    </button>
  );
}

const thTitleStyle: React.CSSProperties = {
  fontSize: 13, fontWeight: 600, color: 'var(--text-1)',
  letterSpacing: '-0.005em',
  overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
};
const titleEditTriggerStyle: React.CSSProperties = {
  display: 'inline-flex', alignItems: 'center', gap: 8,
  background: 'transparent', border: '1px dashed transparent',
  padding: '2px 6px',
  margin: '-2px -6px',
  borderRadius: 6,
  cursor: 'text',
  color: 'var(--text-1)',
  maxWidth: '100%',
  textAlign: 'left',
};
const titleEditPencilStyle: React.CSSProperties = {
  fontSize: 12,
  color: 'var(--text-4)',
  opacity: 0.6,
  flexShrink: 0,
};
const titleEditInputStyle: React.CSSProperties = {
  fontSize: 13, fontWeight: 600,
  letterSpacing: '-0.005em',
  color: 'var(--text-1)',
  background: 'var(--bg-input)',
  border: '1px solid var(--accent-a40)',
  borderRadius: 6,
  padding: '2px 6px',
  margin: '-3px -7px',
  outline: 'none',
  width: '100%',
  fontFamily: 'inherit',
};
const editWrapStyle: React.CSSProperties = {
  display: 'inline-flex', flexDirection: 'column', gap: 4, width: '100%',
};
const titleEditErrorStyle: React.CSSProperties = {
  fontSize: 11, color: 'var(--err-text, #ef4444)', fontWeight: 500,
};
