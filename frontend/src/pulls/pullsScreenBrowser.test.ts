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

it('acknowledges a viewed PR when leaving zoom without closing the regular detail pane', async () => {
  const result = await runBrowserFixture<string>({
    prefix: 'bytequay-pull-attention-', entry: 'src/pulls/fixtures/pullsScreenBrowser.tsx', global: 'PullAttentionTests', styles: true,
    body: "const results = await window.webContents.executeJavaScript('PullAttentionTests.runMainScreen()');",
  });
  expect(result).toContain('Main PR page');
}, 30_000);

it('opens Changes in local and linked review workspaces while keeping Summary independent', async () => {
  const results = await runBrowserFixture<string[]>({
    prefix: 'bytequay-review-workbench-', entry: 'src/pulls/fixtures/pullsScreenBrowser.tsx', global: 'PullAttentionTests', styles: true,
    width: 1450, height: 950,
    body: `const results = await window.webContents.executeJavaScript('(async () => [await PullAttentionTests.runReviewWorkspace(), await PullAttentionTests.runReviewWorkspace(true), await PullAttentionTests.runOpenReviewWorkspaceStream()])()');`,
  });
  expect(results).toHaveLength(3);
}, 30_000);
