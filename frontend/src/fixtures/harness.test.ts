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

import { expect, it, vi } from 'vitest';
import { button, controlTimers, element, runScenarios, textButton } from './harness';

it('keeps DOM queries scoped and preserves their missing-element diagnostics', () => {
  const found = { textContent: 'value' };
  const querySelector = vi.fn().mockReturnValue(found);
  const root = { querySelector } as unknown as ParentNode;
  expect(element('.field', root)).toBe(found);
  expect(querySelector).toHaveBeenCalledWith('.field');
  querySelector.mockReturnValue(null);
  expect(() => element('.field', root)).toThrow('Missing .field');
  expect(() => element('.field', root, 'The composer must exist')).toThrow('The composer must exist');
});

it('keeps exact text button matching separate from trimmed labels and aria labels', () => {
  const labeled = { textContent: 'Icon', getAttribute: () => 'Send' };
  const spaced = { textContent: ' Send ', getAttribute: (): string | null => null };
  const exact = { textContent: 'Send', getAttribute: (): string | null => null };
  const querySelectorAll = vi.fn().mockReturnValue([labeled, spaced, exact]);
  const root = { querySelectorAll } as unknown as ParentNode;
  expect(button('Send', root)).toBe(labeled);
  expect(textButton('Send', root)).toBe(exact);
  querySelectorAll.mockReturnValue([spaced]);
  expect(button('Send', root)).toBe(spaced);
  expect(() => textButton('Send', root)).toThrow('Missing Send button');
  expect(() => textButton('Send', root, 'No send action')).toThrow('No send action');
  querySelectorAll.mockReturnValue([]);
  expect(() => button('Send', root)).toThrow('Missing button: Send');
  expect(() => button('Send', root, 'No send action')).toThrow('No send action');
  expect(querySelectorAll.mock.calls.every(([selector]) => selector === 'button')).toBe(true);
});

it('runs every scenario in order and awaits cleanup after success or failure', async () => {
  const order: string[] = [];
  const results = await runScenarios([
    ['first', () => { order.push('first'); }],
    ['broken', async () => { order.push('broken'); throw new Error('Unavailable'); }],
    ['last', () => { order.push('last'); throw 'Plain failure'; }],
  ], async () => { await Promise.resolve(); order.push('clean'); });
  expect(order).toEqual(['first', 'clean', 'broken', 'clean', 'last', 'clean']);
  expect(results).toEqual([{ name: 'first' }, { name: 'broken', error: 'Unavailable' }, { name: 'last', error: 'Plain failure' }]);
});

it('propagates cleanup failures and does not begin the next scenario', async () => {
  const next = vi.fn();
  await expect(runScenarios([['first', () => {}], ['next', next]], () => { throw new Error('Cleanup failed'); })).rejects.toThrow('Cleanup failed');
  expect(next).not.toHaveBeenCalled();
});

it('does not add a scheduling turn after synchronous cleanup', async () => {
  const order: string[] = [];
  await runScenarios([['first', () => {}], ['second', () => { order.push('second'); }]], () => {
    queueMicrotask(() => order.push('cleanup turn'));
  });
  expect(order).toEqual(['second', 'cleanup turn', 'cleanup turn']);
});

it('advances only the selected delay, honors cancellation and restores real timers', () => {
  const originalSet = vi.fn(() => 7);
  const originalClear = vi.fn();
  vi.stubGlobal('window', { setTimeout: originalSet, clearTimeout: originalClear });
  const timers = controlTimers(400);
  try {
    const cancelled = vi.fn();
    const called = vi.fn();
    const cancelledId = window.setTimeout(cancelled, 400);
    window.clearTimeout(cancelledId);
    window.setTimeout(called, 400, 'argument');
    expect(window.setTimeout(called, 30)).toBe(7);
    expect(originalSet).toHaveBeenCalledWith(called, 30);
    window.clearTimeout(7);
    expect(originalClear).toHaveBeenCalledWith(7);
    expect(called).not.toHaveBeenCalled();
    expect(timers.tick()).toBe(1);
    expect(called).toHaveBeenCalledTimes(1);
    expect(called).toHaveBeenCalledWith('argument');
    expect(cancelled).not.toHaveBeenCalled();
    expect(timers.tick()).toBe(0);
    window.setTimeout(cancelled, 400);
    timers.restore();
    expect(window.setTimeout).toBe(originalSet);
    expect(window.clearTimeout).toBe(originalClear);
    expect(timers.tick()).toBe(0);
  }
  finally { timers.restore(); vi.unstubAllGlobals(); }
});
