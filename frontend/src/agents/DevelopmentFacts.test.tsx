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
import { expect, it } from 'vitest';
import { TurnView } from './AgentConversation';
import { draftPullRequestRequest } from './DevelopmentFacts';
import { parseAgentTurn } from './contracts';
import { AgentTaskBody } from './AgentTaskScreen';
import { taskStatePill } from './AgentTasksView';
import { initialAgentTaskState } from './model';
import { parseAgentEventSequence } from './events';
import contracts from './fixtures/agent-contracts-v1.json';
import events from './fixtures/agent-events-v2.json';
import { previewTask, TASK_BODY_NOOPS } from './fixtures/previewBridge';
import type { AgentTaskDto, AgentTurnDto } from '../types';

const baseTurn = parseAgentTurn(contracts.turn);
function taskHtml(turn: AgentTurnDto, approvals = false): string {
  const task: AgentTaskDto = { ...previewTask('workspace-1', turn.taskId), interactive: true, mutationClaim: null };
  const state = { ...initialAgentTaskState(), task, turn, latestTurnId: turn.id, turns: { [turn.id]: turn },
    events: parseAgentEventSequence(events.events).filter(event => approvals || !event.type.startsWith('approval')).map(event => ({ ...event, taskId: task.id, turnId: turn.id })), connection: 'open' as const };
  return renderToStaticMarkup(<AgentTaskBody state={state} models={[]} {...TASK_BODY_NOOPS} />);
}

it('asks for the durable answer in the composer, not a pinned card', () => {
  const question = 'Which implementation should I use?';
  const html = taskHtml({ ...baseTurn, state: 'COMPLETED', developmentResult: { readyForDelivery: false, message: question } });
  expect(html).not.toContain(question);
  expect(html).toContain('placeholder="Waiting for your answer…"');
});

it('attributes generated requests using trusted durable origins and names live activity', () => {
  for (const [kind, shown, activity] of [
    ['AUTO_COMMIT', 'ByteQuay · Auto commit', 'Committing'],
    ['CREATE_DRAFT_PR', 'Open draft pull request', 'Creating draft PR'],
    ['REFINE_SUMMARY', 'ByteQuay · Refine summary', 'Refining summary'],
  ] as const) {
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

it('shows the create-PR request as the action and the branches it names, never as the prompt', () => {
  const ordinary: AgentTurnDto = { ...baseTurn, taskId: 'c587a121' };
  const branch = 'chenjian2664/bytequay/find-and-refactor-minor-issue-c587a121';
  const route = { action: 'Open draft pull request', head: branch, base: 'master' };
  const generated: AgentTurnDto = { ...ordinary, developmentOrigin: { kind: 'CREATE_DRAFT_PR', parentUserTurnId: 'source-turn' } };
  const said = (text: string, attachments: unknown[] = []) => ({ text, attachments });
  // The lines DevelopmentFollowupCoordinator.publicationPrompt writes for a fork route, the last one naming any PR to reuse.
  const publication = (base = "'master'", pullRequest = 'No pull request tracked this branch at verification.') => [
    'ByteQuay · Create draft PR',
    `Worktree: '/w/worktrees/t'. Branch: '${branch}'. Exact clean HEAD to publish: e5083dc3.`,
    `Base repository/branch: trinodb/trino / ${base}. Push repository: alice/trino.`,
    `and never read or ask for credentials. ${pullRequest}`,
  ].join('\n');
  expect(draftPullRequestRequest(generated, said(publication()), branch)).toEqual(route);
  expect(draftPullRequestRequest(generated, said(publication()), null)).toEqual({ ...route, head: null });
  // Publishing a later revision into a pull request that is already open opens nothing, draft or not.
  const reuse = 'Pull request https://github.com/trinodb/trino/pull/12 already tracks this branch; publishing updates it and creates no second one.';
  expect(draftPullRequestRequest(generated, said(publication(undefined, reuse)), branch)).toEqual({ ...route, action: 'Update pull request #12' });
  // The base arrives shell-quoted, so a quote inside the branch name must survive.
  expect(draftPullRequestRequest(generated, said(publication(`'o'"'"'brien/fix'`)), branch)?.base).toBe("o'brien/fix");
  // A reworded template still reads as the action; only its base goes unnamed.
  expect(draftPullRequestRequest(generated, said('Push the revision and create its draft'), branch)).toEqual({ ...route, base: null });
  // Only the trusted origin qualifies, and only with its durable turn: a person who types the same
  // words gets an ordinary bubble, because they authorize no publication.
  expect(draftPullRequestRequest(ordinary, said(publication()), branch)).toBeNull();
  expect(draftPullRequestRequest(ordinary, said('Open a draft pull request for this task.'), branch)).toBeNull();
  expect(draftPullRequestRequest(undefined, said(publication()), branch)).toBeNull();

  const html = renderToStaticMarkup(<TurnView
    turn={{ turnId: generated.id, ordinal: 1, blocks: [], answer: null, startedAt: null, endedAt: null, outcome: null,
      users: [{ id: 'request', text: publication(), attachments: [], delivery: 'TURN', at: '2026-01-01T12:00:00Z' }] }}
    first={false} open={false} now={0} models={[]} durableTurns={{ [generated.id]: generated }} activityChoice={{}} previews={{}} agentLabels={{}} generatedBranch={branch}
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
