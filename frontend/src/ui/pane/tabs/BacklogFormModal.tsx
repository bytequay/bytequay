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
import type { CSSProperties } from 'react';
import { createPortal } from 'react-dom';
import MarkdownComposer from '../../../MarkdownComposer';
import { WS_DIALOG_OVERLAY, WS_DIALOG_PANEL, dialogStyles } from '../../../workspace/dialogStyles';

export type NewBacklogItem = {
  title: string;
  body: string;
  tags: string[];
  priority: 'low' | 'medium' | 'high';
};

const PRIORITIES: NewBacklogItem['priority'][] = ['low', 'medium', 'high'];

/**
 * Modal form for adding a backlog item: a required title, a markdown
 * description, tag chips, and a priority toggle. ESC or a backdrop click
 * cancels; ⌘↵ saves. Portals to {@code document.body} so it overlays the
 * whole window regardless of the pane it was opened from.
 */
export function BacklogFormModal({ onSave, onClose }: {
  onSave: (item: NewBacklogItem) => void;
  onClose: () => void;
}) {
  const [title, setTitle] = useState('');
  const [body, setBody] = useState('');
  const [tags, setTags] = useState<string[]>([]);
  const [tagDraft, setTagDraft] = useState('');
  const [priority, setPriority] = useState<NewBacklogItem['priority']>('medium');
  const titleRef = useRef<HTMLInputElement>(null);

  useEffect(() => { titleRef.current?.focus(); }, []);

  const canSave = title.trim().length > 0;
  const save = () => {
    if (canSave) onSave({ title: title.trim(), body: body.trim(), tags, priority });
  };

  // Re-bind each render so the keydown closure sees the latest draft values.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') { e.preventDefault(); onClose(); }
      else if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) { e.preventDefault(); save(); }
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  });

  const commitTag = () => {
    const t = tagDraft.replace(/,/g, '').trim();
    if (t.length > 0 && !tags.includes(t)) setTags(prev => [...prev, t]);
    setTagDraft('');
  };

  return createPortal(
    <div
      style={WS_DIALOG_OVERLAY}
      onMouseDown={e => { if (e.target === e.currentTarget) onClose(); }}
    >
      <div style={WS_DIALOG_PANEL} role="dialog" aria-label="Add backlog item">
        <div style={dialogStyles.header}>
          <h2 style={dialogStyles.title}>＋ Add backlog item</h2>
        </div>

        <div style={dialogStyles.field}>
          <label style={dialogStyles.fieldLabel} htmlFor="bl-title">Title</label>
          <input
            id="bl-title"
            ref={titleRef}
            style={dialogStyles.input}
            value={title}
            onChange={e => setTitle(e.target.value)}
            placeholder="What's the parked work?"
            maxLength={120}
          />
        </div>

        <div style={dialogStyles.field}>
          <label style={dialogStyles.fieldLabel}>Description</label>
          <MarkdownComposer
            value={body}
            onChange={setBody}
            placeholder="Describe the proposal — markdown supported."
            rows={5}
            initialTab="write"
          />
        </div>

        <div style={dialogStyles.field}>
          <label style={dialogStyles.fieldLabel} htmlFor="bl-tags">Tags</label>
          {tags.length > 0 && (
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, marginBottom: 6 }}>
              {tags.map(t => (
                <span key={t} style={CHIP}>
                  {t}
                  <button
                    type="button"
                    style={CHIP_REMOVE}
                    aria-label={`Remove tag ${t}`}
                    onClick={() => setTags(prev => prev.filter(x => x !== t))}
                  >×</button>
                </span>
              ))}
            </div>
          )}
          <input
            id="bl-tags"
            style={dialogStyles.input}
            value={tagDraft}
            onChange={e => setTagDraft(e.target.value)}
            onKeyDown={e => { if (e.key === 'Enter' || e.key === ',') { e.preventDefault(); commitTag(); } }}
            onBlur={commitTag}
            placeholder="Add a tag, press Enter"
          />
        </div>

        <div style={dialogStyles.field}>
          <label style={dialogStyles.fieldLabel}>Priority</label>
          <div style={{ display: 'flex', gap: 6 }}>
            {PRIORITIES.map(p => (
              <button
                key={p}
                type="button"
                style={p === priority ? PRIORITY_ACTIVE : PRIORITY_BTN}
                onClick={() => setPriority(p)}
              >{p}</button>
            ))}
          </div>
        </div>

        <div style={dialogStyles.footer}>
          <span style={dialogStyles.footerNote}>⌘↵ to save · Esc to cancel</span>
          <div style={dialogStyles.footerButtons}>
            <button type="button" style={dialogStyles.secondaryBtn} onClick={onClose}>Cancel</button>
            <button
              type="button"
              style={canSave ? dialogStyles.primaryBtn : dialogStyles.primaryBtnDisabled}
              disabled={!canSave}
              onClick={save}
            >Add item</button>
          </div>
        </div>
      </div>
    </div>,
    document.body,
  );
}

const CHIP: CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 4,
  padding: '2px 4px 2px 9px',
  fontSize: 11,
  background: 'var(--ws-accent-soft, rgba(124,58,237,0.08))',
  border: '1px solid var(--ws-card-border)',
  borderRadius: 999,
  color: 'var(--ws-text-2)',
};
const CHIP_REMOVE: CSSProperties = {
  border: 0,
  background: 'transparent',
  cursor: 'pointer',
  color: 'var(--ws-text-3)',
  fontSize: 14,
  lineHeight: 1,
  padding: '0 2px',
};
const PRIORITY_BTN: CSSProperties = {
  padding: '5px 14px',
  fontSize: 11.5,
  textTransform: 'capitalize',
  border: '1px solid var(--ws-card-border)',
  borderRadius: 7,
  background: '#fff',
  color: 'var(--ws-text-2)',
  cursor: 'pointer',
};
const PRIORITY_ACTIVE: CSSProperties = {
  ...PRIORITY_BTN,
  background: 'var(--ws-accent)',
  color: '#fff',
  borderColor: 'transparent',
  fontWeight: 600,
};
