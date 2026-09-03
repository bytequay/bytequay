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
import fixture from './fixtures/agent-contracts-v1.json';
import {
  parseAgentApproval,
  parseAgentCompletion,
  parseAgentTask,
  parseAgentTasks,
  parseAgentTurn,
} from './contracts';

describe('agent contract parsing', () => {
  it('reads a task the backend actually returns', () => {
    const task = parseAgentTask(fixture.task);

    expect(task.id).toBe(fixture.task.id);
    expect(task.state).toBe(fixture.task.state);
    expect(task.defaults.permissionPolicy).toBe('AUTO');
    expect(task.summary).toBeNull();
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
    expect(turn.settings?.sandboxPolicy).toBe('WORKSPACE_WRITE');
    expect(turn.settings?.attachments).toEqual([]);
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
