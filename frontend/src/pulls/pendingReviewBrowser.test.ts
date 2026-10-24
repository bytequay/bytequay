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
    prefix: 'bytequay-pendingReview-', entry: 'src/pulls/fixtures/pendingReviewBrowser.tsx', global: 'PendingReviewTests', styles: true,
  });
}, deadline(30_000));
afterAll(() => fixture?.dispose());

it('keeps and edits staged review comments across panel tabs in Chromium', async () => {
  const results = await fixture.run<ScenarioResult[]>();
  expect(results).toEqual([{ name: 'A staged review lasts as long as the pull request page' }]);
}, deadline(40_000));

it('edits saved review comments and retries unconfirmed delivery only after confirmation in Chromium', async () => {
  const result = await fixture.run<string>({
    body: "const results = await window.webContents.executeJavaScript('PendingReviewTests.runDraftCommentEdit()');",
  });
  expect(result).toContain('Saved draft comments support editing');
}, deadline(40_000));

it('refreshes resolved findings after confirming GitHub publication in Chromium', async () => {
  const result = await fixture.run<string>({
    body: "const results = await window.webContents.executeJavaScript('PendingReviewTests.runConfirmedPublication()');",
  });
  expect(result).toContain('Confirmed publications refresh resolved findings');
}, deadline(40_000));

it('keeps an approval on its viewed commit when detail refreshes and blocks an unknown commit', async () => {
  const result = await fixture.run<string>({
    body: "const results = await window.webContents.executeJavaScript('PendingReviewTests.runApprovalCommitBinding()');",
  });
  expect(result).toBe('Approvals stay bound to the viewed commit');
}, deadline(40_000));

it('keeps review text while the saved draft is still loading', async () => {
  const result = await fixture.run<string>({
    body: "const results = await window.webContents.executeJavaScript('PendingReviewTests.runDelayedDraftClose()');",
  });
  expect(result).toBe('Unloaded review drafts never silently discard edits');
}, deadline(40_000));
