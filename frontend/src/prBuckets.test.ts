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
import type { PullRequestDto } from './types';
import {
  RESURFACE_GRACE_MS,
  bucketize,
  isHandled,
  isResurfaced,
} from './prBuckets';

function pr(overrides: Partial<PullRequestDto> = {}): PullRequestDto {
  return {
    id: 1,
    repo: 'owner/repo',
    number: 1,
    title: 'Test',
    author: 'alice',
    htmlUrl: '',
    createdAt: '2026-04-23T09:00:00Z',
    updatedAt: '2026-04-23T10:00:00Z',
    origin: 'AUTHORED',
    labels: [],
    labelColors: null,
    draft: false,
    viewedAt: null,
    reviewedAt: null,
    handledAction: null,
    requestedReviewers: [],
    ciStatus: null,
    additions: 0,
    deletions: 0,
    commentCount: 0,
    attentionReason: null,
    state: 'open',
    closedAt: null,
    mergedAt: null,
    mergeable: null,
    mergeableState: null,
    headPushedAt: null,
    reviewerVerdicts: null,
    snoozedUntil: null,
    snoozeWakeReason: null,
    ...overrides,
  };
}

describe('isHandled', () => {
  it('MERGED, DISMISSED, MANUAL are handled (terminal)', () => {
    expect(isHandled(pr({ handledAction: 'MERGED' }))).toBe(true);
    expect(isHandled(pr({ handledAction: 'DISMISSED' }))).toBe(true);
    expect(isHandled(pr({ handledAction: 'MANUAL' }))).toBe(true);
  });

  it('APPROVED is NOT handled — it stays in the Inbox Cleared zone', () => {
    expect(isHandled(pr({ handledAction: 'APPROVED' }))).toBe(false);
  });

  it('COMMENTED and CHANGES_REQUESTED are not handled', () => {
    expect(isHandled(pr({ handledAction: 'COMMENTED' }))).toBe(false);
    expect(isHandled(pr({ handledAction: 'CHANGES_REQUESTED' }))).toBe(false);
  });

  it('null handledAction is not handled', () => {
    expect(isHandled(pr({ handledAction: null }))).toBe(false);
  });
});

describe('bucketize', () => {
  it('returns inbox when the PR has never been reviewed', () => {
    expect(bucketize(pr({ reviewedAt: null }))).toBe('inbox');
  });

  it('returns handled for MERGED regardless of activity', () => {
    expect(bucketize(pr({
      reviewedAt: '2026-04-23T10:00:00Z',
      updatedAt: '2026-04-25T10:00:00Z',
      handledAction: 'MERGED',
    }))).toBe('handled');
  });

  it('returns handled for MANUAL (user dismissed from Inbox)', () => {
    expect(bucketize(pr({ handledAction: 'MANUAL' }))).toBe('handled');
  });

  it('returns handled for DISMISSED (user closed the PR)', () => {
    expect(bucketize(pr({ handledAction: 'DISMISSED' }))).toBe('handled');
  });

  it('APPROVED stays in Inbox — approved is the Cleared zone, not terminal', () => {
    expect(bucketize(pr({
      reviewedAt: '2026-04-23T10:00:00Z',
      handledAction: 'APPROVED',
    }))).toBe('inbox');
  });

  it('COMMENTED / CHANGES_REQUESTED stay in Inbox (Awaiting author)', () => {
    expect(bucketize(pr({ handledAction: 'COMMENTED' }))).toBe('inbox');
    expect(bucketize(pr({ handledAction: 'CHANGES_REQUESTED' }))).toBe('inbox');
  });
});

describe('isResurfaced', () => {
  it('is false for a fresh PR that was never reviewed', () => {
    expect(isResurfaced(pr({ reviewedAt: null }))).toBe(false);
  });

  it('is true for a COMMENTED PR whose author replied (updatedAt past grace)', () => {
    const reviewed = '2026-04-23T10:00:00Z';
    const updated = '2026-04-23T13:00:00Z';
    expect(isResurfaced(pr({
      reviewedAt: reviewed,
      updatedAt: updated,
      handledAction: 'COMMENTED',
    }))).toBe(true);
  });

  it('is false for MANUAL — Handled PRs do not auto-resurface', () => {
    expect(isResurfaced(pr({
      reviewedAt: '2026-04-23T10:00:00Z',
      updatedAt: '2026-04-25T10:00:00Z',
      handledAction: 'MANUAL',
    }))).toBe(false);
  });

  it('is false for APPROVED — Cleared PRs stay Cleared', () => {
    expect(isResurfaced(pr({
      reviewedAt: '2026-04-23T10:00:00Z',
      updatedAt: '2026-04-25T10:00:00Z',
      handledAction: 'APPROVED',
    }))).toBe(false);
  });

  it('exactly at the grace boundary is not resurfaced', () => {
    const reviewed = '2026-04-23T10:00:00Z';
    const updated = new Date(new Date(reviewed).getTime() + RESURFACE_GRACE_MS).toISOString();
    expect(isResurfaced(pr({
      reviewedAt: reviewed,
      updatedAt: updated,
      handledAction: 'COMMENTED',
    }))).toBe(false);
  });
});

// ── Phase 3 kanban refactor ────────────────────────────────────────────────
