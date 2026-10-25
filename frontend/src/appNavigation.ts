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

export const APP_NAVIGATION_SHORTCUTS = [
  { page: 'home', label: 'Home', accelerator: 'Command+H', hint: '⌘H / ⌥⌘1' },
  { page: 'workspaces', label: 'Workspaces', accelerator: 'Alt+Command+2', hint: '⌥⌘2' },
  { page: 'teams', label: 'Circles', accelerator: 'Alt+Command+3', hint: '⌥⌘3' },
  { page: 'recent', label: 'Recent', accelerator: 'Alt+Command+4', hint: '⌥⌘4' },
  { page: 'pulls', label: 'Pull requests', accelerator: 'Alt+Command+5', hint: '⌥⌘5' },
  { page: 'settings', label: 'Settings', accelerator: 'Command+,', hint: '⌘,' },
  { page: 'new-task', label: 'New task', accelerator: 'Shift+Command+N', hint: '⇧⌘N' },
  { page: 'switch-task', label: 'Switch task', accelerator: 'Control+Command+T', hint: '⌃⌘T' },
] as const;

export type AppNavigationTarget = typeof APP_NAVIGATION_SHORTCUTS[number]['page'] | 'shortcuts';
