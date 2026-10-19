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
import type { RunningTask } from '../agents/runningTaskModel';
import type { AppNotice } from '../notices/contracts';
import type { AgendaTargetDto, AssistantItemDto, AssistantReceiptDto, AssistantRowDto } from '../types';
import { workspaceAgentTaskRoute, workspaceIssueRoute, workspacePullRequestRoute, workspaceReviewTaskRoute } from '../workspaces/model';
import { settingsRoute } from '../settings/routes';
import {
  arrivedOpen, createdDrafts, dayLabel, linkRefs, localDay, pageRoute, refFromHref, refLabel, rowState, rowTitle, screenRef, stackOf, timelineDays, waitingCards,
} from './model';

const target: AgendaTargetDto = { kind: 'PULL', workspaceId: 'ws', repo: 'trinodb/trino', number: 31402, id: null, connectionId: null, cloudId: null };

describe('screenRef', () => {
  it('names the top layer before the page under it', () => {
    expect(screenRef({ kind: 'pull', repo: 'TrinoDB/Trino', number: 7 }, workspaceAgentTaskRoute('ws', 't1'), 'trinodb/trino')).toBe('pr:trinodb/trino#7');
    expect(screenRef({ kind: 'issue', repo: 'a/B', number: 3, workspaceId: 'ws' }, '#/home', null)).toBe('issue:a/b#3');
    expect(screenRef({ kind: 'jira', connectionId: 'c', cloudId: 'cloud', issueId: '10042' }, '#/home', null)).toBe('jira:cloud:10042');
    expect(screenRef({ kind: 'work', workspaceId: 'ws', itemId: 'w-22' }, '#/home', null)).toBe('work:w-22');
    expect(screenRef({ kind: 'task', workspaceId: 'ws', taskId: 't9' }, '#/home', null)).toBe('task:t9');
  });

  it('reads pull request, task and review routes, and nothing elsewhere', () => {
    expect(screenRef(undefined, '#/pulls/TrinoDB/trino/31402', null)).toBe('pr:trinodb/trino#31402');
    expect(screenRef(undefined, workspaceAgentTaskRoute('ws', 't1'), null)).toBe('task:t1');
    expect(screenRef(undefined, workspaceReviewTaskRoute('ws', 'r1'), null)).toBe('review:r1');
    expect(screenRef(undefined, workspacePullRequestRoute('ws', 12), 'TrinoDB/Trino')).toBe('pr:trinodb/trino#12');
    expect(screenRef(undefined, workspaceIssueRoute('ws', 5), 'trinodb/trino')).toBe('issue:trinodb/trino#5');
    expect(screenRef(undefined, workspacePullRequestRoute('ws', 12), null)).toBeNull();
    expect(screenRef(undefined, '#/home', 'trinodb/trino')).toBeNull();
  });
});

describe('refLabel', () => {
  it('shows numbers for pull requests and issues, and the kind otherwise', () => {
    expect(refLabel('pr:trinodb/trino#31402')).toBe('#31402');
    expect(refLabel('issue:trinodb/trino#9')).toBe('#9');
    expect(refLabel('work:w-22')).toBe('work item');
    expect(refLabel('jira:cloud:10042')).toBe('Jira ticket');
  });
});

describe('linkRefs', () => {
  const targets = { 'pr:trinodb/trino#31402': target };
  const link = (label: string) => `[${label}](https://bytequay.invalid/ref?r=${encodeURIComponent('pr:trinodb/trino#31402')})`;

  it('links refs with a target, written as links or bare', () => {
    expect(linkRefs('Review [#31402](pr:trinodb/trino#31402) first.', targets)).toBe(`Review ${link('#31402')} first.`);
    expect(linkRefs('Review pr:trinodb/trino#31402.', targets)).toBe(`Review ${link('#31402')}.`);
    expect(refFromHref(`https://bytequay.invalid/ref?r=${encodeURIComponent('pr:trinodb/trino#31402')}`)).toBe('pr:trinodb/trino#31402');
    expect(refFromHref('https://github.com/trinodb/trino/pull/31402')).toBeNull();
  });

  it('keeps only the label of a ref without a target, and leaves longer refs and code alone', () => {
    expect(linkRefs('See [the fix](pr:trinodb/trino#1).', targets)).toBe('See the fix.');
    expect(linkRefs('See pr:trinodb/trino#314021 and `pr:trinodb/trino#31402`.', targets)).toBe('See pr:trinodb/trino#314021 and `pr:trinodb/trino#31402`.');
    expect(linkRefs('Nothing to link.', {})).toBe('Nothing to link.');
  });
});

describe('waitingCards', () => {
  const notice = (id: string, kind: AppNotice['kind'], minute: number): AppNotice => ({
    id, kind, taskId: id, workspaceId: 'ws', turnId: null, reviewId: null, title: id, pullRequest: null, pullRequestTitle: null, changes: null,
    message: '', repository: 'trinodb/trino', laneId: 'claude', occurredAt: `2026-10-17T09:${String(minute).padStart(2, '0')}:00Z`,
  });
  const task = (id: string, status: RunningTask['status'], minute: number): RunningTask => ({
    id, workspaceId: 'ws', title: id, repository: 'trinodb/trino', kind: 'TASK', targetId: id, status, startedAt: `2026-10-17T09:${String(minute).padStart(2, '0')}:00Z`,
    expiresAt: null,
  });

  it('puts what needs the user under Decision, finished turns under Finished and running tasks under Running, newest first', () => {
    const cards = waitingCards([notice('approval:a', 'APPROVAL', 49), notice('done', 'TURN_FINISHED', 31), notice('failed', 'FAILURE', 58)],
      [task('t1', 'RUNNING', 50), task('t2', 'COMPLETED', 59)]);
    expect(cards.map(card => [card.key, card.group])).toEqual([['failed', 'decision'], ['running:t1', 'running'], ['approval:a', 'decision'], ['done', 'finished']]);
  });
});

describe('stackOf', () => {
  it('shows the top card and at most two behind it, wrapping round', () => {
    expect(stackOf(['a', 'b', 'c', 'd'], 0)).toEqual(['a', 'b', 'c']);
    expect(stackOf(['a', 'b', 'c', 'd'], 3)).toEqual(['d', 'a', 'b']);
    expect(stackOf(['a', 'b'], 1)).toEqual(['b', 'a']);
    expect(stackOf([], 0)).toEqual([]);
  });
});

describe('lists', () => {
  const row = (ref: string, kind: AssistantRowDto['kind'], at: string | null, fields: Partial<AssistantRowDto> = {}): AssistantRowDto => ({
    ref, kind, title: ref, key: null, state: null, ci: null, review: null, approvals: null, mine: false, mergeBlocked: false, youApproved: false, agent: null, epic: null,
    done: null, total: null, at, latest: null, target,
    ...fields,
  });
  const local = (day: number, hour: number): string => new Date(2026, 9, day, hour, 30).toISOString();

  it('puts each ref under the local day it last moved, the newest first, and the ones with no time last', () => {
    const rows = Object.fromEntries([row('pr:a#1', 'PULL', local(16, 9)), row('task:t1', 'TASK', local(17, 8)), row('work:w1', 'WORK', local(16, 23)),
      row('epic:e1', 'EPIC', null)].map(each => [each.ref, each]));
    expect(timelineDays(['pr:a#1', 'epic:e1', 'task:t1', 'pr:gone#2', 'work:w1'], rows)).toEqual([
      { day: '2026-10-17', refs: ['task:t1'] },
      { day: '2026-10-16', refs: ['work:w1', 'pr:a#1'] },
      { day: null, refs: ['epic:e1', 'pr:gone#2'] },
    ]);
    expect(timelineDays([], rows)).toEqual([]);
  });

  it('heads a day as Today, Yesterday or its date', () => {
    const now = new Date(2026, 9, 17, 0, 5).getTime();
    expect(localDay(now)).toBe('2026-10-17');
    expect(dayLabel('2026-10-17', now)).toBe('Today');
    expect(dayLabel('2026-10-16', now)).toBe('Yesterday');
    expect(dayLabel('2026-10-13', now)).toBe('Tue 13 Oct');
  });

  it('names where a row stands, a pull request by what it needs first', () => {
    expect(rowState(row('pr:a#1', 'PULL', null, { state: 'open', ci: 'failing', review: 'changes_requested' }))).toBe('Changes requested');
    expect(rowState(row('pr:a#1', 'PULL', null, { state: 'open', ci: 'failing', review: 'approved' }))).toBe('Checks failing');
    expect(rowState(row('pr:a#1', 'PULL', null, { state: 'open', ci: 'failing', mergeBlocked: true }))).toBe('Checks failing');
    expect(rowState(row('pr:a#1', 'PULL', null, { state: 'open', ci: 'passing', review: 'approved', mergeBlocked: true }))).toBe('Merge blocked');
    expect(rowState(row('pr:a#1', 'PULL', null, { state: 'open', ci: 'passing', review: 'approved' }))).toBe('Approved');
    expect(rowState(row('pr:a#1', 'PULL', null, { state: 'merged', review: 'approved' }))).toBe('Merged');
    expect(rowState(row('task:t1', 'TASK', null, { state: 'waiting' }))).toBe('Waiting');
    expect(rowState(row('work:w1', 'WORK', null, { state: 'planned' }))).toBe('Planned');
    // An Epic's done of total is a fact, so only its conclusion is a state.
    expect(rowState(row('epic:e1', 'EPIC', null, { done: 3, total: 6 }))).toBeNull();
    expect(rowState(row('epic:e1', 'EPIC', null, { state: 'concluded', done: 6, total: 6 }))).toBe('Concluded');
    expect(rowState(row('issue:a#2', 'ISSUE', null))).toBeNull();
  });

  it('names where each kind of row lives in its tooltip', () => {
    expect(rowTitle(row('pr:trinodb/trino#31402', 'PULL', null, { key: '#31402', title: 'Retry commit' }))).toBe('trinodb/trino #31402 · Retry commit');
    expect(rowTitle(row('jira:c:1', 'JIRA', null, { key: 'ICE-7', title: 'Expire snapshots' }))).toBe('Jira ICE-7 · Expire snapshots');
    expect(rowTitle(row('task:t1', 'TASK', null, { agent: 'Claude Code', title: 'Expose ids' }))).toBe('Claude Code · Expose ids');
    expect(rowTitle(row('work:w1', 'WORK', null, { title: 'Add a column' }))).toBe('Work item · Add a column');
    expect(rowTitle(row('epic:e1', 'EPIC', null, { title: 'Write path' }))).toBe('Epic · Write path');
  });
});

describe('opens', () => {
  const receipt = (id: string, at: string, outcome: AssistantReceiptDto['outcome'], operation: AssistantReceiptDto['operation'] = 'OPEN'): AssistantItemDto => ({
    id, day: '2026-10-17', at, kind: 'RECEIPT', text: id, targets: {}, notReady: false, list: null, draft: null,
    receipt: { operation, outcome, detail: null, target, page: null, turnId: null, fix: null, words: 'open it' },
  });

  it('names the route of each page the assistant may open', () => {
    expect(pageRoute('settings/models')).toBe(settingsRoute('models'));
    expect(pageRoute('settings/workflow')).toBe('#/settings/agents/workflow');
    expect(pageRoute('pulls')).toBe('#/pulls');
    expect(pageRoute('settings')).toBe('#/settings');
    expect(pageRoute('settings/ws-token')).toBeNull();
    expect(pageRoute('terminal')).toBeNull();
  });

  it('follows only the newest done open that came in after the panel opened, once', () => {
    const opened = Date.parse('2026-10-17T09:00:00Z');
    const items = [receipt('old', '2026-10-17T08:59:00Z', 'DONE'), receipt('first', '2026-10-17T09:00:05Z', 'DONE'), receipt('refused', '2026-10-17T09:00:06Z', 'REFUSED'),
      receipt('started', '2026-10-17T09:00:07Z', 'DONE', 'START_TASK'), receipt('second', '2026-10-17T09:00:08Z', 'DONE')];
    expect(arrivedOpen(items, opened, new Set())?.id).toBe('second');
    expect(arrivedOpen(items, opened, new Set(['second']))?.id).toBe('first');
    expect(arrivedOpen(items.slice(0, 1), opened, new Set())).toBeNull();
  });
});

describe('createdDrafts', () => {
  it('names the work of each draft a receipt says the user created', () => {
    const receipt = (id: string, operation: AssistantReceiptDto['operation'], work: string | null): AssistantItemDto => ({
      id, day: '2026-10-17', at: '2026-10-17T07:00:00Z', kind: 'RECEIPT', text: id, targets: {}, notReady: false, list: null, draft: null,
      receipt: { operation, outcome: 'DONE', detail: null, target: work === null ? null : { ...target, kind: 'WORK', id: work }, page: null, turnId: null, fix: null, words: null },
    });
    expect([...createdDrafts([receipt('c1', 'CREATE_EPIC', 'w1'), receipt('c2', 'CREATE_WORK_ITEM', 'w2'), receipt('c3', 'CREATE_EPIC', null)])]).toEqual(['w1']);
  });
});
