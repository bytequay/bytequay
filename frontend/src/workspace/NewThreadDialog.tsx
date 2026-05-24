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
import { WS_DIALOG_OVERLAY, WS_DIALOG_PANEL, dialogStyles } from './dialogStyles';

type StartMode = 'discussion' | 'task';

type Props = {
  /** Close without taking any action — fired on Cancel and the
   *  backdrop. */
  onClose: () => void;
  /** Punch out to the existing full create page so the user can
   *  fill in repo + agent + skills + linked PR/issue. The dialog
   *  keeps the minimum-friction "prompt + mode" picker; everything
   *  beyond is owned by the full page (a polish pass can inline
   *  more of it here once the dialog has proven the shape). */
  onContinueFullForm: (params: { prompt: string; startMode: StartMode }) => void;
};

/**
 * Workspace-scoped new-thread modal per
 * docs/mockups/design/tasks/create-thread.png — a free-form prompt
 * at the top, a chip row for Add files / Reference a PR / Skills, a
 * "Lands on the thread's trunk — plan here; a task begins when work
 * turns branch-worthy" hint with the word "trunk" picked out, and an
 * ADVANCED · INHERITS BYTEQUAY DEFAULTS section that exposes the
 * repo + agent picks as inline chips with the workspace defaults
 * pre-filled. The Discussion / Start-a-task picker is removed — the
 * trunk *is* the discussion altitude, and tasks materialise from the
 * first branch-worthy turn rather than an up-front choice.
 */
function NewThreadDialog({ onClose, onContinueFullForm }: Props) {
  const [prompt, setPrompt] = useState('');

  const handleSubmit = () => {
    // Trunk-first creation: the thread lands on the trunk; the agent
    // proposes materialising a task on the first branch-worthy turn.
    onContinueFullForm({ prompt: prompt.trim(), startMode: 'discussion' });
  };

  return (
    <div
      style={WS_DIALOG_OVERLAY}
      onClick={onClose}
      role="presentation"
    >
      <div
        style={WS_DIALOG_PANEL}
        role="dialog"
        aria-modal="true"
        aria-labelledby="new-thread-title"
        onClick={e => e.stopPropagation()}
      >
        <header style={dialogStyles.header}>
          <h2 id="new-thread-title" style={dialogStyles.title}>
            New thread
            <span style={dialogStyles.workspaceChip}>
              <span style={brandSquareStyle} aria-hidden>B</span>
              ByteQuay
            </span>
          </h2>
          <button
            type="button"
            style={dialogStyles.closeBtn}
            onClick={onClose}
            aria-label="Close"
          >
            ✕
          </button>
        </header>

        <textarea
          autoFocus
          value={prompt}
          onChange={e => setPrompt(e.target.value)}
          placeholder="What do you want to work on or think through?"
          style={dialogStyles.textarea}
        />

        <div style={dialogStyles.chipRow}>
          <button type="button" style={dialogStyles.chip} disabled>📎 Add files</button>
          <button type="button" style={dialogStyles.chip} disabled>↗ Reference a PR</button>
          <button type="button" style={dialogStyles.chip} disabled>✦ Skills</button>
        </div>

        <div style={trunkHintRowStyle}>
          <span style={trunkHintBulletStyle} aria-hidden>●</span>
          <span style={trunkHintTextStyle}>
            Lands on the thread's <span style={trunkLinkStyle}>trunk</span> —
            plan here; a task begins when work turns branch-worthy.
            Auto-titled from your first message.
          </span>
        </div>

        <div style={advancedHeaderStyle}>
          ADVANCED <span style={advancedMutedStyle}>· INHERITS BYTEQUAY DEFAULTS</span>
          <span style={advancedOverrideHintStyle}>— override if needed</span>
        </div>
        <div style={advancedRowStyle}>
          <button type="button" style={advancedChipStyle} disabled>
            <span style={advChipGlyphStyle('repo')} aria-hidden>●</span>
            <span style={advChipLabelStyle}>bytequay</span>
            <span style={advChipMetaStyle}>· backend, docs</span>
            <span style={advChipCaretStyle}>▾</span>
          </button>
          <button type="button" style={advancedChipStyle} disabled>
            <span style={advChipGlyphStyle('agent')} aria-hidden>C</span>
            <span style={advChipLabelStyle}>Claude Code</span>
            <span style={advChipMetaStyle}>CLI · sonnet-4.6</span>
            <span style={advChipCaretStyle}>▾</span>
          </button>
        </div>

        <footer style={dialogStyles.footer}>
          <div style={dialogStyles.footerNote}>
            Lands on the trunk · inherits ByteQuay's memory &amp; skills
          </div>
          <div style={dialogStyles.footerButtons}>
            <button type="button" style={dialogStyles.secondaryBtn} onClick={onClose}>
              Cancel
            </button>
            <button
              type="button"
              style={dialogStyles.primaryBtn}
              onClick={handleSubmit}
            >
              Start thread <span style={{ marginLeft: 4 }}>⏎</span>
            </button>
          </div>
        </footer>
      </div>
    </div>
  );
}

const brandSquareStyle: React.CSSProperties = {
  width: 16,
  height: 16,
  borderRadius: 4,
  background: 'linear-gradient(135deg, #7c3aed, #6366f1)',
  color: '#fff',
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  fontSize: 10,
  fontWeight: 700,
  marginRight: 6,
};

const trunkHintRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'flex-start',
  gap: 8,
  marginTop: 14,
  fontSize: 11,
  color: 'var(--ws-text-3)',
  lineHeight: 1.55,
};

const trunkHintBulletStyle: React.CSSProperties = {
  color: '#7c3aed',
  fontSize: 10,
  marginTop: 2,
  flexShrink: 0,
};

const trunkHintTextStyle: React.CSSProperties = {
  flex: 1,
};

const trunkLinkStyle: React.CSSProperties = {
  color: '#7c3aed',
  fontWeight: 600,
  borderBottom: '1px dotted rgba(124,58,237,0.5)',
  cursor: 'help',
};

const advancedHeaderStyle: React.CSSProperties = {
  marginTop: 14,
  fontSize: 10,
  fontWeight: 700,
  letterSpacing: '0.06em',
  color: 'var(--ws-text-2)',
};

const advancedMutedStyle: React.CSSProperties = {
  fontWeight: 500,
  color: 'var(--ws-text-3)',
};

const advancedOverrideHintStyle: React.CSSProperties = {
  marginLeft: 8,
  fontWeight: 500,
  color: 'var(--ws-text-4)',
  letterSpacing: '0.02em',
  textTransform: 'none',
  fontStyle: 'italic',
};

const advancedRowStyle: React.CSSProperties = {
  display: 'flex',
  gap: 8,
  marginTop: 8,
  flexWrap: 'wrap',
};

const advancedChipStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 6,
  padding: '6px 12px',
  border: '1px solid var(--ws-card-border)',
  background: 'rgba(255,255,255,0.86)',
  borderRadius: 10,
  fontSize: 12,
  color: 'var(--ws-text-1)',
  cursor: 'pointer',
};

function advChipGlyphStyle(kind: 'repo' | 'agent'): React.CSSProperties {
  if (kind === 'agent') {
    return {
      width: 18,
      height: 18,
      borderRadius: 999,
      background: '#ea580c',
      color: '#fff',
      display: 'inline-flex',
      alignItems: 'center',
      justifyContent: 'center',
      fontSize: 11,
      fontWeight: 700,
      flexShrink: 0,
    };
  }
  return {
    color: '#7c3aed',
    fontSize: 10,
    flexShrink: 0,
  };
}

const advChipLabelStyle: React.CSSProperties = {
  fontWeight: 600,
};

const advChipMetaStyle: React.CSSProperties = {
  color: 'var(--ws-text-3)',
  fontSize: 11,
};

const advChipCaretStyle: React.CSSProperties = {
  color: 'var(--ws-text-4)',
  fontSize: 10,
  marginLeft: 4,
};

export default NewThreadDialog;
