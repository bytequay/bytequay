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
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { pollVisible } from './pollVisible';

let document: EventTarget & { visibilityState: string };
let stop: (() => void) | undefined;
beforeEach(() => {
  vi.useFakeTimers();
  document = Object.assign(new EventTarget(), { visibilityState: 'visible' });
  vi.stubGlobal('document', document);
});
afterEach(() => { stop?.(); stop = undefined; vi.useRealTimers(); vi.unstubAllGlobals(); });

it('keeps one slow refresh in flight and resumes after it settles', async () => {
  let finish!: () => void;
  const pending = new Promise<void>(resolve => { finish = resolve; });
  const load = vi.fn(() => pending);
  stop = pollVisible(load, 1500);
  await vi.advanceTimersByTimeAsync(15_000);
  document.dispatchEvent(new Event('visibilitychange'));
  expect(load).toHaveBeenCalledTimes(1);
  finish();
  await vi.advanceTimersByTimeAsync(1500);
  expect(load).toHaveBeenCalledTimes(2);
});

it('pauses hidden editors, refreshes on return and stops on unmount', async () => {
  document.visibilityState = 'hidden';
  const load = vi.fn(async () => {});
  stop = pollVisible(load, 5000);
  await vi.advanceTimersByTimeAsync(15_000);
  expect(load).not.toHaveBeenCalled();
  document.visibilityState = 'visible';
  document.dispatchEvent(new Event('visibilitychange'));
  expect(load).toHaveBeenCalledTimes(1);
  stop();
  await vi.advanceTimersByTimeAsync(15_000);
  document.dispatchEvent(new Event('visibilitychange'));
  expect(load).toHaveBeenCalledTimes(1);
});

it('does not resume a disposed poll when its pending read completes', async () => {
  let finish!: () => void;
  const load = vi.fn(() => new Promise<void>(resolve => { finish = resolve; }));
  stop = pollVisible(load, 1500);
  stop(); finish();
  await vi.advanceTimersByTimeAsync(15_000);
  document.dispatchEvent(new Event('visibilitychange'));
  expect(load).toHaveBeenCalledTimes(1);
});
