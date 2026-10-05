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

it('renders saved models during refresh and discovers new choices in Chromium', async () => {
  const result = await runBrowserFixture<ScenarioResult[]>({
    prefix: 'bytequay-model-settings-', entry: 'src/settings/fixtures/modelsSettingsBrowser.tsx', global: 'ModelSettingsTests',
    styles: true, charset: true, width: 1000, height: 900,
  });
  expect(result.filter(value => value.error)).toEqual([]);
  expect(result).toHaveLength(6);
}, 30_000);
