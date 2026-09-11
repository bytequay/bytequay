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
    expect(sender.pushed.filter(message => (message as { kind: string }).kind === 'changed')).toHaveLength(1);
    expect(sender.pushed.some(message => (message as { kind: string }).kind === 'events')).toBe(false);
    expect(JSON.stringify(sender.pushed)).not.toContain('Review the authentication change');
  });

  it('stamps every push with its subscription and task', () => {
    const { subscriptions } = registry();
    const sender = new FakeSender();

    const subscriptionId = subscriptions.start(sender, 'task-1', 0);

    expect(sender.pushed).toEqual([{ subscriptionId, taskId: 'task-1', kind: 'state', state: 'connecting' }]);
  });

  it('replaces the reader when a renderer subscribes to the same task again', () => {
    const { subscriptions, signals } = registry();
    const sender = new FakeSender();

    subscriptions.start(sender, 'task-1', 0);
    subscriptions.start(sender, 'task-1', 5);

    expect(signals.map((signal) => signal.aborted)).toEqual([true, false]);
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
