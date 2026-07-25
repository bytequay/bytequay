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
import { ConfirmDialog } from '../../workspace/ConfirmDialog';

/**
 * The top-bar lifecycle controls (the relocated M8 Pause / Resume / Close).
 * The trigger shows the run state; running tasks use a dropdown for Run /
 * Pause, while paused tasks make Resume the direct action.
 * Close is surfaced as a direct danger button (not buried in the menu) and
 * confirms first — closing kills the agent subprocess and reaps the
 * worktree, which can't be undone. Terminal tasks render a static label.
 */
export function RunMenu({ statusLabel = 'Running', statusDetail, paused = false, terminal = false, onRun, onPause, onResume, resumeLabel = 'Resume', resumeConfirmation, onClose }: {
  statusLabel?: string;
  /** Why this state needs attention, shown inside the lifecycle menu. */
  statusDetail?: string;
  paused?: boolean;
  terminal?: boolean;
  onRun?: () => void;
  onPause?: () => void;
  onResume?: () => void;
  resumeLabel?: string;
  resumeConfirmation?: { title: string; body: string; confirmLabel: string };
  onClose?: () => void;
}) {
  const [open, setOpen] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [confirmingResume, setConfirmingResume] = useState(false);

  if (terminal) {
    return <button type="button" className="btn" disabled>{statusLabel}</button>;
  }

  const pick = (fn?: () => void) => () => { setOpen(false); fn?.(); };
  const resume = () => {
    setOpen(false);
    if (resumeConfirmation === undefined) onResume?.();
    else setConfirmingResume(true);
  };
  const hasMenu = onRun !== undefined || onPause !== undefined || onResume !== undefined;
  const displayedStatus = paused && statusLabel === 'Running' ? 'Paused' : statusLabel;

  return (
    <>
      <span className="run-menu">
        {paused && onResume !== undefined ? (
          <button type="button" className="btn" title={statusDetail} onClick={resume}>
            <span className="ic" aria-hidden>▶</span>
            {resumeLabel} · {displayedStatus}
          </button>
        ) : (
          <button
            type="button"
            className="btn"
            title={statusDetail}
            aria-haspopup={hasMenu ? 'menu' : undefined}
            aria-expanded={hasMenu ? open : undefined}
            onClick={hasMenu ? () => setOpen(o => !o) : undefined}
          >
            <span className="ic" aria-hidden>{paused ? '⏸' : '▶'}</span>
            {displayedStatus}
            {hasMenu && <span className="chev" aria-hidden>▾</span>}
          </button>
        )}
        {open && hasMenu && !(paused && onResume !== undefined) && (
          <div className="run-menu__pop" role="menu">
            {statusDetail !== undefined && statusDetail.trim().length > 0 && (
              <span className="run-menu__detail" role="status">{statusDetail}</span>
            )}
            {onRun !== undefined && (
              <button type="button" className="run-menu__item" role="menuitem" onClick={pick(onRun)}>Run</button>
            )}
            {paused
              ? onResume !== undefined && (
                <button type="button" className="run-menu__item" role="menuitem" onClick={resume}>{resumeLabel}</button>
              )
              : onPause !== undefined && (
                <button type="button" className="run-menu__item" role="menuitem" onClick={pick(onPause)}>Pause</button>
              )}
          </div>
        )}
      </span>
      {onClose !== undefined && (
        <button type="button" className="btn danger" onClick={() => setConfirming(true)}>
          <span className="ic" aria-hidden>✕</span>
          Close task
        </button>
      )}
      {confirming && (
        <ConfirmDialog
          title="Close this task?"
          body={'This stops the agent and discards the task’s working copy. Any uncommitted changes in its worktree are lost.\n\nThis can’t be undone.'}
          confirmLabel="Close task"
          destructive
          onConfirm={() => { setConfirming(false); onClose(); }}
          onCancel={() => setConfirming(false)}
        />
      )}
      {confirmingResume && resumeConfirmation !== undefined && (
        <ConfirmDialog
          title={resumeConfirmation.title}
          body={resumeConfirmation.body}
          confirmLabel={resumeConfirmation.confirmLabel}
          onConfirm={() => { setConfirmingResume(false); onResume?.(); }}
          onCancel={() => setConfirmingResume(false)}
        />
      )}
    </>
  );
}
