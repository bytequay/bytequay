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
import type { PullRequestDto, RepositoryIssueSnapshotDto } from '../types';
import { pullRequestNeedsReview } from '../pulls/model';
import { relativeTime } from '../relativeTime';

/** Why an item is on the "Needs attention" list, in priority order. */
export type AttentionKind = 'review' | 'assigned' | 'changes' | 'ci' | 'commits' | 'merge';

export const ATTENTION_KINDS: Record<AttentionKind, {
  label: string;
  color: string;
  background: string;
}> = {
  review: { label: 'Review requested', color: '#0969da', background: '#ddf4ff' },
  assigned: { label: 'Assigned to you', color: '#6639ba', background: '#f3eefe' },
  changes: { label: 'Changes requested', color: '#cf222e', background: '#ffebe9' },
  ci: { label: 'CI failing', color: '#bc4c00', background: '#fff1e5' },
  commits: { label: 'New commits', color: '#9a6700', background: '#fff8c5' },
  merge: { label: 'Ready to merge', color: '#1a7f37', background: '#dafbe1' },
};

const PRIORITY: AttentionKind[] = ['review', 'assigned', 'changes', 'ci', 'commits', 'merge'];

/** One row: a pull request or an issue assigned to you. */
export type AttentionItem = {
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
};

/**
 * First matching reason, or null when the pull request doesn't need you.
 *
 * ponytail: "Changes requested" leans on GitHub's `blocked` merge state — the
 * only review-decision signal the list payload carries. A PR still waiting for
 * its first approval reads the same. Exact would mean reading the detail's
 * reviewer states, which costs a request per row.
 */
export function attentionKind(pr: PullRequestDto): AttentionKind | null {
  if (pr.state !== 'open' || pr.draft) return null;
  if (pullRequestNeedsReview(pr) && pr.reviewedAt === null) return 'review';
  if (pr.origin === 'AUTHORED' && pr.mergeableState === 'blocked') return 'changes';
  if (pr.ciStatus === 'FAILING' || pr.attentionReason === 'CI_FAILING') return 'ci';
  if (hasCommitsSinceReview(pr)) return 'commits';
  if (pr.mergeableState === 'clean') return 'merge';
  return null;
}

function hasCommitsSinceReview(pr: PullRequestDto): boolean {
  if (pr.reviewedAt === null || pr.headPushedAt === null) return false;
  return Date.parse(pr.headPushedAt) > Date.parse(pr.reviewedAt);
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
    authorAvatarUrl: null,
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
    .filter(issue => issue.assignees.some(assignee => assignee.toLowerCase() === me))
    .map(issue => ({
      key: `${repo}#${issue.number}`,
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

export function attentionCandidates(
  prs: PullRequestDto[],
  issues: AttentionItem[] = [],
): AttentionItem[] {
  const items = prs
    .map(pr => ({ pr, kind: attentionKind(pr) }))
    .filter((row): row is { pr: PullRequestDto; kind: AttentionKind } => row.kind !== null)
    .map(row => pullRequestItem(row.pr, row.kind))
    .concat(issues);
  return items.sort((left, right) =>
    PRIORITY.indexOf(left.kind) - PRIORITY.indexOf(right.kind)
    || Date.parse(right.updatedAt) - Date.parse(left.updatedAt));
}

/* ── Acknowledged and snoozed rows ──────────────────────────────── */

export type HandledEntry = {
  kind: 'ack' | 'snooze';
  /** Epoch millis the row comes back; null means "until new activity". */
  until: number | null;
  /** The item's updatedAt when it was handled. */
  seenUpdatedAt: string;
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
}

/** False once the snooze expires or the item moved on without us. */
export function stillHandled(entry: HandledEntry, item: AttentionItem, now: number): boolean {
  if (Date.parse(item.updatedAt) > Date.parse(entry.seenUpdatedAt)) return false;
  return entry.until === null || now < entry.until;
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

/* ── Preview copy ───────────────────────────────────────────────── */

/** "56m ago", "3d ago", "just now" — matches the compact age column. */
export function ago(value: string | number | null | undefined): string {
  const elapsed = relativeTime(value, { suffix: false });
  if (elapsed === '') return '';
  return elapsed === 'now' ? 'just now' : `${elapsed} ago`;
}

/**
 * A body ready for the preview card: still Markdown, minus the parts that
 * don't belong in a few clamped lines. HTML comments carry issue-template
 * scaffolding the author never meant to publish, and images (badges, mostly)
 * would both eat the space and pull remote files on hover.
 */
export function previewMarkdown(body: string | null | undefined): string {
  if (!body) return '';
  return body
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/!\[[^\]]*\]\([^)]*\)/g, '')
    .replace(/<img\b[^>]*>/gi, '')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}

/* ── Floating panels ────────────────────────────────────────────── */

export type PanelAnchor = { top: number; bottom: number; left: number; right: number };

/**
 * Places a floating panel under its anchor, flipping it above and clamping it
 * inside the viewport when it would otherwise be cut off at an edge. Both the
 * hover preview and the snooze menu open from rows that can sit anywhere in a
 * scrolled list, so neither can assume there is room below.
 */
export function anchorPanel(
  anchor: PanelAnchor,
  width: number,
  height: number,
  desiredLeft: number,
  viewport: { width: number; height: number } = {
    width: window.innerWidth,
    height: window.innerHeight,
  },
): { top: number; left: number } {
  const below = anchor.bottom + 6;
  const top = below + height > viewport.height - 10
    ? Math.max(10, anchor.top - height - 6)
    : below;
  return {
    top,
    left: Math.max(12, Math.min(desiredLeft, viewport.width - width - 12)),
  };
}
