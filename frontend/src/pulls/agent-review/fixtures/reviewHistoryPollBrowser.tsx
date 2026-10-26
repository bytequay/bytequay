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
import type { ScenarioResult } from '../../../fixtures/browserFixture';
import { createFixtureRoot } from '../../../fixtures/reactFixture';
import type { Bridge } from '../../../types';
import type { AgentEventEnvelope } from '../../../agents/events';
import { assert, settle, until } from '../../../fixtures/harness';
import { useReviewActivity } from '../ReviewRunPanels';
import { runningReviewFixture } from './review';

export async function run(): Promise<ScenarioResult[]> {
  const { root, dispose } = createFixtureRoot();
  const review = runningReviewFixture();
  const events: AgentEventEnvelope[] = [];
  const add = () => {
    const sequence = events.length + 1;
    events.push({
      schemaVersion: 2, eventId: `event-${sequence}`, sequence, taskId: review.task.id, turnId: 'turn-1',
      source: { providerThreadId: null, parentProviderThreadId: null, logicalAgentPath: null, providerTurnId: null,
        providerItemId: `item-${sequence}`, providerEventId: null },
      occurredAt: `2026-09-06T16:04:0${sequence}Z`, type: 'tool',
      payload: { name: 'mcp__bytequay__review_get_context', state: 'COMPLETED', summary: '', durationMs: null, description: `Read file ${sequence}` },
    } as AgentEventEnvelope);
  };
  add();
  add();
  const cursors: Array<string | undefined> = [];
  const previous = window.bridge;
  // The bridge ignores the cursor and returns everything, so the hook itself must keep only what is new.
  const bridge = {
    readAgentReviewHistory: async (_reviewId, _taskId, _before, after) => {
      cursors.push(after);
      return { events: [...events], beforeCursor: null, afterCursor: null, latestSequence: events.length };
    },
  } satisfies Pick<Bridge, 'readAgentReviewHistory'>;
  Object.defineProperty(window, 'bridge', { configurable: true, writable: true, value: bridge });
  const originalSetInterval = window.setInterval;
  let poll = (): void => { throw new Error('A running review must poll its history'); };
  window.setInterval = ((handler: TimerHandler, delay?: number, ...args: unknown[]): number => {
    if (delay !== 2000 || typeof handler !== 'function') return originalSetInterval(handler, delay, ...args);
    poll = () => handler(...args);
    return 1_000_000;
  }) as typeof window.setInterval;
  let renders = 0;
  let shown: string[] = [];
  function Probe() {
    const activity = useReviewActivity(review, review.runs[0], true, true);
    renders++;
    shown = activity.actions.map(action => action.member.kind === 'tool' ? action.member.description ?? '' : '');
    return null;
  }
  try {
    flushSync(() => root.render(<Probe />));
    await until(() => shown.length === 2, 'The first read must show both actions');
    const quiet = renders;
    poll();
    await settle();
    await settle();
    assert(cursors.at(-1) === '2', `A poll must read only after the newest shown event, read after ${cursors.at(-1)}`);
    assert(renders === quiet, 'A poll that finds nothing new must not render again');
    add();
    poll();
    await until(() => shown.length === 3, 'A new event must reach the activity list');
    assert(shown.join() === 'Read file 3,Read file 2,Read file 1', `Each action must show once, newest first: ${shown.join()}`);
    poll();
    await settle();
    assert(cursors.at(-1) === '3', 'The next poll must start after the new event');
    return [{ name: 'A running review reads its history once, then only the events after it' }];
  }
  catch (error) { return [{ name: 'Review history poll', error: error instanceof Error ? error.message : String(error) }]; }
  finally {
    window.setInterval = originalSetInterval;
    dispose();
    Object.assign(window, { bridge: previous });
  }
}
