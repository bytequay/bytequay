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
import { useState, type ReactNode } from 'react';
import { Diff, GitCommitHorizontal } from 'lucide-react';
import { relativeTime } from '../../relativeTime';

/** One line on the Summary timeline, like an event in a pull request's timeline: who did what, and when. */
export function TimelineEvent({ avatar, children, actions, id }: { avatar: ReactNode; children: ReactNode; actions?: ReactNode; id?: string }) {
  return <div className="agr-event" id={id}>
    <span className="agr-event__avatar">{avatar}</span>
    <div className="agr-event__text">{children}</div>
    {actions}
  </div>;
}

/** A commit on the timeline. Its card follows directly, so the row carries only what identifies the commit. */
export function CommitEvent({ ordinal, sha, title, body = '', time = null, onViewChanges }: {
  ordinal: number;
  sha: string;
  title: string;
  body?: string;
  time?: string | null;
  onViewChanges?: () => void;
}) {
  const [open, setOpen] = useState(false);
  return <>
    <TimelineEvent avatar={<GitCommitHorizontal size={16} aria-hidden="true" />}
      actions={onViewChanges && <button type="button" className="agr-event__action" title="Open this commit's diff in Changes" onClick={onViewChanges}>
        <Diff size={14} aria-hidden="true" />View changes
      </button>}>
      <span className="agr-event__ordinal">{ordinal}.</span>
      <strong className="agr-commit__title">{title}</strong>
      {body !== '' && <button type="button" className="agr-commit__more" aria-expanded={open} aria-label="Show commit message"
        title="Show commit message" onClick={() => setOpen(value => !value)}>···</button>}
      <code className="agr-commit__sha">{sha.slice(0, 7)}</code>
      {time !== null && <span className="agr-event__time"> · {relativeTime(time)}</span>}
    </TimelineEvent>
    {open && <pre className="agr-commit__message">{body}</pre>}
  </>;
}
