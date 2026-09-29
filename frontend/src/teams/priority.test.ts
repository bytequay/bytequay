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
import { attentionCards, handledEntry, pullRequestItem, visibleAttention, type AttentionItem } from '../home/attention';
import { pr } from '../pulls/fixtures/pullRequest';
import { circleNeeds, teamAttentionMatch } from './priority';
import type { TeamCircle } from './types';
import { taskAttentionItem } from '../agents/attention';
import { previewTask } from '../agents/fixtures/previewBridge';
import type { PullRequestDto } from '../types';
import { jiraAttentionItems } from '../jira/attention';
import { jiraIssue } from '../jira/fixtures/jira';

const circles: TeamCircle[] = [{ id: 'one', name: 'Maintainers', description: '', avatar: { color: 'gray', shape: 'square' }, repositories: ['BYTEQUAY/APP'], members: [{ githubId: 1, login: 'Alice', name: 'Alice', avatarUrl: '' }] }];
const preference = { circles, viewerLogin: 'jack' };
const NOW = Date.parse('2026-09-29T00:00:00Z');
const item = (number: number, author = 'alice', updatedAt = '2026-09-28T12:00:00Z') => {
  const pull = pr({ id: number, number, author, origin: 'REVIEW_REQUESTED', requestedReviewers: ['jack'], updatedAt });
  return { ...pullRequestItem(pull, 'review'), pr: pull };
};

describe('private team priority', () => {
  it('matches authors and direct request actors within repository scope, never arbitrary commenters', () => {
    expect(teamAttentionMatch(item(1), circles, 'jack')).toEqual({ circle: circles[0], member: circles[0].members[0], reason: 'author' });
    expect(teamAttentionMatch({ ...item(1), repo: 'other/app' }, circles, 'jack')).toBeNull();
    const direct = item(2, 'bob');
    const activity: NonNullable<PullRequestDto['latestActivity']> = {
      event: 'review_requested', actor: 'ALICE', requestedReviewer: 'JACK', timestamp: direct.updatedAt, label: null, state: null,
    };
    direct.pr = { ...direct.pr, latestActivity: activity };
    expect(teamAttentionMatch(direct, circles, 'jack')).toEqual({ circle: circles[0], member: circles[0].members[0], reason: 'review-requester' });
    expect(teamAttentionMatch(direct, circles, 'another-viewer')).toBeNull();
    expect(teamAttentionMatch({ ...direct, pr: { ...direct.pr, latestActivity: { ...activity, event: 'commented' } } }, circles, 'jack')).toBeNull();
    expect(teamAttentionMatch({ ...item(1), repo: 'other/app' }, [{ ...circles[0], repositories: [] }], 'jack')?.circle.id).toBe('one');
    expect(teamAttentionMatch(item(1), [{ ...circles[0], members: [] }], 'jack')).toBeNull();
  });

  it('puts review requests from circle members before newer requests from outside every circle', () => {
    const others = [1, 2, 3, 4].map(number => item(number, 'bob'));
    const trusted = [5, 6, 7, 8].map(number => item(number, 'alice', '2026-09-27T00:00:00Z'));
    expect(attentionCards([...others, ...trusted], 'all', 4, preference, NOW).map(row => row.number)).toEqual([5, 6, 7, 8]);
    expect(attentionCards([...others, trusted[0]], 'all', 4, preference, NOW).map(row => row.number)).toEqual([5, 1, 2, 3]);
    expect(attentionCards([...others, ...trusted], 'all', 4, { ...preference, circles: [] }, NOW).map(row => row.number)).toEqual([1, 2, 3, 4]);
  });

  it('does not multiply a boost for overlapping circles and preserves source and Team-only filters', () => {
    const issue: AttentionItem = { ...item(7), type: 'issue', kind: 'assigned', pr: null };
    const live = [item(1, 'bob'), item(2), item(3), item(4), issue];
    const overlapping = { ...preference, circles: [...circles, { ...circles[0], id: 'two' }] };
    expect(attentionCards(live, 'all', 4, overlapping, NOW)).toEqual(attentionCards(live, 'all', 4, preference, NOW));
    const selected = attentionCards(live, 'issue', 4, { ...overlapping, onlyTeam: true }, NOW);
    expect(selected).toEqual([issue]);
    expect(attentionCards(live, 'all', 4, { ...overlapping, onlyTeam: true }, NOW).map(row => row.number)).toEqual([2, 3, 4, 7]);
  });

  it('ranks the last week of all four sources by how they involve the viewer, then older items newest first', () => {
    const newest = item(1, 'bob', '2026-09-28T15:00:00Z');
    const issue: AttentionItem = { ...item(2, 'bob', '2026-09-28T14:00:00Z'), type: 'issue', pr: null };
    const [ticket] = jiraAttentionItems([{ ...jiraIssue, updatedAt: '2026-09-28T13:00:00Z' }]);
    const task = taskAttentionItem({ ...previewTask('workspace', '1'), updatedAt: '2026-09-28T12:00:00Z' }, null,
      { id: 'workspace', name: '', repository: 'bytequay/app' });
    const oldApproval = { ...task, key: 'old-task', kind: 'approval' as const, updatedAt: '2026-09-01T00:00:00Z' };
    const oldTeam = item(3, 'alice', '2026-09-01T00:00:00Z');
    const selected = attentionCards([oldTeam, oldApproval, task, ticket, issue, newest], 'all', 6, preference, NOW);
    expect(selected.map(row => row.key)).toEqual([task.key, newest.key, issue.key, ticket.key, oldApproval.key, oldTeam.key]);
    const weekOld = { ...oldApproval, updatedAt: '2026-09-22T01:00:00Z' };
    expect(attentionCards([weekOld, ticket, issue, newest], 'all', 4, preference, NOW).map(row => row.key)).toEqual([weekOld.key, newest.key, issue.key, ticket.key]);
    expect(attentionCards([ticket, issue, newest], 'all', 4, preference, NOW).map(row => row.key)).toEqual([newest.key, issue.key, ticket.key]);
  });

  it('keeps team filtering limited to live rows and never wakes acknowledged or timed-snoozed members', () => {
    const now = Date.parse('2026-09-28T13:00:00Z');
    const live = [item(1), item(2), item(3), item(4, 'bob')];
    const handled = { [live[0].key]: handledEntry(live[0], 'ack', null, 'Viewed'), [live[1].key]: handledEntry(live[1], 'snooze', now + 1, 'soon') };
    expect(attentionCards(visibleAttention(live, handled, now), 'all', 4, preference, NOW).map(row => row.number)).toEqual([3, 4]);
    expect(attentionCards(visibleAttention(live, handled, now + 1), 'all', 4, preference, NOW).map(row => row.number)).toEqual([2, 3, 4]);
  });
});

describe('circle Needs you', () => {
  it('keeps review requests and assigned issues from members, leaving other updates to Activity', () => {
    const review = item(1);
    const updated = { ...item(2), kind: 'updated' as const };
    const recommit = { ...item(3), kind: 'commits' as const };
    const stranger = item(4, 'bob');
    const issue: AttentionItem = { ...pullRequestItem(pr({ id: 5, number: 5, author: 'alice' })), type: 'issue', kind: 'assigned', pr: null, repo: 'bytequay/app' };
    const needs = circleNeeds([review, updated, recommit, stranger, issue], circles, 'jack');
    expect(needs.map(need => [need.item.number, need.action])).toEqual([[1, 'review'], [3, 'review'], [5, 'assigned']]);
    expect(needs[0].match.member.login).toBe('Alice');
    expect(circleNeeds([review], [{ ...circles[0], repositories: ['other/app'] }], 'jack')).toEqual([]);
  });
});
