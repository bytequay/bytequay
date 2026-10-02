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
import type { AgentSkillDto, DevelopmentResultDto } from '../types';
import fixture from './fixtures/agent-contracts-v1.json';
import {
  parseAgentApproval,
  parseAgentAttachment,
  parseAgentCompletion,
  parseAgentInputDelivery,
  agentProviderUnavailableFrom,
  parseAgentProviderStatus,
  parseAgentProviderUnavailable,
  parseAgentQueue,
  parseAgentQueuedInput,
  parseAgentSkillInput,
  parseAgentSkills,
  parseAgentTask,
  parseAgentTaskActivity,
  parseAgentTaskChanges,
  parseAgentTasks,
  parseAgentTurn,
  parseDevelopmentResult,
  parseModelSettings,
} from './contracts';

const capabilities = {
  structuredPlans: true,
  nestedAgentActivity: true,
  steering: true,
  permissionPolicies: ['AUTO'],
  attachmentMediaTypes: ['image/png', 'image/jpeg', 'image/gif'],
};

describe('agent skills bridge contract', () => {
  const skill: AgentSkillDto = { id: 'release-notes', name: 'Release notes', instructions: 'Use our release note format.', enabled: true, scope: 'GLOBAL' };

  it('preserves instructions while dropping backend-only fields', () => {
    expect(parseAgentSkills([{ ...skill, internalPath: '/private/skills' }])).toEqual([skill]);
    expect(parseAgentSkillInput(skill)).toEqual({ name: skill.name, instructions: skill.instructions, enabled: true, scope: 'GLOBAL' });
    expect(parseAgentSkills([])).toEqual([]);
  });

  it.each(['GLOBAL', 'DEVELOPMENT', 'REVIEW'])('preserves the %s scope in both directions', scope => {
    expect(parseAgentSkillInput({ ...skill, scope }).scope).toBe(scope);
    expect(parseAgentSkills([{ ...skill, scope }])[0].scope).toBe(scope);
  });

  it.each([undefined, null, '', 'global', 'SYSTEM', 42])('rejects an invalid or missing scope %s in both directions', scope => {
    expect(() => parseAgentSkillInput({ ...skill, scope })).toThrow('agentSkill.scope is not an agent skill scope');
    expect(() => parseAgentSkills([{ ...skill, scope }])).toThrow('agentSkills[0].scope is not an agent skill scope');
  });

  it('rejects malformed instructions or toggle state before they reach the renderer or backend', () => {
    expect(() => parseAgentSkills({ skills: [skill] })).toThrow('agentSkills is not an array');
    expect(() => parseAgentSkills([{ ...skill, instructions: 42 }])).toThrow('agentSkills[0].instructions is not a string');
    expect(() => parseAgentSkillInput({ ...skill, enabled: 'false' })).toThrow('agentSkill.enabled is not a boolean');
  });
});

describe('agent contract parsing', () => {
  it('retains issue provenance without exposing additional backend fields', () => {
    const source = { host: 'github.com', issueId: 23, number: 7, repo: 'upstream/repo', url: 'https://github.com/upstream/repo/issues/7',
      initialTurnId: 'initial-turn', instructions: 'Keep retries unchanged', title: 'Fix upload', body: 'Reproduce the timeout', updatedAt: '2026-09-17T00:00:00Z' };
    expect(parseAgentTask({ ...fixture.task, issueSource: { ...source, internalPath: '/private' } }).issueSource).toEqual(source);
    expect(() => parseAgentTask({ ...fixture.task, issueSource: { ...source, issueId: '23' } })).toThrow(/issueId/);
    expect(parseAgentTask({ ...fixture.task, issueSource: { ...source, initialTurnId: undefined, instructions: undefined } }).issueSource)
      .toMatchObject({ initialTurnId: null, instructions: '' });
  });

  it('reads a task the backend actually returns', () => {
    const task = parseAgentTask(fixture.task);

    expect(task.id).toBe(fixture.task.id);
    expect(task.generatedBranch).toBe(fixture.task.generatedBranch);
    expect(task.state).toBe(fixture.task.state);
    expect(task.defaults.permissionPolicy).toBe('AUTO');
    expect(task.summary).toBeNull();
    expect(parseAgentTask({ ...fixture.task, generatedBranch: null }).generatedBranch).toBeNull();
    expect(() => parseAgentTask({ ...fixture.task, generatedBranch: 42 })).toThrow(/generatedBranch/);
    expect(task.pullRequest).toBeNull();
    const pullRequest = { number: 31105, title: 'Clean up nits', state: 'open', draft: true, htmlUrl: 'https://example.test/31105' };
    expect(parseAgentTask({ ...fixture.task, pullRequest }).pullRequest).toEqual({ number: 31105, title: 'Clean up nits', state: 'open', draft: true });
    expect(() => parseAgentTask({ ...fixture.task, pullRequest: { ...pullRequest, draft: 'yes' } })).toThrow(/pullRequest\.draft/);
  });

  it('reads what each task checkout holds by task id', () => {
    expect(parseAgentTaskChanges({ 't-1': { uncommitted: true, commits: 2 }, 't-2': { uncommitted: false, commits: 0 } }))
      .toEqual({ 't-1': { uncommitted: true, commits: 2 }, 't-2': { uncommitted: false, commits: 0 } });
    expect(() => parseAgentTaskChanges({ 't-1': { uncommitted: true } })).toThrow(/taskChanges\.t-1\.commits/);
    expect(() => parseAgentTaskChanges([])).toThrow();
  });

  it('reads what each task last said and waits on, dropping fields the contract does not name', () => {
    expect(parseAgentTaskActivity({
      't-1': {
        lastMessage: { author: 'AGENT', text: 'Done.', at: '2026-09-27T10:00:00Z', turnId: 'turn-1' },
        pendingApproval: {
          approvalId: 'a-1', taskId: 't-1', turnId: 'turn-1', occurredAt: '2026-09-27T10:01:00Z', title: 'Run command',
          detail: 'npm test', approvalKind: 'COMMAND', scope: ['command:npm test'], availableDecisions: ['DENY'], actionable: true,
        },
        latestTurn: { id: 'turn-1', state: 'RUNNING', endedAt: null },
      },
      't-2': { lastMessage: null, pendingApproval: null, latestTurn: null },
    })).toEqual({
      't-1': {
        lastMessage: { author: 'AGENT', text: 'Done.', at: '2026-09-27T10:00:00Z', turnId: 'turn-1' },
        pendingApproval: { approvalId: 'a-1', occurredAt: '2026-09-27T10:01:00Z', title: 'Run command', detail: 'npm test', approvalKind: 'COMMAND', scope: ['command:npm test'] },
        latestTurn: { id: 'turn-1', state: 'RUNNING', endedAt: null },
      },
      't-2': { lastMessage: null, pendingApproval: null, latestTurn: null },
    });
    expect(() => parseAgentTaskActivity({ 't-1': { lastMessage: { author: 'SYSTEM', text: '', at: '' } } })).toThrow(/lastMessage\.author/);
  });

  it('reads a completed task with its summary', () => {
    const task = parseAgentTask(fixture.finalTask);

    expect(task.summary?.finalResponse).toBe(fixture.finalTask.summary.finalResponse);
    expect(task.summary?.resultBranch).toBe(fixture.finalTask.summary.resultBranch);
    // Completion removed the clean worktree; the screen can say so without a path.
    expect(task.checkoutRemovedAt).toBe(fixture.finalTask.checkoutRemovedAt);
  });

  it('reads the worktree claim that decides whether the task is idle', () => {
    expect(parseAgentTask(fixture.task).mutationClaim).toBeNull();
    expect(parseAgentTask(fixture.task).codegraph).toBe(false);
    expect(parseAgentTask({ ...fixture.task, codegraph: true }).codegraph).toBe(true);

    const held = parseAgentTask({
      ...fixture.task,
      mutationClaim: { kind: 'TURN', ownerId: 'turn-9', acquiredAt: '2026-08-20T10:00:00Z' },
    });

    expect(held.mutationClaim).toEqual({ kind: 'TURN', ownerId: 'turn-9', acquiredAt: '2026-08-20T10:00:00Z' });
    // A claim with no owner is not a claim the renderer can reason about.
    expect(() => parseAgentTask({ ...fixture.task, mutationClaim: { kind: 'TURN' } })).toThrow(/ownerId/);
  });

  it('reads a turn and its settings snapshot', () => {
    const turn = parseAgentTurn(fixture.turn);

    expect(turn.id).toBe(fixture.turn.id);
    expect(turn.settings?.attachments).toEqual([]);
    // The guideline files that went with the turn's input, by path; a turn without any reads as an empty list.
    expect(parseAgentTurn({ ...fixture.turn, guidelines: undefined }).guidelines).toEqual([]);
    expect(parseAgentTurn({ ...fixture.turn, guidelines: ['CONTRIBUTING.md'] }).guidelines).toEqual(['CONTRIBUTING.md']);
    expect(() => parseAgentTurn({ ...fixture.turn, guidelines: [3] })).toThrow(/guidelines\[0\]/);

    // A turn that ran with an image names it the way the backend does: a reference, not a bare id.
    const reference = { id: 'attachment-1', name: 'red-square.png', mediaType: 'image/png' };
    const withImage = parseAgentTurn({ ...fixture.turn, settings: { ...fixture.turn.settings, attachments: [reference] } });
    expect(withImage.settings?.attachments).toEqual([reference]);
    expect(() => parseAgentTurn({ ...fixture.turn, settings: { ...fixture.turn.settings, attachments: ['attachment-1'] } }))
      .toThrow(/attachments\[0\]/);
  });

  it('retains the requested service tier on defaults and turn snapshots', () => {
    expect(parseAgentTask(fixture.task).defaults.serviceTier).toBeNull();
    expect(parseAgentTask({ ...fixture.task, defaults: { ...fixture.task.defaults, serviceTier: 'priority' } }).defaults.serviceTier)
      .toBe('priority');
    expect(parseAgentTurn({ ...fixture.turn, settings: { ...fixture.turn.settings, serviceTier: 'priority' } }).settings?.serviceTier)
      .toBe('priority');
    expect(() => parseAgentTurn({ ...fixture.turn, settings: { ...fixture.turn.settings, serviceTier: true } })).toThrow(/serviceTier/);
  });

  it('reads structured learning reports on refinement turns while preserving older report shapes', () => {
    const summary = { purpose: 'Preserve the previous statistics.',
      commits: [{ sha: 'a'.repeat(40), title: 'Keep a snapshot', summary: 'Read the old values before mutation.' }],
      combinedResult: 'Failed updates can restore the original state.', evidence: 'The rollback unit tests passed.',
      limitations: ['No live metastore was available.'] };
    const report: DevelopmentResultDto = { readyForDelivery: true, message: null, learningSummary: summary };
    const turn = parseAgentTurn({ ...fixture.turn, developmentOrigin: { kind: 'REFINE_SUMMARY', parentUserTurnId: 'implementation-1' },
      developmentResult: { ...report, internalReceiptPath: '/private/report.json',
        learningSummary: { ...summary, rawProviderPayload: 'private', commits: [{ ...summary.commits[0], worktreePath: '/private/worktree' }] } } });
    expect(turn.developmentOrigin).toEqual({ kind: 'REFINE_SUMMARY', parentUserTurnId: 'implementation-1' });
    expect(turn.developmentResult).toEqual(report);
    expect(parseDevelopmentResult({ readyForDelivery: false, message: 'Which option should I use?' }))
      .toEqual({ readyForDelivery: false, message: 'Which option should I use?' });
    expect(parseDevelopmentResult({ readyForDelivery: true })).toEqual({ readyForDelivery: true, message: null });
    expect(parseDevelopmentResult({ ...report, learningSummary: null }).learningSummary).toBeNull();
    expect(() => parseDevelopmentResult({ ...report, learningSummary: { ...summary, evidence: 42 } })).toThrow(/learningSummary.evidence/);
    expect(() => parseDevelopmentResult({ ...report, learningSummary: { ...summary, commits: [{ ...summary.commits[0], summary: null }] } }))
      .toThrow(/commit.summary/);
    expect(() => parseDevelopmentResult({ ...report, learningSummary: { ...summary, limitations: [false] } })).toThrow(/learningSummary.limitations\[0\]/);
  });

  it('keeps the typed authentication cause and rejects an invented failure kind', () => {
    expect(parseAgentTurn({ ...fixture.turn, failureKind: 'AUTHENTICATION_REQUIRED' }).failureKind)
      .toBe('AUTHENTICATION_REQUIRED');
    expect(() => parseAgentTurn({ ...fixture.turn, failureKind: 'LOGIN_FAILED' })).toThrow(/failureKind/);
  });

  it('reads a completion', () => {
    const completion = parseAgentCompletion(fixture.completion);

    expect(completion.taskId).toBe(fixture.completion.taskId);
    expect(completion.summary.notes).toBe(fixture.completion.summary.notes);
  });

  it('drops a field the contract does not name', () => {
    const leaky = {
      ...fixture.task,
      worktreePath: '/Users/someone/worktrees/task-1',
      providerPayload: { raw: 'codex-native' },
    };

    const task = parseAgentTask(leaky) as Record<string, unknown>;

    // A path or a provider detail added upstream must not reach the renderer just because the
    // backend started sending it.
    expect(task.worktreePath).toBeUndefined();
    expect(task.providerPayload).toBeUndefined();
    expect(JSON.stringify(task)).not.toContain('worktrees');
  });

  it('refuses a decision the renderer cannot represent', () => {
    expect(() => parseAgentApproval({
      approvalId: 'a-1',
      decision: 'ESCALATE',
      scopedPermission: null,
      resolvedAt: '2026-08-20T10:00:00Z',
      timedOut: false,
    })).toThrow(/decision/);
  });

  it('refuses a task that is missing a field the renderer needs', () => {
    const { state, ...withoutState } = fixture.task;

    expect(() => parseAgentTask(withoutState)).toThrow(/state/);
    expect(state).toBeDefined();
  });

  it('reads a list of tasks and names the offending entry', () => {
    expect(parseAgentTasks([fixture.task, fixture.finalTask])).toHaveLength(2);
    expect(() => parseAgentTasks([fixture.task, { id: 'broken' }])).toThrow(/tasks\[1\]/);
  });
});

describe('provider contracts', () => {
  it('requires the complete normalized capability object on status and model catalog rows', () => {
    const provider = {
      laneId: 'codex',
      readiness: 'READY',
      version: '0.152.1',
      diagnostic: 'ready',
      capabilities,
      signInCommand: null as string | null,
    };
    expect(parseAgentProviderStatus(provider).capabilities.attachmentMediaTypes).toEqual([
      'image/png', 'image/jpeg', 'image/gif',
    ]);
    expect(() => parseAgentProviderStatus({ ...provider, capabilities: { ...capabilities, steering: undefined } }))
      .toThrow(/steering/);

    const settings = parseModelSettings({
      agents: [
        { type: 'DEVELOPMENT', explicit: true, configId: 'codex', label: 'Codex', model: 'm', modelLabel: 'Model', effort: 'high' },
        { type: 'REVIEW', explicit: false, configId: 'codex', label: 'Codex', model: 'm', modelLabel: 'Model', effort: 'high' },
      ],
      providers: [{
        id: 'codex',
        label: 'Codex',
        readiness: 'READY',
        version: '0.152.1',
        diagnostic: 'ready',
        signInCommand: null,
        capabilities,
        models: [
          { id: 'm', name: 'Model', description: '', efforts: ['high'], defaultEffort: 'high', inputModalities: ['text', 'image'],
            serviceTiers: [{ id: 'priority', name: 'Fast', description: 'Faster responses' }] },
          { id: 'legacy', name: 'Legacy', description: '', efforts: ['high'], defaultEffort: 'high', inputModalities: null },
        ],
        selectedModel: 'm',
        selectedEffort: 'high',
        main: true,
      }],
      apiKeys: [],
      keyProviders: [],
    });
    expect(settings.providers[0].capabilities).toEqual(capabilities);
    expect(settings.providers[0].models[0].inputModalities).toEqual(['text', 'image']);
    expect(settings.providers[0].models[1].inputModalities).toBeUndefined();
    expect(settings.providers[0].models[0].serviceTiers).toEqual([{ id: 'priority', name: 'Fast', description: 'Faster responses' }]);
    expect(settings.providers[0].models[1].serviceTiers).toEqual([]);
  });

  it('validates and recovers the typed known-unsent preflight error from Electron error text', () => {
    const detail = {
      timestamp: '2026-09-04T10:00:00Z',
      status: 503,
      code: 'AGENT_PROVIDER_UNAVAILABLE',
      laneId: 'claude',
      readiness: 'AUTHENTICATION_REQUIRED',
      diagnostic: 'Claude login required',
      signInCommand: 'claude auth login',
      path: '/agent/tasks',
    };
    expect(parseAgentProviderUnavailable(detail).laneId).toBe('claude');
    const serialized = new Error(`Error invoking remote method: AGENT_PROVIDER_UNAVAILABLE:${JSON.stringify(detail)}`);
    expect(agentProviderUnavailableFrom(serialized)).toEqual(detail);
    expect(agentProviderUnavailableFrom(new Error('ordinary failure'))).toBeNull();
  });
});

describe('attachment contract', () => {
  it('accepts the facts the backend issues and rejects a reference without them', () => {
    const stored = { id: 'att-1', name: 'shot.png', mediaType: 'image/png', byteSize: 2048, width: 12, height: 7 };
    expect(parseAgentAttachment(stored)).toEqual(stored);
    expect(() => parseAgentAttachment({ ...stored, width: 0 })).toThrow('width');
    expect(() => parseAgentAttachment({ ...stored, byteSize: '2048' })).toThrow('byteSize');
    expect(() => parseAgentAttachment({ id: 'att-1' })).toThrow();
  });
});

it('reads durable plan snapshots and distinguishes a clear from no plan', () => {
  const plan = [{ text: 'Inspect planner', state: 'COMPLETED' }, { text: 'Verify fix', state: 'IN_PROGRESS' }];
  expect(parseAgentTurn({ ...fixture.turn, plan }).plan).toEqual(plan);
  expect(parseAgentTurn({ ...fixture.turn, plan: [] }).plan).toEqual([]);
  expect(parseAgentTurn({ ...fixture.turn, plan: null }).plan).toBeNull();
  expect(parseAgentTurn({ ...fixture.turn, plan: undefined }).plan).toBeNull();
  expect(() => parseAgentTurn({ ...fixture.turn, plan: [{ text: 'Fix', state: 'UNKNOWN' }] })).toThrow(/plan\[0\]\.state/);
});

describe('queue and steer contract parsing', () => {
  const queued: Record<string, unknown> = {
    id: 'q-1',
    taskId: fixture.task.id,
    position: 1,
    state: 'PAUSED',
    preview: 'Then update the design doc',
    settings: { model: 'gpt-5.6-codex', effort: 'high', permissionPolicy: 'AUTO', maximumRuntimeMillis: 60000, attachments: [] },
    turnId: null,
    pauseReason: 'Backend restarted',
    createdAt: '2026-08-20T10:00:02Z',
    updatedAt: '2026-08-20T10:00:03Z',
  };

  it('reads a queued input with the settings it will run with', () => {
    const parsed = parseAgentQueuedInput(queued);
    expect(parsed.state).toBe('PAUSED');
    expect(parsed.pauseReason).toBe('Backend restarted');
    expect(parsed.settings.model).toBe('gpt-5.6-codex');
    expect(parseAgentQueue([queued, { ...queued, id: 'q-2', position: 2 }]).map((input) => input.id)).toEqual(['q-1', 'q-2']);
    expect(() => parseAgentQueuedInput({ ...queued, preview: 7 })).toThrow(/preview/);
    expect(() => parseAgentQueue([queued, { ...queued, settings: null }])).toThrow(/queue\[1\]\.settings/);
  });

  it('reads a steer delivery and its failure', () => {
    const delivery = parseAgentInputDelivery({
      id: 'd-1',
      taskId: fixture.task.id,
      turnId: fixture.turn.id,
      state: 'UNKNOWN',
      failure: 'Timed out waiting for Codex turn/steer',
      createdAt: '2026-08-20T10:00:02Z',
      updatedAt: '2026-08-20T10:00:33Z',
    });
    expect(delivery.state).toBe('UNKNOWN');
    expect(delivery.failure).toContain('Timed out');
    expect(() => parseAgentInputDelivery({ id: 'd-1' })).toThrow(/taskId/);
  });
});
