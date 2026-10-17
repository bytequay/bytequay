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
import { useId, useState, type ReactNode } from 'react';
import { ChevronRight } from 'lucide-react';
import type { PullRequestDetailDto } from '../types';
import { openWebLink } from '../githubReference';

type Activity = PullRequestDetailDto['recentActivity'][number];

export default function MergeQueueRemoval({ activity, repo, number }: { activity: Activity; repo: string; number: number }) {
  const [expanded, setExpanded] = useState(false);
  const detailsId = useId();
  const removal = activity.payload.mergeQueueRemoval;
  const failedCount = removal?.failedChecks.length ?? 0;
  const reason = removal?.reason;
  const explanation = reason === 'failed_checks' ? null
    : reason === 'merge_conflict' ? 'The pull request conflicted with the target branch or another queued change.'
      : reason ? `GitHub reason: ${reason.replaceAll('_', ' ')}` : 'Removal reason unavailable.';
  const timelineUrl = `https://github.com/${repo}/pull/${number}${activity.githubId === null ? '' : `#event-${activity.githubId}`}`;
  return <div className="pr-queue-removal">
    {failedCount > 0 && <button type="button" className="pr-queue-removal__toggle" aria-expanded={expanded} aria-controls={detailsId}
      onClick={() => setExpanded(value => !value)}>
      <ChevronRight size={14} aria-hidden="true" />
      {expanded ? 'Hide' : 'View'} failed checks ({failedCount})
    </button>}
    <div id={detailsId} hidden={failedCount > 0 && !expanded}>
      {explanation && <p>{explanation}</p>}
      {failedCount > 0 && <ul>{removal?.failedChecks.map((check, index) => <li key={`${check.name}:${index}`}>
        <QueueLink href={check.url}>{check.name}</QueueLink>
      </li>)}</ul>}
      <div className="pr-queue-removal__links">
        {removal?.commitUrl && <QueueLink href={`${removal.commitUrl}/checks`}>View merge-queue checks</QueueLink>}
        <QueueLink href={timelineUrl}>View removal on GitHub</QueueLink>
      </div>
    </div>
  </div>;
}

function QueueLink({ href, children }: { href: string | null; children: ReactNode }) {
  if (!href || !/^https?:\/\//i.test(href)) return <span>{children}</span>;
  return <a href={href} onClick={event => { event.preventDefault(); void openWebLink(href); }}>{children}</a>;
}
