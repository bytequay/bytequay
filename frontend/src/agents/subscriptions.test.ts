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

import { EventEmitter } from 'node:events';
import { describe, expect, it, vi } from 'vitest';
import fixture from './fixtures/agent-events-v2.json';
import { AgentSubscriptions } from './subscriptions';

/** Stands in for a WebContents: an emitter that records what main pushed to it. */
class FakeSender extends EventEmitter {
  readonly pushed: unknown[] = [];

  isDestroyed(): boolean {
    return false;
  }

  send(_channel: string, message: unknown): void {
    this.pushed.push(message);
  }
}

/** A backend whose stream stays open until the reader gives up on it, as a live task's does. */
function registry(): { subscriptions: AgentSubscriptions; signals: AbortSignal[] } {
  const signals: AbortSignal[] = [];
  const subscriptions = new AgentSubscriptions((_taskId, _after, signal) => {
    signals.push(signal);
    return new Promise<Response>((_resolve, reject) => {
      signal.addEventListener('abort', () => reject(signal.reason), { once: true });
    });
  });
  return { subscriptions, signals };
}

describe('AgentSubscriptions', () => {
  it('forwards bounded development progress between ordered history watermarks', async () => {
    const progress = { ...fixture.events[1], eventId: 'progress', sequence: 3, turnId: null as string | null,
      payload: { phase: 'DEVELOPMENT_AUTOMATION_UPDATED', message: `Waiting for PR checks: ${'x'.repeat(1000)}` } };
    const afterProgress = { ...fixture.events[2], eventId: 'later-output', sequence: 4 };
    const frames = [...fixture.events.slice(0, 2), progress, afterProgress].map(event =>
      `id: ${event.sequence}\nevent: ${event.type}\ndata: ${JSON.stringify(event)}\n\n`).join('');
    const subscriptions = new AgentSubscriptions(async () => new Response(frames));
    const sender = new FakeSender();
    const subscriptionId = subscriptions.start(sender, fixture.events[0].taskId, 0, true);
    await vi.waitFor(() => expect(sender.pushed).toContainEqual({ subscriptionId, taskId: progress.taskId,
      kind: 'changed', sequence: 4, turnId: afterProgress.turnId }));
    expect(sender.pushed.filter(message => (message as { kind: string }).kind !== 'state')).toEqual([
      { subscriptionId, taskId: progress.taskId, kind: 'changed', sequence: 1, turnId: fixture.events[0].turnId },
      { subscriptionId, taskId: progress.taskId, kind: 'changed', sequence: 2, turnId: fixture.events[1].turnId },
      { subscriptionId, taskId: progress.taskId, kind: 'events', events: [{ ...progress, payload: { ...progress.payload, message: progress.payload.message.slice(0, 500) } }] },
      { subscriptionId, taskId: progress.taskId, kind: 'changed', sequence: 4, turnId: afterProgress.turnId },
    ]);
    expect(JSON.stringify(sender.pushed)).not.toContain('Review the authentication change');
  });

  it('coalesces live history into a watermark without forwarding transcript payloads', async () => {
    const closing = {
      ...fixture.events[1],
      eventId: 'closing-event',
      sequence: fixture.events.length + 1,
      turnId: null as string | null,
      payload: { phase: 'TASK_COMPLETED', message: 'Task completed' },
    };
    const frames = [...fixture.events, closing].map(event =>
      `id: ${event.sequence}\nevent: ${event.type}\ndata: ${JSON.stringify(event)}\n\n`).join('');
    const subscriptions = new AgentSubscriptions(async () => new Response(frames));
    const sender = new FakeSender();
    const subscriptionId = subscriptions.start(sender, fixture.events[0].taskId, 0, true);

    await vi.waitFor(() => expect(sender.pushed).toContainEqual({
      subscriptionId,
      taskId: fixture.events[0].taskId,
      kind: 'changed',
      sequence: closing.sequence,
      turnId: fixture.events.at(-1)?.turnId,
    }));
    expect(sender.pushed.filter(message => (message as { kind: string }).kind === 'changed')).toHaveLength(2);
    expect(sender.pushed.some(message => (message as { kind: string }).kind === 'events')).toBe(false);
    expect(JSON.stringify(sender.pushed)).not.toContain('Review the authentication change');
  });

  it('pushes the first live update immediately and coalesces the rest of the burst', async () => {
    vi.useFakeTimers();
    const stream = new TransformStream<Uint8Array, Uint8Array>();
    const writer = stream.writable.getWriter();
    const subscriptions = new AgentSubscriptions(async () => new Response(stream.readable));
    const sender = new FakeSender();
    const subscriptionId = subscriptions.start(sender, fixture.events[0].taskId, 0, true);
    const write = async (index: number): Promise<void> => {
      const event = fixture.events[index];
      await writer.write(new TextEncoder().encode(`id: ${event.sequence}\ndata: ${JSON.stringify(event)}\n\n`));
      await vi.advanceTimersByTimeAsync(0);
    };
    const updates = () => sender.pushed.filter(message => (message as { kind: string }).kind === 'changed');
    try {
      await write(0);
      expect(updates()).toEqual([{ subscriptionId, taskId: fixture.events[0].taskId,
        kind: 'changed', sequence: 1, turnId: fixture.events[0].turnId }]);
      await write(1);
      await write(2);
      expect(updates()).toHaveLength(1);
      await vi.advanceTimersByTimeAsync(100);
      expect(updates()).toHaveLength(2);
      expect(updates().at(-1)).toMatchObject({ sequence: 3 });
    }
    finally {
      subscriptions.stop(subscriptionId);
      await writer.close();
      vi.useRealTimers();
    }
  });

  it('stamps every push with its subscription and task', () => {
    const { subscriptions } = registry();
    const sender = new FakeSender();

    const subscriptionId = subscriptions.start(sender, 'task-1', 0);

    expect(sender.pushed).toEqual([{ subscriptionId, taskId: 'task-1', kind: 'state', state: 'connecting' }]);
  });

  it('keeps the workspace task live after its pinned overlay closes', async () => {
    const signals: AbortSignal[] = [];
    const streams: ReadableStreamDefaultController<Uint8Array>[] = [];
    const subscriptions = new AgentSubscriptions(async (_taskId, _after, signal) => {
      signals.push(signal);
      return new Response(new ReadableStream<Uint8Array>({ start(controller) {
        streams.push(controller);
        signal.addEventListener('abort', () => controller.error(signal.reason), { once: true });
      } }));
    });
    const sender = new FakeSender();
    const taskId = fixture.events[0].taskId;
    const workspace = subscriptions.start(sender, taskId, 0, true);
    const overlay = subscriptions.start(sender, taskId, 0, true);
    const frame = (index: number) => new TextEncoder().encode(`data: ${JSON.stringify(fixture.events[index])}\n\n`);

    try {
      expect(workspace).not.toBe(overlay);
      expect(signals.map(signal => signal.aborted)).toEqual([false, false]);
      streams.forEach(stream => stream.enqueue(frame(0)));
      for (const subscriptionId of [workspace, overlay]) {
        await vi.waitFor(() => expect(sender.pushed).toContainEqual(expect.objectContaining({ subscriptionId, kind: 'changed', sequence: 1 })));
      }
      subscriptions.stop(overlay);
      expect(signals.map(signal => signal.aborted)).toEqual([false, true]);
      streams[0].enqueue(frame(1));
      await vi.waitFor(() => expect(sender.pushed).toContainEqual(expect.objectContaining({ subscriptionId: workspace, kind: 'changed', sequence: 2 })));
      expect(sender.pushed).not.toContainEqual(expect.objectContaining({ subscriptionId: overlay, kind: 'changed', sequence: 2 }));
    }
    finally { subscriptions.stopAll(); }
  });

  it('stops every reader a renderer opened when its page navigates away, and no others', () => {
    const { subscriptions, signals } = registry();
    const sender = new FakeSender();
    const other = new FakeSender();
    subscriptions.start(sender, 'task-1', 0);
    subscriptions.start(sender, 'task-2', 0);
    subscriptions.start(other, 'task-1', 0);

    // A reload never runs the unsubscribe the old document registered.
    sender.emit('did-navigate');

    expect(signals.map((signal) => signal.aborted)).toEqual([true, true, false]);
  });

  it('stops every reader a renderer opened when its window closes', () => {
    const { subscriptions, signals } = registry();
    const sender = new FakeSender();
    subscriptions.start(sender, 'task-1', 0);
    subscriptions.start(sender, 'task-2', 0);

    sender.emit('destroyed');

    expect(signals.map((signal) => signal.aborted)).toEqual([true, true]);
  });

  it('listens to a renderer once, however many readers it opens', () => {
    const { subscriptions } = registry();
    const sender = new FakeSender();

    for (let task = 0; task < 12; task++) {
      subscriptions.start(sender, `task-${task}`, 0);
    }

    // One listener per reader that was never removed tripped Node's leak warning after ten.
    expect(sender.listenerCount('destroyed')).toBe(1);
    expect(sender.listenerCount('did-navigate')).toBe(1);
  });
});
