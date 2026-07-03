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
import { describe, expect, it } from 'vitest';
import type { NotificationDto, PullRequestDto } from '../types';
import { buildInboxItems, notificationToInboxItem, prToInboxItem } from './inboxItems';

function notif(over: Partial<NotificationDto> = {}): NotificationDto {
  return {
    id: 'n1',
    kind: 'AUTO_FIX_DONE',
    threadId: null,
    taskId: null,
    status: 'UNREAD',
    payloadJson: '{}',
    createdAt: '2026-07-01T10:00:00Z',
    readAt: null,
    ...over,
  };
}

function pr(over: Partial<PullRequestDto> = {}): PullRequestDto {
  return {
    id: 1,
    repo: 'org/backend-core',
    number: 412,
    title: 'feat: migrate auth to JWT',
    author: 'mariam',
    htmlUrl: 'https://github.com/org/backend-core/pull/412',
    createdAt: '2026-06-30T09:00:00Z',
    updatedAt: '2026-07-01T09:00:00Z',
    origin: 'REVIEW_REQUESTED',
    labels: [],
    labelColors: null,
    draft: false,
    viewedAt: null,
    reviewedAt: null,
    handledAction: null,
    requestedReviewers: ['me'],
    ciStatus: 'PASSING',
    additions: 284,
    deletions: 97,
    commentCount: 3,
    attentionReason: null,
    state: 'open',
    closedAt: null,
    mergedAt: null,
    mergeable: true,
    mergeableState: 'clean',
    headPushedAt: null,
    reviewerVerdicts: null,
    snoozedUntil: null,
    snoozeWakeReason: null,
    ...over,
  };
}

describe('notificationToInboxItem', () => {
  it('maps kinds to row flavours', () => {
    expect(notificationToInboxItem(notif({ kind: 'AWAITING_REVIEW' })).type).toBe('approval');
    expect(notificationToInboxItem(notif({ kind: 'NEEDS_ATTENTION' })).type).toBe('blocked');
    expect(notificationToInboxItem(notif({ kind: 'AUTO_FIX_DONE' })).type).toBe('done');
  });

  it('falls back to info for kinds the frontend does not know yet', () => {
    const n = notif({ kind: 'READY_TO_MERGE' as NotificationDto['kind'] });
    expect(notificationToInboxItem(n).type).toBe('info');
  });

  it('treats UNREAD and RESOLVING as unread, everything else as read', () => {
    expect(notificationToInboxItem(notif({ status: 'UNREAD' })).read).toBe(false);
    expect(notificationToInboxItem(notif({ status: 'RESOLVING' })).read).toBe(false);
    expect(notificationToInboxItem(notif({ status: 'READ' })).read).toBe(true);
    expect(notificationToInboxItem(notif({ status: 'DISMISSED' })).read).toBe(true);
  });
});

describe('prToInboxItem', () => {
  it('turns a fresh review request into a review row', () => {
    const item = prToInboxItem(pr());
    expect(item?.type).toBe('review');
    expect(item?.title).toBe('Review requested on #412');
    expect(item?.read).toBe(false);
  });

  it('escalates hard attention reasons to blocked, even on review requests', () => {
    expect(prToInboxItem(pr({ attentionReason: 'CI_FAILING' }))?.type).toBe('blocked');
    expect(prToInboxItem(pr({ origin: 'AUTHORED', attentionReason: 'MERGE_CONFLICT' }))?.type).toBe('blocked');
    expect(prToInboxItem(pr({ origin: 'AUTHORED', attentionReason: 'BLOCKING' }))?.type).toBe('blocked');
  });

  it('maps mentions and new comments on authored PRs', () => {
    expect(prToInboxItem(pr({ origin: 'AUTHORED', attentionReason: 'MENTIONED' }))?.type).toBe('mention');
    expect(prToInboxItem(pr({ origin: 'AUTHORED', attentionReason: 'NEW_COMMENT' }))?.type).toBe('info');
  });

  it('skips authored PRs with nothing actionable and reviewed requests', () => {
    expect(prToInboxItem(pr({ origin: 'AUTHORED' }))).toBeNull();
    expect(prToInboxItem(pr({ origin: 'AUTHORED', attentionReason: 'MINE' }))).toBeNull();
    expect(prToInboxItem(pr({ reviewedAt: '2026-07-01T08:00:00Z' }))).toBeNull();
  });

  it('skips snoozed and handled PRs (non-inbox buckets)', () => {
    expect(prToInboxItem(pr({ snoozedUntil: '2999-01-01T00:00:00Z' }))).toBeNull();
    expect(prToInboxItem(pr({ reviewedAt: '2026-07-01T08:00:00Z', handledAction: 'APPROVED' as PullRequestDto['handledAction'] }))).toBeNull();
  });

  it('marks viewed PRs as read', () => {
    expect(prToInboxItem(pr({ viewedAt: '2026-07-01T08:00:00Z' }))?.read).toBe(true);
  });
});

describe('buildInboxItems', () => {
  it('merges sources newest-first', () => {
    const items = buildInboxItems(
      [notif({ createdAt: '2026-07-01T10:00:00Z' })],
      [pr({ updatedAt: '2026-07-01T11:00:00Z' })],
      [],
    );
    expect(items.map(i => i.id)).toEqual(['pr:1', 'n:n1']);
  });
});
