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
  issueAttentionItems,
  attentionCandidates,
  attentionCards,
  attentionEnded,
  attentionKind,
  attentionRank,
  attentionSummary,
  attentionTime,
  handledEntry,
  handledSummary,
  myPullRequestCards,
  pullRequestItem,
  snoozeUntil,
  stillHandled,
  visibleAttention,
  type AttentionItem,
  type HandledEntry,
} from './attention';
import { pr } from '../pulls/fixtures/pullRequest';
import { jiraAttentionItems } from '../jira/attention';
import type { JiraActivity, JiraUser } from '../jira/contracts';
import { jiraIssue } from '../jira/fixtures/jira';
import { taskAttentionItem } from '../agents/attention';
import { previewTask } from '../agents/fixtures/previewBridge';
import type { TeamCircle } from '../teams/types';


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

  it('includes ordinary discovered PR updates without inventing a personal request', () => {
    expect(attentionKind(pr({ origin: 'OTHER', ciStatus: 'FAILING' }))).toBe('updated');
    expect(attentionKind(pr({ origin: 'OTHER' }))).toBe('updated');
    expect(attentionKind(pr({ origin: 'AUTHORED', author: 'alice' }), 'jack')).toBe('updated');
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
      expect(attentionKind({ ...reviewed, ciStatus: 'FAILING' })).toBe('updated');
      expect(attentionKind({ ...reviewed, ciStatus: 'PENDING' })).toBe('updated');
      expect(attentionKind({ ...reviewed, draft: true })).toBe('updated');
      expect(attentionKind({ ...reviewed, state: 'merged' })).toBe('updated');
    }
  });

  it('uses monitoring changes for specific reasons while every source update remains eligible', () => {
    const monitored = pr({
      origin: 'OTHER', monitoredAt: '2026-08-15T01:00:00Z', activityAt: '2026-08-15T00:00:00Z',
    });
    expect(attentionKind(monitored)).toBe('updated');
    expect(attentionKind({ ...monitored, activityAt: monitored.monitoredAt })).toBe('updated');
    const changed = { ...monitored, activityAt: '2026-08-15T02:00:00Z' };
    expect(attentionKind({ ...changed, ciStatus: 'FAILING' })).toBe('ci');
    expect(attentionKind({ ...changed, ciStatus: 'PENDING' })).toBe('updated');
    expect(attentionKind({ ...changed, state: 'closed' })).toBe('updated');
    expect(attentionKind({ ...changed, state: 'merged' })).toBe('updated');
    expect(attentionKind({ ...changed, draft: true })).toBe('updated');
    expect(attentionKind({ ...changed, monitoredAt: null, ciStatus: 'FAILING' })).toBe('updated');
  });

  it('sorts by the most recent update before the attention reason', () => {
    const rows = attentionCandidates([
      pr({ id: 1, number: 1, mergeableState: 'blocked' }),
      pr({ id: 2, number: 2, origin: 'REVIEW_REQUESTED', updatedAt: '2026-08-10T00:00:00Z' }),
      pr({ id: 3, number: 3, origin: 'REVIEW_REQUESTED', updatedAt: '2026-08-14T00:00:00Z' }),
    ]);

    expect(rows.map(row => row.number)).toEqual([1, 3, 2]);
  });

  it('orders rows by GitHub activity instead of local observation time', () => {
    const rows = attentionCandidates([
      pr({ id: 1, number: 1, origin: 'REVIEW_REQUESTED', activityAt: '2026-09-08T01:47:58Z',
        latestActivity: { event: 'commented', actor: 'bob', timestamp: '2026-08-16T00:00:00Z', state: null, label: null, requestedReviewer: null } }),
      pr({ id: 2, number: 2, origin: 'REVIEW_REQUESTED', updatedAt: '2026-08-17T00:00:00Z', activityAt: '2026-09-08T01:47:55Z' }),
    ]);
    expect(rows.map(row => row.number)).toEqual([2, 1]);
  });

  it('uses the updated source timestamp when the cached timeline is older', () => {
    const [row] = attentionCandidates([pr({ updatedAt: '2026-09-28T12:00:00Z',
      latestActivity: { event: 'commented', actor: 'bob', timestamp: '2026-09-27T10:00:00Z', state: null, label: null, requestedReviewer: null } })]);
    expect(attentionTime(row)).toBe('2026-09-28T12:00:00Z');
    expect(attentionTime({ ...row, updatedAt: '2026-09-26T10:00:00Z' })).toBe('2026-09-27T10:00:00Z');
  });

  it('uses reason priority only when the source update times match', () => {
    const rows = attentionCandidates(
      [pr({ number: 1, ciStatus: 'FAILING' }), pr({ number: 2, origin: 'REVIEW_REQUESTED' })],
      issueAttentionItems(snapshot(), 'workspace-id', 'JACK'),
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

  it('includes unassigned issue updates without calling them assigned to the viewer', () => {
    const [item] = issueAttentionItems(snapshot(), 'workspace-id', 'someone-else');
    expect(item.kind).toBe('updated');
    expect(attentionSummary(item).tag.label).toBe('Open');
  });

  it('keeps Epic and linked-task issue updates while preventing a new development launch', () => {
    const source = snapshot();
    source.issues.push(
      { ...source.issues[0], id: 10, number: 8, isEpic: true },
      { ...source.issues[0], id: 11, number: 9, developmentTask: { taskId: 'finished-task', workspaceId: 'other-workspace' } },
    );
    expect(issueAttentionItems(source, 'workspace-id', 'jack').map(item => item.number)).toEqual([7, 8, 9]);
    expect(issueAttentionItems(source, 'workspace-id', 'jack').map(item => item.canStartDevelopment)).toEqual([true, false, false]);
    expect(issueAttentionItems(source, 'workspace-id', 'jack').map(item => item.isEpic)).toEqual([false, true, false]);
    source.issues[1].isEpic = false;
    expect(issueAttentionItems(source, 'workspace-id', 'jack').map(item => item.canStartDevelopment)).toEqual([true, true, false]);
  });

  it('deduplicates the upstream issue across workspaces with a stable launch destination', () => {
    const first = issueAttentionItems(snapshot(), 'workspace-a', 'jack');
    const second = issueAttentionItems(snapshot(), 'workspace-b', 'jack');
    expect(attentionCandidates([], [...second, ...first])).toEqual(first);
    expect(attentionCandidates([], [...first, ...second])).toEqual(first);
    // Repository display names can change without changing the source identity.
    expect(attentionCandidates([], [...first, { ...second[0], key: 'renamed/app#7', repo: 'renamed/app' }])).toEqual(first);
    expect(attentionCandidates([], [...first, { ...second[0], sourceKey: 'another-host:issue:9' }])).toHaveLength(2);
  });

  it('keeps acknowledgement after Epic classification changes', () => {
    const source = snapshot();
    const original = issueAttentionItems(source, 'workspace-id', 'jack')[0];
    const handled = { [original.key]: handledEntry(original, 'ack', null, 'acknowledged') };
    source.issues[0].isEpic = true;
    expect(visibleAttention(issueAttentionItems(source, 'workspace-id', 'jack'), handled, Date.now())).toEqual([]);
    source.issues[0].isEpic = false;
    expect(visibleAttention(issueAttentionItems(source, 'workspace-id', 'jack'), handled, Date.now())).toEqual([]);
  });
});

describe('attentionRank', () => {
  const circles: TeamCircle[] = [{ id: 'engine', name: 'Engine', description: '', avatar: { color: 'gray', shape: 'square' }, repositories: [],
    members: [{ githubId: 1, login: 'Alice', name: 'Alice', avatarUrl: '' }] }];
  const theirs: Partial<PullRequestDto> = { origin: 'OTHER', author: 'bob' };
  const row = (overrides: Partial<PullRequestDto>) => {
    const pull = pr({ ...theirs, ...overrides });
    return pullRequestItem(pull, attentionKind(pull, 'jack'));
  };
  const rank = (overrides: Partial<PullRequestDto>) => attentionRank(row(overrides), circles, 'jack');
  const activity = (event: string, actor: string): NonNullable<PullRequestDto['latestActivity']> =>
    ({ event, actor, timestamp: '2026-08-15T00:00:00Z', state: null, label: null, requestedReviewer: null });

  it('ranks every event on your own pull requests first', () => {
    expect(rank({ author: 'JACK', state: 'merged' })).toBe(0);
    expect(rank({ author: 'jack', draft: true, latestActivity: activity('labeled', 'bob') })).toBe(0);
    expect(rank({ author: 'jack', latestActivity: activity('commented', 'bob') })).toBe(0);
  });

  it("ranks review requests and mentions next, a circle member's first", () => {
    expect(rank({ origin: 'REVIEW_REQUESTED', author: 'alice' })).toBe(1);
    expect(rank({ origin: 'REVIEW_REQUESTED' })).toBe(2);
    expect(rank({ mentioned: true })).toBe(2);
    expect(rank({ author: 'alice', mentioned: true, state: 'merged' })).toBe(1);
  });

  it("then pull requests you reviewed or monitor, then circle members' own", () => {
    expect(rank({ handledAction: 'APPROVED', reviewedAt: '2026-08-14T00:00:00Z', headPushedAt: '2026-08-14T06:00:00Z' })).toBe(3);
    expect(rank({ handledAction: 'COMMENTED', reviewedAt: '2026-08-14T00:00:00Z', state: 'merged' })).toBe(3);
    expect(rank({ monitoredAt: '2026-08-14T00:00:00Z' })).toBe(3);
    expect(rank({ author: 'alice', latestActivity: activity('commented', 'bob') })).toBe(4);
  });

  it("leaves out updates with no tie to you or your circles, a circle member acting on another person's included", () => {
    expect(rank({})).toBeNull();
    expect(rank({ latestActivity: activity('commented', 'alice') })).toBeNull();
    expect(rank({ requestedReviewers: ['alice'], latestActivity: activity('added_to_merge_queue', 'alice') })).toBeNull();
    const cards = attentionCards([row({ number: 1, author: 'alice' }), row({ number: 2 }), row({ number: 3, author: 'jack', updatedAt: '2026-08-10T00:00:00Z' })],
      'all', 4, { circles, viewerLogin: 'jack' }, Date.parse('2026-08-16T00:00:00Z'));
    expect(cards.map(card => card.number)).toEqual([3, 1]);
  });

  it('ranks only the last week of activity, then older items newest first', () => {
    const staleMine = row({ number: 1, author: 'jack', state: 'merged', updatedAt: '2026-08-01T00:00:00Z' });
    const staleAsk = row({ number: 2, origin: 'REVIEW_REQUESTED', updatedAt: '2026-08-05T00:00:00Z' });
    const circle = row({ number: 3, author: 'alice', updatedAt: '2026-08-19T00:00:00Z' });
    const ask = row({ number: 4, origin: 'REVIEW_REQUESTED', updatedAt: '2026-08-14T00:00:00Z' });
    const cards = attentionCards([staleMine, staleAsk, circle, ask], 'all', 4, { circles, viewerLogin: 'jack' }, Date.parse('2026-08-20T00:00:00Z'));
    expect(cards.map(card => card.number)).toEqual([4, 3, 2, 1]);
  });

  it('ranks tasks, issues and assigned Jira tickets by the same ties', () => {
    const [assigned] = issueAttentionItems(snapshot(), 'workspace-id', 'jack');
    const [unassigned] = issueAttentionItems(snapshot(), 'workspace-id', 'someone-else');
    expect(attentionRank(assigned, circles, 'jack')).toBe(2);
    expect(attentionRank({ ...assigned, author: 'alice' }, circles, 'jack')).toBe(1);
    expect(attentionRank({ ...unassigned, author: 'JACK' }, circles, 'jack')).toBe(0);
    expect(attentionRank({ ...unassigned, author: 'alice' }, circles, 'jack')).toBe(4);
    expect(attentionRank(unassigned, circles, 'jack')).toBeNull();
    expect(attentionRank(jiraAttentionItems([jiraIssue])[0], circles, 'jack')).toBe(2);
    const task = taskAttentionItem(previewTask('workspace', '1'), null, { id: 'workspace', name: '', repository: 'bytequay/app' });
    expect(attentionRank(task, circles, 'jack')).toBe(0);
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
  });

  it('skips today when today is already Monday', () => {
    const monday = new Date(2026, 7, 17, 14, 40).getTime();

    expect(new Date(snoozeUntil('monday', monday))).toEqual(new Date(2026, 7, 24, 9, 0, 0, 0));
  });
});

describe('attentionEnded', () => {
  it('names a merged or closed pull request or a closed issue', () => {
    const [open, merged, closed] = attentionCandidates([pr({ number: 1 }), pr({ number: 2, state: 'MERGED' }), pr({ number: 3, state: 'closed' })]);

    expect(attentionEnded(open)).toBeNull();
    expect(attentionEnded(merged)).toBe('merged');
    expect(attentionEnded(closed)).toBe('closed');
    expect(attentionEnded({ ...open, pr: null, type: 'issue', issueState: 'closed' })).toBe('closed');
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

describe('attentionSummary', () => {
  const base: AttentionItem = {
    key: 'o/r#1', type: 'pr', kind: 'updated', repo: 'o/r', number: 1, title: 't', author: 'alice', authorAvatarUrl: null,
    createdAt: null, updatedAt: '2026-08-28T00:00:00Z', labels: [], pr: null, body: '', commentCount: 3, workspaceId: null,
  };

  function summary(overrides: Partial<NonNullable<PullRequestDto['latestActivity']>>, kind: AttentionItem['kind'] = 'updated') {
    const item = { ...base, kind, pr: pr({ author: 'alice', latestActivity: {
      event: 'commented', actor: 'bob', timestamp: base.updatedAt, state: null, label: null, requestedReviewer: null, ...overrides,
    } }) };
    return attentionSummary(item, 'jack');
  }

  it('names the actual actor and action instead of inferring them from the PR author', () => {
    expect(summary({})).toMatchObject({ actor: { name: 'bob' }, verb: 'commented' });
    expect(summary({ event: 'review_thread' }).verb).toBe('left a review comment');
    expect(summary({ event: 'reviewed', state: 'approved' }).verb).toBe('approved these changes');
    expect(summary({ event: 'reviewed', state: 'CHANGES_REQUESTED' }).verb).toBe('requested changes');
    expect(summary({ event: 'labeled', label: 'stale-ignore' })).toMatchObject({
      verb: 'added a label', detail: { kind: 'labels', labels: [{ name: 'stale-ignore', color: null }] },
    });
    // A review request says whose review it asked for.
    expect(summary({ event: 'review_requested', requestedReviewer: 'jack' }, 'review')).toMatchObject({
      tag: { label: 'Review requested', tone: 'blue' }, verb: 'requested your review',
    });
    expect(summary({ event: 'review_requested', requestedReviewer: 'kim' }, 'review').verb).toBe('requested review from kim');
    expect(summary({ actor: null }).actor).toMatchObject({ name: 'alice' });
  });

  it('tags each card with where it stands, whatever event moved it', () => {
    // The event is the actor line; the tag is the state that keeps the item on the list.
    expect(summary({}).tag).toEqual({ label: 'Open', tone: 'neutral' });
    expect(summary({ event: 'reviewed', state: 'APPROVED' }).tag).toEqual({ label: 'Open', tone: 'neutral' });
    expect(attentionSummary({ ...base, pr: pr({ reviewDecision: 'APPROVED' }) }).tag).toEqual({ label: 'Approved', tone: 'green' });
    expect(attentionSummary({ ...base, pr: pr({ draft: true }) }).tag.label).toBe('Draft');
    expect(attentionSummary({ ...base, pr: pr({ state: 'MERGED' }) }).tag.label).toBe('Merged');
    expect(attentionSummary({ ...base, pr: pr({ state: 'closed' }) }).tag.label).toBe('Closed');
    expect(summary({ event: 'committed' }, 'commits').tag).toEqual({ label: 'New commits', tone: 'orange' });
    expect(summary({ event: 'committed' }, 'ci')).toMatchObject({ tag: { label: 'Checks failing', tone: 'red' }, detail: { kind: 'ci' } });
    const pushed = attentionSummary({ ...base, kind: 'commits', pr: pr({
      reviewedAt: '2026-08-20T00:00:00Z', headPushedAt: '2026-08-21T00:00:00Z', latestActivity: null,
    }) });
    expect(pushed.detail).toEqual({ kind: 'text', text: 'New commits since your last review' });
  });

  it('details what happened from the cached facts the attention read carries', () => {
    const commitsSinceReview = [
      { sha: '4f1a2c9aaaaaaa', message: 'Map CommitFailedException', actor: 'ravi', committedAt: '2026-08-28T00:00:00Z' },
      { sha: 'b73e0d1bbbbbbb', message: 'Add a conflict test', actor: 'ravi', committedAt: '2026-08-27T00:00:00Z' },
    ];
    const reviewed = attentionSummary({ ...base, kind: 'review', pr: pr({ commitsSinceReview, latestActivity: {
      event: 'review_requested', actor: 'chenjian2664', timestamp: base.updatedAt, state: null, label: null, requestedReviewer: 'jack',
    } }) }, 'jack');
    expect(reviewed).toMatchObject({ verb: 'requested your review', detail: {
      kind: 'commits', lead: '2 commits since your last review', commits: [{ sha: '4f1a2c9', message: 'Map CommitFailedException' }, { sha: 'b73e0d1', message: 'Add a conflict test' }],
    } });

    // A comment left after those commits is what the card names, so the detail quotes it instead.
    expect(attentionSummary({ ...base, kind: 'review', pr: pr({ commitsSinceReview, latestActivity: {
      event: 'review_thread', actor: 'ravi-moreau', timestamp: base.updatedAt, state: null, label: null, requestedReviewer: null,
      excerpt: 'Can we keep the identity check?', path: 'DeltaLakeWriter.java', line: 214,
    } }) }, 'jack')).toMatchObject({
      verb: 'left a review comment', detail: { kind: 'quote', text: 'Can we keep the identity check?', file: 'DeltaLakeWriter.java:214' },
    });

    // A pushed commit that failed CI names the push, the commit and the check that failed.
    expect(attentionSummary({ ...base, kind: 'ci', pr: pr({ commitsSinceReview: commitsSinceReview.slice(0, 1), failingChecks: ['ci / test (plugin/trino-iceberg)'], latestActivity: {
      event: 'committed', actor: 'wren-d', timestamp: base.updatedAt, state: null, label: null, requestedReviewer: null,
    } }) })).toMatchObject({
      tag: { label: 'Checks failing', tone: 'red' }, verb: 'pushed 1 commit, then CI failed',
      detail: { kind: 'ci', commits: [{ sha: '4f1a2c9' }], checks: ['ci / test (plugin/trino-iceberg)'] },
    });

    expect(summary({ event: 'review_thread', excerpt: 'Keep the identity check in DeletionVectorEntry?', path: 'DeltaLakeWriter.java', line: 214 }).detail)
      .toEqual({ kind: 'quote', text: 'Keep the identity check in DeletionVectorEntry?', file: 'DeltaLakeWriter.java:214' });
    expect(summary({ excerpt: 'Looks good.' }).detail).toEqual({ kind: 'quote', text: 'Looks good.', file: null });
  });

  it('uses GitHub updatedAt without inventing an action when no event is known', () => {
    const item = { ...base, pr: pr({ author: 'alice', activityAt: '2026-09-08T01:47:58Z', reviewRequestedAt: '2026-09-08T01:47:58Z', latestActivity: null }) };
    expect(attentionSummary(item)).toMatchObject({ tag: { label: 'Open' }, actor: { name: 'alice' }, verb: 'updated this pull request' });
    expect(attentionTime(item)).toBe(base.updatedAt);
    expect(attentionSummary({ ...base, type: 'issue', kind: 'assigned', labels: ['bug'] })).toMatchObject({
      tag: { label: 'Assigned to you', tone: 'green' }, verb: 'opened this issue', detail: { kind: 'labels', labels: [{ name: 'bug' }] },
    });
  });
});

describe('Jira ticket events', () => {
  const ticket = (latestActivity: JiraActivity | null) => jiraAttentionItems([{ ...jiraIssue, updatedAt: '2026-09-27T09:00:00Z', latestActivity }])[0];
  const status = (name: string, category: string) => ({ id: name, name, category });

  it('says a ticket moved, where from and to, with a note that came with it, while the card names its current status', () => {
    const moved = ticket({ kind: 'STATUS_CHANGED', at: '2026-09-27T09:00:00Z', actor: null, from: status('In Progress', 'indeterminate'),
      to: status('Blocked', 'indeterminate'), note: 'Staging is mid-upgrade.', fields: [] });
    expect(attentionSummary(moved)).toEqual({
      tag: { label: jiraIssue.status.name, tone: 'neutral' },
      actor: null,
      verb: 'Moved to Blocked',
      detail: { kind: 'status', from: { name: 'In Progress', tone: 'blue' }, to: { name: 'Blocked', tone: 'red' }, note: 'Staging is mid-upgrade.' },
    });
    expect(attentionTime(moved)).toBe('2026-09-27T09:00:00Z');
    const done = ticket({ kind: 'STATUS_CHANGED', at: '2026-09-27T09:00:00Z', actor: null, from: status('To Do', 'new'),
      to: status('Done', 'done'), note: null, fields: [] });
    expect(attentionSummary(done).detail).toMatchObject({ from: { tone: 'neutral' }, to: { tone: 'green' } });
  });

  it('quotes a comment with who wrote it, names changed fields, and otherwise names the ticket status', () => {
    const priya: JiraUser = { accountId: 'priya', displayName: 'Priya Raman', avatarUrl: null };
    expect(attentionSummary(ticket({ kind: 'COMMENTED', at: '2026-09-27T09:00:00Z', actor: priya, from: null, to: null, note: 'Can we keep it?', fields: [] })))
      .toEqual({ tag: { label: jiraIssue.status.name, tone: 'neutral' }, actor: { name: 'Priya Raman', avatarUrl: null }, verb: 'commented',
        detail: { kind: 'quote', text: 'Can we keep it?', file: null } });
    expect(attentionSummary(ticket({ kind: 'FIELDS_CHANGED', at: '2026-09-27T09:00:00Z', actor: null, from: null, to: null, note: null,
      fields: ['priority', 'labels', 'due date'] }))).toMatchObject({ verb: 'Updated in Jira', detail: { kind: 'text', text: 'Changed priority, labels and due date' } });
    expect(attentionSummary(ticket(null)).tag).toEqual({ label: jiraIssue.status.name, tone: 'neutral' });
  });
});


describe('my pull request cards', () => {
  const now = Date.parse('2026-09-15T00:00:00Z');
  const own = (number: number, updatedAt: string) => pullRequestItem(pr({ number, author: 'Me', updatedAt }));
  const older = own(1, '2026-09-01T00:00:00Z');
  const newer = own(2, '2026-09-02T00:00:00Z');
  const newest = own(3, '2026-09-03T00:00:00Z');
  const items = [older, newest, newer];
  const read = Object.fromEntries(items.map(item => [item.key, handledEntry(item, 'ack', null, 'Viewed')]));

  it('only includes open PRs and drafts, even when merged or closed PRs have unread updates', () => {
    const draft = pullRequestItem(pr({ number: 4, author: 'Me', state: 'open', draft: true }));
    const merged = pullRequestItem(pr({ number: 5, author: 'Me', state: 'merged' }));
    const closedDraft = pullRequestItem(pr({ number: 6, author: 'Me', state: 'closed', draft: true }));
    expect(myPullRequestCards([merged, closedDraft, older, draft], {}, 'me', now)).toEqual([older, draft]);
  });

  it('keeps the latest two read PRs and puts new activity ahead of newer read PRs', () => {
    expect(myPullRequestCards(items, read, 'me', now)).toEqual([newest, newer]);
    const changed = pullRequestItem(pr({ ...older.pr, ciStatus: 'FAILING' }));
    expect(myPullRequestCards([changed, newest, newer], read, 'me', now)).toEqual([changed, newest]);
    expect(myPullRequestCards(items, {}, 'me', now)).toEqual([newest, newer]);
  });

  it('hides snoozed PRs until their deadline and then fills the two-card list again', () => {
    const handled = { ...read, [newest.key]: handledEntry(newest, 'snooze', now + 1000, 'soon') };
    expect(myPullRequestCards(items, handled, 'me', now)).toEqual([newer, older]);
    expect(myPullRequestCards(items, handled, 'me', now + 1000)).toEqual([newest, newer]);
  });

  it('uses the signed-in author, falling back to authored origin before the profile loads', () => {
    const other = pullRequestItem(pr({ author: 'someone-else', origin: 'AUTHORED' }));
    const mine = pullRequestItem(pr({ author: 'ME', origin: 'OTHER' }));
    expect(myPullRequestCards([other, mine], {}, 'me', now)).toEqual([mine]);
    expect(myPullRequestCards([other, mine], {}, null, now)).toEqual([other]);
  });
});
