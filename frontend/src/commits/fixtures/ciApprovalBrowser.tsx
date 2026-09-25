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
import { createRoot } from 'react-dom/client';
import type { AgentApprovalDecision, AgentApprovalDto, AgentPendingApprovalDto, CherryPickExecution, CiAutofixRunDto, WorkspaceDeliveryStatus } from '../../types';
import { assert, settle } from '../../fixtures/harness';
import ProgressView from '../ProgressView';
import '../../css/agents.css';
import '../../css/commits.css';

const execution: CherryPickExecution = {
  executionId: 'range-1', workspaceId: 'workspace-1', repository: 'acme/demo', workBranch: 'ci-branch',
  localTargetBranch: 'main', targetBranch: 'main', status: 'FINISHED', finishReason: 'COMPLETED',
  initialTargetSha: 'a'.repeat(40), currentTargetSha: 'b'.repeat(40), items: [],
};
const delivery: WorkspaceDeliveryStatus = {
  pushWritable: false, baseRepository: { owner: 'acme', repo: 'demo' }, baseBranch: 'main', head: 'ci-branch',
  ciAutofix: { runId: 'run-1', status: 'ACTIVE', attemptsUsed: 1 },
};
const approval: AgentPendingApprovalDto = {
  approvalId: 'ci-approval', taskId: 'ci-task', turnId: 'ci-turn', occurredAt: '2026-09-25T09:00:00Z',
  source: { providerThreadId: 'thread-1', parentProviderThreadId: null, logicalAgentPath: null,
    providerTurnId: 'ci-turn', providerItemId: 'ci-approval', providerEventId: null },
  title: 'Allow Codex command', detail: 'mvn test', approvalKind: 'COMMAND', scope: ['run: mvn test'],
  availableDecisions: ['ALLOW_ONCE', 'DENY'], actionable: true,
};

async function scenario(parentTaskId: string | null): Promise<{ name: string; error?: string }> {
  const name = parentTaskId === null ? 'Standalone CI approval is answerable in Commits' : 'Linked CI approval is hidden from Commits';
  const original = Object.getOwnPropertyDescriptor(window, 'bridge');
  let reads = 0;
  const decisions: Array<[string, string]> = [];
  const run: CiAutofixRunDto = {
    id: 'run-1', workspaceId: 'workspace-1', repository: 'acme/demo', pullRequestNumber: 17, headRef: 'ci-branch',
    status: 'ACTIVE', activity: 'FIXING', version: 1, authorization: { maxAttempts: 3, expiresAt: '2099-01-01T12:00:00Z' },
    attemptsUsed: 1, rerunsUsed: 0, agentTaskId: approval.taskId, currentTurnId: approval.turnId, parentTaskId,
    canMessage: false, summary: null, pauseReason: null, finishReason: null,
  };
  Object.defineProperty(window, 'bridge', { configurable: true, value: {
    getUserProfile: async (): Promise<null> => null,
    getCherryPickExecution: async () => execution,
    getWorkspaceDeliveryStatus: async () => delivery,
    getCiAutofixRun: async () => run,
    readAgentPendingApprovals: async (taskId: string, turnId: string) => {
      reads++;
      assert(taskId === approval.taskId && turnId === approval.turnId, 'Only the verified CI turn may supply approvals');
      return [approval];
    },
    resolveAgentApproval: async (approvalId: string, decision: AgentApprovalDecision): Promise<AgentApprovalDto> => {
      decisions.push([approvalId, decision]);
      return { approvalId, decision: decision === 'ALLOW_FOR_TASK' ? 'ALLOW' : decision, forTask: false,
        scopedPermission: null, resolvedAt: '2026-09-25T09:01:00Z', timedOut: false };
    },
  } });
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  flushSync(() => root.render(<ProgressView executionId="range-1" workspaceId="workspace-1" upstreamSlug={null}
    onBackToTasks={() => {}} onStartAnotherRange={() => {}} />));
  try {
    await settle();
    const allow = host.querySelector<HTMLButtonElement>('button[aria-label="Allow once: CI Autofix · Allow Codex command"]');
    if (parentTaskId !== null) {
      assert(allow === null && reads === 0, 'A development-owned CI run must stay on its source Task screen');
      return { name };
    }
    assert(allow !== null && reads === 1, 'Standalone CI approval must appear in the Commits delivery view');
    allow.click();
    await settle();
    assert(decisions.length === 1 && decisions[0][0] === approval.approvalId && decisions[0][1] === 'ALLOW', 'The card must send the selected decision');
    assert(host.querySelector('[aria-label="Approval: CI Autofix · Allow Codex command"]') === null, 'The resolved card must disappear');
    return { name };
  } catch (failure) { return { name, error: failure instanceof Error ? failure.message : String(failure) }; }
  finally {
    flushSync(() => root.unmount());
    host.remove();
    if (original === undefined) delete (window as { bridge?: unknown }).bridge;
    else Object.defineProperty(window, 'bridge', original);
  }
}

export async function run(): Promise<{ name: string; error?: string }[]> {
  return [await scenario(null), await scenario('source-task')];
}
