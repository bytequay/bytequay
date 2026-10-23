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
import type { AttentionItem } from '../home/attention';
import type { JiraIssue } from '../jira/contracts';
import type { AgentTaskDto } from '../types';
import { decideOnCurrent } from './changes';
import { parseWorkItem, parseWorkItemContextLink, type GoalItemDto, type WorkItemDecision, type WorkItemDto } from './contracts';
import { addable, goalRef, needsYouKeys, refKey, summarize, waitingOn } from './model';

function item(kind: GoalItemDto['kind'], key: string, label: string, state: GoalItemDto['state'], status: string, extra: Partial<GoalItemDto> = {}): GoalItemDto {
  return { kind, key, label, title: `${label} title`, state, status, blocked: false, url: null, activity: null, addedAt: '2026-09-20T00:00:00Z', ...extra };
}

const ITEMS: GoalItemDto[] = [
  item('PULL_REQUEST', 'owner/repository#30925', '#30925', 'WORKING', 'Open', { activity: { text: '#30925 updated', at: '2026-09-27T08:00:00Z' } }),
  item('PULL_REQUEST', 'owner/repository#30702', '#30702', 'MERGED', 'Merged', { activity: { text: '#30702 merged', at: '2026-09-27T09:00:00Z' } }),
  item('ISSUE', 'owner/repository#31302', '#31302', 'IDLE', 'Open'),
  item('JIRA', 'connection:cloud:10412', 'ENG-412', 'WORKING', 'Blocked', { blocked: true, activity: { text: 'ENG-412 is Blocked', at: '2026-09-26T09:00:00Z' } }),
  item('TASK', 'task-1', 'Task', 'ENDED', 'Completed'),
];
const WORK: WorkItemDto[] = ITEMS.map((link, index): WorkItemDto => ({
  id: `work-${index}`, workspaceId: 'workspace-1', isEpic: false, contextLinks: [], title: link.title, kind: 'DEVELOP', acceptanceCriteria: 'Verified fix',
  completionRule: 'MERGED_DELIVERY', state: index === 1 ? 'DONE' : index === 2 ? 'PLANNED' : 'WORKING', revision: 0, priority: 'NORMAL', blockedBy: [],
  links: [link],
  history: link.activity === null ? [] : [{ operationId: `op-${index}`, action: 'ACCEPT', detail: link.activity.text, at: link.activity.at }],
  createdAt: '2026-09-01T00:00:00Z', updatedAt: '2026-09-27T00:00:00Z',
}));

function attention(extra: Partial<AttentionItem>): AttentionItem {
  return {
    key: 'k', type: 'pr', kind: 'review', repo: 'Owner/Repository', number: 30925, title: 't', author: null, authorAvatarUrl: null, createdAt: null,
    updatedAt: '2026-09-27T08:00:00Z', labels: [], pr: null, body: '', commentCount: 0, workspaceId: null, ...extra,
  };
}

describe('work item model', () => {
  it('names an attention card as the item it stands for', () => {
    expect(goalRef(attention({}))).toEqual({ kind: 'PULL_REQUEST', key: 'owner/repository#30925' });
    expect(goalRef(attention({ type: 'issue', number: 31302 }))).toEqual({ kind: 'ISSUE', key: 'owner/repository#31302' });
    const jira = { id: '10412', connectionId: 'connection', cloudId: 'cloud', workspace: { id: 'workspace-2', name: 'Lake' } } as unknown as JiraIssue;
    expect(goalRef(attention({ type: 'issue', jira, repo: '' }))).toEqual({ kind: 'JIRA', key: 'connection:cloud:10412' });
    const task = { id: 'task-1', workspaceId: 'workspace-3' } as AgentTaskDto;
    expect(goalRef(attention({ type: 'task', task: { dto: task, activity: null } }))).toEqual({ kind: 'TASK', key: 'task-1' });
    expect(goalRef(attention({ repo: '', number: 0 }))).toBeNull();
  });

  it('sends a decision again once when only a receipt moved the work item on', async () => {
    const work: WorkItemDto = { ...WORK[0], revision: 3 };
    const stale = new Error('Work item changed. Refresh before applying this decision.');
    const sent: number[] = [];
    const bridge = {
      decideWorkItem: async (_workspaceId: string, _id: string, command: WorkItemDecision): Promise<WorkItemDto> => {
        sent.push(command.expectedRevision);
        if (command.expectedRevision === 3) throw stale;
        return { ...work, revision: 5, state: 'DONE' };
      },
      listWorkItems: async (): Promise<WorkItemDto[]> => [{ ...work, revision: 4,
        history: [...work.history, { operationId: 'approved:7', action: 'RECEIPT', detail: '@alice approved #31260', at: '2026-10-05T10:00:00Z' }] }],
    };
    const decision: WorkItemDecision = { operationId: 'op-accept', expectedRevision: 3, action: 'ACCEPT', detail: 'Rows survive' };
    expect((await decideOnCurrent(bridge, 'workspace-1', work, decision)).revision).toBe(5);
    expect(sent).toEqual([3, 4]);
    const rescoped = { ...bridge, listWorkItems: async (): Promise<WorkItemDto[]> => [{ ...work, revision: 4, acceptanceCriteria: 'Something else' }] };
    await expect(decideOnCurrent(rescoped, 'workspace-1', work, decision)).rejects.toBe(stale);
  });

  it('counts progress, pull requests, what needs you and what is blocked', () => {
    const needsYou = needsYouKeys([attention({}), attention({ type: 'issue', number: 31302 }), attention({ number: 1 })]);
    expect(summarize({ items: WORK }, needsYou)).toEqual({ total: 5, finished: 1, working: 3, idle: 1, prsMerged: 1, prsOpen: 1, needs: 2, blocked: 1, dropped: 0 });
    expect(waitingOn(WORK[3], WORK)).toEqual(['ENG-412 is Blocked']);
    expect(addable([ITEMS[0], item('ISSUE', 'owner/repository#1', '#1', 'IDLE', 'Open')], { items: WORK }).map(refKey)).toEqual(['ISSUE owner/repository#1']);
  });

  it('reads context separately from source links and defaults older work to no context links', () => {
    const work = WORK[0];
    expect(parseWorkItem(JSON.parse(JSON.stringify(work)))).toEqual(work);
    expect(() => parseWorkItem({ ...work, state: 'FINISHED' })).toThrow('Malformed work item state');
    expect(parseWorkItem({ ...work, contextLinks: undefined }).contextLinks).toEqual([]);
    const contextLinks = [{ targetId: 'ISSUE:github.com:1', title: 'Related request' }];
    expect(parseWorkItem({ ...work, contextLinks })).toEqual({ ...work, contextLinks });
    expect(() => parseWorkItem({ ...work, contextLinks: [{ title: 'Missing identity' }] })).toThrow('Malformed context link target');
    const resolved = { targetId: 'ISSUE:github.com:1', id: 'local-copy', source: 'GITHUB', title: 'Related request', status: 'Open', available: true,
      reference: { kind: 'ISSUE', key: 'org/repo#1', label: '#1' } };
    expect(parseWorkItemContextLink(resolved)).toEqual(resolved);
    expect(parseWorkItemContextLink({ ...resolved, available: false, reference: undefined }).reference).toBeNull();
    expect(() => parseWorkItemContextLink({ ...resolved, available: 'yes' })).toThrow('Malformed context link');
  });
});
