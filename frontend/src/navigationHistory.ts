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

// TypeScript's DOM library does not yet declare the Navigation API used by Electron.
declare global {
  interface Window {
    navigation: EventTarget & { readonly canGoBack: boolean; readonly canGoForward: boolean };
  }
}

export function navigationShortcutDirection(
  event: Pick<KeyboardEvent, 'altKey' | 'ctrlKey' | 'key' | 'metaKey' | 'shiftKey'>,
): -1 | 0 | 1 {
  if (!event.metaKey || event.ctrlKey || event.altKey || event.shiftKey) return 0;
  if (event.key === '[') return -1;
  if (event.key === ']') return 1;
  return 0;
}
