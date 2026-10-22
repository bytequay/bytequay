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
import { flushSync } from 'react-dom';
import { createFixtureRoot } from '../../fixtures/reactFixture';
import { assert, settle, until } from '../../fixtures/harness';
import { CI_RUN } from '../../agents/fixtures/ciAutofix';
import { createPreviewBridge } from '../../agents/fixtures/previewBridge';
import type { AgentApprovalDecision, AgentApprovalDto, AgentHistoryPageDto, AgentPendingApprovalDto, AgentTurnDto, CherryPickExecution, CiAutofixRunDto } from '../../types';
import CherryPickTaskScreen from '../CherryPickTaskScreen';
import { lifecycle } from './lifecycle';
import '../../css/agents.css';
import '../../css/commits.css';

const SOURCE = '1'.repeat(40);
const COPY = '6'.repeat(40);
const FIXUP = '7'.repeat(40);

function execution(stage: CherryPickExecution['lifecycle']['stage'], fixups: boolean): CherryPickExecution {
  return {
    executionId: 'range-1', workspaceId: 'workspace-1', repository: 'acme/demo', sourceRepository: 'acme/upstream', workBranch: 'cp-work',
    localTargetBranch: 'release-target', targetBranch: 'release', status: 'RUNNING', initialTargetSha: 'a'.repeat(40),
    currentTargetSha: fixups ? FIXUP : COPY, agentTaskId: 'cp-task',
    pullRequestDraft: { title: 'Cherry-pick 1 commit into release', body: '' },
    delivery: { pushRemote: 'origin', head: 'cp-work', sha: COPY, pullRequestNumber: 17 },
    items: [{ position: 1, sourceSha: SOURCE, title: 'Port change', result: 'APPLIED', targetSha: COPY }],
    createdAt: '2026-01-01T12:00:00Z', updatedAt: '2026-01-01T12:05:00Z', terminalAt: null,
    lifecycle: lifecycle({
      delivers: true, stage, ciRunIds: ['run-1'],
      commits: [
        { sha: COPY, subject: 'Port change', sourceSha: SOURCE },
        ...fixups ? [{ sha: FIXUP, subject: 'fixup! Port change', fixupOf: SOURCE, origin: 'CI' as const }] : [],
      ],
    }),
  };
}

const APPROVAL: AgentPendingApprovalDto = {
  approvalId: 'ci-approval', taskId: 'ci-task', turnId: 'ci-turn', occurredAt: '2026-01-01T12:01:00Z',
  source: { providerThreadId: 'thread-1', parentProviderThreadId: null, logicalAgentPath: null,
    providerTurnId: 'ci-turn', providerItemId: 'ci-approval', providerEventId: null },
  title: 'Allow Codex command', detail: 'mvn test', approvalKind: 'COMMAND', scope: ['run: mvn test'],
  sandboxed: false, availableDecisions: ['ALLOW_ONCE', 'DENY'], actionable: true,
};

type Calls = { decisions: Array<[string, string]>; squash: Array<[string, string]>; cancels: number };

/** Installs a bridge for one execution: the cherry-pick agent is quiet, the CI agent has a live turn waiting on one approval. */
async function install(value: CherryPickExecution, run: CiAutofixRunDto): Promise<Calls> {
  const preview = createPreviewBridge('workspace-1');
  const event = preview.events.find(entry => entry.turnId !== null);
  assert(event !== undefined && event.turnId !== null, 'The fixture needs an event in a turn');
  const ciEvent = { ...event, taskId: 'ci-task', turnId: 'ci-turn' };
  const turn: AgentTurnDto = { ...await preview.getAgentTurn(event.turnId), id: 'ci-turn', taskId: 'ci-task', state: 'RUNNING', endedAt: null };
  const calls: Calls = { decisions: [], squash: [], cancels: 0 };
  let approvals = [APPROVAL];
  Object.defineProperty(window, 'bridge', { configurable: true, value: {
    ...preview,
    getCherryPickExecution: async () => value,
    getCiAutofixRun: async () => run,
    readAgentHistory: async (taskId: string): Promise<AgentHistoryPageDto> => taskId === 'ci-task'
      ? { events: [ciEvent], beforeCursor: null, afterCursor: null, latestSequence: ciEvent.sequence }
      : { events: [], beforeCursor: null, afterCursor: null, latestSequence: 0 },
    subscribeAgentTask: async (taskId: string, _after: number, notify: (message: unknown) => void) => {
      notify({ subscriptionId: taskId, taskId, kind: 'state', state: 'open' });
      return () => {};
    },
    getAgentTurn: async () => turn,
    readAgentPendingApprovals: async (taskId: string, turnId: string) => {
      assert(taskId === 'ci-task' && turnId === 'ci-turn', 'Only the live CI turn may supply approvals');
      return approvals;
    },
    resolveAgentApproval: async (approvalId: string, decision: AgentApprovalDecision): Promise<AgentApprovalDto> => {
      calls.decisions.push([approvalId, decision]);
      approvals = [];
      return { approvalId, decision: decision === 'ALLOW_FOR_TASK' ? 'ALLOW' : decision, forTask: false,
        scopedPermission: null, resolvedAt: '2026-01-01T12:02:00Z', timedOut: false };
    },
    approveCherryPickSquash: async (executionId: string, head: string) => { calls.squash.push([executionId, head]); return value; },
    cancelCherryPickExecution: async () => { calls.cancels++; return { ...value, status: 'FINISHED', finishReason: 'CANCELLED' }; },
    fetchPullRequestDetailByRef: async () => { throw new Error('PR detail unavailable in this fixture'); },
    refreshPullRequestDetailByRef: async () => { throw new Error('PR detail unavailable in this fixture'); },
  } });
  return calls;
}

async function scenario(name: string, body: (host: HTMLElement, calls: Calls) => Promise<void>, value: CherryPickExecution, run: CiAutofixRunDto)
  : Promise<{ name: string; error?: string }> {
  const original = Object.getOwnPropertyDescriptor(window, 'bridge');
  const calls = await install(value, run);
  const { host, root, dispose } = createFixtureRoot();
  flushSync(() => root.render(<div style={{ height: '760px', display: 'flex' }}><CherryPickTaskScreen executionId={value.executionId} onBack={() => {}} /></div>));
  try {
    await settle();
    await body(host, calls);
    return { name };
  }
  catch (failure) {
    return { name, error: failure instanceof Error ? failure.message : String(failure) };
  }
  finally {
    dispose();
    if (original === undefined) delete (window as { bridge?: unknown }).bridge;
    else Object.defineProperty(window, 'bridge', original);
  }
}

const button = (host: HTMLElement, text: string): HTMLButtonElement | undefined =>
  [...host.querySelectorAll<HTMLButtonElement>('button')].find(candidate => candidate.textContent?.trim() === text);

export async function run(): Promise<{ name: string; error?: string }[]> {
  const active: CiAutofixRunDto = { ...CI_RUN, id: 'run-1', parentTaskId: null, status: 'ACTIVE', agentTaskId: 'ci-task', currentTurnId: 'ci-turn' };
  const green: CiAutofixRunDto = { ...active, status: 'FINISHED', finishReason: 'GREEN', currentTurnId: null };
  return [
    await scenario('A CI agent’s approval is answerable in the cherry-pick conversation', async (host, calls) => {
      const allow = () => host.querySelector<HTMLButtonElement>('button[aria-label="Allow once: Allow Codex command"]');
      await until(() => allow() !== null, `The CI approval must appear in the feed: ${host.textContent}`);
      allow()?.click();
      await settle();
      assert(calls.decisions.length === 1 && calls.decisions[0][0] === APPROVAL.approvalId && calls.decisions[0][1] === 'ALLOW', 'The card sends the chosen decision');
    }, execution('CI_FIX', false), active),
    await scenario('Squash waits for the user and binds to the reviewed head', async (host, calls) => {
      await until(() => button(host, 'Approve squash') !== undefined, `Approve squash must be offered: ${host.textContent}`);
      assert(host.textContent?.includes('Squash 1 fixup') === true, 'The feed asks to squash the one fixup');
      button(host, 'Approve squash')?.click();
      await settle();
      assert(calls.squash.length === 1 && calls.squash[0][0] === 'range-1' && calls.squash[0][1] === FIXUP, 'Approval names the head the user saw');
      button(host, 'Review fixups')?.click();
      await settle();
      assert(host.querySelector('.cpt-row.is-fixup') !== null && host.textContent?.includes('Agent fixups · 1') === true, 'Review fixups opens the source and agent split');
    }, execution('SQUASH', true), green),
    await scenario('Abort asks before discarding the branch', async (host, calls) => {
      await until(() => button(host, 'Abort…') !== undefined, 'Abort must be offered while the task runs');
      button(host, 'Abort…')?.click();
      await settle();
      assert(host.querySelector('[role="dialog"][aria-label="Abort and discard branch?"]') !== null, 'Abort opens its confirmation');
      assert(calls.cancels === 0, 'Opening the confirmation aborts nothing');
      button(host, 'Keep running')?.click();
      await settle();
      assert(host.querySelector('[aria-label="Abort and discard branch?"]') === null && calls.cancels === 0, 'Keep running closes it');
      button(host, 'Abort…')?.click();
      await settle();
      button(host, 'Abort task')?.click();
      await settle();
      assert(Number(calls.cancels) === 1, 'Confirming aborts once');
    }, execution('CI_FIX', false), active),
  ];
}
