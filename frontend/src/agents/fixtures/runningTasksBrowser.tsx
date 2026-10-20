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
import { assert, settle } from '../../fixtures/harness';
import RunningTasks from '../RunningTasks';
import type { RunningTask } from '../runningTaskModel';
import '../../index.css';

export async function run() {
  const host = document.createElement('div');
  host.className = 'app-sidebar';
  host.style.height = '650px';
  document.body.append(host);
  let now = Date.parse('2026-10-01T10:00:00Z');
  Date.now = () => now;
  const task: RunningTask = { id: 'one', workspaceId: 'ws-2', title: 'Fix timestamp encoding in the connector', repository: 'owner/repo',
    kind: 'TASK', targetId: 'one', status: 'RUNNING', startedAt: new Date(now).toISOString(), expiresAt: null };
  let snapshot: RunningTask[] = [];
  let fail = false;
  let pending: ((tasks: RunningTask[]) => void) | null = null;
  let delayRead = false;
  Object.assign(window, { bridge: { listRunningTasks: () => {
    if (fail) return Promise.reject(new Error('Temporarily unavailable'));
    if (delayRead) return new Promise<RunningTask[]>(resolve => { pending = resolve; });
    return Promise.resolve(snapshot);
  } } });
  const timers = new Map<number, () => void>();
  let timerId = 0;
  const originalTimeout = window.setTimeout;
  window.setTimeout = ((callback: () => void) => {
    const id = ++timerId + 1000000; timers.set(id, callback); return id;
  }) as typeof window.setTimeout;
  const originalClear = window.clearTimeout;
  window.clearTimeout = id => { if (typeof id === 'number') timers.delete(id); originalClear(id); };
  const root = createRoot(host);
  root.render(<RunningTasks />);
  await settle();
  assert(!host.textContent?.includes('Running'), 'Empty sidebar should hide Running');
  const refresh = async () => { window.dispatchEvent(new Event('focus')); await settle(); };
  snapshot = [task]; await refresh();
  const row = () => host.querySelector<HTMLButtonElement>('.app-sidebar__running-item');
  assert(row()?.getAttribute('aria-label')?.includes('Running'), 'Running task should appear');
  row()?.click();
  assert(window.location.hash === '#/workspaces/ws-2/tasks/agent/one', 'Click should navigate to the owning workspace');
  const button = host.querySelector<HTMLButtonElement>('[aria-controls="running-tasks"]');
  button?.click(); await settle(); assert(row() === null, 'Section should collapse');
  button?.click(); await settle(); assert(row() !== null, 'Section should expand');
  now += 60_000;
  snapshot = [{ ...task, status: 'COMPLETED', expiresAt: new Date(now + 600_000).toISOString() }]; await refresh();
  assert(row()?.textContent?.includes('Finished'), 'Completion should replace the running indicator');
  fail = true; await refresh();
  assert(row() !== null, 'Failed reads must preserve the last snapshot');
  now += 599_999; await refresh();
  assert(row() !== null, 'Completion should remain for the full ten minutes');
  now += 1;
  for (const callback of [...timers.values()]) callback();
  await settle();
  assert(host.textContent === '', 'Deadline should hide the section even without a successful poll');
  fail = false; snapshot = [{ ...task, startedAt: new Date(now).toISOString() }]; await refresh();
  assert(row()?.getAttribute('aria-label')?.includes('Running'), 'A new run should reappear without the old deadline');
  delayRead = true;
  window.dispatchEvent(new Event('focus')); await settle();
  root.unmount();
  const resolvePending = pending as ((tasks: RunningTask[]) => void) | null;
  resolvePending?.(snapshot); await settle();
  assert(host.textContent === '', 'An in-flight response must not restore an unmounted section');
  window.setTimeout = originalTimeout;
  window.clearTimeout = originalClear;
  return true;
}
