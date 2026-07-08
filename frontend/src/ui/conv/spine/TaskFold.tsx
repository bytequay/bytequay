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

const truncate = (s: string) => (s.length > 90 ? `${s.slice(0, 90)}…` : s);

/**
 * Collapses a task's whole trunk segment — the planning conversation that
 * led up to its cut, the cut card, and (once it has one) its completion
 * summary — into one bar, the instant the cut happens. Every cut task
 * folds this way; there's no "current task stays open" exception — the
 * only thing that stays unfolded is conversation that hasn't resulted in a
 * cut yet. `tone: 'done'` (blue, ✓) leads with the completion summary
 * ("done  Shipped …"); `tone: 'running'` (green, pulsing dot) has no
 * summary yet, so it leads with the task's own title instead ("Remove 4
 * dead GET routes …  in review"). Collapsed by default so the trunk stays
 * scannable; click to reopen. `forceOpen` (density = Full) overrides the
 * local toggle. Reuses the `sp-work` fold styling so it reads as a peer of
 * the work fold; the extra `sp-taskfold__trunk-dot` marks this fold's
 * branch point on the trunk line itself (see v3-conv.css).
 */
export function TaskFold({ title, summary, statusLabel, tone = 'done', forceOpen = false, children }: {
  /** The task's own name — the 'running' tone's label, since it has no
   *  completion summary yet to show instead. */
  title?: string;
  /** The completion summary text — the 'done' tone's label. Only a 'done'
   *  task has one. */
  summary?: string;
  /** The 'running' tone's trailing status word (e.g. "in review"). */
  statusLabel?: string;
  tone?: 'done' | 'running';
  forceOpen?: boolean;
  children?: ReactNode;
}) {
  const [selfOpen, setSelfOpen] = useState(false);
  const open = forceOpen || selfOpen;
  const summaryText = (summary ?? '').replace(/\s+/g, ' ').trim();
  const titleText = (title ?? '').replace(/\s+/g, ' ').trim();
  return (
    <div className={`sp-work sp-taskfold sp-taskfold--${tone}${open ? ' open' : ''}`}>
      <span className="sp-taskfold__trunk-dot" aria-hidden />
      <button
        type="button"
        className="sp-work__bar"
        onClick={() => setSelfOpen(o => !o)}
        aria-expanded={open}
        disabled={forceOpen}
      >
        <span className="sp-work__sp" aria-hidden>{tone === 'done' ? '✓' : '●'}</span>
        {tone === 'done' ? (
          <>
            <span className="sp-work__lbl">done</span>
            {summaryText.length > 0 && <span className="sp-work__meta">{truncate(summaryText)}</span>}
          </>
        ) : (
          <>
            {titleText.length > 0 && <span className="sp-work__lbl">{truncate(titleText)}</span>}
            <span className="sp-work__meta">{statusLabel ?? 'in progress'}</span>
          </>
        )}
        <span className="sp-work__chev" aria-hidden>›</span>
      </button>
      {open && children !== undefined && <div className="sp-work__inner">{children}</div>}
    </div>
  );
}
