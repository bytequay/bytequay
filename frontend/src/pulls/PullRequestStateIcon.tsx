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
import { GitMerge, GitPullRequest, GitPullRequestClosed, GitPullRequestDraft } from 'lucide-react';
import type { PullRequestDto } from '../types';

/** Without a stored pull request, keep the mark neutral until its state is known. */
export function PullRequestStateIcon({ pullRequest, size, className }: {
  pullRequest: (Pick<PullRequestDto, 'state' | 'draft'> & { queued?: boolean }) | null;
  size: number;
  className?: string;
}) {
  const state = pullRequest === null ? null
    : pullRequest.state === 'merged' ? 'merged' : pullRequest.state === 'closed' ? 'closed'
      : pullRequest.draft ? 'draft' : pullRequest.queued ? 'queued' : 'open';
  const Icon = state === 'merged' ? GitMerge : state === 'closed' ? GitPullRequestClosed : state === 'draft' ? GitPullRequestDraft : GitPullRequest;
  const label = state === null ? 'Pull request status unknown' : state === 'queued' ? 'In merge queue'
    : `${state[0].toUpperCase()}${state.slice(1)} pull request`;
  return <Icon className={['agt-pr-icon', state === null ? null : `is-${state}`, className].filter(Boolean).join(' ')} size={size} role="img" aria-label={label}>
    <title>{label}</title>
  </Icon>;
}
