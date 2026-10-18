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
import { runBrowserFixture, deadline, type ScenarioResult } from '../../fixtures/browserFixture';

it('reviews local work, routes questions and fixes, and inspects changing worktree history in Chromium', async () => {
  const results = await runBrowserFixture<ScenarioResult[]>({ prefix: 'bytequay-workbench-',
    entry: 'src/pulls/agent-review/fixtures/workbenchBrowser.tsx', global: 'WorkbenchTests', styles: true, charset: true, width: 700, height: 800 });
  expect(results).toHaveLength(7);
  expect(results.filter(result => result.error !== undefined)).toEqual([]);
}, deadline(30_000));
