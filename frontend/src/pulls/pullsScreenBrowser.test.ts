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
import { buildBrowserFixture } from '../fixtures/browserFixture';

let fixture: Awaited<ReturnType<typeof buildBrowserFixture>>;
beforeAll(async () => {
  fixture = await buildBrowserFixture({
    prefix: 'bytequay-pullsScreen-', entry: 'src/pulls/fixtures/pullsScreenBrowser.tsx', global: 'PullScreenTests', styles: true,
  });
}, 30_000);
afterAll(() => fixture?.dispose());

it('preserves review and merge sheet focus, dismissal, and in-flight guards', async () => {
  const result = await fixture.run<string>({
    body: "const results = await window.webContents.executeJavaScript('PullScreenTests.runActionSheetDismissal()');",
  });
  expect(result).toContain('preserve dismissal and busy behavior');
}, 30_000);

it('opens distinct development and review tasks from the same PR without duplicating agents', async () => {
  const result = await fixture.run<string>({
    body: "const results = await window.webContents.executeJavaScript('PullScreenTests.runAssignedPrActions()');",
  });
  expect(result).toContain('Open distinct development and review task conversations');
}, 30_000);

it('allows watched PRs to open Code & Tests before a review or task exists', async () => {
  const result = await fixture.run<string>({
    body: "const results = await window.webContents.executeJavaScript('PullScreenTests.runMainScreen(false, true)');",
  });
  expect(result).toContain('Watched PRs offer exploration');
}, 30_000);

it('edits PR titles with cancellation, validation, retry, and selection isolation', async () => {
  const result = await fixture.run<string>({
    width: 650, height: 700,
    body: "const results = await window.webContents.executeJavaScript('PullScreenTests.runTitleEdit()');",
  });
  expect(result).toContain('Inline title edits');
}, 30_000);

it('acknowledges a viewed PR when leaving zoom without closing the regular detail pane', async () => {
  const result = await fixture.run<string>({
    body: "const results = await window.webContents.executeJavaScript('PullScreenTests.runMainScreen()');",
  });
  expect(result).toContain('Main PR page');
}, 30_000);

it('opens Changes in local and linked review workspaces while keeping Summary independent', async () => {
  const results = await fixture.run<string[]>({
    width: 1450, height: 950,
    body: `const results = await window.webContents.executeJavaScript('(async () => [await PullScreenTests.runReviewWorkspace(), await PullScreenTests.runReviewWorkspace(true), await PullScreenTests.runOpenReviewWorkspaceStream()])()');`,
  });
  expect(results).toHaveLength(3);
}, 30_000);

it('follows the review on Changes when a review opens over the task list', async () => {
  const result = await fixture.run<string>({
    width: 1250, height: 850,
    body: "const results = await window.webContents.executeJavaScript('PullScreenTests.runOverlayChangesStream()');",
  });
  expect(result).toContain('follows its review on Changes');
}, 30_000);

it('looks up exact PR numbers and shows newly discovered PRs without repeating list reads', async () => {
  const result = await fixture.run<string>({
    body: "const results = await window.webContents.executeJavaScript('PullScreenTests.runListSearch()');",
  });
  expect(result).toContain('newly discovered PRs');
}, 30_000);

it.each(['sync', 'attention'])('finishes initial PR loading when %s supersedes it and ignores stale workspace responses', async trigger => {
  const result = await fixture.run<string>({
    body: `const results = await window.webContents.executeJavaScript('PullScreenTests.runListLoadingRace("${trigger}")');`,
  });
  expect(result).toBe('Latest list reads finish loading, recover from failure and isolate workspaces');
}, 30_000);

it('hosts a task’s or a review’s private threads on the pull-request page for other people’s comments', async () => {
  const results = await fixture.run<string[]>({
    width: 1250, height: 850,
    body: `const results = await window.webContents.executeJavaScript('(async () => [await PullScreenTests.runPrivateThreads(), await PullScreenTests.runPrivateThreads("review")])()');`,
  });
  expect(results).toHaveLength(2);
}, 30_000);

it('prepares comment context on send, preserves setup drafts and isolates late results', async () => {
  const results = await fixture.run<string[]>({
    width: 1250, height: 850,
    body: `const results = await window.webContents.executeJavaScript('(async () => { const results = []; for (const mode of ["ready", "failure", "model", "workspace", "switch"]) results.push(await PullScreenTests.runPreparePrivateThreads(mode)); return results; })()');`,
  });
  expect(results).toHaveLength(5);
}, 30_000);

it.each([
  ['loads, retries and isolates check logs', 'runCheckLogs', 'Open failed CI in the browser, load and retry inline logs, and discard stale attempts'],
  ['publishes review drafts and preserves failed submissions', 'runReviewSheet', 'Submit review sends every review through the draft and keeps text with an unknown outcome as sent'],
  ['retains selected PRs and loaded rows across refreshes', 'runSelectionStability', 'Refresh and sync keep a pull request opened from a later page or a link'],
])('%s', async (_name, scenario, expected) => {
  const result = await fixture.run<string>({
    body: `window.webContents.debugger.attach('1.3');
      await window.webContents.debugger.sendCommand('Emulation.setFocusEmulationEnabled', { enabled: true });
      const results = await window.webContents.executeJavaScript('PullScreenTests.${scenario}()');`,
  });
  expect(result).toBe(expected);
}, 30_000);
