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

export type PullTab = 'all' | 'active' | 'req' | 'reviewed' | 'done';
export const PULL_TABS: { key: PullTab; label: string }[] = [
  { key: 'all', label: 'Open' },
  { key: 'active', label: 'My PRs' },
  { key: 'req', label: 'Needs review' },
  { key: 'reviewed', label: 'Reviewed' },
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
  review: string | null;
};

function toRow(pr: PullRequestDto, reviewedTime = false): PullRow {
  const kind = pullRequestKind(pr.state);
  return {
    id: pr.id,
    repo: pr.repo,
    num: pr.number,
    title: pr.title,
    author: (pr.author ?? 'unknown').replace(/^@/, ''),
    time: reviewedTime && pr.reviewedAt
      ? `Reviewed ${relativeTime(pr.reviewedAt)}`
      : relativeTime(pr.updatedAt),
    kind,
    labels: pr.labels,
    labelColors: pr.labelColors,
    status: pr.ciStatus === 'PASSING' ? 'passing'
      : pr.ciStatus === 'FAILING' ? 'failing'
        : pr.ciStatus === 'PENDING' ? 'pending' : null,
    additions: pr.additions,
    deletions: pr.deletions,
    comments: pr.commentCount,
    review: reviewLabel(pr),
  };
}

export function rowsForTab(prs: PullRequestDto[], tab: PullTab): PullRow[] {
  return prs
    .filter(pr => {
      const done = pullRequestKind(pr.state) !== 'open';
      const reviewed = pr.origin !== 'AUTHORED' && pr.handledAction !== null;
      if (tab === 'done') return done;
      if (done) return false;
      if (tab === 'active') return pr.origin === 'AUTHORED';
      if (tab === 'req') return pullRequestNeedsReview(pr);
      if (tab === 'reviewed') return reviewed;
      return true;
    })
    .sort((a, b) => Date.parse(
      tab === 'reviewed' ? b.reviewedAt ?? b.updatedAt : b.updatedAt,
    ) - Date.parse(
      tab === 'reviewed' ? a.reviewedAt ?? a.updatedAt : a.updatedAt,
    ))
    .map(pr => toRow(pr, tab === 'reviewed'));
}

export function visibleSelectedId(rows: PullRow[], selected: number | null) {
  return selected ?? rows[0]?.id ?? null;
}

export function tabForInitialPullRequest(pr: PullRequestDto, preferred: PullTab): PullTab {
  if (rowsForTab([pr], preferred).length > 0) return preferred;
  return pullRequestKind(pr.state) === 'open' ? 'all' : 'done';
}

export function pullRequestNeedsReview(pr: PullRequestDto) {
  return pr.handledAction === null
    && (pr.origin === 'REVIEW_REQUESTED' || pr.origin === 'OTHER' && pr.reviewedAt !== null);
}

export function rowsForSearch(prs: PullRequestDto[]): PullRow[] {
  return [...prs]
    .sort((a, b) => Date.parse(b.updatedAt) - Date.parse(a.updatedAt))
    .map(pr => toRow(pr));
}

export function reconcilePullRequestDetail(
  pr: PullRequestDto,
  detail: Pick<PullRequestDetailDto, 'state' | 'updatedAt'>,
): PullRequestDto {
  const state = detail.state?.toLowerCase() ?? pr.state;
  const updatedAt = detail.updatedAt ?? pr.updatedAt;
  return state === pr.state && updatedAt === pr.updatedAt ? pr : { ...pr, state, updatedAt };
}

export function reconcilePostedComment(
  pr: PullRequestDto,
  detail: Pick<PullRequestDetailDto, 'state' | 'updatedAt'>,
  kind: 'discussion' | 'review',
  postedAt: string,
): PullRequestDto {
  const updated = reconcilePullRequestDetail(pr, detail);
  return kind === 'discussion'
    ? { ...updated, commentCount: updated.commentCount + 1 }
    : { ...updated, handledAction: 'COMMENTED', reviewedAt: postedAt };
}

function pullRequestKind(state: string | null): PullRow['kind'] {
  const normalized = state?.toLowerCase();
  return normalized === 'merged' ? 'merged' : normalized === 'closed' ? 'closed' : 'open';
}

function reviewLabel(pr: PullRequestDto) {
  if (pullRequestKind(pr.state) !== 'open') return null;
  switch (pr.handledAction) {
    case 'APPROVED': return 'Approved';
    case 'CHANGES_REQUESTED': return 'Changes requested';
    case 'COMMENTED': return 'Reviewed';
    default: return null;
  }
}
