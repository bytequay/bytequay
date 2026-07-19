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
import type { NotificationDto } from '../types';
import type { DashboardPR } from '../types/dashboardPr';
import { titleFor, previewFor } from '../notificationDisplay';
import { bucketize } from '../prBuckets';
import type { DeployNoticeDto } from './homeData';

/** Visual flavour of an inbox row — picks the icon tile color. Matches
 *  the home design's six notification variants. */
export type InboxItemType = 'approval' | 'done' | 'review' | 'mention' | 'blocked' | 'info';
export type InboxItemIcon = 'pr' | 'check' | 'task' | 'attention';

/** One row in the home Inbox. A discriminated `source` keeps the raw
 *  record around so the card can offer the right actions (publish gate
 *  for parked approvals, Approve/View for PRs, …). */
export type InboxItem = {
  /** Stable key, prefixed by source ("n:", "pr:", "dep:"). */
  id: string;
  type: InboxItemType;
  icon?: InboxItemIcon;
  title: string;
  sub: string;
  /** ISO timestamp the row sorts by (newest first). */
  time: string;
  read: boolean;
  /** True for live gates/review requests; false for ackable FYI rows. */
  actionRequired?: boolean;
  source:
    | { kind: 'notification'; notification: NotificationDto }
    | { kind: 'pr'; pr: DashboardPR }
    | { kind: 'deploy'; deploy: DeployNoticeDto };
};

/** App-notification kinds map straight onto row flavours. The switch
 *  deliberately defaults to 'info' so a kind the frontend doesn't know
 *  yet (e.g. the backend's READY_TO_MERGE) still renders. */
function notificationType(n: NotificationDto): InboxItemType {
  switch (n.kind) {
    case 'AWAITING_REVIEW': return 'approval';
    case 'NEEDS_ATTENTION': return 'blocked';
    case 'AUTO_FIX_DONE':   return 'done';
    default:                return 'info';
  }
}

function notificationPayload(n: NotificationDto): Record<string, unknown> {
  try {
    const payload: unknown = JSON.parse(n.payloadJson);
    return typeof payload === 'object' && payload !== null ? payload as Record<string, unknown> : {};
  }
  catch {
    return {};
  }
}

function text(value: unknown): string | null {
  return typeof value === 'string' && value.trim() !== '' ? value.trim() : null;
}

function notificationTitle(n: NotificationDto, payload: Record<string, unknown>): string {
  const pr = typeof payload.pr === 'object' && payload.pr !== null
    ? payload.pr as Record<string, unknown>
    : {};
  return text(payload.prTitle)
    ?? text(pr.title)
    ?? text(payload.taskTitle)
    ?? text(n.summary)
    ?? text(n.title)
    ?? titleFor(n);
}

function notificationIcon(n: NotificationDto, payload: Record<string, unknown>): InboxItemIcon {
  if (n.kind === 'AWAITING_REVIEW' || n.kind === 'READY_TO_MERGE') return 'pr';
  if (n.kind === 'NEEDS_ATTENTION') return 'attention';
  if (text(payload.publishResolution) !== null) return 'check';
  if (typeof payload.prNumber === 'number' || typeof payload.shippedTaskId === 'string') return 'pr';
  return n.taskId !== null ? 'task' : 'check';
}

export function notificationToInboxItem(n: NotificationDto): InboxItem {
  const payload = notificationPayload(n);
  return {
    id: `n:${n.id}`,
    type: notificationType(n),
    icon: notificationIcon(n, payload),
    title: notificationTitle(n, payload),
    sub: [titleFor(n), previewFor(n)].filter(Boolean).join(' · '),
    time: n.createdAt,
    read: n.status !== 'UNREAD' && n.status !== 'RESOLVING',
    actionRequired: n.kind === 'AWAITING_REVIEW'
      || n.kind === 'NEEDS_ATTENTION'
      || n.kind === 'READY_TO_MERGE',
    source: { kind: 'notification', notification: n },
  };
}

/** Attention reasons that escalate a PR row to the red "blocked"
 *  flavour — states where the PR can't move without someone acting. */
const BLOCKED_REASONS = new Set(['CI_FAILING', 'MERGE_CONFLICT', 'BLOCKING']);

function reasonLabel(reason: string): string {
  switch (reason) {
    case 'CI_FAILING':     return 'CI failing';
    case 'MERGE_CONFLICT': return 'merge conflict';
    case 'BLOCKING':       return 'blocking label';
    case 'MENTIONED':      return 'you were mentioned';
    case 'NEW_COMMENT':    return 'new comments';
    default:               return reason.toLowerCase().replace(/_/g, ' ');
  }
}

/** Derive an inbox row from a cached PR, or null when the PR isn't
 *  inbox-worthy. Review requests always qualify; authored PRs only
 *  when something needs the user (failing CI, conflict, mention, …). */
export function prToInboxItem(pr: DashboardPR): InboxItem | null {
  if (bucketize(pr) !== 'inbox') return null;
  const base = {
    time: pr.updatedAt ?? pr.createdAt ?? new Date(0).toISOString(),
    read: pr.viewedAt !== null,
    source: { kind: 'pr' as const, pr },
  };
  const reason = pr.attentionReason;
  if (reason !== null && BLOCKED_REASONS.has(reason)) {
    return {
      ...base,
      id: `pr:${pr.id}`,
      type: 'blocked',
      icon: 'attention',
      title: pr.title,
      sub: `Needs attention · ${reasonLabel(reason)} · ${pr.repo} #${pr.number}`,
      actionRequired: true,
    };
  }
  if (pr.origin === 'REVIEW_REQUESTED' && pr.reviewedAt === null) {
    return {
      ...base,
      id: `pr:${pr.id}`,
      type: 'review',
      icon: 'pr',
      title: pr.title,
      sub: `Review requested · ${pr.repo} #${pr.number}`,
      actionRequired: true,
    };
  }
  if (reason === 'MENTIONED') {
    return {
      ...base,
      id: `pr:${pr.id}`,
      type: 'mention',
      icon: 'pr',
      title: pr.title,
      sub: `You were mentioned · ${pr.repo} #${pr.number}`,
      actionRequired: true,
    };
  }
  if (reason === 'NEW_COMMENT') {
    return {
      ...base,
      id: `pr:${pr.id}`,
      type: 'info',
      icon: 'pr',
      title: pr.title,
      sub: `New comments · ${pr.repo} #${pr.number}`,
      actionRequired: true,
    };
  }
  return null;
}

export function deployToInboxItem(d: DeployNoticeDto): InboxItem {
  return {
    id: `dep:${d.id}`,
    type: 'info',
    icon: d.succeeded ? 'check' : 'attention',
    title: d.repoFullName,
    sub: `Deploy ${d.succeeded ? 'succeeded' : 'failed'} · ${d.environment} · ${d.commit}`,
    time: d.finishedAt,
    read: true,
    actionRequired: false,
    source: { kind: 'deploy', deploy: d },
  };
}

/** Merge every inbox source into one newest-first list. */
export function buildInboxItems(
  notifications: NotificationDto[],
  prs: DashboardPR[],
  deploys: DeployNoticeDto[],
): InboxItem[] {
  const items = [
    ...notifications.map(notificationToInboxItem),
    ...prs.map(prToInboxItem).filter((i): i is InboxItem => i !== null),
    ...deploys.map(deployToInboxItem),
  ];
  return items.sort((a, b) => Date.parse(b.time) - Date.parse(a.time));
}
