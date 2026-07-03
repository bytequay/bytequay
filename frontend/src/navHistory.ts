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

/** Browser-style navigation history over the app's Nav state: a stack
 *  plus a cursor. Navigating pushes (truncating any forward entries,
 *  like a browser); back/forward move the cursor only. Entries are
 *  plain JSON-comparable objects. */
export type NavHistory<T> = {
  stack: T[];
  index: number;
};

/** Entries kept — enough for a day of clicking, bounded memory. */
const MAX_ENTRIES = 100;

export function createHistory<T>(initial: T): NavHistory<T> {
  return { stack: [initial], index: 0 };
}

export function current<T>(h: NavHistory<T>): T {
  return h.stack[h.index];
}

/** Push a navigation. A no-op when the target equals the current entry
 *  (re-clicking the active nav item shouldn't eat a Back press). */
export function push<T>(h: NavHistory<T>, next: T): NavHistory<T> {
  if (JSON.stringify(current(h)) === JSON.stringify(next)) return h;
  const stack = [...h.stack.slice(0, h.index + 1), next].slice(-MAX_ENTRIES);
  return { stack, index: stack.length - 1 };
}

export function canGoBack<T>(h: NavHistory<T>): boolean {
  return h.index > 0;
}

export function canGoForward<T>(h: NavHistory<T>): boolean {
  return h.index < h.stack.length - 1;
}

/** Move the cursor back; returns the history unchanged at the edge. */
export function back<T>(h: NavHistory<T>): NavHistory<T> {
  return canGoBack(h) ? { ...h, index: h.index - 1 } : h;
}

/** Move the cursor forward; returns the history unchanged at the edge. */
export function forward<T>(h: NavHistory<T>): NavHistory<T> {
  return canGoForward(h) ? { ...h, index: h.index + 1 } : h;
}
