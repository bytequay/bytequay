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
import type { BacklogItemDto } from '../../types';

const PRIORITY_RANK: Record<string, number> = { high: 0, medium: 1, low: 2 };

/**
 * The backlog item to offer next on an idle trunk: the highest-priority
 * (then oldest) still-unstarted item. Returns undefined when a task is
 * active or the user has ignored the prompt — both mean "don't offer one".
 */
export function pickTopBacklog(
  backlog: BacklogItemDto[], hasActiveTask: boolean, ignored: boolean,
): BacklogItemDto | undefined {
  if (hasActiveTask || ignored) {
    return undefined;
  }
  return backlog
    .filter(i => i.status === 'created')
    .sort((a, b) => (PRIORITY_RANK[a.priority] ?? 1) - (PRIORITY_RANK[b.priority] ?? 1)
      || a.createdAt - b.createdAt)[0];
}

/**
 * The idle-trunk prompt: when a thread has no active task and its backlog
 * still holds unstarted items, this offers to run the top one next. Approve
 * starts development, Drop marks it not-to-proceed, and Ignore stops the
 * auto-prompting (the user then starts backlog items manually). It stays in
 * the conversation but folds to a one-line bar so it never crowds the feed.
 */
export function BacklogPrompt({ title, body, tags, onApprove, onIgnore, onDrop }: {
  title: string;
  body?: string;
  tags?: string[];
  onApprove?: () => void;
  onIgnore?: () => void;
  onDrop?: () => void;
}) {
  const [open, setOpen] = useState(true);
  return (
    <div className="trunk-prompt">
      <div className="trunk-prompt__head">No active task — run the top backlog next?</div>
      <div className={`backlog-prompt${open ? ' open' : ''}`}>
        <button
          type="button"
          className="backlog-prompt__bar"
          onClick={() => setOpen(o => !o)}
          aria-expanded={open}
        >
          <span className="backlog-prompt__ic" aria-hidden>◆</span>
          <span className="backlog-prompt__title">{title}</span>
          <span className="backlog-prompt__chev" aria-hidden>›</span>
        </button>
        {open && (
          <div className="backlog-prompt__body">
            {body !== undefined && body.trim().length > 0 && (
              <div className="triage-card__body">{body}</div>
            )}
            {tags !== undefined && tags.length > 0 && (
              <div className="triage-card__meta">
                {tags.map((t, i) => <span key={`${t}-${i}`} className="triage-card__tag">{t}</span>)}
              </div>
            )}
            <div className="backlog-prompt__actions">
              <button type="button" className="triage-card__btn triage-card__btn--start" onClick={onApprove}>
                Approve · start <span aria-hidden>→</span>
              </button>
              <button type="button" className="triage-card__btn" onClick={onIgnore}>Ignore</button>
              <button type="button" className="triage-card__btn" onClick={onDrop}>Drop</button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
