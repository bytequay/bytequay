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
  it('renders "PR #N opened" for ship-and-continue audit rows', () => {
    // A shipped task opens a PR but isn't done until it merges, so the
    // audit row says the PR opened rather than the old "Shipped".
    expect(titleFor(autoFixDone({
      shippedTaskId: 'task-1', repoFullName: 'acme/widget', prNumber: 42,
    }))).toBe('PR #42 opened');
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

  it('labels discard after an interrupted publish distinctly', () => {
    expect(titleFor(autoFixDone({
      publishResolution: 'discarded_after_interrupt',
      action: 'push',
      message: 'Remote action may already have run.',
    }))).toBe('Interrupted approval discarded');
  });

  it('renders "Publish failed" when the side effect blew up', () => {
    expect(titleFor(autoFixDone({
      publishResolution: 'failed',
      action: 'push',
      message: 'publish failed: rejected: non-fast-forward',
    }))).toBe('Publish failed');
  });

  it('renders concurrent-approval audit rows as the action-specific approved label', () => {
    // approved_concurrent is written when an approve completed remotely
    // but lost the local finalize race to a concurrent discard. The
    // bell title should still read as success for the user — the
    // remote action ran.
    expect(titleFor(autoFixDone({
      publishResolution: 'approved_concurrent',
      action: 'push',
      message: 'another resolver finalized this row first',
    }))).toBe('Pushed');
    expect(titleFor(autoFixDone({
      publishResolution: 'approved_concurrent',
      action: 'post_comment',
      message: 'another resolver finalized this row first',
    }))).toBe('Posted comment');
    expect(titleFor(autoFixDone({
      publishResolution: 'approved_concurrent',
      action: 'merge_pr',
      message: 'another resolver finalized this row first',
    }))).toBe('Approved');
  });

  it('renders interrupted approvals as requiring local resolution', () => {
    // Three audit-resolution shapes all collapse to the same headline:
    // the legacy single 'interrupted' string and the two v5 variants
    // that distinguish whether the remote outcome was confirmed.
    expect(titleFor(autoFixDone({
      publishResolution: 'interrupted',
      action: 'push',
      message: 'Check remote state.',
    }))).toBe('Approval interrupted');
    expect(titleFor(autoFixDone({
      publishResolution: 'interrupted_unconfirmed',
      action: 'push',
      message: 'publish outcome unknown — the remote action may or may not have run',
    }))).toBe('Approval interrupted');
    expect(titleFor(autoFixDone({
      publishResolution: 'interrupted_confirmed',
      action: 'post_comment',
      message: 'remote action completed; local finalization failed',
    }))).toBe('Approval interrupted');
  });

  it('renders locally recovered approvals distinctly', () => {
    expect(titleFor(autoFixDone({
      publishResolution: 'recovered',
      action: 'push',
      message: 'Closed without repeating publish.',
    }))).toBe('Resolved locally');
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
