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
import { isFindShortcut } from './FindBar';

describe('find-in-page shortcut', () => {
  const key = { altKey: false, ctrlKey: false, key: 'f', metaKey: false };

  it('opens on the platform find chord only', () => {
    expect(isFindShortcut({ ...key, metaKey: true })).toBe(true);
    expect(isFindShortcut({ ...key, ctrlKey: true })).toBe(true);
    expect(isFindShortcut(key)).toBe(false);
    expect(isFindShortcut({ ...key, key: 'g', metaKey: true })).toBe(false);
    // Option-Cmd-F is a different chord; leave it to the app that owns it.
    expect(isFindShortcut({ ...key, altKey: true, metaKey: true })).toBe(false);
  });
});
