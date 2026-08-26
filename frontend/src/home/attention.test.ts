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
import type { PullRequestDto, RepositoryIssueSnapshotDto } from '../types';
import {
  anchorPanel,
  assignedIssues,
  attentionCandidates,
  attentionKind,
  handledSummary,
  previewMarkdown,
  snoozeUntil,
  stillHandled,
  visibleAttention,
  type AttentionItem,
  type HandledEntry,
} from './attention';

function pr(overrides: Partial<PullRequestDto>): PullRequestDto {
  return {
    id: 1,
    repo: 'bytequay/app',
    number: 42,
    title: 'Keep PR details',
    author: 'jack',
    htmlUrl: 'https://github.com/bytequay/app/pull/42',
    createdAt: null,
    updatedAt: '2026-08-15T00:00:00Z',
    origin: 'AUTHORED',
    labels: [],
    labelColors: null,
    draft: false,
    viewedAt: null,
    reviewedAt: null,
    handledAction: null,
    requestedReviewers: [],
    ciStatus: 'PASSING',
    additions: 1,
    deletions: 0,
    commentCount: 0,
    attentionReason: null,
    state: 'open',
    mergeable: true,
    mergeableState: 'clean',
    headPushedAt: null,
    headRef: 'feature',
    ...overrides,
  };
}

describe('attentionKind', () => {
  it('names each reason from what the list payload carries', () => {
    expect(attentionKind(pr({ origin: 'REVIEW_REQUESTED' }))).toBe('review');
    expect(attentionKind(pr({ mergeableState: 'blocked' }))).toBe('changes');
    expect(attentionKind(pr({ ciStatus: 'FAILING' }))).toBe('ci');
    expect(attentionKind(pr({
      origin: 'OTHER',
      handledAction: 'APPROVED',
      reviewedAt: '2026-08-14T00:00:00Z',
      headPushedAt: '2026-08-14T06:00:00Z',
    }))).toBe('commits');
    expect(attentionKind(pr({}))).toBe('merge');
  });

  it('leaves out drafts, closed pull requests and quiet ones', () => {
    expect(attentionKind(pr({ draft: true }))).toBeNull();
    expect(attentionKind(pr({ state: 'merged' }))).toBeNull();
    expect(attentionKind(pr({ mergeableState: 'behind', ciStatus: 'PENDING' }))).toBeNull();
  });

  it('sorts by reason first, then by most recently updated', () => {
    const rows = attentionCandidates([
      pr({ id: 1, number: 1, mergeableState: 'blocked' }),
      pr({ id: 2, number: 2, origin: 'REVIEW_REQUESTED', updatedAt: '2026-08-10T00:00:00Z' }),
      pr({ id: 3, number: 3, origin: 'REVIEW_REQUESTED', updatedAt: '2026-08-14T00:00:00Z' }),
    ]);

    expect(rows.map(row => row.number)).toEqual([3, 2, 1]);
  });

  it('places assigned issues right after review requests', () => {
    const rows = attentionCandidates(
      [pr({ number: 1, ciStatus: 'FAILING' }), pr({ number: 2, origin: 'REVIEW_REQUESTED' })],
      assignedIssues(snapshot(), 'workspace-id', 'JACK'),
    );

    expect(rows.map(row => row.kind)).toEqual(['review', 'assigned', 'ci']);
    expect(rows[1]).toMatchObject({
      type: 'issue',
      repo: 'bytequay/app',
      number: 7,
      workspaceId: 'workspace-id',
      commentCount: 8,
    });
  });

  it('keeps only issues assigned to the signed-in user', () => {
    expect(assignedIssues(snapshot(), 'workspace-id', 'someone-else')).toEqual([]);
  });
});

describe('anchorPanel', () => {
  const viewport = { width: 1000, height: 800 };
  const row = (top: number) => ({ top, bottom: top + 44, left: 100, right: 620 });

  it('opens below the anchor when there is room', () => {
    expect(anchorPanel(row(200), 210, 160, 410, viewport)).toEqual({ top: 250, left: 410 });
  });

  it('flips above the anchor when the panel would fall off the bottom', () => {
    expect(anchorPanel(row(700), 210, 160, 410, viewport)).toEqual({ top: 534, left: 410 });
  });

  it('clamps to both side edges', () => {
    expect(anchorPanel(row(200), 428, 320, 900, viewport).left).toBe(560);
    expect(anchorPanel(row(200), 428, 320, -40, viewport).left).toBe(12);
  });

  it('never pushes a panel taller than the viewport off the top', () => {
    expect(anchorPanel(row(60), 210, 900, 410, viewport).top).toBe(10);
  });
});

describe('previewMarkdown', () => {
  it('keeps the Markdown but drops comments, images and blank runs', () => {
    const body = [
      '<!-- issue template, do not remove -->',
      '',
      '## Description',
      '',
      '',
      '',
      'Restores the **Kudu** connector, see [the issue](https://x/1).',
      '',
      '![build badge](https://img.shields.io/badge.svg)',
      '<img src="https://x/logo.png">',
    ].join('\n');

    expect(previewMarkdown(body)).toBe(
      '## Description\n\nRestores the **Kudu** connector, see [the issue](https://x/1).');
    expect(previewMarkdown('')).toBe('');
    expect(previewMarkdown(null)).toBe('');
  });
});

describe('handled rows', () => {
  const now = Date.parse('2026-08-15T12:00:00Z');
  const acknowledged: HandledEntry = { kind: 'ack', until: null, seenUpdatedAt: '2026-08-15T00:00:00Z', label: '' };

  it('keeps a row hidden until its snooze expires', () => {
    const snoozed: HandledEntry = { ...acknowledged, kind: 'snooze', until: now + 1000 };

    expect(stillHandled(snoozed, item(), now)).toBe(true);
    expect(stillHandled(snoozed, item(), now + 2000)).toBe(false);
  });

  it('brings a row back when the pull request moves on', () => {
    expect(stillHandled(acknowledged, item(), now)).toBe(true);
    expect(stillHandled(acknowledged, item('2026-08-15T09:00:00Z'), now)).toBe(false);
  });

  it('drops handled rows from the visible list', () => {
    const candidates = attentionCandidates([pr({ number: 42 }), pr({ id: 2, number: 7, ciStatus: 'FAILING' })]);

    const visible = visibleAttention(candidates, { 'bytequay/app#42': acknowledged }, now);

    expect(visible.map(row => row.number)).toEqual([7]);
  });
});

describe('snooze targets', () => {
  // A Saturday, so "next Monday" is two days out and never lands on today.
  const saturday = new Date(2026, 7, 15, 14, 40).getTime();

  it('lands on 9:00 tomorrow and on the following Monday', () => {
    expect(new Date(snoozeUntil('hour', saturday)).getHours()).toBe(15);
    expect(new Date(snoozeUntil('tomorrow', saturday))).toEqual(new Date(2026, 7, 16, 9, 0, 0, 0));
    expect(new Date(snoozeUntil('monday', saturday))).toEqual(new Date(2026, 7, 17, 9, 0, 0, 0));
    expect(snoozeUntil('activity', saturday)).toBeNull();
  });

  it('skips today when today is already Monday', () => {
    const monday = new Date(2026, 7, 17, 14, 40).getTime();

    expect(new Date(snoozeUntil('monday', monday))).toEqual(new Date(2026, 7, 24, 9, 0, 0, 0));
  });
});

describe('handledSummary', () => {
  it('counts each kind and omits the empty half', () => {
    const ack: HandledEntry = { kind: 'ack', until: null, seenUpdatedAt: '', label: '' };
    const snooze: HandledEntry = { kind: 'snooze', until: 1, seenUpdatedAt: '', label: '' };

    expect(handledSummary([ack, ack, snooze])).toBe('2 acknowledged · 1 snoozed');
    expect(handledSummary([ack])).toBe('1 acknowledged');
    expect(handledSummary([])).toBe('');
  });
});

function item(updatedAt = '2026-08-15T00:00:00Z'): AttentionItem {
  return attentionCandidates([pr({ updatedAt })])[0];
}

function snapshot(): RepositoryIssueSnapshotDto {
  return {
    page: 0,
    pageSize: 100,
    totalIssues: 1,
    hasMore: false,
    syncedAt: '2026-08-15T00:00:00Z',
    repositoryId: 1,
    repository: { owner: 'bytequay', repo: 'app' },
    issues: [{
      id: 9,
      number: 7,
      title: 'Kudu connector fails to start',
      body: 'Full stack trace in the first comment.',
      author: 'mona-s',
      authorAvatarUrl: 'https://example.invalid/mona-s.png',
      htmlUrl: '',
      labels: ['bug'],
      assignees: ['jack'],
      commentCount: 8,
      createdAt: '2026-08-14T00:00:00Z',
      updatedAt: '2026-08-15T00:00:00Z',
    }],
  };
}
