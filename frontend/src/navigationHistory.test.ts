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
import {
  canGoBack,
  canGoForward,
  INITIAL_NAVIGATION,
  navigationIndex,
  navigationShortcutDirection,
  popNavigation,
  pushNavigation,
  stampNavigationState,
} from './navigationHistory';

describe('app navigation history', () => {
  it('moves backward and forward through visited views', () => {
    expect(canGoBack(INITIAL_NAVIGATION)).toBe(false);
    expect(canGoForward(INITIAL_NAVIGATION)).toBe(false);

    const second = pushNavigation(pushNavigation(INITIAL_NAVIGATION));
    const previous = popNavigation(second, 1);

    expect(canGoBack(previous)).toBe(true);
    expect(canGoForward(previous)).toBe(true);
    expect(popNavigation(previous, 2)).toEqual(second);
  });

  it('drops the forward branch after a new view opens', () => {
    const end = pushNavigation(pushNavigation(INITIAL_NAVIGATION));
    const previous = popNavigation(end, 1);
    const replacement = pushNavigation(previous);

    expect(replacement).toEqual({ index: 2, end: 2 });
    expect(canGoForward(replacement)).toBe(false);
  });

  it('recognizes only the current session entry and route', () => {
    const state = stampNavigationState(null, 'session-1', 2, '#/pulls/owner/repo/7');

    expect(navigationIndex(state, 'session-1', '#/pulls/owner/repo/7')).toBe(2);
    expect(navigationIndex(state, 'session-1', '#/pulls/owner/repo/8')).toBeNull();
    expect(navigationIndex(state, 'session-2', '#/pulls/owner/repo/7')).toBeNull();
  });

  it('maps the unmodified macOS bracket shortcuts', () => {
    const shortcut = { metaKey: true, ctrlKey: false, altKey: false, shiftKey: false };

    expect(navigationShortcutDirection({ ...shortcut, key: '[' })).toBe(-1);
    expect(navigationShortcutDirection({ ...shortcut, key: ']' })).toBe(1);
    expect(navigationShortcutDirection({ ...shortcut, key: 'ArrowLeft' })).toBe(0);
    expect(navigationShortcutDirection({ ...shortcut, key: '[', shiftKey: true })).toBe(0);
  });
});
