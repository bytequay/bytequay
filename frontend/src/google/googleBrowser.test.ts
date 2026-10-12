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

it('uses cached Google data and Gemini and manages account access in Chromium', async () => {
  const results = await runBrowserFixture<ScenarioResult[]>({ prefix: 'bytequay-google-', entry: 'src/google/fixtures/googleBrowser.tsx', global: 'GoogleTests', styles: true, width: 1120, height: 820 });
  expect(results).toHaveLength(12);
  expect(results.filter(result => result.error)).toEqual([]);
}, 30_000);
