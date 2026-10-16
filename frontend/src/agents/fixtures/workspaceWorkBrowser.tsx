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
import { createRoot } from 'react-dom/client';
import { flushSync } from 'react-dom';
import { assert, settle } from '../../fixtures/harness';
import { reviewFixture } from '../../pulls/agent-review/fixtures/review';
import type { AgentReviewDto } from '../../pulls/agent-review/contracts';
import { useWorkspaceWork } from '../AgentTasksView';
import type { AgentBridgePort } from '../model';
import { createPreviewBridge } from './previewBridge';

export async function run() {
  const pending: Array<{ resolve: (reviews: AgentReviewDto[]) => void; reject: (error: Error) => void }> = [];
  let calls = 0;
  const bridge: AgentBridgePort = { ...createPreviewBridge('work-poll'),
    listAgentReviews: () => {
      calls++;
      return new Promise((resolve, reject) => pending.push({ resolve, reject }));
    },
    listCherryPickExecutions: async () => [], listCiAutofixRuns: async () => [],
  };
  const live = () => bridge;
  function Work() {
    const work = useWorkspaceWork('work-poll', live);
    return <output>{JSON.stringify({ ids: work.reviews.map(review => review.id), failed: work.failed })}</output>;
  }
  const interval = window.setInterval;
  let tick = () => {};
  Object.defineProperty(window, 'setInterval', { configurable: true, value: (handler: TimerHandler, delay?: number) => {
    assert(typeof handler === 'function' && delay === 5000, 'Captures the workflow polling interval');
    tick = () => handler();
    return 0;
  } });
  const visibility = Object.getOwnPropertyDescriptor(document, 'visibilityState');
  Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' });
  const host = document.createElement('main');
  document.body.append(host);
  const content = () => host.textContent;
  const root = createRoot(host);
  try {
    flushSync(() => root.render(<Work />));
    await settle();
    assert(calls === 1, 'Starts the first read');
    for (let i = 0; i < 2; i++) tick();
    assert(calls === 1, 'A slow read never overlaps another polling request');
    pending.shift()?.resolve([reviewFixture({ id: 'saved-review' })]);
    await settle();
    assert(content() === '{"ids":["saved-review"],"failed":[]}', 'Shows the review when the slow read finishes');

    tick();
    pending.shift()?.reject(new Error('Read timed out'));
    await settle();
    assert(content() === '{"ids":["saved-review"],"failed":["reviews"]}', 'Keeps the last review and reports the failure');
    tick();
    pending.shift()?.resolve([reviewFixture({ id: 'updated-review' })]);
    await settle();
    assert(content() === '{"ids":["updated-review"],"failed":[]}', 'Retries after failure and clears the error');

    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' });
    const before = calls;
    tick();
    assert(calls === before, 'Hidden pages do not poll');
    return [{ name: 'Nonoverlapping workspace reads retain reviews and recover after failure' }];
  }
  finally {
    flushSync(() => root.unmount());
    host.remove();
    window.setInterval = interval;
    if (visibility === undefined) Reflect.deleteProperty(document, 'visibilityState');
    else Object.defineProperty(document, 'visibilityState', visibility);
  }
}
