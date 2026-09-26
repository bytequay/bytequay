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

it('preserves editor drafts across automatic review worktree sync in Chromium', async () => {
  const result = await runBrowserFixture<string>({ prefix: 'bytequay-worktree-sync-',
    entry: 'src/pulls/code-tests/fixtures/worktreeSyncBrowser.tsx', global: 'WorktreeSyncTests',
    styles: true, charset: true, width: 1100, height: 900 });
  expect(result).toBe('Review sync notices preserve modified and deleted drafts with save hash guards');
}, 30_000);
