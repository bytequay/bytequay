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
/**
 * `short` is what the tab shows; `label` is the full name, kept for the
 * tooltip and the screen-reader name, and `empty` names the filter in the
 * empty state. Short labels are what keep four filters plus the toolbar
 * buttons on one row in a narrow split pane.
 */
export const PULL_TABS: { key: PullTab; label: string; short: string; empty: string }[] = [
  { key: 'all', label: 'Open', short: 'Open', empty: 'open pull requests' },
  { key: 'active', label: 'My PRs', short: 'My PRs', empty: 'pull requests you authored' },
  { key: 'req', label: 'Needs review', short: 'Review', empty: 'pull requests needing your review' },
  { key: 'reviewed', label: 'Reviewed', short: 'Viewed', empty: 'pull requests you reviewed' },
  { key: 'done', label: 'Closed', short: 'Closed', empty: 'closed pull requests' },
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

export function pullRequestIsMine(pr: PullRequestDto, viewerLogin?: string | null) {
  return viewerLogin == null
    ? pr.origin === 'AUTHORED'
    : pr.author?.toLowerCase() === viewerLogin.toLowerCase();
}

export function rowsForTab(prs: PullRequestDto[], tab: PullTab, viewerLogin?: string | null): PullRow[] {
  return prs
    .filter(pr => {
      const done = pullRequestKind(pr.state) !== 'open';
      const authored = pullRequestIsMine(pr, viewerLogin);
      const reviewed = !authored && pr.handledAction !== null;
      if (tab === 'active') return authored;
      if (tab === 'done') return done;
      if (done) return false;
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

export function tabForInitialPullRequest(pr: PullRequestDto, preferred: PullTab, viewerLogin?: string | null): PullTab {
  if (rowsForTab([pr], preferred, viewerLogin).length > 0) return preferred;
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

export function reconcileSubmittedReview(
  pr: PullRequestDto,
  detail: Pick<PullRequestDetailDto, 'state' | 'updatedAt'>,
  event: 'COMMENT' | 'APPROVE' | 'REQUEST_CHANGES',
  reviewedAt: string,
): PullRequestDto {
  const updated = reconcilePullRequestDetail(pr, detail);
  const handledAction = event === 'APPROVE'
    ? 'APPROVED'
    : event === 'REQUEST_CHANGES' ? 'CHANGES_REQUESTED' : 'COMMENTED';
  return { ...updated, handledAction, reviewedAt };
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
