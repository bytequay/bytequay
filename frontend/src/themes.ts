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
export type ThemeId = 'purple' | 'github-light' | 'atom-one-dark' | 'warm';

// Order matches the picker: brand theme first (also the default for
// fresh installs), then the neutral light option, then dark, then the
// older warm experiment. IDs are kept stable so anyone who already
// picked a theme keeps that choice across the rename.
export const THEMES: { id: ThemeId; label: string }[] = [
  { id: 'purple', label: 'Purple' },
  { id: 'github-light', label: 'Light' },
  { id: 'atom-one-dark', label: 'Dark' },
  { id: 'warm', label: 'Warm' },
];

const STORAGE_KEY = 'bytequay-theme';

export function applyTheme(id: ThemeId): void {
  document.documentElement.setAttribute('data-theme', id);
  localStorage.setItem(STORAGE_KEY, id);
}

export function loadTheme(): ThemeId {
  const saved = localStorage.getItem(STORAGE_KEY) as ThemeId | null;
  return saved && THEMES.some((t) => t.id === saved) ? saved : 'purple';
}
