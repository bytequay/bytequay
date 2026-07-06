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

/** One row in the home Inbox. A discriminated `source` keeps the raw
 *  record around so the card can offer the right actions (publish gate
 *  for parked approvals, Approve/View for PRs, …). */
export type InboxItem = {
  /** Stable key, prefixed by source ("n:", "pr:", "dep:"). */
  id: string;
  type: InboxItemType;
  title: string;
  sub: string;
  /** ISO timestamp the row sorts by (newest first). */
  time: string;
  read: boolean;
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

export function notificationToInboxItem(n: NotificationDto): InboxItem {
  return {
    id: `n:${n.id}`,
    type: notificationType(n),
    title: titleFor(n),
    sub: previewFor(n),
    time: n.createdAt,
    read: n.status !== 'UNREAD' && n.status !== 'RESOLVING',
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
      title: `PR #${pr.number} needs attention`,
      sub: `${reasonLabel(reason)} — ${pr.title} — ${pr.repo}`,
    };
  }
  if (pr.origin === 'REVIEW_REQUESTED' && pr.reviewedAt === null) {
    return {
      ...base,
      id: `pr:${pr.id}`,
      type: 'review',
      title: `Review requested on #${pr.number}`,
      sub: `${pr.title} — ${pr.repo}`,
    };
  }
  if (reason === 'MENTIONED') {
    return {
      ...base,
      id: `pr:${pr.id}`,
      type: 'mention',
      title: `You were mentioned on #${pr.number}`,
      sub: `${pr.title} — ${pr.repo}`,
    };
  }
  if (reason === 'NEW_COMMENT') {
    return {
      ...base,
      id: `pr:${pr.id}`,
      type: 'info',
      title: `New comments on #${pr.number}`,
      sub: `${pr.title} — ${pr.repo}`,
    };
  }
  return null;
}

export function deployToInboxItem(d: DeployNoticeDto): InboxItem {
  return {
    id: `dep:${d.id}`,
    type: 'info',
    title: `Deploy ${d.succeeded ? 'succeeded' : 'failed'}`,
    sub: `${d.environment} — ${d.repoFullName} @ ${d.commit}`,
    time: d.finishedAt,
    read: true,
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
