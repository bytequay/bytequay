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
import type { ReactNode } from 'react';
import type { PullRequestCreatedData } from '../../types/brainView';
import { PullRequestIcon } from '../TaskBrainDesignIcons';

/** GitHub-style milestone shared by Development, the task Brain, and the PR
 * timeline. The payload can be absent on older events, which still render as
 * a clear creation notice. */
export function PullRequestCreatedEvent({
  pullRequest, timestamp, timeline = false,
}: {
  pullRequest?: PullRequestCreatedData | null;
  timestamp?: ReactNode;
  /** Align the marker with the PR timeline rail rather than a conversation spine. */
  timeline?: boolean;
}) {
  const branch = pullRequest?.branch?.trim() || 'branch';
  const baseBranch = pullRequest?.baseBranch?.trim() || 'base branch';
  const number = pullRequest?.number;
  const additions = pullRequest?.additions ?? 0;
  const deletions = pullRequest?.deletions ?? 0;

  return (
    <div className={`pr-created-event${timeline ? ' pr-created-event--timeline' : ''}`}>
      <span className="pr-created-event__icon" aria-hidden>
        <PullRequestIcon size={16} strokeWidth={2.1} />
      </span>
      <div className="pr-created-event__body">
        <div className="pr-created-event__title">
          <strong>Pull request created</strong>
          {typeof number === 'number' && number > 0 && <span className="pr-created-event__number">#{number}</span>}
        </div>
        <div className="pr-created-event__flow">
          <code>{branch}</code>
          <span aria-label="into">&rarr;</span>
          <code>{baseBranch}</code>
          {(additions > 0 || deletions > 0) && (
            <span className="pr-created-event__diff">
              {additions > 0 && <span className="pr-created-event__add">+{additions}</span>}
              {deletions > 0 && <span className="pr-created-event__del">-{deletions}</span>}
            </span>
          )}
        </div>
      </div>
      {timestamp !== undefined && <span className="pr-created-event__time">{timestamp}</span>}
    </div>
  );
}
