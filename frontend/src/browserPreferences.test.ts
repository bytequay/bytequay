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
import { afterEach, expect, it, vi } from 'vitest';
import { readBrowserDisplayMode, saveBrowserDisplayMode } from './browserPreferences';

afterEach(() => { vi.unstubAllGlobals(); });

it('defaults to zoom when the saved preference is missing or invalid', () => {
  const getItem = vi.fn();
  vi.stubGlobal('window', { localStorage: { getItem } });
  for (const value of [null, '', 'unknown', 'zoom']) {
    getItem.mockReturnValue(value);
    expect(readBrowserDisplayMode()).toBe('zoom');
  }
});

it('persists both choices on this Mac and restores the selected mode', () => {
  const values = new Map<string, string>();
  vi.stubGlobal('window', { localStorage: {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => values.set(key, value),
  } });
  saveBrowserDisplayMode('fullscreen');
  expect(values.get('bytequay.browser-display-mode')).toBe('fullscreen');
  expect(readBrowserDisplayMode()).toBe('fullscreen');
  saveBrowserDisplayMode('zoom');
  expect(readBrowserDisplayMode()).toBe('zoom');
});

it('keeps browser settings usable when local storage is unavailable', () => {
  const unavailable = () => { throw new Error('Storage unavailable'); };
  vi.stubGlobal('window', { localStorage: { getItem: unavailable, setItem: unavailable } });
  expect(readBrowserDisplayMode()).toBe('zoom');
  expect(() => saveBrowserDisplayMode('fullscreen')).not.toThrow();
});
