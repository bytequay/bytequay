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
import type { JiraTaskSource } from '../jira/contracts';
import type { PullRequestDto } from '../types';
import { pullRequestItem, type AttentionItem } from './attention';
import { isOwnWork, myWorkItems } from './work';

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

  it('lists a ticket with the task and pull request delivering it once, and leaves others’ pull requests out', () => {
    const task = { ...previewTask('ws1', 'task-1'), jiraSource: { ...jiraSource(), cloudId: jiraIssue.cloudId, issueId: jiraIssue.id },
      pullRequest: { number: 7, title: 'Change 7', state: 'open', draft: false } };
    const ticket = jiraAttentionItem(jiraIssue);
    const items = myWorkItems({ candidates: [ticket, mine(7), pullRequestItem(pr({ repo: 'owner/repository', number: 8, author: 'ana' }))],
      tasks: [{ task, workspace }], activity: {}, work: [], viewerLogin: 'me' });
    expect(items.map(item => [item.source, item.label, item.prs.map(each => each.number)])).toEqual([['jira', jiraIssue.key, [7]]]);
    expect(items[0].keys).toEqual([ticket.key.toLowerCase(), 'owner/repository#7', 'task:task-1']);
    expect(isOwnWork(ticket, 'me')).toBe(true);
    expect(isOwnWork(mine(7), 'me')).toBe(true);
    expect(isOwnWork(pullRequestItem(pr({ number: 8, author: 'ana' })), 'me')).toBe(false);
  });

});

function jiraSource(): JiraTaskSource {
  return { connectionId: jiraIssue.connectionId, cloudId: jiraIssue.cloudId, issueId: jiraIssue.id, key: jiraIssue.key, title: jiraIssue.title,
    body: '', updatedAt: jiraIssue.updatedAt, url: jiraIssue.url, initialTurnId: null, instructions: '' };
}
