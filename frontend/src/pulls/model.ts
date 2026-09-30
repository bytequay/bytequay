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
import type { PullRequestDetailDto, PullRequestDto, PullTimelinePayloadDto } from '../types';
import { relativeTime } from '../relativeTime';

/** A timeline payload with every field at rest, so a caller sets only what its event carries. */
export const EMPTY_TIMELINE_PAYLOAD: PullTimelinePayloadDto = {
  actorAvatarUrl: null,
  actorBot: false,
  state: null,
  htmlUrl: null,
  label: null,
  labelColor: null,
  renameFrom: null,
  renameTo: null,
  commitSha: null,
  commitMessage: null,
  sourceTitle: null,
  sourceUrl: null,
  sourceNumber: null,
  milestoneTitle: null,
  requestedReviewer: null,
  assignee: null,
  path: null,
  diffHunk: null,
  line: null,
  originalLine: null,
  startLine: null,
  originalStartLine: null,
  side: null,
  resolved: false,
  outdated: false,
  reactions: [],
  comments: [],
};

export type PullTab = 'all' | 'active' | 'req' | 'monitored' | 'reviewed' | 'done';
/**
 * One fixed order, used for three things at once: the Command-digit
 * shortcuts, the order tab slots fill, and the order of the overflow menu. A
 * filter therefore never moves to a different slot as the pane resizes.
 * `empty` names the filter in the empty state. Review leads because it is what
 * the list opens on: the work waiting on the reader comes before their own.
 */
export const PULL_TABS: { key: PullTab; label: string; empty: string }[] = [
  { key: 'req', label: 'Review', empty: 'pull requests needing your review' },
  { key: 'all', label: 'Open', empty: 'open pull requests' },
  { key: 'active', label: 'My work', empty: 'open pull requests you authored' },
  { key: 'monitored', label: 'Monitored', empty: 'monitored pull requests' },
  { key: 'reviewed', label: 'Reviewed', empty: 'pull requests you reviewed' },
  { key: 'done', label: 'Closed', empty: 'closed pull requests' },
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
  monitored: boolean;
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
    monitored: pr.monitoredAt != null,
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
      if (tab === 'done') return done;
      // Monitored is the set the user pinned, not a state the repository
      // derives. ponytail: a pinned pull request stays listed after it is
      // merged; whether merging should unpin it is still an open question.
      if (tab === 'monitored') return pr.monitoredAt != null;
      if (done) return false;
      if (tab === 'active') return authored;
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

/**
 * A linked or reordered pull request may be outside the refreshed pages.
 * Dropping it would move the reviewer to another pull request.
 */
export function keepSelectedPullRequest(page: PullRequestDto[], selected: PullRequestDto | null) {
  return selected === null || page.some(pr => pr.id === selected.id) ? page : [...page, selected];
}

export function tabForInitialPullRequest(pr: PullRequestDto, preferred: PullTab, viewerLogin?: string | null): PullTab {
  if (rowsForTab([pr], preferred, viewerLogin).length > 0) return preferred;
  return pullRequestKind(pr.state) === 'open' ? 'all' : 'done';
}

export function pullRequestNeedsReview(pr: PullRequestDto) {
  return pr.handledAction === null
    && (pr.origin === 'REVIEW_REQUESTED' || pr.origin === 'OTHER' && pr.reviewedAt !== null);
}

export type PullSearch = {
  /** Login from an `@login` term, without the marker. */
  author: string | null;
  /** Every term left once the author term is taken out; matched against title and `#number`. */
  text: string;
};

/**
 * Splits a search box value into an author term and free text, so `@alice` asks
 * for one author's pull requests and `@alice flaky` narrows those by title. The
 * list filters what is already loaded, so the split happens here, not on the server.
 */
export function parsePullSearch(raw: string): PullSearch | null {
  const tokens = raw.trim().split(/\s+/).filter(token => token !== '');
  const author = tokens.find(token => token.startsWith('@') && token.length > 1);
  const text = tokens.filter(token => token !== author).join(' ');
  if (author === undefined && text === '') return null;
  return { author: author === undefined ? null : author.slice(1), text };
}

/** The author term is a prefix, so `@ab` lists ab and abc as the typing settles. */
export function pullRequestMatchesSearch(pr: Pick<PullRequestDto, 'author' | 'number' | 'title'>, search: PullSearch) {
  if (search.author !== null && !(pr.author ?? '').toLowerCase().startsWith(search.author.toLowerCase())) return false;
  if (search.text === '') return true;
  // A `#number` term matches the number, not the title digits of another pull request.
  const number = search.text.startsWith('#') ? search.text.slice(1) : search.text;
  return /^\d+$/.test(number)
    ? String(pr.number).startsWith(number)
    : pr.title.toLowerCase().includes(search.text.toLowerCase());
}

export function filterPullRequests(prs: PullRequestDto[], search: PullSearch | null): PullRequestDto[] {
  return search === null ? prs : prs.filter(pr => pullRequestMatchesSearch(pr, search));
}

export function reconcilePullRequestDetail(
  pr: PullRequestDto,
  detail: Pick<PullRequestDetailDto, 'state' | 'updatedAt'>
    & Partial<Pick<PullRequestDetailDto, 'author' | 'labels' | 'reviewers'>>,
  viewerLogin?: string | null,
): PullRequestDto {
  if (detail.updatedAt !== null && Date.parse(detail.updatedAt) < Date.parse(pr.updatedAt)) return pr;
  const state = detail.state?.toLowerCase() ?? pr.state;
  const updatedAt = detail.updatedAt ?? pr.updatedAt;
  const labels = detail.labels?.map(label => label.name) ?? pr.labels;
  const labelColors = detail.labels === undefined
    ? pr.labelColors
    : Object.fromEntries(detail.labels.map(label => [label.name, label.color ?? 'd0d7de']));
  const requestedReviewers = detail.reviewers?.map(reviewer => reviewer.login) ?? pr.requestedReviewers;
  const origin = viewerLogin && detail.author !== undefined && detail.reviewers !== undefined
    ? detail.author?.toLowerCase() === viewerLogin.toLowerCase()
      ? 'AUTHORED'
      : state === 'open' && detail.reviewers.some(reviewer =>
        reviewer.login.toLowerCase() === viewerLogin.toLowerCase() && reviewer.state.toLowerCase() === 'pending')
        ? 'REVIEW_REQUESTED'
        : 'OTHER'
    : pr.origin;
  return state === pr.state && updatedAt === pr.updatedAt
      && labels === pr.labels && labelColors === pr.labelColors && requestedReviewers === pr.requestedReviewers
      && origin === pr.origin
    ? pr
    : { ...pr, state, updatedAt, labels, labelColors, requestedReviewers, origin };
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
