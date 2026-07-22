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
 * The trigger shows the run state; a dropdown offers Run / Pause / Resume.
 * Close is surfaced as a direct danger button (not buried in the menu) and
 * confirms first — closing kills the agent subprocess and reaps the
 * worktree, which can't be undone. Terminal tasks render a static label.
 */
export function RunMenu({ statusLabel = 'Running', paused = false, terminal = false, hideStatus = false, onRun, onPause, onResume, onClose }: {
  statusLabel?: string;
  paused?: boolean;
  terminal?: boolean;
  /** Hide the run-state trigger (and its Pause/Resume menu), leaving only the
   *  Close button — for surfaces that already show the phase elsewhere. */
  hideStatus?: boolean;
  onRun?: () => void;
  onPause?: () => void;
  onResume?: () => void;
  onClose?: () => void;
}) {
  const [open, setOpen] = useState(false);
  const [confirming, setConfirming] = useState(false);

  if (terminal) {
    return hideStatus ? null : <button type="button" className="btn" disabled>{statusLabel}</button>;
  }

  const pick = (fn?: () => void) => () => { setOpen(false); fn?.(); };
  const hasMenu = onRun !== undefined || onPause !== undefined || onResume !== undefined;
  const displayedStatus = paused && statusLabel === 'Running' ? 'Paused' : statusLabel;

  return (
    <>
      {!hideStatus && (
        <span className="run-menu">
          <button
            type="button"
            className="btn"
            aria-haspopup={hasMenu ? 'menu' : undefined}
            aria-expanded={hasMenu ? open : undefined}
            onClick={hasMenu ? () => setOpen(o => !o) : undefined}
          >
            <span className="ic" aria-hidden>{paused ? '⏸' : '▶'}</span>
            {displayedStatus}
            {hasMenu && <span className="chev" aria-hidden>▾</span>}
          </button>
          {open && hasMenu && (
            <div className="run-menu__pop" role="menu">
              {onRun !== undefined && (
                <button type="button" className="run-menu__item" role="menuitem" onClick={pick(onRun)}>Run</button>
              )}
              {paused
                ? onResume !== undefined && (
                  <button type="button" className="run-menu__item" role="menuitem" onClick={pick(onResume)}>Resume</button>
                )
                : onPause !== undefined && (
                  <button type="button" className="run-menu__item" role="menuitem" onClick={pick(onPause)}>Pause</button>
                )}
            </div>
          )}
        </span>
      )}
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
    </>
  );
}
