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
import { attentionActivityVersion, attentionCandidates, attentionSummary, handledEntry, stillHandled } from '../home/attention';
import { pr } from '../pulls/fixtures/pullRequest';
import type { AgentTaskActivityDto, AgentTaskDto } from '../types';
import { linkTasks, taskAttentionAfterQueue, taskAttentionItem, taskAttentionItems, taskAttentionKind, taskCardState, taskLastMessage } from './attention';
import { previewTask } from './fixtures/previewBridge';

const WORKSPACE = { id: 'workspace-1', name: 'repository', repository: 'owner/repository' };

function task(id: string, overrides: Partial<AgentTaskDto> = {}): AgentTaskDto {
  return {
    ...previewTask('workspace-1', id), laneId: 'claude', title: `Task ${id}`, updatedAt: '2026-09-27T09:00:00Z',
    defaults: { model: 'claude-opus', effort: 'high', permissionPolicy: 'AUTO' }, ...overrides,
  };
}

function activity(overrides: Partial<AgentTaskActivityDto> = {}): AgentTaskActivityDto {
  return { lastMessage: null, pendingApproval: null, latestTurn: null, ...overrides };
}

const COMMAND = {
  approvalId: 'approval-1', occurredAt: '2026-09-27T10:05:00Z', title: 'Re-run the checkpoint tests',
  detail: JSON.stringify({ command: './mvnw -pl plugin/storage test', description: 'Run the tests' }),
  approvalKind: 'COMMAND', scope: ['Bash'],
};

describe('task attention', () => {
  it('consumes an accepted queue timestamp without consuming a later approval or assistant completion', () => {
    const running = task('t', { mutationClaim: { kind: 'TURN', ownerId: 'turn-1', acquiredAt: '2026-09-27T10:00:00Z' } });
    const facts = activity({ pendingApproval: COMMAND, latestTurn: { id: 'turn-1', state: 'RUNNING', endedAt: null } });
    const original = taskAttentionItem(running, facts, WORKSPACE);
    const queued = { taskId: 't', createdAt: '2026-09-27T10:10:00Z' };
    const accepted = taskAttentionAfterQueue(original, queued);
    if (accepted === undefined) throw new Error('The accepted queue must retain this task snapshot');
    const acknowledged = handledEntry(accepted, 'ack', null, 'Prompted');
    const refreshed = { ...running, updatedAt: queued.createdAt };
    expect(stillHandled(acknowledged, taskAttentionItem(refreshed, facts, WORKSPACE), 0)).toBe(true);
    expect(stillHandled(acknowledged, taskAttentionItem(refreshed, { ...facts, pendingApproval: { ...COMMAND, approvalId: 'approval-2' } }, WORKSPACE), 0)).toBe(false);
    expect(stillHandled(acknowledged, taskAttentionItem({ ...refreshed, mutationClaim: null }, activity({
      latestTurn: { id: 'turn-1', state: 'COMPLETED', endedAt: '2026-09-27T10:20:00Z' },
    }), WORKSPACE), 0)).toBe(false);
    expect(taskAttentionAfterQueue(accepted, { ...queued, createdAt: '2026-09-27T10:09:00Z' })).toBe(accepted);
    expect(taskAttentionAfterQueue(original, { ...queued, taskId: 'different-task' })).toBeUndefined();
  });

  it('shares a detail revision with the card and brings a prompted task back when its turn finishes', () => {
    const running = task('t', { mutationClaim: { kind: 'TURN', ownerId: 'turn-2', acquiredAt: '2026-09-27T11:00:00Z' } });
    const facts = activity({ latestTurn: { id: 'turn-2', state: 'RUNNING', endedAt: null } });
    const detail = taskAttentionItem(running, facts, WORKSPACE);
    expect(detail.key).toBe('task:t');
    expect(taskAttentionItems([running], { t: facts }, WORKSPACE)).toEqual([detail]);
    expect(attentionSummary(detail).tag.label).toBe('Running');
    const prompted = handledEntry(detail, 'ack', null, 'Prompted');
    const finished: AgentTaskDto = { ...running, mutationClaim: null };
    const done = activity({ latestTurn: { id: 'turn-2', state: 'COMPLETED', endedAt: '2026-09-27T12:00:00Z' } });
    const [card] = taskAttentionItems([finished], { t: { ...done, lastMessage: {
      author: 'AGENT', text: 'Done.', at: '2026-09-27T12:00:00Z', turnId: 'turn-2',
    } } }, WORKSPACE);
    expect(attentionActivityVersion(taskAttentionItem(finished, done, WORKSPACE))).toBe(attentionActivityVersion(card));
    expect(stillHandled(prompted, card, Date.parse('2026-09-27T12:01:00Z'))).toBe(false);
  });

  it('includes every unarchived task update with a truthful state', () => {
    const finished = activity({ latestTurn: { id: 'turn-1', state: 'COMPLETED', endedAt: '2026-09-27T10:00:00Z' } });
    expect(taskAttentionKind(task('t'), activity({ pendingApproval: COMMAND }))).toBe('approval');
    expect(taskAttentionKind(task('t'), finished)).toBe('finished');
    expect(taskAttentionKind(task('t'), activity({ latestTurn: { id: 'turn-1', state: 'FAILED', endedAt: '2026-09-27T10:00:00Z' } }))).toBe('failed');
    expect(taskAttentionKind(task('t', { mutationClaim: { kind: 'RECOVERY', ownerId: 'x', acquiredAt: '2026-09-27T10:00:00Z' } }), finished)).toBe('failed');
    // Running, cancelled, fresh and completed tasks remain visible; archived tasks do not.
    expect(taskAttentionKind(task('t', { mutationClaim: { kind: 'TURN', ownerId: 'turn-2', acquiredAt: '2026-09-27T10:00:00Z' } }), finished)).toBe('updated');
    expect(taskAttentionKind(task('t'), activity({ latestTurn: { id: 'turn-1', state: 'CANCELLED', endedAt: '2026-09-27T10:00:00Z' } }))).toBe('updated');
    expect(taskAttentionKind(task('t', { state: 'COMPLETED' }), finished)).toBe('finished');
    expect(taskAttentionKind(task('t', { archivedAt: '2026-09-27T11:00:00Z' }), finished)).toBeNull();
    expect(taskAttentionKind(task('t'), null)).toBe('updated');
  });

  it('uses the latest task or message change and wakes an acknowledged running task', () => {
    const running = task('t', { updatedAt: '2026-09-27T11:00:00Z',
      mutationClaim: { kind: 'TURN', ownerId: 'turn-2', acquiredAt: '2026-09-27T11:00:00Z' } });
    const previous = activity({ latestTurn: { id: 'turn-1', state: 'COMPLETED', endedAt: '2026-09-27T10:00:00Z' } });
    const original = taskAttentionItem(running, previous, WORKSPACE);
    expect(original.updatedAt).toBe(running.updatedAt);
    const changed = taskAttentionItem(running, { ...previous, lastMessage: {
      author: 'AGENT', text: 'Tests are running.', at: '2026-09-27T12:00:00Z', turnId: 'turn-2',
    } }, WORKSPACE);
    expect(changed.updatedAt).toBe('2026-09-27T12:00:00Z');
    expect(stillHandled(handledEntry(original, 'ack', null, 'Viewed'), changed, 0)).toBe(false);
  });

  it('draws an approval as the exact command, names the lane, and links the task to its ticket, pull request and issue', () => {
    const waiting = task('t-1', {
      jiraSource: {
        connectionId: 'c', cloudId: 'cloud', issueId: '412', key: 'ENG-412', title: 'Validate the fix', body: '', updatedAt: '',
        url: 'https://example.atlassian.net/browse/ENG-412', initialTurnId: null, instructions: '',
      },
      pullRequest: { number: 31264, title: 'Keep deletion vectors', state: 'open', draft: true },
    });
    const [item] = taskAttentionItems([waiting, task('t-2')], { 't-1': activity({ pendingApproval: COMMAND }) }, WORKSPACE,
      new Map([['claude', 'Claude Code']]));

    // A card names the lane without its model.
    expect(item).toMatchObject({ key: 'task:t-1', type: 'task', kind: 'approval', reference: 'Claude Code', updatedAt: COMMAND.occurredAt, workspaceName: 'repository' });
    // Without the catalog the card still names the lane.
    expect(taskAttentionItems([waiting], { 't-1': activity({ pendingApproval: COMMAND }) }, WORKSPACE)[0].reference).toBe('Claude');
    // A DeepSeek key lane goes by the key's name alone.
    const [keyed] = taskAttentionItems([{ ...waiting, laneId: 'deepseek-key-7' }], { 't-1': activity({ pendingApproval: COMMAND }) }, WORKSPACE,
      new Map([['deepseek-key-7', 'DeepSeek · ds-001']]));
    expect(keyed).toMatchObject({ reference: 'ds-001', author: 'ds-001' });
    expect(item.links).toEqual([
      { source: 'jira', key: 'ENG-412', title: 'Validate the fix', status: null, tone: 'neutral' },
      { source: 'pr', key: '#31264', title: 'Keep deletion vectors', status: 'Draft', tone: 'neutral' },
    ]);
    expect(attentionSummary(item)).toEqual({
      tag: { label: 'Approval needed', tone: 'amber' },
      actor: { name: 'Claude Code', avatarUrl: null },
      verb: 'wants to run a command',
      detail: { kind: 'command', lead: 'Re-run the checkpoint tests', command: './mvnw -pl plugin/storage test' },
    });
    // Approvals come before every other reason.
    expect(attentionCandidates([pr({ number: 7, origin: 'AUTHORED', ciStatus: 'FAILING' })], [item], 'riley')[0].key).toBe('task:t-1');
  });

  it('quotes the question or the last answer, and comes back when a later turn finishes after an acknowledgement', () => {
    const question = { ...COMMAND, scope: ['AskUserQuestion'], detail: JSON.stringify({ questions: [{ question: 'Pin the test to JDK 21?', options: [] }] }) };
    const [asked] = taskAttentionItems([task('t')], { t: activity({ pendingApproval: question }) }, WORKSPACE);
    expect(attentionSummary(asked)).toMatchObject({
      tag: { label: 'Question for you', tone: 'amber' }, verb: 'asked you a question', detail: { kind: 'quote', text: 'Pin the test to JDK 21?' },
    });

    const answer = { author: 'AGENT' as const, text: 'Pushed the fix; 20 reruns green.', at: '2026-09-27T10:00:00Z', turnId: 'turn-1' };
    const first = taskAttentionItems([task('t')], { t: activity({ lastMessage: answer, latestTurn: { id: 'turn-1', state: 'COMPLETED', endedAt: '2026-09-27T10:00:00Z' } }) }, WORKSPACE)[0];
    expect(attentionSummary(first)).toMatchObject({ tag: { label: 'Turn finished', tone: 'green' }, verb: 'finished a turn', detail: { kind: 'quote', text: answer.text } });
    const acknowledged = handledEntry(first, 'ack', null, 'acknowledged');
    expect(stillHandled(acknowledged, first, Date.parse('2026-09-27T12:00:00Z'))).toBe(true);
    const later = taskAttentionItems([task('t')], { t: activity({ latestTurn: { id: 'turn-2', state: 'COMPLETED', endedAt: '2026-09-27T11:00:00Z' } }) }, WORKSPACE)[0];
    expect(stillHandled(acknowledged, later, Date.parse('2026-09-27T12:00:00Z'))).toBe(false);
  });

  it('names a task card state, waiting on the user before whatever else the task is doing', () => {
    const running = { mutationClaim: { kind: 'TURN', ownerId: 'turn-1', acquiredAt: '2026-09-27T10:00:00Z' } };
    expect(taskCardState(task('t', running), activity({ pendingApproval: COMMAND })))
      .toEqual({ label: 'Needs you', tone: 'orange', spinning: false, reason: 'Approval needed to run a command' });
    expect(taskCardState(task('t', running), activity({ pendingApproval: { ...COMMAND, scope: ['AskUserQuestion'] } })).reason).toBe('Question for you');
    expect(taskCardState(task('t', running), null)).toMatchObject({ label: 'Running', spinning: true, reason: null });
    expect(taskCardState(task('t'), null)).toMatchObject({ label: 'Idle', tone: 'green' });
    expect(taskCardState(task('t', { state: 'COMPLETED' }), activity({ pendingApproval: COMMAND }))).toMatchObject({ label: 'Completed' });
    expect(taskCardState(task('t', { state: 'FAILED' }), null)).toMatchObject({ label: 'Failed', tone: 'red' });
  });

  it('quotes a generated first request as the request it stands for, and every other message as said', () => {
    const issueSource = {
      issueId: 9, number: 31190, updatedAt: '', initialTurnId: 'turn-1', instructions: '', host: 'github.com',
      repo: 'owner/repository', url: '', title: 'Checkpoint drops deletion vectors', body: '',
    };
    const generated = { author: 'USER' as const, text: 'INTERNAL GENERATED PROMPT', at: '2026-09-27T10:00:00Z', turnId: 'turn-1' };
    expect(taskLastMessage(task('t', { issueSource }), activity({ lastMessage: generated }))?.text).toBe('Start development · owner/repository#31190');
    expect(taskLastMessage(task('t', { issueSource }), activity({ lastMessage: { ...generated, turnId: 'turn-2', text: 'Also add a test' } }))?.text)
      .toBe('Also add a test');
    expect(taskLastMessage(task('t'), activity({ lastMessage: generated }))?.text).toBe('INTERNAL GENERATED PROMPT');
    expect(taskLastMessage(task('t'), null)).toBeNull();
  });

  it('links a pull request card to the task whose branch opened it, in the same repository only', () => {
    const items = attentionCandidates([pr({ number: 31264, repo: 'owner/repository', origin: 'AUTHORED' }), pr({ id: 2, number: 31264, repo: 'other/repo', origin: 'AUTHORED' })], [], 'riley');
    const opener = task('t-1', { pullRequest: { number: 31264, title: 'Keep deletion vectors', state: 'open', draft: false } });

    const linked = linkTasks(items, [{ task: opener, workspace: WORKSPACE }]);
    expect(linked.find(item => item.repo === 'owner/repository')?.links).toEqual([{ source: 'task', key: 'Task', title: 'Task t-1', status: 'Idle', tone: 'green' }]);
    expect(linked.find(item => item.repo === 'other/repo')?.links).toBeUndefined();
  });
});
