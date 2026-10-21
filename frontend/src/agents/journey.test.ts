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
import { createPreviewBridge, type PreviewCommand } from './fixtures/previewBridge';
import {
  agentTaskReducer,
  initialAgentTaskState,
  pollAgentTask,
  startAgentConversationSession,
  startAgentTaskSession,
  type AgentTaskAction,
  type AgentTaskState,
} from './model';
import { projectTranscript } from './transcript';
import type { AgentHistoryPageDto, AgentStreamMessage } from '../types';

/** Drives one preview screen without a DOM: the same session helper the screen's effect runs. */
async function open(bridge: ReturnType<typeof createPreviewBridge>, taskId: string) {
  let state = initialAgentTaskState();
  const stop = await startAgentTaskSession(bridge, taskId, 'workspace-1', (action: AgentTaskAction) => {
    state = agentTaskReducer(state, action);
  });
  await vi.waitFor(() => expect(state.events.at(-1)?.sequence ?? 0).toBe(state.lastSequence));
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

describe('a task window', () => {
  it('replaces one recent page on every poll instead of accumulating history', async () => {
    const bridge = createPreviewBridge('workspace-1', { replayThrough: 14 });
    let state = initialAgentTaskState();
    const dispatch = (action: AgentTaskAction): void => { state = agentTaskReducer(state, action); };
    await pollAgentTask(bridge, 'task-1', 'workspace-1', dispatch);
    expect(state.events).toHaveLength(14);
    expect(state.historyPages).toHaveLength(1);
    expect(names(bridge.commands)).toEqual(['getAgentTask', 'readAgentHistory']);
    bridge.commands.length = 0;
    await pollAgentTask(bridge, 'task-1', 'workspace-1', dispatch);
    expect(names(bridge.commands)).toEqual(['getAgentTask', 'readAgentHistory']);
    expect(state.events).toHaveLength(14);
    expect(state.historyPages).toHaveLength(1);
  });
});

describe('a workflow-owned conversation', () => {
  it.each([false, true])('retries a failed final history read without another event (reconnected: %s)', async (reconnected) => {
    vi.useFakeTimers();
    let stop = (): void => {};
    try {
      const preview = createPreviewBridge('workspace-1');
      const initial: AgentHistoryPageDto = { events: [], beforeCursor: null, afterCursor: null, latestSequence: 0 };
      const latest = { ...initial, events: preview.events, latestSequence: preview.events.at(-1)?.sequence ?? 0 };
      const readAgentHistory = vi.fn().mockResolvedValueOnce(initial).mockRejectedValueOnce(new Error('Temporary history failure')).mockResolvedValue(latest);
      let notify: (message: AgentStreamMessage) => void = () => { throw new Error('subscription not open'); };
      const subscribeAgentTask = async (_taskId: string, _after: number, listener: typeof notify) => {
        notify = listener;
        return (): void => {};
      };
      let state = initialAgentTaskState();
      stop = await startAgentConversationSession({ readAgentHistory, subscribeAgentTask }, 'task-1', action => { state = agentTaskReducer(state, action); });
      const connection = (status: 'open' | 'reconnecting') => notify({ kind: 'state', subscriptionId: 'review-stream', taskId: 'task-1', state: status });
      connection('open');
      notify({ kind: 'changed', subscriptionId: 'review-stream', taskId: 'task-1', sequence: latest.latestSequence, turnId: 'turn-1' });
      await vi.advanceTimersByTimeAsync(200);
      expect(state.notice).toBe('Temporary history failure');
      expect(readAgentHistory).toHaveBeenCalledTimes(2);

      if (reconnected) { connection('reconnecting'); connection('open'); }
      await vi.advanceTimersByTimeAsync(reconnected ? 200 : 1000);

      expect(state.events).toEqual(preview.events);
      expect(readAgentHistory).toHaveBeenCalledTimes(3);
      await vi.advanceTimersByTimeAsync(60000);
      expect(readAgentHistory).toHaveBeenCalledTimes(3);
    }
    finally { stop(); vi.useRealTimers(); }
  });

  it('backs off history failures and cancels retries when the conversation closes', async () => {
    vi.useFakeTimers();
    let stop = (): void => {};
    try {
      const readAgentHistory = vi.fn().mockResolvedValueOnce({ events: [], beforeCursor: null, afterCursor: null, latestSequence: 0 })
        .mockRejectedValue(new Error('Offline'));
      let notify: (message: AgentStreamMessage) => void = () => { throw new Error('subscription not open'); };
      const unsubscribe = vi.fn();
      stop = await startAgentConversationSession({ readAgentHistory, subscribeAgentTask: async (_taskId, _after, listener) => {
        notify = listener;
        return unsubscribe;
      } }, 'task-1', () => {});
      notify({ kind: 'changed', subscriptionId: 'review-stream', taskId: 'task-1', sequence: 1, turnId: 'turn-1' });
      await vi.advanceTimersByTimeAsync(0);
      expect(readAgentHistory).toHaveBeenCalledTimes(2);
      for (const [delay, count] of [[1000, 3], [2000, 4], [4000, 5], [5000, 6]]) {
        await vi.advanceTimersByTimeAsync(delay - 1);
        expect(readAgentHistory).toHaveBeenCalledTimes(count - 1);
        await vi.advanceTimersByTimeAsync(1);
        expect(readAgentHistory).toHaveBeenCalledTimes(count);
      }
      stop();
      await vi.advanceTimersByTimeAsync(60000);
      expect(readAgentHistory).toHaveBeenCalledTimes(6);
      expect(unsubscribe).toHaveBeenCalledOnce();
    }
    finally { vi.useRealTimers(); }
  });

  it('uses the same bounded history and status stream without reading an interactive task', async () => {
    vi.useFakeTimers();
    let stop = (): void => {};
    try {
      const preview = createPreviewBridge('workspace-1');
      const initial: AgentHistoryPageDto = { events: [], beforeCursor: null, afterCursor: null, latestSequence: 0 };
      const latest = { ...initial, events: preview.events, latestSequence: preview.events.at(-1)?.sequence ?? 0 };
      const readAgentHistory = vi.fn().mockResolvedValueOnce(initial).mockResolvedValue(latest);
      let notify: (message: AgentStreamMessage) => void = () => { throw new Error('subscription not open'); };
      const subscribeAgentTask = vi.fn(async (_taskId: string, _after: number, listener: typeof notify, statusOnly?: boolean) => {
        expect(statusOnly).toBe(true);
        notify = listener;
        return (): void => {};
      });
      let state = initialAgentTaskState();
      const changes: AgentTaskAction[] = [];
      stop = await startAgentConversationSession({ readAgentHistory, subscribeAgentTask }, 'task-1', action => {
        state = agentTaskReducer(state, action);
        if (action.type === 'live/changed') changes.push(action);
      });
      notify({ kind: 'changed', subscriptionId: 'review-stream', taskId: 'task-1', sequence: latest.latestSequence, turnId: 'turn-1' });
      expect(changes).toEqual([{ type: 'live/changed', sequence: latest.latestSequence, turnId: 'turn-1' }]);
      expect(readAgentHistory).toHaveBeenCalledTimes(2);
      await vi.advanceTimersByTimeAsync(200);
      expect(state.events).toEqual(preview.events);
      expect(state.historyPages).toHaveLength(1);
      expect(readAgentHistory).toHaveBeenCalledTimes(2);
    }
    finally {
      stop();
      vi.useRealTimers();
    }
  });
});

describe('preview journey', () => {
  it.each([false, true])('loads the final answer without another notification (page read in flight: %s)', async (inFlight) => {
    vi.useFakeTimers();
    let stop = (): void => {};
    try {
      const preview = createPreviewBridge('workspace-1');
      const answer = preview.events.find((event) => event.type === 'assistant_message' && event.payload.phase === 'FINAL');
      if (answer?.type !== 'assistant_message') throw new Error('missing final answer fixture');
      const page = (through: number): AgentHistoryPageDto => ({
        events: preview.events.filter((event) => event.sequence <= through),
        beforeCursor: null,
        afterCursor: null,
        latestSequence: through,
      });
      const completed = page(preview.events.at(-1)?.sequence ?? 0);
      const pendingPage = Promise.withResolvers<AgentHistoryPageDto>();
      const readAgentHistory = vi.fn().mockResolvedValueOnce(page(2));
      if (inFlight) readAgentHistory.mockReturnValueOnce(pendingPage.promise);
      readAgentHistory.mockResolvedValue(completed);
      let notify: (message: AgentStreamMessage) => void = () => { throw new Error('subscription not open'); };
      const bridge = {
        ...preview,
        readAgentHistory,
        subscribeAgentTask: async (_taskId: string, _after: number, listener: typeof notify) => {
          notify = listener;
          return (): void => {};
        },
      };
      let state = initialAgentTaskState();
      stop = await startAgentTaskSession(bridge, answer.taskId, 'workspace-1', (action) => {
        state = agentTaskReducer(state, action);
      });
      const changed = (sequence: number): void => notify({
        kind: 'changed', subscriptionId: 'session', taskId: answer.taskId, sequence, turnId: answer.turnId,
      });
      if (inFlight) {
        changed(answer.sequence - 1);
        await vi.advanceTimersByTimeAsync(200);
        expect(readAgentHistory).toHaveBeenCalledTimes(2);
      }
      changed(completed.latestSequence);
      if (inFlight) pendingPage.resolve(page(answer.sequence - 1));
      await vi.advanceTimersByTimeAsync(400);

      expect(state.events.at(-1)?.sequence).toBe(completed.latestSequence);
      expect(projectTranscript(state.events)[0].answer?.text).toBe(answer.payload.text);
      expect(state.events.filter((event) => event.eventId === answer.eventId)).toHaveLength(1);
      expect(readAgentHistory).toHaveBeenCalledTimes(inFlight ? 3 : 2);
    }
    finally {
      stop();
      vi.useRealTimers();
    }
  });

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
