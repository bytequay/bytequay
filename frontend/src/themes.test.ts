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
import { beforeEach, describe, expect, it } from 'vitest';
import { applyTheme, DEFAULT_THEME, loadTheme, THEMES } from './themes';

describe('themes', () => {
  beforeEach(() => {
    localStorage.clear();
    document.documentElement.removeAttribute('data-theme');
  });

  it('starts in Codex Light and presents it first', () => {
    expect(DEFAULT_THEME).toBe('github-light');
    expect(loadTheme()).toBe('github-light');
    expect(THEMES[0]).toEqual({ id: 'github-light', label: 'Codex Light' });
  });

  it('applies and persists later theme choices', () => {
    applyTheme('atom-one-dark');
    expect(document.documentElement.dataset.theme).toBe('atom-one-dark');
    expect(loadTheme()).toBe('atom-one-dark');
  });

  it('does not carry the former purple default into the v2 preference', () => {
    localStorage.setItem('bytequay-theme', 'purple');
    expect(loadTheme()).toBe('github-light');
  });

  it('migrates an explicit legacy non-default choice', () => {
    localStorage.setItem('bytequay-theme', 'atom-one-dark');
    expect(loadTheme()).toBe('atom-one-dark');
    expect(localStorage.getItem('bytequay-theme-v2')).toBe('atom-one-dark');
  });
});
