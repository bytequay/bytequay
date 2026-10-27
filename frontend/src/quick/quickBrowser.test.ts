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
import { runBrowserFixture, deadline, type ScenarioResult } from '../fixtures/browserFixture';

it('shows the app’s Assistant panel sized to its window, opens pages in the main window and closes on Escape', async () => {
  const results = await runBrowserFixture<ScenarioResult[]>({
    prefix: 'bytequay-quick-', entry: 'src/quick/fixtures/quickBrowser.tsx', global: 'QuickTests', styles: true, width: 720, height: 600,
  });
  expect(results.filter(result => result.error !== undefined)).toEqual([]);
  expect(results).toHaveLength(2);
}, deadline(30_000));
