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
import type { PullRequestDetailDto, PullRequestDto } from '../types';
import { relativeTime } from '../relativeTime';

export type PullTab = 'all' | 'active' | 'req' | 'done';
export const PULL_TABS: { key: PullTab; label: string }[] = [
  { key: 'all', label: 'All' },
  { key: 'active', label: 'My PRs' },
  { key: 'req', label: 'Review' },
  { key: 'done', label: 'Done' },
];

export type PullRow = {
  id: number;
  repo: string;
  num: number;
  title: string;
  author: string;
  time: string;
  kind: 'open' | 'merged' | 'closed';
  labels: string[];
  labelColors: Record<string, string> | null;
  status: 'passing' | 'failing' | 'pending' | null;
  additions: number;
  deletions: number;
  comments: number;
};

function toRow(pr: PullRequestDto): PullRow {
  const kind = pullRequestKind(pr.state);
  return {
    id: pr.id,
    repo: pr.repo,
    num: pr.number,
    title: pr.title,
    author: (pr.author ?? 'unknown').replace(/^@/, ''),
    time: relativeTime(pr.updatedAt),
    kind,
    labels: pr.labels,
    labelColors: pr.labelColors,
    status: pr.ciStatus === 'PASSING' ? 'passing'
      : pr.ciStatus === 'FAILING' ? 'failing'
        : pr.ciStatus === 'PENDING' ? 'pending' : null,
    additions: pr.additions,
    deletions: pr.deletions,
    comments: pr.commentCount,
  };
}

export function rowsForTab(prs: PullRequestDto[], tab: PullTab): PullRow[] {
  return prs
    .filter(pr => {
      const done = pullRequestKind(pr.state) !== 'open';
      if (tab === 'done') return done;
      if (done) return false;
      if (tab === 'active') return pr.origin === 'AUTHORED';
      if (tab === 'req') return pr.origin === 'REVIEW_REQUESTED';
      return true;
    })
    .sort((a, b) => Date.parse(b.updatedAt) - Date.parse(a.updatedAt))
    .map(toRow);
}

export function reconcilePullRequestDetail(
  pr: PullRequestDto,
  detail: Pick<PullRequestDetailDto, 'state' | 'updatedAt'>,
): PullRequestDto {
  const state = detail.state?.toLowerCase() ?? pr.state;
  const updatedAt = detail.updatedAt ?? pr.updatedAt;
  return state === pr.state && updatedAt === pr.updatedAt ? pr : { ...pr, state, updatedAt };
}

function pullRequestKind(state: string | null): PullRow['kind'] {
  const normalized = state?.toLowerCase();
  return normalized === 'merged' ? 'merged' : normalized === 'closed' ? 'closed' : 'open';
}
