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
import { navigationShortcutDirection } from './navigationHistory';

describe('app navigation history', () => {
  it('maps the unmodified macOS bracket shortcuts', () => {
    const shortcut = { metaKey: true, ctrlKey: false, altKey: false, shiftKey: false };

    expect(navigationShortcutDirection({ ...shortcut, key: '[' })).toBe(-1);
    expect(navigationShortcutDirection({ ...shortcut, key: ']' })).toBe(1);
    expect(navigationShortcutDirection({ ...shortcut, key: 'ArrowLeft' })).toBe(0);
    expect(navigationShortcutDirection({ ...shortcut, key: '[', shiftKey: true })).toBe(0);
  });
});
