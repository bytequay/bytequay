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

/** Workspace-scoped new-thread modal. Matches the create-thread
 *  mockup's two-step shape: a free-form prompt at the top, a
 *  Discussion / Start-a-task picker beneath it, and "Start thread"
 *  drops the user into the existing full create page with the
 *  intent pre-set. The repo + agent + skills picker continues to
 *  live on the full page — bringing them into the modal is a
 *  follow-up commit. */
function NewThreadDialog({ onClose, onContinueFullForm }: Props) {
  const [prompt, setPrompt] = useState('');
  const [startMode, setStartMode] = useState<StartMode>('discussion');

  const handleSubmit = () => {
    onContinueFullForm({ prompt: prompt.trim(), startMode });
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
            <span style={dialogStyles.workspaceChip}>ByteQuay</span>
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
          <button type="button" style={dialogStyles.chip} disabled>+ Skills</button>
        </div>

        <div style={dialogStyles.helperRow}>
          Threads <strong>auto-title</strong> from your first message — no
          naming needed.
        </div>

        <div style={dialogStyles.modeSectionLabel}>Start as</div>
        <div style={dialogStyles.modeRow}>
          <ModeCard
            label="Discussion"
            description="No branch yet. Brainstorm, read code, plan. A task materialises only when work gets branch-worthy."
            icon="💬"
            active={startMode === 'discussion'}
            onClick={() => setStartMode('discussion')}
          />
          <ModeCard
            label="Start a task"
            description="Cut a branch + worktree now on a chosen repo and begin building immediately."
            icon="↗"
            active={startMode === 'task'}
            onClick={() => setStartMode('task')}
          />
        </div>

        <footer style={dialogStyles.footer}>
          <div style={dialogStyles.footerNote}>
            Repo + agent picks land on the full create page next — the
            modal hands off so the heavy form keeps one home.
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
              Start thread ⏎
            </button>
          </div>
        </footer>
      </div>
    </div>
  );
}

function ModeCard({ label, description, icon, active, onClick }: {
  label: string;
  description: string;
  icon: string;
  active: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      style={dialogStyles.modeCard(active)}
      aria-pressed={active}
    >
      <div style={dialogStyles.modeCardLabel}>
        <span style={{ marginRight: 6 }} aria-hidden>{icon}</span>
        {label}
      </div>
      <div style={dialogStyles.modeCardDesc}>{description}</div>
    </button>
  );
}

export default NewThreadDialog;
