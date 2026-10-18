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
import { afterAll, beforeAll, expect, it } from 'vitest';
import { buildBrowserFixture, deadline } from '../fixtures/browserFixture';

let fixture: Awaited<ReturnType<typeof buildBrowserFixture>>;
beforeAll(async () => {
  fixture = await buildBrowserFixture({
    prefix: 'bytequay-workbench-', entry: 'src/agents/fixtures/workbenchBrowser.tsx', global: 'WorkbenchTests', styles: true, width: 1450, height: 950,
  });
}, deadline(30_000));
afterAll(() => fixture?.dispose());

it('keeps local WorkBench views and drafts when switching destinations in Chromium', async () => {
  const result = await fixture.run<string>();
  expect(result).toContain('unsaved code before a PR exists');
}, deadline(30_000));

it('opens Discussions from the conversation as a tab that closes in Chromium', async () => {
  const result = await fixture.run<string>({
    body: "const results = await window.webContents.executeJavaScript('WorkbenchTests.utilityTabs()');" });
  expect(result).toContain('closes back to the covered view');
}, deadline(30_000));

it.each([
  ["keeps the panel tab when switching between a pull request's developer and reviewer", ''],
  ['keeps a PR-only reviewer visible after opening its developer conversation', 'true'],
  ['keeps the destination panel and agent tabs open when switching from a utility', 'false, true'],
])('%s in Chromium', async (_name, args) => {
  const result = await fixture.run<string>({
    body: `const results = await window.webContents.executeJavaScript('WorkbenchTests.agentSwitch(${args})');` });
  expect(result).toContain('keeps the panel tab');
}, deadline(30_000));
