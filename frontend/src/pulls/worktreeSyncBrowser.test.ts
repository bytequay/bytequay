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

it('creates an exploration worktree on demand and opens it without starting a review', async () => {
  const result = await runBrowserFixture<string>({ prefix: 'bytequay-worktree-explore-',
    entry: 'src/pulls/code-tests/fixtures/worktreeSyncBrowser.tsx', global: 'WorktreeSyncTests',
    styles: true, charset: true, width: 1100, height: 900,
    body: "const results = await window.webContents.executeJavaScript('WorktreeSyncTests.runExploration()');" });
  expect(result).toContain('opens the prepared checkout');
}, 30_000);

it('preserves drafts during automatic sync and resets only after confirmation in Chromium', async () => {
  const result = await runBrowserFixture<string>({ prefix: 'bytequay-worktree-sync-',
    entry: 'src/pulls/code-tests/fixtures/worktreeSyncBrowser.tsx', global: 'WorktreeSyncTests',
    styles: true, charset: true, width: 1100, height: 900 });
  expect(result).toBe('Worktree sync preserves drafts until a confirmed reset and never starts a review');
}, 30_000);
