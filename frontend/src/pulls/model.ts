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
import type { PullRequestDto } from '../types';
import { relativeTime } from '../relativeTime';

export type PullTab = 'all' | 'active' | 'req' | 'done';
export const PULL_TABS: { key: PullTab; label: string }[] = [
  { key: 'all', label: 'All' },
  { key: 'active', label: 'My PRs' },
  { key: 'req', label: 'Review requests' },
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
  status: 'passing' | 'failing' | 'pending' | null;
};

function toRow(pr: PullRequestDto): PullRow {
  return {
    id: pr.id,
    repo: pr.repo,
    num: pr.number,
    title: pr.title,
    author: (pr.author ?? 'unknown').replace(/^@/, ''),
    time: relativeTime(pr.updatedAt),
    kind: pr.state === 'merged' ? 'merged' : pr.state === 'closed' ? 'closed' : 'open',
    labels: pr.labels,
    status: pr.ciStatus === 'PASSING' ? 'passing'
      : pr.ciStatus === 'FAILING' ? 'failing'
        : pr.ciStatus === 'PENDING' ? 'pending' : null,
  };
}

export function rowsForTab(prs: PullRequestDto[], tab: PullTab): PullRow[] {
  return prs
    .filter(pr => {
      const done = pr.state === 'merged' || pr.state === 'closed';
      if (tab === 'done') return done;
      if (done) return false;
      if (tab === 'active') return pr.origin === 'AUTHORED';
      if (tab === 'req') return pr.origin === 'REVIEW_REQUESTED';
      return true;
    })
    .sort((a, b) => Date.parse(b.updatedAt) - Date.parse(a.updatedAt))
    .map(toRow);
}
