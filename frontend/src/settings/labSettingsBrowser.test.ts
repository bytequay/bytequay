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
import { expect, it } from 'vitest';
import { runBrowserFixture, type ScenarioResult } from '../fixtures/browserFixture';

it('turns the Lab on in Settings and opens its dashboard from the sidebar, in the running app', async () => {
  const results = await runBrowserFixture<ScenarioResult[]>({
    prefix: 'bytequay-lab-settings-',
    entry: 'src/settings/fixtures/labSettingsBrowser.tsx',
    global: 'LabSettingsTests',
    styles: true,
    width: 1120,
    height: 760,
  });
  expect(results).toHaveLength(5);
  expect(results.filter(result => result.error)).toEqual([]);
}, 30_000);
