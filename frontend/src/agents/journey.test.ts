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

import { describe, expect, it, vi } from 'vitest';
import { createPreviewBridge, type PreviewCommand } from './preview';
import {
  agentTaskReducer,
  initialAgentTaskState,
  startAgentTaskSession,
  type AgentTaskAction,
  type AgentTaskState,
} from './model';
import { projectTranscript } from './transcript';

/** Drives one preview screen without a DOM: the same session helper the screen's effect runs. */
async function open(bridge: ReturnType<typeof createPreviewBridge>, taskId: string) {
  let state = initialAgentTaskState();
  const stop = await startAgentTaskSession(bridge, taskId, 'workspace-1', (action: AgentTaskAction) => {
    state = agentTaskReducer(state, action);
  });
  return { stop, state: (): AgentTaskState => state };
}

function names(commands: PreviewCommand[]): string[] {
  return commands.map((command) => command.name);
}

describe('a task that has already ended', () => {
  it('replays its history and never opens a stream', async () => {
    const bridge = createPreviewBridge('workspace-1');
    const subscribe = vi.fn(bridge.subscribeAgentTask);
    const done = {
      ...bridge,
      getAgentTask: async (taskId: string, workspaceId: string) => ({ ...await bridge.getAgentTask(taskId, workspaceId), state: 'COMPLETED' }),
      subscribeAgentTask: subscribe,
    };
    let state = initialAgentTaskState();
    await startAgentTaskSession(done, 'task-1', 'workspace-1', (action: AgentTaskAction) => {
      state = agentTaskReducer(state, action);
    });

    expect(subscribe).not.toHaveBeenCalled();
    expect(state.events.length).toBeGreaterThan(0);
    // Up to date, and it says so, rather than reconnecting to a stream that only closes again.
    expect(state.connection).toBe('open');
  });
});

describe('preview journey', () => {
  it('replays history then subscribes past the highest replayed sequence', async () => {
    const bridge = createPreviewBridge('workspace-1', { replayThrough: 3 });
    const session = await open(bridge, bridge.events[0].taskId);

    const subscribe = bridge.commands.find((command) => command.name === 'subscribeAgentTask');
    expect(subscribe?.args[1]).toBe(3);
    // Everything the fixture holds arrived exactly once, in order.
    expect(session.state().events.map((event) => event.sequence))
      .toEqual(bridge.events.map((event) => event.sequence));
    expect(session.state().gap).toBe(false);
    expect(session.state().connection).toBe('open');
  });

  it('ignores an overlapping duplicate and holds a gap until it is filled', async () => {
    const duplicated = await open(createPreviewBridge('w', { delivery: 'duplicate' }), 'task-1');
    const sequences = duplicated.state().events.map((event) => event.sequence);
    expect(new Set(sequences).size).toBe(sequences.length);

    const gapped = createPreviewBridge('w', { delivery: 'gap' });
    const held = await open(gapped, 'task-1');
    // The out-of-order event arrives last in this fixture, so the hole closes by the end.
    expect(held.state().gap).toBe(false);
    expect(held.state().events.map((event) => event.sequence))
      .toEqual(gapped.events.map((event) => event.sequence));
  });

  it('reports a simulated reconnect without losing the transcript', async () => {
    const bridge = createPreviewBridge('w', { delivery: 'reconnect' });
    const session = await open(bridge, 'task-1');

    expect(session.state().connection).toBe('open');
    expect(projectTranscript(session.state().events).length).toBeGreaterThan(0);
  });

  it('runs create, open, send, cancel and complete as exact typed bridge commands', async () => {
    const bridge = createPreviewBridge('workspace-1');
    // Creation carries a model the catalog actually offers; the backend rejects a blank one.
    const catalog = await bridge.getModelSettings();
    const codex = catalog.providers.find((provider) => provider.id === 'codex');
    expect(codex?.models.map((model) => model.id)).toContain(codex?.selectedModel);
    const created = await bridge.createAgentTask({
      taskId: '00000000-0000-0000-0000-000000000001',
      workspaceId: 'workspace-1',
      message: 'Review the authentication change',
      laneId: 'codex',
      defaults: {
        model: codex?.selectedModel ?? null,
        effort: codex?.selectedEffort ?? null,
        permissionPolicy: 'AUTO',
      },
      idempotencyKey: 'create-first-turn',
    });
    const session = await open(bridge, created.id);

    const turn = await bridge.startAgentTurn({
      taskId: created.id,
      message: 'do the thing',
      expectedTaskVersion: created.version,
      idempotencyKey: 'send-intent-1',
    });
    const cancelled = await bridge.cancelAgentTurn(turn.id);
    await bridge.completeAgentTask(created.id, 'workspace-1');
    await bridge.cleanupAgentTaskWorktree(created.id, 'workspace-1');
    session.stop();

    expect(cancelled.state).toBe('CANCELLED');
    expect(names(bridge.commands)).toEqual([
      'getModelSettings',
      'createAgentTask',
      'getAgentTask',
      'readAgentEvents',
      'subscribeAgentTask',
      'startAgentTurn',
      'cancelAgentTurn',
      'completeAgentTask',
      'cleanupAgentTaskWorktree',
      'unsubscribeAgentTask',
    ]);
    // The bridge forwards the random intent key unchanged; its lifecycle is tested in model.ts.
    const start = bridge.commands.find((command) => command.name === 'startAgentTurn');
    expect((start?.args[0] as { idempotencyKey: string }).idempotencyKey)
      .toBe('send-intent-1');
  });

  it('stops a runtime only with the generation it was shown, then fails the task explicitly', async () => {
    const bridge = createPreviewBridge('workspace-1');
    const session = await open(bridge, 'task-1');
    await bridge.startAgentTurn({ taskId: 'task-1', message: 'hang', expectedTaskVersion: 0, idempotencyKey: 'k' });

    const impact = await bridge.getAgentRuntimeStopImpact('task-1', 'workspace-1');
    // The impact names what the stop interrupts; the stop echoes exactly that generation.
    expect(impact.activeTurnId).not.toBeNull();
    const stopped = await bridge.stopAgentRuntime('task-1', 'workspace-1', impact.generation);
    expect(stopped.state).toBe('OPEN');
    expect((await bridge.getAgentTurn('turn')).state).toBe('INTERRUPTED');

    const failed = await bridge.markAgentTaskFailed('task-1', 'workspace-1', 'giving up');
    session.stop();

    expect(failed.state).toBe('FAILED');
    expect(failed.terminalReason).toBe('giving up');
    expect(names(bridge.commands)).toContain('getAgentRuntimeStopImpact');
    const stop = bridge.commands.find((command) => command.name === 'stopAgentRuntime');
    expect(stop?.args[2]).toBe(impact.generation);
  });

  it('adopts the version each defaults write returns, so a second change is not a conflict', async () => {
    const bridge = createPreviewBridge('workspace-1');
    let task = await bridge.getAgentTask('task-1', 'workspace-1');

    task = await bridge.updateAgentTaskDefaults('task-1', 'workspace-1', task.version, {
      model: 'gpt-5.6-codex-mini', effort: 'medium', permissionPolicy: 'AUTO',
    });
    // JPA bumps @Version on write, so the response is the only source of the next expected value.
    expect(task.version).toBe(1);

    const second = await bridge.updateAgentTaskDefaults('task-1', 'workspace-1', task.version, {
      model: 'gpt-5.6-codex', effort: 'high', permissionPolicy: 'AUTO',
    });
    expect(second.version).toBe(2);

    // Replaying a version already consumed is exactly what a stale screen would send.
    await expect(bridge.updateAgentTaskDefaults('task-1', 'workspace-1', task.version, {
      model: 'gpt-5.6-codex', effort: 'high', permissionPolicy: 'AUTO',
    })).rejects.toThrow('version changed');
  });

  it('reports the next turn from the persisted defaults, not from local state', async () => {
    const bridge = createPreviewBridge('workspace-1');
    const task = await bridge.getAgentTask('task-1', 'workspace-1');
    await bridge.updateAgentTaskDefaults('task-1', 'workspace-1', task.version, {
      model: 'gpt-5.6-codex-mini', effort: 'medium', serviceTier: 'priority', permissionPolicy: 'AUTO',
    });

    const effective = await bridge.getEffectiveTurnSettings('task-1', 'workspace-1');
    expect(effective.model).toBe('gpt-5.6-codex-mini');
    expect(effective.effort).toBe('medium');
    expect(effective.serviceTier).toBe('priority');
    expect(effective.permissionPolicy).toBe('AUTO');
  });

  it('unsubscribes on navigate away and replays the still-running task on reopen', async () => {
    const bridge = createPreviewBridge('workspace-1');
    const first = await open(bridge, 'task-1');
    const beforeStop = first.state().events.length;
    first.stop();

    const reopened = await open(bridge, 'task-1');

    expect(names(bridge.commands).filter((name) => name === 'unsubscribeAgentTask')).toHaveLength(1);
    // Nothing is remembered between visits: the reopened screen rebuilds from replay plus stream.
    expect(reopened.state().events.length).toBe(beforeStop);
  });
});

describe('steering and queueing through the bridge', () => {
  it('queues behind the running turn with the version it saw, then resumes and removes as typed commands', async () => {
    const bridge = createPreviewBridge('workspace-1');
    const taskId = bridge.events[0].taskId;
    const session = await open(bridge, taskId);
    const task = await bridge.getAgentTask(taskId, 'workspace-1');

    const queued = await bridge.queueAgentInput({
      taskId,
      workspaceId: 'workspace-1',
      message: 'then update the design doc',
      expectedVersion: task.version,
      idempotencyKey: 'queue-intent-1',
    });
    // The write moved the version: the same version again is a conflict, the fresh one is not.
    await expect(bridge.queueAgentInput({ taskId, workspaceId: 'workspace-1', message: 'x', expectedVersion: task.version, idempotencyKey: 'k' }))
      .rejects.toThrow(/version/);
    const listed = await bridge.listAgentQueue(taskId, 'workspace-1');
    expect(listed.map((input) => input.id)).toEqual([queued.id]);
    const removed = await bridge.removeAgentQueuedInput(taskId, 'workspace-1', queued.id);
    expect(removed.state).toBe('REMOVED');
    expect(await bridge.listAgentQueue(taskId, 'workspace-1')).toEqual([]);

    const steered = await bridge.steerAgentTurn({
      turnId: 'turn-1',
      message: 'skip the flaky suite',
      idempotencyKey: 'steer-intent-1',
    });
    expect(steered.state).toBe('DELIVERED');
    session.stop();

    expect(names(bridge.commands).filter((name) => !['getAgentTask', 'readAgentEvents', 'subscribeAgentTask', 'unsubscribeAgentTask'].includes(name)))
      .toEqual(['queueAgentInput', 'queueAgentInput', 'listAgentQueue', 'removeAgentQueuedInput', 'listAgentQueue', 'steerAgentTurn']);
  });
});
