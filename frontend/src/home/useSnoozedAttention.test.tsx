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
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { previewTask } from '../agents/fixtures/previewBridge';
import { taskAttentionItem } from '../agents/attention';
import { jiraAttentionItem } from '../jira/attention';
import { jiraDetail, jiraIssue } from '../jira/fixtures/jira';
import { pr } from '../pulls/fixtures/pullRequest';
import type { Bridge } from '../types';
import { handledEntry, pullRequestItem, type AttentionItem, type Handled } from './attention';
import { refreshSnoozedItem, useSnoozedAttention, type AttentionScope } from './useSnoozedAttention';

function retained(candidates: AttentionItem[], handled: Handled, now: number, scope?: AttentionScope): AttentionItem[] {
  let items: AttentionItem[] = [];
  function Probe(): null { items = useSnoozedAttention(candidates, handled, now, scope); return null; }
  renderToStaticMarkup(<Probe />);
  return items;
}

const pull = pullRequestItem(pr({}));
const taskDto = { ...previewTask('ws-1', 'task-1'), updatedAt: '2026-09-28T10:00:00Z' };
const task = taskAttentionItem(taskDto, null, { id: 'ws-1', name: 'Workspace', repository: pull.repo });
const jira = jiraAttentionItem({ ...jiraIssue, workspace: { id: 'ws-1', name: 'Workspace' } });
const issue: AttentionItem = { ...pull, key: `${pull.repo}#99`, number: 99, type: 'issue', kind: 'assigned', pr: null, workspaceId: 'ws-1' };

describe('retained snoozed attention', () => {
  it('restores each missing source at its deadline, deduplicates eligible cards and stops after acknowledgment', () => {
    const sources = [pull, task, jira, issue];
    const handled = Object.fromEntries(sources.map(item => [item.key, handledEntry(item, 'snooze', 10, 'soon')]));
    expect(retained([], handled, 9)).toEqual([]);
    expect(retained([], handled, 10)).toEqual(sources);
    expect(retained([pull], handled, 10)).toEqual(sources);
    expect(retained([], { ...handled, [pull.key]: handledEntry(pull, 'ack', null, 'Viewed') }, 10)).toEqual(sources.slice(1));
    expect(handledEntry(pull, 'ack', null, 'Viewed').snoozedItem).toBeUndefined();
    expect(retained([], handled, 10, { workspaceId: 'ws-1', repository: pull.repo })).toEqual(sources);
    expect(retained([], handled, 10, { workspaceId: 'another', repository: 'other/repo' })).toEqual([]);
  });

  it('refreshes a closed PR, a done ticket, and a running task without reapplying eligibility', async () => {
    const running = { ...taskDto, mutationClaim: { kind: 'TURN' as const, ownerId: 'turn-2', acquiredAt: '2026-09-28T10:00:00Z' } };
    const done = { ...jiraIssue, title: 'Latest ticket title', status: { id: 'done', name: 'Done', category: 'done' } };
    const bridge = {
      findStoredPullRequest: async () => ({ ...pr({}), state: 'closed', title: 'Latest closed PR' }),
      getJiraIssue: async () => jiraDetail(done),
      getAgentTask: async () => running,
      listAgentTaskActivity: async () => ({ 'task-1': { lastMessage: null, pendingApproval: null, latestTurn: { id: 'turn-2', state: 'RUNNING', endedAt: null } } }),
    } satisfies Partial<Bridge>;
    expect((await refreshSnoozedItem(pull, bridge as unknown as Bridge)).pr?.state).toBe('closed');
    expect((await refreshSnoozedItem(jira, bridge as unknown as Bridge)).jira?.status.name).toBe('Done');
    expect((await refreshSnoozedItem(task, bridge as unknown as Bridge)).task?.dto.mutationClaim?.kind).toBe('TURN');
  });

  it('loads an exact closed issue after cache removal and retains the snapshot when its source is inaccessible', async () => {
    const bridge = {
      fetchWorkspaceIssue: async () => null,
      fetchGitHubIssuePreview: async () => ({ title: 'Resolved issue', body: 'Latest issue body', author: 'alice', labels: ['fixed'],
        updatedAt: '2026-09-28T10:00:00Z', state: 'closed', pullRequest: false }),
    } satisfies Partial<Bridge>;
    expect(await refreshSnoozedItem(issue, bridge as unknown as Bridge)).toMatchObject({ title: 'Resolved issue', body: 'Latest issue body', issueState: 'closed', workspaceId: 'ws-1' });
    for (const source of [pull, task, jira, issue]) expect(await refreshSnoozedItem(source, {} as Bridge)).toBe(source);
  });

  it('ignores malformed retained snapshots loaded from local storage', () => {
    const handled = JSON.parse(JSON.stringify({
      broken: { kind: 'snooze', until: 1, snoozedItem: { key: 'broken' } },
      [pull.key]: { ...handledEntry(pull, 'snooze', 1, 'soon'), snoozedItem: { ...pull, pr: null } },
      [task.key]: { ...handledEntry(task, 'snooze', 1, 'soon'), snoozedItem: { ...task, task: { dto: taskDto, activity: { pendingApproval: { scope: null } } } } },
      [jira.key]: { ...handledEntry(jira, 'snooze', 1, 'soon'), snoozedItem: { ...jira, jira: { ...jira.jira, status: null } } },
    })) as Handled;
    expect(retained([], handled, 2)).toEqual([]);
  });
});
