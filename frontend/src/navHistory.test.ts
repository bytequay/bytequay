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
import { describe, expect, it } from 'vitest';
import { back, canGoBack, canGoForward, createHistory, current, forward, push } from './navHistory';

type N = { view: string };

describe('navHistory', () => {
  it('pushes and walks back / forward like a browser', () => {
    let h = createHistory<N>({ view: 'home' });
    h = push(h, { view: 'a' });
    h = push(h, { view: 'b' });
    expect(current(h)).toEqual({ view: 'b' });
    expect(canGoBack(h)).toBe(true);
    expect(canGoForward(h)).toBe(false);

    h = back(h);
    expect(current(h)).toEqual({ view: 'a' });
    expect(canGoForward(h)).toBe(true);

    h = forward(h);
    expect(current(h)).toEqual({ view: 'b' });
  });

  it('is inert at both edges', () => {
    const h = createHistory<N>({ view: 'home' });
    expect(back(h)).toBe(h);
    expect(forward(h)).toBe(h);
  });

  it('truncates the forward branch on a new push', () => {
    let h = createHistory<N>({ view: 'home' });
    h = push(h, { view: 'a' });
    h = push(h, { view: 'b' });
    h = back(h);
    h = push(h, { view: 'c' });
    expect(canGoForward(h)).toBe(false);
    expect(current(h)).toEqual({ view: 'c' });
    h = back(h);
    expect(current(h)).toEqual({ view: 'a' });
  });

  it('ignores a push equal to the current entry', () => {
    let h = createHistory<N>({ view: 'home' });
    h = push(h, { view: 'a' });
    const same = push(h, { view: 'a' });
    expect(same).toBe(h);
  });

  it('caps the stack at 100 entries, dropping the oldest', () => {
    let h = createHistory<N>({ view: 'home' });
    for (let i = 0; i < 150; i++) h = push(h, { view: `v${i}` });
    expect(h.stack.length).toBe(100);
    expect(current(h)).toEqual({ view: 'v149' });
    expect(h.stack[0]).toEqual({ view: 'v50' });
  });
});
