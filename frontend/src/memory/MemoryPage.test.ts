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
import { buildBrowserFixture, deadline, type ScenarioResult } from '../fixtures/browserFixture';

let fixture: Awaited<ReturnType<typeof buildBrowserFixture>>;
beforeAll(async () => {
  fixture = await buildBrowserFixture({
    prefix: 'bytequay-Memory-', entry: 'src/memory/fixtures/memoryPageBrowser.tsx', global: 'MemoryPageTests', styles: true, width: 1100, height: 750,
  });
}, deadline(30_000));
afterAll(() => fixture?.dispose());

it('switches memory sections without stacking content or losing filters', async () => {
  const results = await fixture.run<ScenarioResult[]>();
  expect(results).toEqual([{ name: 'Memory section navigation, filters, scrolling and spec entry' }]);
}, deadline(30_000));

it('finds one closed pull request by number or title words and learns from it only on Learn', async () => {
  const results = await fixture.run<ScenarioResult[]>({
    body: 'const results = await window.webContents.executeJavaScript("MemoryPageTests.runLearnOne()");' });
  expect(results).toEqual([{ name: 'Find one closed pull request by number or title words and learn from it' }]);
}, deadline(30_000));

it('shows confirmed findings on the page and opens their retained local work', async () => {
  const results = await fixture.run<ScenarioResult[]>({ height: 900,
    body: 'const results = await window.webContents.executeJavaScript("MemoryPageTests.runFindings()");' });
  expect(results).toEqual([{ name: 'Inline spec findings and retained local work' }]);
}, deadline(30_000));


it('retains edited memory text when saving fails and a newer version reloads', async () => {
  const results = await fixture.run<ScenarioResult[]>({
    body: 'const results = await window.webContents.executeJavaScript("MemoryPageTests.runDraftSave()");',
  });
  expect(results).toEqual([{ name: 'Retain memory draft through failed save and version refresh' }]);
}, deadline(30_000));
