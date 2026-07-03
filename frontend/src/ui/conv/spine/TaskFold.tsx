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
import type { ReactNode } from 'react';

/**
 * Collapses a finished task's whole trunk block — every round and cut card
 * from the previous completion up to (and including) this task's completion
 * summary — into one `✓ Task N · done` bar. Collapsed by default so the
 * trunk stays scannable once a task is behind you; click to reopen the full
 * block. `forceOpen` (density = Full) overrides the local toggle. Reuses the
 * `sp-work` fold styling so it reads as a peer of the work fold.
 */
export function TaskFold({ seq, summary, forceOpen = false, children }: {
  seq?: number | null;
  /** The completion summary text, previewed on the collapsed bar. */
  summary?: string;
  forceOpen?: boolean;
  children?: ReactNode;
}) {
  const [selfOpen, setSelfOpen] = useState(false);
  const open = forceOpen || selfOpen;
  const preview = (summary ?? '').replace(/\s+/g, ' ').trim();
  return (
    <div className={`sp-work sp-taskfold${open ? ' open' : ''}`}>
      <button
        type="button"
        className="sp-work__bar"
        onClick={() => setSelfOpen(o => !o)}
        aria-expanded={open}
        disabled={forceOpen}
      >
        <span className="sp-work__sp" aria-hidden>✓</span>
        <span className="sp-work__lbl">{seq != null ? `Task ${seq} · done` : 'Task done'}</span>
        {preview.length > 0 && (
          <span className="sp-work__meta">{preview.length > 90 ? `${preview.slice(0, 90)}…` : preview}</span>
        )}
        <span className="sp-work__chev" aria-hidden>›</span>
      </button>
      {open && children !== undefined && <div className="sp-work__inner">{children}</div>}
    </div>
  );
}
