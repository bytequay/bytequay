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

import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { expect, it } from 'vitest';
import { TurnView } from './AgentConversation';
import { draftPullRequestRequest } from './DevelopmentFacts';
import { parseAgentTurn } from './contracts';
import { AgentTaskBody, draftPullRequestPrompt } from './AgentTaskScreen';
import { taskStatePill } from './AgentTasksView';
import { initialAgentTaskState } from './model';
import { parseAgentEventSequence } from './events';
import contracts from './fixtures/agent-contracts-v1.json';
import events from './fixtures/agent-events-v2.json';
import { previewTask } from './fixtures/previewBridge';
import type { AgentTaskDto, AgentTurnDto, WorkspaceDto } from '../types';

const baseTurn = parseAgentTurn(contracts.turn);
function taskHtml(turn: AgentTurnDto, approvals = false): string {
  const task: AgentTaskDto = { ...previewTask('workspace-1', turn.taskId), interactive: true, mutationClaim: null };
  const state = { ...initialAgentTaskState(), task, turn, latestTurnId: turn.id, turns: { [turn.id]: turn },
    events: parseAgentEventSequence(events.events).filter(event => approvals || !event.type.startsWith('approval')).map(event => ({ ...event, taskId: task.id, turnId: turn.id })), connection: 'open' as const };
  return renderToStaticMarkup(<AgentTaskBody state={state} models={[]}
    onBack={() => {}} onSend={() => {}} onCancel={() => {}} onComplete={() => {}} onDecide={() => {}} onCompose={() => {}}
    onSelectModel={() => {}} onStopServer={() => {}} onMarkFailed={() => {}} />);
}

it('keeps the durable question ahead of the composer', () => {
  const question = 'Which implementation should I use?';
  const html = taskHtml({ ...baseTurn, state: 'COMPLETED', developmentResult: { readyForDelivery: false, message: question, stopAfter: null } });
  expect(html.indexOf(question)).toBeLessThan(html.indexOf('agt-compose__input'));
  expect(html).toContain('Waiting for your answer');
});

it('attributes generated requests using trusted durable origins and names live activity', () => {
  for (const [kind, shown, activity] of [['AUTO_COMMIT', 'ByteQuay · Auto commit', 'Committing'], ['CREATE_DRAFT_PR', 'Open draft pull request', 'Creating draft PR']] as const) {
    const origin = { kind, parentUserTurnId: 'source-turn' };
    const turn = parseAgentTurn({ ...contracts.turn, state: 'RUNNING', developmentOrigin: { ...origin, hiddenPath: '/secret' } });
    expect(turn.developmentOrigin).toEqual(origin);
    const html = taskHtml(turn);
    expect(html).toContain(shown);
    expect(html).toContain(activity);
    expect(taskHtml(turn, true)).toContain('>Needs approval<');
  }
  expect(() => parseAgentTurn({ ...contracts.turn, developmentOrigin: { kind: 'USER', parentUserTurnId: 'source' } })).toThrow();
  expect(taskHtml({ ...baseTurn, state: 'COMPLETED' })).not.toContain('ByteQuay · Auto commit');
});

it('shows both create-PR requests as the action and the branches they name, never as the prompt', () => {
  const ordinary: AgentTurnDto = { ...baseTurn, taskId: 'c587a121' };
  const branch = 'chenjian2664/bytequay/find-and-refactor-minor-issue-c587a121';
  const route = { head: branch, base: 'master' };
  const generated: AgentTurnDto = { ...ordinary, developmentOrigin: { kind: 'CREATE_DRAFT_PR', parentUserTurnId: 'source-turn' } };
  // The lines DevelopmentFollowupCoordinator.publicationPrompt writes for a fork route.
  const publication = [
    'ByteQuay · Create draft PR',
    `Worktree: '/w/worktrees/t'. Branch: '${branch}'. Exact clean HEAD to publish: e5083dc3.`,
    "Base repository/branch: trinodb/trino / 'master'. Push repository: alice/trino. Push URL: 'https://github.com/alice/trino.git'.",
  ].join('\n');
  const workspace: WorkspaceDto = {
    id: 'workspace-1', name: 'trino', selectedRepositoryId: 1, selectedOwner: 'alice', selectedRepo: 'trino',
    upstreamRepositoryId: 2, upstreamOwner: 'trinodb', upstreamRepo: 'trino', monitoredRepositoryId: 2, monitoredOwner: 'trinodb', monitoredRepo: 'trino',
    topology: 'FORK', defaultBranch: 'main', upstreamDefaultBranch: 'master', localRepositoryPath: '/w', lastCodeSyncAt: null, lastMetadataSyncAt: null, displayOrder: 0,
  };
  const panel = draftPullRequestPrompt(workspace);
  expect(draftPullRequestRequest(generated, publication, branch)).toEqual(route);
  expect(draftPullRequestRequest(ordinary, panel, branch)).toEqual(route);
  expect(draftPullRequestRequest(ordinary, panel, null)).toEqual({ ...route, head: null });
  // A reworded template still reads as the action; only its base goes unnamed.
  expect(draftPullRequestRequest(generated, 'Push the revision and create its draft', branch)).toEqual({ ...route, base: null });
  // Only the trusted origin or the panel's own request qualifies, and only with its durable turn.
  expect(draftPullRequestRequest(ordinary, publication, branch)).toBeNull();
  expect(draftPullRequestRequest(ordinary, 'Please open a draft pull request for this task.', branch)).toBeNull();
  expect(draftPullRequestRequest(undefined, panel, branch)).toBeNull();

  const html = renderToStaticMarkup(<TurnView
    turn={{ turnId: ordinary.id, ordinal: 1, blocks: [], answer: null, startedAt: null, endedAt: null, outcome: null, usage: null,
      users: [{ id: 'request', text: panel, attachments: [], delivery: 'TURN', at: '2026-01-01T12:00:00Z' }] }}
    first={false} open={false} now={0} models={[]} durableTurns={{ [ordinary.id]: ordinary }} activityChoice={{}} previews={{}} agentLabels={{}} generatedBranch={branch}
    onToggle={() => {}} onToggleActivity={() => {}} />);
  expect(html).toContain('Open draft pull request');
  expect(html).toContain(`title="${route.head}">${route.head}</span>`);
  expect(html).toContain('→ master');
  expect(html).not.toContain('agt-user__bubble');
  expect(html).not.toContain('gh pr create');
});

it('uses Idle for settled runtime and preserves live automation and recovery precedence in task cards', () => {
  expect(taskHtml({ ...baseTurn, state: 'COMPLETED' })).toContain('>Idle<');
  const task = previewTask('workspace-1', baseTurn.taskId);
  expect(taskStatePill({ ...task, mutationClaim: null }).label).toBe('Idle');
  expect(taskStatePill({ ...task, mutationClaim: null, developmentContinuationUnfinished: true }).label).toBe('Automation running');
  expect(taskStatePill({ ...task, mutationClaim: { kind: 'RECOVERY', ownerId: 'owner', acquiredAt: '' }, developmentContinuationUnfinished: true }).label).toBe('Recovery required');
});
