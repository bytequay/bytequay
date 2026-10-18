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

it('edits workspace relationships and opens their folded navigation within the current workspace', async () => {
  const results = await runBrowserFixture<ScenarioResult[]>({ prefix: 'bytequay-related-',
    entry: 'src/workspaces/fixtures/relatedWorkspacesBrowser.tsx', global: 'RelatedWorkspacesTests', styles: true, charset: true,
    width: 1100, height: 850,
    setup: "window.webContents.on('console-message', event => { if (event.level === 'error') console.error(event.message); });" });
  expect(results).toHaveLength(2);
  expect(results.filter(result => result.error)).toEqual([]);
}, deadline(120_000));
