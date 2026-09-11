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
import { describe, expect, it } from 'vitest';
import { DevelopmentFacts } from './DevelopmentFacts';
import { parseAgentDevelopmentFacts, parseAgentTurn } from './contracts';
import { AgentTaskBody } from './AgentTaskScreen';
import { taskStatePill } from './AgentTasksView';
import { initialAgentTaskState } from './model';
import { parseAgentEventSequence } from './events';
import contracts from './fixtures/agent-contracts-v1.json';
import events from './fixtures/agent-events-v2.json';
import { previewTask } from './fixtures/previewBridge';
import type { AgentDevelopmentFactsDto, AgentTaskDto, AgentTurnDto } from '../types';

const facts: AgentDevelopmentFactsDto = {
  observedAt: '2026-01-01T12:00:00Z',
  local: { branch: 'codex/change', headSha: '1234567890', stagedFiles: 1, unstagedFiles: 1, untrackedFiles: 1, uncommittedFileCount: 2, commitCount: 1 },
  localError: null,
  remote: { headSha: null, pullRequest: null },
  remoteError: null,
};
const renderFacts = (value: AgentDevelopmentFactsDto, running = false) => renderToStaticMarkup(<DevelopmentFacts facts={value} loading={false} error={null} running={running} />);

describe('verified development observations', () => {
  it('keeps unknown remote results separate from confirmed absence', () => {
    const unknown = renderFacts({ ...facts, remote: null, remoteError: 'GitHub could not be read' });
    expect(unknown).toContain('Remote result unknown');
    expect(unknown).not.toContain('No remote branch');
    expect(unknown).not.toContain('No PR');
    expect(unknown).toContain('GitHub could not be read');
    expect(renderFacts(facts)).toContain('No remote branch');
    expect(renderFacts(facts)).toContain('No PR');
  });
  it('shows a retained merged PR honestly after its remote branch is deleted', () => {
    const html = renderFacts({ ...facts, remote: { headSha: null, pullRequest: {
      number: 17, url: 'https://github.com/acme/demo/pull/17', state: 'MERGED', draft: false, headSha: '1234567890',
    } } });
    expect(html).toContain('No remote branch');
    expect(html).toContain('merged PR #17');
    expect(html).toContain('https://github.com/acme/demo/pull/17');
    expect(html).not.toContain('Not pushed');
    expect(html).not.toContain('No PR');
  });
  it('shows readiness only from an observed open non-draft PR, retaining dated facts while automation runs', () => {
    const pr = {
      number: 17, url: 'https://github.com/acme/demo/pull/17', state: 'OPEN', draft: true, headSha: '1234567890',
    };
    const draft: AgentDevelopmentFactsDto = { ...facts, remote: { headSha: '1234567890', pullRequest: pr } };
    const pending = renderToStaticMarkup(<DevelopmentFacts facts={draft} loading={false} error={null} running={false} automationActive />);
    expect(pending).toContain('Automation running; showing previous observations');
    expect(pending).toContain('Draft PR #17');
    expect(pending).not.toContain('Ready for review');
    expect(renderFacts({ ...draft, remote: { headSha: '1234567890', pullRequest: { ...pr, draft: false } } }))
      .toContain('Ready for review · PR #17');
  });
  it('shows unique dirty files and distinct local and remote revisions without implying checks passed', () => {
    const html = renderFacts({ ...facts, remote: { headSha: 'abcdefghij', pullRequest: { number: 17, url: 'https://github.com/acme/demo/pull/17', state: 'OPEN', draft: true, headSha: 'abcdefghij' } } }, true);
    expect(html).toContain('2 uncommitted files');
    expect(html).toContain('1 staged · 1 unstaged · 1 untracked');
    expect(html).toContain('1 local commit');
    expect(html).toContain('12345678');
    expect(html).toContain('abcdefgh');
    expect(html).toContain('Local HEAD differs from remote');
    expect(html).toContain('Draft PR #17');
    expect(html).toContain('Agent running; showing previous observations');
    expect(html).not.toContain('passed');
    expect(html).not.toContain('green');
  });
  it('rejects omitted observations and invalid counts instead of inventing absence', () => {
    expect(parseAgentDevelopmentFacts({ ...facts, privatePath: '/secret' })).toEqual(facts);
    expect(() => parseAgentDevelopmentFacts({ ...facts, remote: {} })).toThrow();
    expect(() => parseAgentDevelopmentFacts({ ...facts, remote: { pullRequest: null } })).toThrow(/headSha/);
    expect(() => parseAgentDevelopmentFacts({ ...facts, local: { ...facts.local, commitCount: -1 } })).toThrow();
    expect(() => parseAgentDevelopmentFacts({ ...facts, local: { ...facts.local, uncommittedFileCount: 1.5 } })).toThrow();
    const html = renderFacts({ ...facts, localError: 'x'.repeat(500) });
    expect(html).not.toContain('x'.repeat(241));
  });
});

const baseTurn = parseAgentTurn(contracts.turn);
function taskHtml(turn: AgentTurnDto, approvals = false): string {
  const task: AgentTaskDto = { ...previewTask('workspace-1', turn.taskId), interactive: true, mutationClaim: null };
  const state = { ...initialAgentTaskState(), task, turn, latestTurnId: turn.id, turns: { [turn.id]: turn },
    events: parseAgentEventSequence(events.events).filter(event => approvals || !event.type.startsWith('approval')).map(event => ({ ...event, taskId: task.id, turnId: turn.id })), connection: 'open' as const };
  return renderToStaticMarkup(<AgentTaskBody state={state} models={[]} developmentFacts={facts}
    onBack={() => {}} onSend={() => {}} onCancel={() => {}} onComplete={() => {}} onDecide={() => {}} onCompose={() => {}}
    onSelectModel={() => {}} onStopServer={() => {}} onMarkFailed={() => {}} />);
}

it('keeps the durable question ahead of observed files, commits and the composer', () => {
  const question = 'Which implementation should I use?';
  const html = taskHtml({ ...baseTurn, state: 'COMPLETED', developmentResult: { readyForDelivery: false, message: question, stopAfter: null } });
  expect(html.indexOf(question)).toBeLessThan(html.indexOf('Observed development results'));
  expect(html.indexOf('Observed development results')).toBeLessThan(html.indexOf('agt-compose__input'));
  expect(html).toContain('Waiting for your answer');
});

it('attributes generated requests using trusted durable origins and names live activity', () => {
  for (const [kind, label, activity] of [['AUTO_COMMIT', 'Auto commit', 'Committing'], ['CREATE_DRAFT_PR', 'Create draft PR', 'Creating draft PR']] as const) {
    const origin = { kind, parentUserTurnId: 'source-turn' };
    const turn = parseAgentTurn({ ...contracts.turn, state: 'RUNNING', developmentOrigin: { ...origin, hiddenPath: '/secret' } });
    expect(turn.developmentOrigin).toEqual(origin);
    const html = taskHtml(turn);
    expect(html).toContain(`ByteQuay · ${label}`);
    expect(html).toContain(activity);
    expect(taskHtml(turn, true)).toContain('>Needs approval<');
  }
  expect(() => parseAgentTurn({ ...contracts.turn, developmentOrigin: { kind: 'USER', parentUserTurnId: 'source' } })).toThrow();
  expect(taskHtml({ ...baseTurn, state: 'COMPLETED' })).not.toContain('ByteQuay · Auto commit');
});

it('uses Idle for settled runtime and preserves live automation and recovery precedence in task cards', () => {
  expect(taskHtml({ ...baseTurn, state: 'COMPLETED' })).toContain('>Idle<');
  const task = previewTask('workspace-1', baseTurn.taskId);
  expect(taskStatePill({ ...task, mutationClaim: null }).label).toBe('Idle');
  expect(taskStatePill({ ...task, mutationClaim: null, developmentContinuationUnfinished: true }).label).toBe('Automation running');
  expect(taskStatePill({ ...task, mutationClaim: { kind: 'RECOVERY', ownerId: 'owner', acquiredAt: '' }, developmentContinuationUnfinished: true }).label).toBe('Recovery required');
});
