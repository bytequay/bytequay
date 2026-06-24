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

/**
 * The top-bar lifecycle dropdown (the relocated M8 Pause / Resume / Close
 * controls). The trigger shows the run state; the menu offers the actions
 * valid for that state. Terminal tasks render a static label with no menu.
 */
export function RunMenu({ statusLabel = 'Running', paused = false, terminal = false, onRun, onPause, onResume, onClose }: {
  statusLabel?: string;
  paused?: boolean;
  terminal?: boolean;
  onRun?: () => void;
  onPause?: () => void;
  onResume?: () => void;
  onClose?: () => void;
}) {
  const [open, setOpen] = useState(false);

  if (terminal) {
    return <button type="button" className="btn" disabled>{statusLabel}</button>;
  }

  const pick = (fn?: () => void) => () => { setOpen(false); fn?.(); };

  return (
    <span className="run-menu">
      <button type="button" className="btn" aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen(o => !o)}>
        <span className="ic" aria-hidden>{paused ? '⏸' : '▶'}</span>
        {paused ? 'Paused' : statusLabel}
        <span className="chev" aria-hidden>▾</span>
      </button>
      {open && (
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
          {onClose !== undefined && (
            <button type="button" className="run-menu__item danger" role="menuitem" onClick={pick(onClose)}>Close task</button>
          )}
        </div>
      )}
    </span>
  );
}
