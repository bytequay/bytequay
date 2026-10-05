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

it('switches memory sections without stacking content or losing filters', async () => {
  const results = await runBrowserFixture<ScenarioResult[]>({ prefix: 'bytequay-memory-tabs-',
    entry: 'src/memory/fixtures/memoryPageBrowser.tsx', global: 'MemoryPageTests', styles: true, charset: true, width: 1100, height: 750 });
  expect(results).toEqual([{ name: 'Memory section navigation, filters, scrolling and spec entry' }]);
}, 30_000);

it('shows confirmed findings on the page and opens GitHub only on an explicit click', async () => {
  const results = await runBrowserFixture<ScenarioResult[]>({ prefix: 'bytequay-spec-findings-',
    entry: 'src/memory/fixtures/memoryPageBrowser.tsx', global: 'MemoryPageTests', styles: true, charset: true, width: 1100, height: 900,
    body: 'const results = await window.webContents.executeJavaScript("MemoryPageTests.runFindings()");' });
  expect(results).toEqual([{ name: 'Inline spec findings and explicit GitHub form action' }]);
}, 30_000);
