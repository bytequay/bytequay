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
import { previewTask } from '../agents/fixtures/previewBridge';
import { jiraAttentionItem } from '../jira/attention';
import { jiraIssue } from '../jira/fixtures/jira';
import { pr } from '../pulls/fixtures/pullRequest';
import { laneLabel } from '../settings/model';
import type { JiraTaskSource } from '../jira/contracts';
import type { AppNotice } from '../notices/contracts';
import type { AuthoredIssueDto, PullRequestDto } from '../types';
import { QUESTION_TOOL, issueAttentionItem, pullRequestItem, type AttentionItem } from './attention';
import { decisionMarks, isOwnWork, myWorkItems, workPrStatus } from './work';

const workspace = { id: 'ws1', name: 'Repository', repository: 'owner/repository' };

function mine(number: number, extra: Partial<PullRequestDto> = {}): AttentionItem {
  return pullRequestItem(pr({ repo: 'owner/repository', number, title: `Change ${number}`, author: 'me', state: 'open', ...extra }));
}

function lanes(candidates: AttentionItem[], tasks = [] as ReturnType<typeof previewTask>[]) {
  return myWorkItems({ candidates, tasks: tasks.map(task => ({ task, workspace })), activity: {}, work: [], viewerLogin: 'me' })
    .map(item => [item.label, item.lane, item.next]);
}

describe('My work', () => {
  it('puts each pull request in the first lane whose rule holds', () => {
    expect(lanes([
      mine(1, { reviewDecision: 'APPROVED', mergeable: true, mergeableState: 'clean', updatedAt: '2026-08-15T00:05:00Z' }),
      mine(2, { reviewDecision: 'CHANGES_REQUESTED', updatedAt: '2026-08-15T00:04:00Z' }),
      mine(3, { ciStatus: 'FAILING', failingChecks: ['maven-checks'], updatedAt: '2026-08-15T00:03:00Z' }),
      mine(4, { requestedReviewers: ['ana'], updatedAt: '2026-08-15T00:02:00Z' }),
      mine(5, { draft: true, updatedAt: '2026-08-15T00:01:00Z' }),
    ])).toEqual([
      ['repository#1', 'ready', 'Merge · no conflicts'],
      ['repository#2', 'needs', 'Address the requested changes'],
      ['repository#3', 'needs', 'Fix maven-checks'],
      ['repository#4', 'review', null],
      ['repository#5', 'progress', null],
    ]);
  });

  it.each([
    { mergeable: null, mergeableState: null },
    { mergeable: true, mergeableState: 'unknown' },
    { mergeable: true, mergeableState: 'blocked' },
    { mergeable: true, mergeableState: 'behind' },
    { ciStatus: 'PENDING' as const },
  ])('keeps an approved PR actionable when merge readiness is not confirmed: %j', extra => {
    expect(lanes([mine(1, { reviewDecision: 'APPROVED', ...extra })]))
      .toEqual([['repository#1', 'needs', 'Check merge requirements']]);
  });

  it.each(['commented', 'review_thread', 'reviewed'])('surfaces incoming %s feedback even after opening the PR', event => {
    const items = myWorkItems({ candidates: [mine(1, {
      latestActivity: feedback(event), viewedAt: '2026-08-15T00:02:00Z', headPushedAt: '2026-08-15T00:00:00Z',
    })], tasks: [], activity: {}, work: [], viewerLogin: 'me' });
    expect(items[0]).toMatchObject({ lane: 'needs', next: 'Respond to review feedback' });
    expect(workPrStatus(items[0].prs[0])).toEqual({ text: 'Feedback received', tone: 'amber' });
  });

  it.each([
    { latestActivity: feedback('commented', 'ME') },
    { latestActivity: feedback('commented', 'checks[bot]') },
    { latestActivity: feedback('commented', null) },
    { latestActivity: feedback('labeled') },
    { latestActivity: feedback('committed') },
    { latestActivity: feedback(), headPushedAt: '2026-08-15T00:02:00Z' },
    { latestActivity: feedback(), reviewedAt: '2026-08-15T00:02:00Z', handledAction: 'COMMENTED' as const },
  ])('leaves quiet or already answered activity in review: %j', extra => {
    expect(lanes([mine(1, extra)])).toEqual([['repository#1', 'review', null]]);
  });

  it('keeps concrete blockers ahead of approval and feedback, and ready PRs ready', () => {
    expect(lanes([
      mine(1, { latestActivity: feedback(), reviewDecision: 'APPROVED', ciStatus: 'FAILING', failingChecks: ['build'] }),
      mine(2, { latestActivity: feedback(), reviewDecision: 'APPROVED', mergeable: false, mergeableState: 'dirty' }),
      mine(3, { latestActivity: feedback(), reviewDecision: 'CHANGES_REQUESTED' }),
      mine(4, { latestActivity: feedback(), reviewDecision: 'APPROVED' }),
      mine(5, { latestActivity: feedback(), draft: true }),
    ])).toEqual([
      ['repository#1', 'needs', 'Fix build'],
      ['repository#2', 'needs', 'Resolve the merge conflicts'],
      ['repository#3', 'needs', 'Address the requested changes'],
      ['repository#4', 'ready', 'Merge · no conflicts'],
      ['repository#5', 'progress', null],
    ]);
  });

  it('moves linked local work when its PR receives feedback', () => {
    const task = { ...previewTask('ws1', 'task-1'), pullRequest: { number: 7, title: 'Change 7', state: 'open', draft: false } };
    expect(lanes([mine(7, { latestActivity: feedback() })], [task])).toEqual([['', 'needs', 'Respond to review feedback']]);
  });

  it('keeps a task that waits on a decision in its pull request’s lane, marked exactly while Decision holds its notice', () => {
    const waiting = (id: string, number: number | null, scope: string[]) => {
      const task = { ...previewTask('ws1', id), ...(number === null ? {} : { pullRequest: { number, title: `Change ${number}`, state: 'open', draft: false } }) };
      const pendingApproval = { approvalId: `a-${id}`, occurredAt: '2026-08-15T00:00:00Z', title: 'Run', detail: 'mvn test', approvalKind: 'COMMAND', scope };
      return { task, activity: { lastMessage: null, pendingApproval, latestTurn: null } };
    };
    const local = waiting('task-1', null, []);
    const reviewed = waiting('task-2', 7, [QUESTION_TOOL]);
    const stuck = waiting('task-3', null, []);
    const notice = (taskId: string, kind: AppNotice['kind']): AppNotice => ({ id: `${kind}:${taskId}`, kind, taskId, workspaceId: 'ws1', turnId: null, reviewId: null,
      title: taskId, pullRequest: null, pullRequestTitle: null, changes: null, message: '', repository: 'owner/repository', laneId: 'claude', occurredAt: '2026-08-15T00:00:00Z' });
    const build = (notices: AppNotice[]) => myWorkItems({ candidates: [mine(7, { requestedReviewers: ['ana'] })],
      tasks: [local, reviewed, stuck].map(({ task }) => ({ task, workspace })), work: [], viewerLogin: 'me', decisions: decisionMarks(notices),
      activity: { 'task-1': local.activity, 'task-2': reviewed.activity, 'task-3': stuck.activity } })
      .map(item => [item.title, item.lane, item.decision, item.next]).sort();
    expect(build([notice('task-1', 'APPROVAL'), notice('task-2', 'QUESTION'), notice('task-3', 'STUCK'), notice('task-3', 'FAILURE')])).toEqual([
      [local.task.title, 'progress', 'Approval', null],
      [reviewed.task.title, 'review', 'Question', null],
      [stuck.task.title, 'progress', 'Stuck', null],
    ].sort());
    // Once Decision no longer holds the notice, answered or cleared, the card loses its mark, whatever the task last said.
    expect(build([notice('task-2', 'QUESTION')]).filter(([, , decision]) => decision !== null)).toEqual([[reviewed.task.title, 'review', 'Question', null]]);
  });

  it('lists a ticket with the task and pull request delivering it once, and leaves others’ pull requests out', () => {
    const task = { ...previewTask('ws1', 'task-1'), jiraSource: { ...jiraSource(), cloudId: jiraIssue.cloudId, issueId: jiraIssue.id },
      pullRequest: { number: 7, title: 'Change 7', state: 'open', draft: false } };
    const ticket = jiraAttentionItem(jiraIssue);
    const items = myWorkItems({ candidates: [ticket, mine(7), pullRequestItem(pr({ repo: 'owner/repository', number: 8, author: 'ana' }))],
      tasks: [{ task, workspace }], activity: {}, work: [], viewerLogin: 'me' });
    expect(items.map(item => [item.source, item.label, item.prs.map(each => each.number)])).toEqual([['jira', jiraIssue.key, [7]]]);
    expect(items[0].keys).toEqual([ticket.key.toLowerCase(), 'pr:1', 'task:task-1']);
    expect(isOwnWork(ticket, 'me')).toBe(true);
    expect(isOwnWork(mine(7), 'me')).toBe(true);
    expect(isOwnWork(pullRequestItem(pr({ number: 8, author: 'ana' })), 'me')).toBe(false);
  });

  it('puts issues the viewer opened under In review, and lists one assigned to them only once', () => {
    const assigned = { ...issueAttentionItem(issue(11), 'owner/repository', 'ws1'), kind: 'assigned' as const };
    const items = myWorkItems({ candidates: [assigned], tasks: [], activity: {}, work: [], viewerLogin: 'me',
      authored: [authoredIssue('owner/repository', 11), authoredIssue('other/project', 12)] });
    expect(items.map(item => [item.label, item.lane, item.authored ?? false, item.target.workspaceId])).toEqual([
      ['repository#11', 'progress', false, 'ws1'],
      ['project#12', 'review', true, null],
    ]);
  });

  it('lists ended work only for All my work, with how it ended and who worked on it', () => {
    const done = { ...previewTask('ws1', 'task-1'), state: 'COMPLETED', updatedAt: '2026-08-15T00:01:00Z' };
    const input: Parameters<typeof myWorkItems>[0] = { candidates: [mine(9, { state: 'merged', updatedAt: '2026-08-15T00:02:00Z' })], tasks: [{ task: done, workspace }],
      activity: {}, work: [], viewerLogin: 'me' };
    expect(myWorkItems(input)).toEqual([]);
    expect(myWorkItems({ ...input, ended: true }).map(item => [item.label, item.ended, item.repos, item.agents])).toEqual([
      ['repository#9', 'Merged', ['owner/repository'], []],
      ['', 'Completed', ['owner/repository'], [laneLabel(done.laneId)]],
    ]);
  });
});

function feedback(event = 'commented', actor: string | null = 'ana'): NonNullable<PullRequestDto['latestActivity']> {
  return { event, actor, timestamp: '2026-08-15T00:01:00Z', state: event === 'reviewed' ? 'COMMENTED' : null, label: null, requestedReviewer: null };
}

function issue(number: number) {
  return { id: number, epicId: `ISSUE:github.com:${number}`, number, title: `Issue ${number}`, body: '', author: 'ana', authorAvatarUrl: '', htmlUrl: '', labels: [], assignees: ['me'],
    commentCount: 0, createdAt: '2026-08-15T00:00:00Z', updatedAt: '2026-08-15T00:02:00Z' };
}

function authoredIssue(repo: string, number: number): AuthoredIssueDto {
  return { repo, number, title: `Issue ${number}`, htmlUrl: `https://github.com/${repo}/issues/${number}`, labels: [], assignees: [], commentCount: 0,
    createdAt: '2026-08-15T00:00:00Z', updatedAt: '2026-08-15T00:01:00Z' };
}

function jiraSource(): JiraTaskSource {
  return { connectionId: jiraIssue.connectionId, cloudId: jiraIssue.cloudId, issueId: jiraIssue.id, key: jiraIssue.key, title: jiraIssue.title,
    body: '', updatedAt: jiraIssue.updatedAt, url: jiraIssue.url, initialTurnId: null, instructions: '' };
}
