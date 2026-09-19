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

it('filters the outline and keeps the definition chooser on screen in Chromium', async () => {
  const results = await runBrowserFixture<ScenarioResult[]>({
    prefix: 'bytequay-outline-filter-',
    entry: 'src/pulls/code-tests/fixtures/outlineFilterBrowser.tsx',
    global: 'OutlineFilterTests',
    charset: true,
    styles: true,
    width: 900,
    height: 600,
  });
  expect(results).toHaveLength(5);
  expect(results.filter(result => result.error !== undefined)).toEqual([]);
}, 30_000);
