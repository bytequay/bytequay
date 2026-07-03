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
import { useEffect } from 'react';
import type { CSSProperties } from 'react';
import { createPortal } from 'react-dom';
import { WS_DIALOG_OVERLAY, WS_DIALOG_PANEL, dialogStyles } from '../../../workspace/dialogStyles';

/**
 * Confirmation shown when the user hits "Start development" on a backlog
 * item. It surfaces exactly what will be handed to the trunk — title,
 * description, tags — before dispatching, since the click kicks off a real
 * trunk planning turn (the agent confirms the request, weighs goal & risk,
 * drafts the plan, and eventually cuts a task). ESC / backdrop cancels; ⌘↵
 * confirms.
 */
export function StartDevelopmentDialog({ title, body, tags, onConfirm, onClose }: {
  title: string;
  body: string;
  tags: string[];
  onConfirm: () => void;
  onClose: () => void;
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') { e.preventDefault(); onClose(); }
      else if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) { e.preventDefault(); onConfirm(); }
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  });

  return createPortal(
    <div
      style={WS_DIALOG_OVERLAY}
      onMouseDown={e => { if (e.target === e.currentTarget) onClose(); }}
    >
      <div style={WS_DIALOG_PANEL} role="dialog" aria-label="Start development">
        <div style={dialogStyles.header}>
          <h2 style={dialogStyles.title}>Start development</h2>
        </div>

        <p style={LEAD}>
          This hands the item to the trunk agent as a fresh request. It confirms
          the goal, weighs the risk, drafts an architecture plan, then cuts a
          task for the dev agent — the normal task flow.
        </p>

        <div style={dialogStyles.field}>
          <span style={dialogStyles.fieldLabel}>Backlog item</span>
          <div style={TITLE_BOX}>{title}</div>
        </div>

        {body.trim().length > 0 && (
          <div style={dialogStyles.field}>
            <span style={dialogStyles.fieldLabel}>Content</span>
            <div style={BODY_BOX}>{body}</div>
          </div>
        )}

        {tags.length > 0 && (
          <div style={dialogStyles.field}>
            <span style={dialogStyles.fieldLabel}>Tags</span>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
              {tags.map(t => <span key={t} style={CHIP}>{t}</span>)}
            </div>
          </div>
        )}

        <div style={dialogStyles.footer}>
          <span style={dialogStyles.footerNote}>⌘↵ to start · Esc to cancel</span>
          <div style={dialogStyles.footerButtons}>
            <button type="button" style={dialogStyles.secondaryBtn} onClick={onClose}>Cancel</button>
            <button type="button" style={dialogStyles.primaryBtn} onClick={onConfirm}>
              Send to trunk &amp; plan →
            </button>
          </div>
        </div>
      </div>
    </div>,
    document.body,
  );
}

const LEAD: CSSProperties = {
  margin: '0 0 4px',
  fontSize: 12.5,
  lineHeight: 1.5,
  color: 'var(--ws-text-2)',
};
const TITLE_BOX: CSSProperties = {
  fontSize: 13.5,
  fontWeight: 600,
  color: 'var(--ws-text-1)',
};
const BODY_BOX: CSSProperties = {
  maxHeight: 200,
  overflowY: 'auto',
  padding: '8px 10px',
  fontSize: 12.5,
  lineHeight: 1.5,
  whiteSpace: 'pre-wrap',
  color: 'var(--ws-text-2)',
  background: 'var(--ws-accent-soft, rgba(124,58,237,0.05))',
  border: '1px solid var(--ws-card-border)',
  borderRadius: 7,
};
const CHIP: CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  padding: '2px 9px',
  fontSize: 11,
  background: 'var(--ws-accent-soft, rgba(124,58,237,0.08))',
  border: '1px solid var(--ws-card-border)',
  borderRadius: 999,
  color: 'var(--ws-text-2)',
};
