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

// Order matches the picker: the Codex light baseline first, followed by the
// optional brand, dark, and warm variants. IDs remain stable across labels.
export const THEMES: { id: ThemeId; label: string }[] = [
  { id: 'github-light', label: 'Codex Light' },
  { id: 'purple', label: 'Purple' },
  { id: 'atom-one-dark', label: 'Dark' },
  { id: 'warm', label: 'Warm' },
];

// v2 deliberately resets the old purple-first preference once so existing
// installs adopt the new app-wide Codex Light baseline. Choices made after
// this migration still persist normally.
const STORAGE_KEY = 'bytequay-theme-v2';
const LEGACY_STORAGE_KEY = 'bytequay-theme';
export const DEFAULT_THEME: ThemeId = 'github-light';

export function applyTheme(id: ThemeId): void {
  document.documentElement.setAttribute('data-theme', id);
  localStorage.setItem(STORAGE_KEY, id);
}

export function loadTheme(): ThemeId {
  const saved = localStorage.getItem(STORAGE_KEY) as ThemeId | null;
  if (saved && THEMES.some((t) => t.id === saved)) {
    return saved;
  }

  // Purple was also the implicit legacy default, so it cannot be distinguished
  // from a deliberate choice. Reset only that value; preserve explicit legacy
  // choices for the other themes.
  const legacy = localStorage.getItem(LEGACY_STORAGE_KEY) as ThemeId | null;
  if (legacy && legacy !== 'purple' && THEMES.some((t) => t.id === legacy)) {
    localStorage.setItem(STORAGE_KEY, legacy);
    return legacy;
  }
  return DEFAULT_THEME;
}
