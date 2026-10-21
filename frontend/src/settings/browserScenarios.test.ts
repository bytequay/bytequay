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
import { testBrowserScenarios } from '../fixtures/browserScenarios';

testBrowserScenarios([
  {
    name: 'starts and refreshes the Lab from Settings and the sidebar with shared progress and retry',
    expected: 5, timeout: 30_000,
    fixture: {
      prefix: 'bytequay-lab-settings-',
      entry: 'src/settings/fixtures/labSettingsBrowser.tsx',
      global: 'LabSettingsTests',
      styles: true,
      width: 1120,
      height: 760,
    },
  },
  {
    name: 'saves and edits user skill scopes through the Settings controls in Chromium',
    expected: 2, timeout: 30_000,
    fixture: {
      prefix: 'bytequay-skill-scopes-',
      entry: 'src/settings/fixtures/skillScopesBrowser.tsx',
      global: 'SkillScopesTests',
    },
  },
  {
    name: 'keeps workspace settings loaded when the picker is used in Chromium',
    expected: 8, timeout: 30_000,
    fixture: {
      prefix: 'bytequay-workspace-picker-',
      entry: 'src/settings/fixtures/workspacePickerBrowser.tsx',
      global: 'WorkspacePickerTests',
      styles: true,
    },
  },
]);
