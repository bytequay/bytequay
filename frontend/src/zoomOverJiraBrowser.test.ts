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
import { runBrowserFixture } from './fixtures/browserFixture';

it('draws the most recently opened ticket, issue or web page on top in the running app', async () => {
  const results = await runBrowserFixture<{ name: string; error?: string }[]>({
    prefix: 'bytequay-zoom-over-jira-',
    entry: 'src/fixtures/zoomOverJiraBrowser.tsx',
    global: 'ZoomOverJiraTests',
    styles: true,
    width: 1280,
    height: 900,
  });
  expect(results.filter(result => result.error !== undefined)).toEqual([]);
  expect(results).toHaveLength(3);
}, 30_000);
