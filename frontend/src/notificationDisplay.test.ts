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
import { previewFor, titleFor } from './notificationDisplay';
import type { NotificationDto } from './types';

describe('notificationDisplay.titleFor', () => {
  it('uses the legacy "Shipped" copy for ship-and-continue audit rows', () => {
    expect(titleFor(autoFixDone({ repoFullName: 'acme/widget', prNumber: 42 })))
        .toBe('Shipped');
  });

  it('renders "Pushed" for an approved push audit row', () => {
    expect(titleFor(autoFixDone({
      publishResolution: 'approved',
      action: 'push',
      message: 'Pushed feature/x.',
    }))).toBe('Pushed');
  });

  it('renders "Posted comment" for an approved post_comment audit row', () => {
    expect(titleFor(autoFixDone({
      publishResolution: 'approved',
      action: 'post_comment',
      message: 'Posted comment on acme/widget#42.',
    }))).toBe('Posted comment');
  });

  it('renders "Discarded" when the user chose not to publish', () => {
    expect(titleFor(autoFixDone({
      publishResolution: 'discarded',
      action: 'push',
      message: 'user discarded the proposed push',
    }))).toBe('Discarded');
  });

  it('renders "Publish failed" when the side effect blew up', () => {
    expect(titleFor(autoFixDone({
      publishResolution: 'failed',
      action: 'push',
      message: 'publish failed: rejected: non-fast-forward',
    }))).toBe('Publish failed');
  });

  it('keeps the kind-based titles for AWAITING_REVIEW and NEEDS_ATTENTION', () => {
    expect(titleFor(awaitingReview({}))).toBe('Awaiting your review');
    expect(titleFor(needsAttention({}))).toBe('Needs your attention');
  });
});

describe('notificationDisplay.previewFor', () => {
  it('surfaces the audit message directly for publish-gate rows', () => {
    const preview = previewFor(autoFixDone({
      publishResolution: 'approved',
      action: 'push',
      message: 'Pushed feature/x from /tmp/wt.',
    }));
    expect(preview).toBe('Pushed feature/x from /tmp/wt.');
  });

  it('surfaces the failure message for failed audit rows', () => {
    const preview = previewFor(autoFixDone({
      publishResolution: 'failed',
      action: 'post_comment',
      message: 'publish failed: GitHub returned 422',
    }));
    expect(preview).toBe('publish failed: GitHub returned 422');
  });

  it('falls back to repo + PR + next title for ship-and-continue payloads', () => {
    const preview = previewFor(autoFixDone({
      repoFullName: 'acme/widget',
      prNumber: 42,
      nextTitle: 'Address review comments',
    }));
    expect(preview).toBe('acme/widget #42 · next: Address review comments');
  });
});

function autoFixDone(payload: Record<string, unknown>): NotificationDto {
  return notification('AUTO_FIX_DONE', payload);
}

function awaitingReview(payload: Record<string, unknown>): NotificationDto {
  return notification('AWAITING_REVIEW', payload);
}

function needsAttention(payload: Record<string, unknown>): NotificationDto {
  return notification('NEEDS_ATTENTION', payload);
}

function notification(
    kind: NotificationDto['kind'],
    payload: Record<string, unknown>): NotificationDto {
  return {
    id: 'notif-1',
    kind,
    threadId: 'thread-1',
    taskId: 'task-1',
    status: 'UNREAD',
    payloadJson: JSON.stringify(payload),
    createdAt: '2026-05-22T12:00:00Z',
    readAt: null,
  };
}
