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
export type SettingsSection =
  | 'account'
  | 'appearance'
  | 'credentials'
  // Kept in the union for back-compat with onboarding deep links and
  // older URLs; the SettingsShell aliases it to 'credentials' on render.
  | 'github-token'
  | 'ai-review'
  | 'local-ai'
  | 'skills'
  | 'agent-roles'
  | 'watched-repos'
  | 'workspace-memory'
  | 'integrations'
  | 'email'
  | 'saved-views'
  | 'concepts'
  | 'help';
