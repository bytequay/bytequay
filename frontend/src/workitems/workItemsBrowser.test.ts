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
import { runBrowserFixture, deadline } from '../fixtures/browserFixture';

it('keeps the original source screens under three tabs and creates work locally', async () => {
  const result = await runBrowserFixture({ prefix: 'bytequay-unified-work-', entry: 'src/workitems/fixtures/workItemsBrowser.tsx', global: 'UnifiedWorkTests',
    styles: true, charset: true, width: 1280, height: 850,
    body: `const results = await window.webContents.executeJavaScript('UnifiedWorkTests.run()');
      require('node:fs').writeFileSync('/tmp/bytequay-unified-work.png', (await window.webContents.capturePage()).toPNG());` });
  expect(result).toEqual({ sources: 3, publications: 1, count: 4 });
}, deadline(30_000));

it('marks local Epics, assigns work and preserves their identity through publication', async () => {
  expect(await runBrowserFixture({ prefix: 'bytequay-local-epic-', entry: 'src/workitems/fixtures/workItemsBrowser.tsx', global: 'UnifiedWorkTests',
    styles: true, charset: true, width: 1280, height: 850,
    body: 'const results = await window.webContents.executeJavaScript("UnifiedWorkTests.localEpic()");' })).toBe(true);
}, deadline(30_000));

it('links local, GitHub and Jira context without changing ownership, Epic membership or blockers', async () => {
  expect(await runBrowserFixture({ prefix: 'bytequay-work-context-', entry: 'src/workitems/fixtures/workItemsBrowser.tsx', global: 'UnifiedWorkTests',
    styles: true, charset: true, width: 1280, height: 850,
    body: 'const results = await window.webContents.executeJavaScript("UnifiedWorkTests.contextLinks()");' })).toBe(true);
}, deadline(30_000));
