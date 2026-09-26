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
import type { JiraIssue } from '../jira/contracts';
import type { PullRequestDto, RepositoryIssueSnapshotDto } from '../types';
import { pullRequestIsMine } from '../pulls/model';

/** Why an item is on the "Needs attention" list, in priority order. */
export type AttentionKind = 'review' | 'assigned' | 'changes' | 'blocked' | 'ci' | 'commits' | 'merge' | 'updated';

export const ATTENTION_KINDS: Record<AttentionKind, {
  label: string;
  color: string;
  background: string;
}> = {
  review: { label: 'Review requested', color: '#0969da', background: '#ddf4ff' },
  assigned: { label: 'Assigned to you', color: '#6639ba', background: '#f3eefe' },
  changes: { label: 'Changes requested', color: '#cf222e', background: '#ffebe9' },
  blocked: { label: 'Merge blocked', color: '#9a6700', background: '#fff8c5' },
  ci: { label: 'CI failing', color: '#bc4c00', background: '#fff1e5' },
  commits: { label: 'New commits', color: '#9a6700', background: '#fff8c5' },
  merge: { label: 'Ready to merge', color: '#1a7f37', background: '#dafbe1' },
  updated: { label: 'Updated', color: '#0969da', background: '#ddf4ff' },
};

const PRIORITY: AttentionKind[] = ['review', 'assigned', 'changes', 'ci', 'blocked', 'commits', 'merge', 'updated'];

/** One row: a pull request or an issue assigned to you. */
export type AttentionItem = {
  jira?: JiraIssue;
  reference?: string;
  key: string;
  type: 'pr' | 'issue';
  kind: AttentionKind;
  /** owner/repo */
  repo: string;
  number: number;
  title: string;
  author: string | null;
  authorAvatarUrl: string | null;
  createdAt: string | null;
  updatedAt: string;
  labels: string[];
  /** The pull request row the list already loaded; null for issues. */
  pr: PullRequestDto | null;
  /** Issues carry their body and comment count in the list payload. */
  body: string;
  commentCount: number;
  /** Issues open inside their workspace. */
  workspaceId: string | null;
  workspaceName?: string;
  /** Stable upstream identity, independent of which workspace supplied this issue. */
  sourceKey?: string;
};

/** Authored and explicitly monitored PRs follow all activity; review work follows its relationship to you. */
export function attentionKind(pr: PullRequestDto, viewerLogin?: string | null): AttentionKind | null {
  const mine = pullRequestIsMine(pr, viewerLogin);
  const requested = pr.origin === 'REVIEW_REQUESTED'
    || viewerLogin != null && pr.requestedReviewers.some(login => login.toLowerCase() === viewerLogin.toLowerCase());
  const reviewing = requested || pr.reviewRequestedAt != null || pr.reviewedAt !== null;
  const monitoredChange = pr.monitoredAt != null
    && Date.parse(pr.activityAt ?? pr.updatedAt) > Date.parse(pr.monitoredAt);
  const followsAllActivity = mine || monitoredChange;

  if (pr.state !== 'open' || pr.draft) return followsAllActivity ? 'updated' : null;
  if (!mine && requested && (pr.reviewedAt === null || pr.handledAction === null
    || pr.reviewRequestedAt != null && Date.parse(pr.reviewRequestedAt) > Date.parse(pr.reviewedAt))) return 'review';
  if (followsAllActivity && pr.reviewDecision === 'CHANGES_REQUESTED') return 'changes';
  if (followsAllActivity && pr.ciStatus === 'FAILING') return 'ci';
  if (followsAllActivity && pr.mergeableState === 'blocked') return 'blocked';
  if ((followsAllActivity || reviewing) && hasCommitsSinceReview(pr)) return 'commits';
  if ((followsAllActivity || reviewing) && pr.mergeable === true && pr.mergeableState === 'clean'
    && pr.ciStatus !== 'FAILING' && pr.ciStatus !== 'PENDING') return 'merge';
  return followsAllActivity ? 'updated' : null;
}

function hasCommitsSinceReview(pr: PullRequestDto): boolean {
  const since = pr.reviewedAt ?? pr.reviewRequestedAt;
  return since != null && pr.headPushedAt !== null && Date.parse(pr.headPushedAt) > Date.parse(since);
}

function pullRequestItem(pr: PullRequestDto, kind: AttentionKind): AttentionItem {
  return {
    key: `${pr.repo}#${pr.number}`,
    type: 'pr',
    kind,
    repo: pr.repo,
    number: pr.number,
    title: pr.title,
    author: pr.author,
    authorAvatarUrl: pr.authorAvatarUrl ?? null,
    createdAt: pr.createdAt,
    updatedAt: pr.updatedAt,
    labels: pr.labels,
    pr,
    body: '',
    commentCount: pr.commentCount,
    workspaceId: null,
  };
}

/** Open issues in one workspace that are assigned to the signed-in user. */
export function assignedIssues(
  snapshot: RepositoryIssueSnapshotDto,
  workspaceId: string,
  login: string,
): AttentionItem[] {
  const repo = `${snapshot.repository.owner}/${snapshot.repository.repo}`;
  const me = login.toLowerCase();
  return snapshot.issues
    .filter(issue => !issue.isGoal && issue.developmentTask == null
      && issue.assignees.some(assignee => assignee.toLowerCase() === me))
    .map(issue => ({
      key: `${repo}#${issue.number}`,
      sourceKey: `github.com:issue:${issue.id}`,
      type: 'issue' as const,
      kind: 'assigned' as const,
      repo,
      number: issue.number,
      title: issue.title,
      author: issue.author,
      authorAvatarUrl: issue.authorAvatarUrl,
      createdAt: issue.createdAt,
      updatedAt: issue.updatedAt,
      labels: issue.labels,
      pr: null as PullRequestDto | null,
      body: issue.body,
      commentCount: issue.commentCount,
      workspaceId,
    }));
}

/**
 * The dataCache entries Home and every workspace Home paint their first frame from: the last
 * /prs/attention rows, the viewer's profile, and every workspace's assigned issues. Rows are ranked
 * with all three, so seeding the rows alone would re-sort them once the login and issues land.
 */
export const ATTENTION_SEED = { prs: 'home:attention', profile: 'home:profile', issues: 'home:issues' };

export function attentionCandidates(
  prs: PullRequestDto[],
  issues: AttentionItem[] = [],
  viewerLogin?: string | null,
): AttentionItem[] {
  // The same upstream can be watched through multiple forks. Keep one stable, explicit route.
  const bySource = new Map<string, AttentionItem>();
  for (const issue of issues) {
    const key = issue.sourceKey ?? issue.key.toLowerCase();
    const previous = bySource.get(key);
    if (!previous || (issue.workspaceId ?? '').localeCompare(previous.workspaceId ?? '') < 0) bySource.set(key, issue);
  }
  const items = prs
    .map(pr => ({ pr, kind: attentionKind(pr, viewerLogin) }))
    .filter((row): row is { pr: PullRequestDto; kind: AttentionKind } => row.kind !== null)
    .map(row => pullRequestItem(row.pr, row.kind))
    .concat([...bySource.values()]);
  return items.sort((left, right) =>
    PRIORITY.indexOf(left.kind) - PRIORITY.indexOf(right.kind)
    || Date.parse(attentionActivity(right).timestamp) - Date.parse(attentionActivity(left).timestamp));
}

/** The action and age describe the same GitHub event, independently of the attention reason. */
export function attentionActivity(item: AttentionItem): { label: string; detail: string; timestamp: string } {
  const activity = item.pr?.latestActivity;
  if (!activity) {
    return { label: 'Updated', detail: item.type === 'issue' ? 'issue updated' : 'pull request updated', timestamp: item.updatedAt };
  }
  const labels: Record<string, string> = {
    commented: 'Commented', review_thread: 'Commented', committed: 'Committed',
    review_requested: 'Review requested', review_request_removed: 'Review request removed',
    labeled: 'Label added', unlabeled: 'Label removed', renamed: 'Title changed',
    'cross-referenced': 'Mentioned', head_ref_force_pushed: 'Force-pushed',
    closed: 'Closed', merged: 'Merged', reopened: 'Reopened',
    convert_to_draft: 'Converted to draft', ready_for_review: 'Ready for review',
    milestoned: 'Milestone added', demilestoned: 'Milestone removed',
    assigned: 'Assigned', unassigned: 'Unassigned',
    added_to_merge_queue: 'Queued for merge', removed_from_merge_queue: 'Removed from merge queue',
  };
  const label = activity.event === 'reviewed'
    ? activity.state?.toUpperCase() === 'APPROVED' ? 'Approved'
      : activity.state?.toUpperCase() === 'CHANGES_REQUESTED' ? 'Changes requested' : 'Reviewed'
    : labels[activity.event] ?? 'Updated';
  const context = activity.event === 'labeled' || activity.event === 'unlabeled' ? activity.label
    : activity.event === 'review_requested' && activity.requestedReviewer ? `review from ${activity.requestedReviewer}` : null;
  return { label, detail: [activity.actor, context].filter(Boolean).join(' · ') || 'on GitHub', timestamp: activity.timestamp };
}

/* ── Acknowledged and snoozed rows ──────────────────────────────── */

export type HandledEntry = {
  kind: 'ack' | 'snooze';
  /** Epoch millis the row comes back; null means "until new activity". */
  until: number | null;
  /** The item's updatedAt when it was handled. */
  seenUpdatedAt: string;
  /** Includes CI and review changes that need not advance GitHub's updatedAt. */
  seenActivityVersion?: string;
  /** Toast/footer wording, e.g. "next monday". */
  label: string;
};

export type Handled = Record<string, HandledEntry>;

const STORAGE_KEY = 'bytequay.attention.handled.v1';

export function loadHandled(): Handled {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    const parsed: unknown = raw === null ? null : JSON.parse(raw);
    if (parsed === null || typeof parsed !== 'object') return {};
    return parsed as Handled;
  }
  catch {
    return {};
  }
}

export function saveHandled(handled: Handled): void {
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(handled));
  }
  catch { /* A full or disabled store just means the row comes back. */ }
  void window.bridge?.setPrAttentionHandled?.(handled).catch(() => {});
  window.dispatchEvent(new CustomEvent('bytequay:attention-handled-changed'));
}

export function attentionActivityVersion(item: AttentionItem): string {
  const pr = item.pr;
  return JSON.stringify(pr === null ? [item.updatedAt] : [
    pr.activityAt, pr.updatedAt, pr.title, pr.state, pr.draft, pr.ciStatus, pr.mergeable, pr.mergeableState,
    pr.reviewDecision, pr.reviewRequestedAt, pr.headPushedAt, pr.commentCount, [...pr.labels].sort(),
  ]);
}

export function handledEntry(item: AttentionItem, kind: HandledEntry['kind'], until: number | null, label: string): HandledEntry {
  return { kind, until, label, seenUpdatedAt: item.updatedAt, seenActivityVersion: attentionActivityVersion(item) };
}

/** Timed snoozes stay quiet through activity; acknowledgment and activity snoozes wake on a change. */
export function stillHandled(entry: HandledEntry, item: AttentionItem, now: number): boolean {
  if (entry.kind === 'snooze' && entry.until !== null) return now < entry.until;
  if (entry.seenActivityVersion !== undefined) return entry.seenActivityVersion === attentionActivityVersion(item);
  return Date.parse(item.updatedAt) <= Date.parse(entry.seenUpdatedAt);
}

export function visibleAttention(
  candidates: AttentionItem[],
  handled: Handled,
  now: number,
): AttentionItem[] {
  return candidates.filter(item => {
    const entry = handled[item.key];
    return entry === undefined || !stillHandled(entry, item, now);
  });
}

/* ── Snooze menu ────────────────────────────────────────────────── */

export type SnoozeId = 'hour' | 'tomorrow' | 'monday' | 'activity';

export const SNOOZE_OPTIONS: { id: SnoozeId; label: string; icon: string }[] = [
  { id: 'hour', label: '1 hour', icon: '◷' },
  { id: 'tomorrow', label: 'Tomorrow', icon: '☀' },
  { id: 'monday', label: 'Next Monday', icon: '▤' },
  { id: 'activity', label: 'Until new activity', icon: '⟳' },
];

function atNineAm(from: Date, addDays: number): Date {
  const day = new Date(from);
  day.setDate(day.getDate() + addDays);
  day.setHours(9, 0, 0, 0);
  return day;
}

function nextMonday(from: Date): Date {
  // getDay(): Sunday is 0, Monday is 1. Always land on a future Monday.
  return atNineAm(from, ((8 - from.getDay()) % 7) || 7);
}

export function snoozeUntil(id: SnoozeId, now: number): number | null {
  const from = new Date(now);
  switch (id) {
    case 'hour': return now + 3_600_000;
    case 'tomorrow': return atNineAm(from, 1).getTime();
    case 'monday': return nextMonday(from).getTime();
    case 'activity': return null;
  }
}

export function snoozeHint(id: SnoozeId, now: number): string {
  const until = snoozeUntil(id, now);
  if (until === null) return '';
  const date = new Date(until);
  return id === 'monday'
    ? date.toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
    : date.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' });
}

/** "2 acknowledged · 1 snoozed", or an empty string when nothing was handled. */
export function handledSummary(entries: HandledEntry[]): string {
  const acknowledged = entries.filter(entry => entry.kind === 'ack').length;
  const snoozed = entries.length - acknowledged;
  return [
    acknowledged > 0 ? `${acknowledged} acknowledged` : null,
    snoozed > 0 ? `${snoozed} snoozed` : null,
  ].filter(Boolean).join(' · ');
}
