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
import { describe, expect, it } from 'vitest';
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
