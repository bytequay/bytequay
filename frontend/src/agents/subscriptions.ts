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

import { randomUUID } from 'node:crypto';
import type { AgentStreamPayload } from '../types';
import { readAgentStream } from './sse';

/** What a stream needs from the WebContents it feeds, so a test can stand in for one. */
export interface StreamSender {
  isDestroyed(): boolean;
  send(channel: string, message: unknown): void;
  on(event: 'did-navigate', listener: () => void): unknown;
  once(event: 'destroyed', listener: () => void): unknown;
}

interface Subscription {
  controller: AbortController;
  sender: StreamSender;
  taskId: string;
}

/**
 * Streams tasks to renderers, one live reader per renderer and task, until each is stopped.
 *
 * Every push names its subscription and task, so a renderer that has moved on can drop events
 * still arriving for the screen it left rather than rendering them into the new one.
 */
export class AgentSubscriptions {
  private readonly subscriptions = new Map<string, Subscription>();
  private readonly watched = new WeakSet<StreamSender>();

  constructor(
    /** Opens a task's stream from an exclusive sequence cursor. */
    private readonly request: (taskId: string, after: number, signal: AbortSignal) => Promise<Response>,
  ) {}

  start(sender: StreamSender, taskId: string, after: number): string {
    // Re-subscribing replaces: two readers on one task would push the same events twice into the
    // same screen and both would hold a connection open.
    for (const [id, existing] of this.subscriptions) {
      if (existing.sender === sender && existing.taskId === taskId) {
        this.stop(id);
      }
    }
    this.watch(sender);
    const subscriptionId = randomUUID();
    const controller = new AbortController();
    this.subscriptions.set(subscriptionId, { controller, sender, taskId });

    const push = (message: AgentStreamPayload): void => {
      if (sender.isDestroyed() || !this.subscriptions.has(subscriptionId)) {
        return;
      }
      sender.send('agent:stream:message', { subscriptionId, taskId, ...message });
    };
    void readAgentStream({
      after,
      signal: controller.signal,
      request: (cursor, signal) => this.request(taskId, cursor, signal),
      onStatus: (state, detail) => push({ kind: 'state', state, detail }),
      onEvents: (events) => push({ kind: 'events', events }),
    }).finally(() => this.subscriptions.delete(subscriptionId));
    return subscriptionId;
  }

  stop(subscriptionId: string): void {
    this.subscriptions.get(subscriptionId)?.controller.abort();
    this.subscriptions.delete(subscriptionId);
  }

  /** Nothing may keep reading a backend that is going away. */
  stopAll(): void {
    for (const id of [...this.subscriptions.keys()]) {
      this.stop(id);
    }
  }

  /**
   * Ties a renderer's readers to its lifetime, listening to it once however many it opens.
   *
   * A closed window must not leave a stream reading forever. Neither may a reloaded page: the
   * unsubscribe its old document registered never runs, so its readers would otherwise hold their
   * connections and keep retrying until the same task was opened again. Only a committed main-frame
   * navigation counts, because a link the will-navigate handler cancels leaves the page, and its
   * screens, in place.
   */
  private watch(sender: StreamSender): void {
    if (this.watched.has(sender)) {
      return;
    }
    this.watched.add(sender);
    const stopOwn = (): void => {
      for (const [id, subscription] of [...this.subscriptions]) {
        if (subscription.sender === sender) {
          this.stop(id);
        }
      }
    };
    sender.once('destroyed', stopOwn);
    sender.on('did-navigate', stopOwn);
  }
}
