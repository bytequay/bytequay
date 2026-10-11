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

it('preserves review and merge sheet focus, dismissal, and in-flight guards', async () => {
  const result = await runBrowserFixture<string>({
    prefix: 'bytequay-action-sheets-', entry: 'src/pulls/fixtures/pullsScreenBrowser.tsx', global: 'ActionSheetTests', styles: true,
    body: "const results = await window.webContents.executeJavaScript('ActionSheetTests.runActionSheetDismissal()');",
  });
  expect(result).toContain('preserve dismissal and busy behavior');
}, 30_000);

it('opens distinct development and review tasks from the same PR without duplicating agents', async () => {
  const result = await runBrowserFixture<string>({
    prefix: 'bytequay-pr-tasks-', entry: 'src/pulls/fixtures/pullsScreenBrowser.tsx', global: 'PullTaskTests', styles: true,
    body: "const results = await window.webContents.executeJavaScript('PullTaskTests.runAssignedPrActions()');",
  });
  expect(result).toContain('Open distinct development and review task conversations');
}, 30_000);

it('allows watched PRs to open Code & Tests before a review or task exists', async () => {
  const result = await runBrowserFixture<string>({
    prefix: 'bytequay-pull-explore-', entry: 'src/pulls/fixtures/pullsScreenBrowser.tsx', global: 'PullExploreTests', styles: true,
    body: "const results = await window.webContents.executeJavaScript('PullExploreTests.runMainScreen(false, true)');",
  });
  expect(result).toContain('Watched PRs offer exploration');
}, 30_000);

it('edits PR titles with cancellation, validation, retry, and selection isolation', async () => {
  const result = await runBrowserFixture<string>({
    prefix: 'bytequay-pr-title-', entry: 'src/pulls/fixtures/pullsScreenBrowser.tsx', global: 'PullTitleTests', styles: true,
    width: 650, height: 700,
    body: "const results = await window.webContents.executeJavaScript('PullTitleTests.runTitleEdit()');",
  });
  expect(result).toContain('Inline title edits');
}, 30_000);

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


it('looks up exact PR numbers and shows newly discovered PRs without repeating list reads', async () => {
  const result = await runBrowserFixture<string>({
    prefix: 'bytequay-pull-list-updates-', entry: 'src/pulls/fixtures/pullsScreenBrowser.tsx', global: 'PullAttentionTests', styles: true,
    body: "const results = await window.webContents.executeJavaScript('PullAttentionTests.runListSearch()');",
  });
  expect(result).toContain('newly discovered PRs');
}, 30_000);

it('hosts a task’s or a review’s private threads on the pull-request page for other people’s comments', async () => {
  const results = await runBrowserFixture<string[]>({
    prefix: 'bytequay-pull-private-threads-', entry: 'src/pulls/fixtures/pullsScreenBrowser.tsx', global: 'PullAttentionTests', styles: true,
    width: 1250, height: 850,
    body: `const results = await window.webContents.executeJavaScript('(async () => [await PullAttentionTests.runPrivateThreads(), await PullAttentionTests.runPrivateThreads("review")])()');`,
  });
  expect(results).toHaveLength(2);
}, 30_000);


it('prepares comment context on send, preserves setup drafts and isolates late results', async () => {
  const results = await runBrowserFixture<string[]>({
    prefix: 'bytequay-prepare-comment-', entry: 'src/pulls/fixtures/pullsScreenBrowser.tsx', global: 'PrepareCommentTests', styles: true,
    width: 1250, height: 850,
    body: `const results = await window.webContents.executeJavaScript('(async () => { const results = []; for (const mode of ["ready", "failure", "model", "workspace", "switch"]) results.push(await PrepareCommentTests.runPreparePrivateThreads(mode)); return results; })()');`,
  });
  expect(results).toHaveLength(5);
}, 30_000);
