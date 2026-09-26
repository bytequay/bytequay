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
import { runBrowserFixture } from '../fixtures/browserFixture';

it('keeps local WorkBench views and drafts when switching destinations in Chromium', async () => {
  const result = await runBrowserFixture<string>({ prefix: 'bytequay-workbench-', entry: 'src/agents/fixtures/workbenchBrowser.tsx',
    global: 'WorkbenchTests', styles: true, charset: true, width: 1450, height: 950 });
  expect(result).toContain('unsaved code before a PR exists');
}, 30_000);

it('opens Discussions from the conversation as a tab that closes in Chromium', async () => {
  const result = await runBrowserFixture<string>({ prefix: 'bytequay-workbench-utility-', entry: 'src/agents/fixtures/workbenchBrowser.tsx',
    global: 'WorkbenchTests', styles: true, charset: true, width: 1450, height: 950,
    body: "const results = await window.webContents.executeJavaScript('WorkbenchTests.utilityTabs()');" });
  expect(result).toContain('closes back to the covered view');
}, 30_000);
