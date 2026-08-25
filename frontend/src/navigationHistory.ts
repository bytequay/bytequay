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

export type NavigationMode = 'push' | 'replace';

export type NavigationPosition = {
  index: number;
  end: number;
};

const NAVIGATION_STATE = 'byteQuayNavigation';

export const INITIAL_NAVIGATION: NavigationPosition = { index: 0, end: 0 };

export function pushNavigation(current: NavigationPosition): NavigationPosition {
  const index = current.index + 1;
  return { index, end: index };
}

export function popNavigation(current: NavigationPosition, index: number): NavigationPosition {
  return Number.isInteger(index) && index >= 0 && index <= current.end
    ? { ...current, index }
    : current;
}

export function canGoBack(current: NavigationPosition) {
  return current.index > 0;
}

export function canGoForward(current: NavigationPosition) {
  return current.index < current.end;
}

export function navigationShortcutDirection(
  event: Pick<KeyboardEvent, 'altKey' | 'ctrlKey' | 'key' | 'metaKey' | 'shiftKey'>,
): -1 | 0 | 1 {
  if (!event.metaKey || event.ctrlKey || event.altKey || event.shiftKey) return 0;
  if (event.key === '[') return -1;
  if (event.key === ']') return 1;
  return 0;
}

export function navigationIndex(state: unknown, session: string, hash: string): number | null {
  if (!isRecord(state)) return null;
  const stamp = state[NAVIGATION_STATE];
  if (!isRecord(stamp)
    || stamp.session !== session
    || stamp.hash !== hash
    || !Number.isInteger(stamp.index)
    || (stamp.index as number) < 0) {
    return null;
  }
  return stamp.index as number;
}

export function stampNavigationState(state: unknown, session: string, index: number, hash: string) {
  return {
    ...(isRecord(state) ? state : {}),
    [NAVIGATION_STATE]: { session, index, hash },
  };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
