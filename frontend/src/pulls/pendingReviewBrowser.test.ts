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

it('keeps and edits staged review comments across panel tabs in Chromium', async () => {
  const results = await runBrowserFixture<ScenarioResult[]>({
    prefix: 'bytequay-pending-review-',
    entry: 'src/pulls/fixtures/pendingReviewBrowser.tsx',
    global: 'PendingReviewTests',
    styles: true,
    charset: true,
  });
  expect(results.filter(result => result.error !== undefined)).toEqual([]);
}, 40_000);

it('edits saved review comments with validation, retry and explicit publication in Chromium', async () => {
  const result = await runBrowserFixture<string>({
    prefix: 'bytequay-review-comment-edit-', entry: 'src/pulls/fixtures/pendingReviewBrowser.tsx', global: 'PendingReviewTests',
    styles: true, charset: true,
    body: "const results = await window.webContents.executeJavaScript('PendingReviewTests.runDraftCommentEdit()');",
  });
  expect(result).toContain('Saved draft comments support editing');
}, 40_000);
