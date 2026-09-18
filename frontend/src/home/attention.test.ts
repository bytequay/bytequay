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
  attentionActivity,
  attentionKind,
  handledEntry,
  handledSummary,
  previewMarkdown,
  snoozeUntil,
  stillHandled,
  visibleAttention,
  type AttentionItem,
  type HandledEntry,
} from './attention';
import { pr } from '../pulls/fixtures/pullRequest';


describe('attentionKind', () => {
  it('keeps the cached upstream PR author avatar and handles an uncached author', () => {
    const avatarUrl = 'https://avatars.githubusercontent.com/u/123';
    const [withAvatar] = attentionCandidates([pr({ authorAvatarUrl: avatarUrl })]);
    const [withoutAvatar] = attentionCandidates([pr({})]);

    expect(withAvatar.authorAvatarUrl).toBe(avatarUrl);
    expect(withoutAvatar.authorAvatarUrl).toBeNull();
  });

  it('keeps every authored PR eligible, with truthful status reasons', () => {
    expect(attentionKind(pr({ mergeableState: 'blocked' }))).toBe('blocked');
    expect(attentionKind(pr({ reviewDecision: 'CHANGES_REQUESTED' }))).toBe('changes');
    expect(attentionKind(pr({ ciStatus: 'FAILING' }))).toBe('ci');
    expect(attentionKind(pr({ draft: true }))).toBe('updated');
    expect(attentionKind(pr({ state: 'closed' }))).toBe('updated');
    expect(attentionKind(pr({ state: 'merged' }))).toBe('updated');
    expect(attentionKind(pr({ mergeableState: 'behind', ciStatus: 'PENDING' }))).toBe('updated');
    expect(attentionKind(pr({}))).toBe('merge');
    expect(attentionKind(pr({ origin: 'OTHER', author: 'JACK', ciStatus: 'FAILING' }), 'jack')).toBe('ci');
  });

  it('excludes unrelated PRs even when their CI fails or they are mergeable', () => {
    expect(attentionKind(pr({ origin: 'OTHER', ciStatus: 'FAILING' }))).toBeNull();
    expect(attentionKind(pr({ origin: 'OTHER', attentionReason: 'CI_FAILING' }))).toBeNull();
    expect(attentionKind(pr({ origin: 'OTHER' }))).toBeNull();
    expect(attentionKind(pr({ origin: 'AUTHORED', author: 'alice' }), 'jack')).toBeNull();
  });

  it('includes requests and re-requests after an earlier completed review', () => {
    expect(attentionKind(pr({ origin: 'REVIEW_REQUESTED' }))).toBe('review');
    expect(attentionKind(pr({ origin: 'OTHER', author: 'alice', requestedReviewers: ['JACK'] }), 'jack')).toBe('review');
    expect(attentionKind(pr({
      origin: 'REVIEW_REQUESTED', handledAction: 'APPROVED',
      reviewedAt: '2026-08-14T00:00:00Z', reviewRequestedAt: '2026-08-15T00:00:00Z',
    }))).toBe('review');
  });

  it('follows commits and merge readiness for requested or previously reviewed PRs', () => {
    for (const relationship of [
      { reviewedAt: '2026-08-14T00:00:00Z' },
      { reviewRequestedAt: '2026-08-14T00:00:00Z' },
    ]) {
      const reviewed = pr({ origin: 'OTHER', handledAction: 'APPROVED', ...relationship });
      expect(attentionKind(reviewed)).toBe('merge');
      expect(attentionKind({ ...reviewed, headPushedAt: '2026-08-14T06:00:00Z' })).toBe('commits');
      expect(attentionKind({ ...reviewed, ciStatus: 'FAILING' })).toBeNull();
      expect(attentionKind({ ...reviewed, ciStatus: 'PENDING' })).toBeNull();
      expect(attentionKind({ ...reviewed, draft: true })).toBeNull();
      expect(attentionKind({ ...reviewed, state: 'merged' })).toBeNull();
    }
  });

  it('follows changes only after monitoring starts, including CI and closure', () => {
    const monitored = pr({
      origin: 'OTHER', monitoredAt: '2026-08-15T01:00:00Z', activityAt: '2026-08-15T00:00:00Z',
    });
    expect(attentionKind(monitored)).toBeNull();
    expect(attentionKind({ ...monitored, activityAt: monitored.monitoredAt })).toBeNull();
    const changed = { ...monitored, activityAt: '2026-08-15T02:00:00Z' };
    expect(attentionKind({ ...changed, ciStatus: 'FAILING' })).toBe('ci');
    expect(attentionKind({ ...changed, ciStatus: 'PENDING' })).toBe('updated');
    expect(attentionKind({ ...changed, state: 'closed' })).toBe('updated');
    expect(attentionKind({ ...changed, state: 'merged' })).toBe('updated');
    expect(attentionKind({ ...changed, draft: true })).toBe('updated');
    expect(attentionKind({ ...changed, monitoredAt: null, ciStatus: 'FAILING' })).toBeNull();
  });

  it('sorts by reason first, then by most recently updated', () => {
    const rows = attentionCandidates([
      pr({ id: 1, number: 1, mergeableState: 'blocked' }),
      pr({ id: 2, number: 2, origin: 'REVIEW_REQUESTED', updatedAt: '2026-08-10T00:00:00Z' }),
      pr({ id: 3, number: 3, origin: 'REVIEW_REQUESTED', updatedAt: '2026-08-14T00:00:00Z' }),
    ]);

    expect(rows.map(row => row.number)).toEqual([3, 2, 1]);
  });

  it('orders equal-priority rows by GitHub activity instead of local observation time', () => {
    const rows = attentionCandidates([
      pr({ id: 1, number: 1, origin: 'REVIEW_REQUESTED', activityAt: '2026-09-08T01:47:58Z',
        latestActivity: { event: 'commented', actor: 'bob', timestamp: '2026-08-16T00:00:00Z', state: null, label: null, requestedReviewer: null } }),
      pr({ id: 2, number: 2, origin: 'REVIEW_REQUESTED', updatedAt: '2026-08-17T00:00:00Z', activityAt: '2026-09-08T01:47:55Z' }),
    ]);
    expect(rows.map(row => row.number)).toEqual([2, 1]);
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

  it('excludes Goals and previously started development, even when the source stays open', () => {
    const source = snapshot();
    source.issues.push(
      { ...source.issues[0], id: 10, number: 8, isGoal: true },
      { ...source.issues[0], id: 11, number: 9, developmentTask: { taskId: 'finished-task', workspaceId: 'other-workspace' } },
    );
    expect(assignedIssues(source, 'workspace-id', 'jack').map(item => item.number)).toEqual([7]);
    source.issues[1].isGoal = false;
    expect(assignedIssues(source, 'workspace-id', 'jack').map(item => item.number)).toEqual([7, 8]);
  });

  it('deduplicates the upstream issue across workspaces with a stable launch destination', () => {
    const first = assignedIssues(snapshot(), 'workspace-a', 'jack');
    const second = assignedIssues(snapshot(), 'workspace-b', 'jack');
    expect(attentionCandidates([], [...second, ...first])).toEqual(first);
    expect(attentionCandidates([], [...first, ...second])).toEqual(first);
    // Repository display names can change without changing the source identity.
    expect(attentionCandidates([], [...first, { ...second[0], key: 'renamed/app#7', repo: 'renamed/app' }])).toEqual(first);
    expect(attentionCandidates([], [...first, { ...second[0], sourceKey: 'another-host:issue:9' }])).toHaveLength(2);
  });

  it('keeps acknowledgement after Goal classification changes', () => {
    const source = snapshot();
    const original = assignedIssues(source, 'workspace-id', 'jack')[0];
    const handled = { [original.key]: handledEntry(original, 'ack', null, 'acknowledged') };
    source.issues[0].isGoal = true;
    expect(assignedIssues(source, 'workspace-id', 'jack')).toEqual([]);
    source.issues[0].isGoal = false;
    expect(visibleAttention(assignedIssues(source, 'workspace-id', 'jack'), handled, Date.now())).toEqual([]);
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
    expect(stillHandled(snoozed, item('2026-08-15T09:00:00Z'), now)).toBe(true);
    expect(stillHandled(snoozed, item(), now + 2000)).toBe(false);
  });

  it('brings a row back when the pull request moves on', () => {
    expect(stillHandled(acknowledged, item(), now)).toBe(true);
    expect(stillHandled(acknowledged, item('2026-08-15T09:00:00Z'), now)).toBe(false);
  });

  it('wakes acknowledgments and activity snoozes on CI changes without a new updatedAt', () => {
    const original = attentionCandidates([pr({})])[0];
    const changed = attentionCandidates([pr({ ciStatus: 'FAILING' })])[0];
    for (const kind of ['ack', 'snooze'] as const) {
      const entry = handledEntry(original, kind, null, '');
      expect(stillHandled(entry, original, now)).toBe(true);
      expect(stillHandled(entry, changed, now)).toBe(false);
    }
    const timed = handledEntry(original, 'snooze', now + 1000, '');
    expect(stillHandled(timed, changed, now)).toBe(true);
    expect(stillHandled(timed, changed, now + 1000)).toBe(false);
  });

  it('uses meaningful activity versions when displayed status returns to its previous value', () => {
    const original = attentionCandidates([pr({ activityAt: '2026-08-15T01:00:00Z' })])[0];
    const changed = attentionCandidates([pr({ activityAt: '2026-08-15T02:00:00Z' })])[0];
    expect(stillHandled(handledEntry(original, 'ack', null, ''), changed, now)).toBe(false);
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

describe('attentionActivity', () => {
  const base: AttentionItem = {
    key: 'o/r#1', type: 'pr', kind: 'review', repo: 'o/r', number: 1, title: 't', author: 'alice', authorAvatarUrl: null,
    createdAt: null, updatedAt: '2026-08-28T00:00:00Z', labels: [], pr: null, body: '', commentCount: 3, workspaceId: null,
  };

  function activity(overrides: Partial<NonNullable<PullRequestDto['latestActivity']>>) {
    return attentionActivity({ ...base, pr: pr({ latestActivity: {
      event: 'commented', actor: 'bob', timestamp: base.updatedAt, state: null, label: null, requestedReviewer: null, ...overrides,
    } }) });
  }

  it('keeps the actual actor, action and timestamp together instead of inferring them from the PR author', () => {
    expect(activity({})).toEqual({ label: 'Commented', detail: 'bob', timestamp: base.updatedAt });
    expect(activity({ event: 'reviewed', state: 'approved' }).label).toBe('Approved');
    expect(activity({ event: 'reviewed', state: 'CHANGES_REQUESTED' }).label).toBe('Changes requested');
    expect(activity({ event: 'labeled', label: 'stale-ignore' })).toMatchObject({ label: 'Label added', detail: 'bob · stale-ignore' });
    expect(activity({ event: 'review_requested', requestedReviewer: 'jack' })).toMatchObject({ label: 'Review requested', detail: 'bob · review from jack' });
    expect(activity({ actor: null }).detail).toBe('on GitHub');
  });

  it('uses GitHub updatedAt without inventing an action or actor when no event is known', () => {
    const item = { ...base, pr: pr({ activityAt: '2026-09-08T01:47:58Z', reviewRequestedAt: '2026-09-08T01:47:58Z' }) };
    expect(attentionActivity(item)).toEqual({ label: 'Updated', detail: 'pull request updated', timestamp: base.updatedAt });
    expect(attentionActivity({ ...base, type: 'issue' })).toEqual({ label: 'Updated', detail: 'issue updated', timestamp: base.updatedAt });
  });
});
