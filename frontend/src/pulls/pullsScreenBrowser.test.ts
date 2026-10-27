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
import { buildBrowserFixture, deadline } from '../fixtures/browserFixture';

let fixture: Awaited<ReturnType<typeof buildBrowserFixture>>;
beforeAll(async () => {
  fixture = await buildBrowserFixture({
    prefix: 'bytequay-pullsScreen-', entry: 'src/pulls/fixtures/pullsScreenBrowser.tsx', global: 'PullScreenTests', styles: true,
  });
}, deadline(30_000));
afterAll(() => fixture?.dispose());

it.each([
  ['preserves review and merge sheet focus, dismissal, and in-flight guards',
    'runActionSheetDismissal()', 'preserve dismissal and busy behavior', {}],
  ['shows merge queue removal details and opens failed jobs',
    'runMergeQueueRemoval()', 'opens exact failed jobs', { width: 1280, height: 800 }],
  ['schedules and cancels a queued merge while CI is running',
    'runMergeWhenReady()', 'schedules and cancels during CI while direct rebase stays blocked', {}],
  ['opens distinct development and review tasks and finishes creation after leaving the PR',
    'runAssignedPrActions()', 'Open distinct development and review task conversations', {}],
  ['shows the Epic of a PR as a card on wide pages and a link on narrow ones',
    'runPullRequestEpic()', 'Show the Epic of a PR beside or inside the page', {}],
  ['allows watched PRs to open Tears of time before a review or task exists',
    'runMainScreen(false, true)', 'Watched PRs offer exploration', {}],
  ['edits PR titles with cancellation, validation, retry, and selection isolation',
    'runTitleEdit()', 'Inline title edits', { width: 650, height: 700 }],
  ['acknowledges a viewed PR when leaving zoom without closing the regular detail pane',
    'runMainScreen()', 'Main PR page', {}],
  ['follows the review on Changes when a review opens over the task list',
    'runOverlayChangesStream()', 'follows its review on Changes', { width: 1250, height: 850 }],
  ['looks up exact PR numbers and shows newly discovered PRs without repeating list reads',
    'runListSearch()', 'newly discovered PRs', {}],
] as const)('%s', async (_name, scenario, expected, viewport) => {
  const result = await fixture.run<string>({
    ...viewport,
    body: `const results = await window.webContents.executeJavaScript('PullScreenTests.${scenario}');`,
  });
  expect(result).toContain(expected);
}, deadline(30_000));

it('defaults linked reviews to PR and local reviews to Changes while keeping Summary independent', async () => {
  const results = await fixture.run<string[]>({
    width: 1450, height: 950,
    body: `const results = await window.webContents.executeJavaScript('(async () => [await PullScreenTests.runReviewWorkspace(), await PullScreenTests.runReviewWorkspace(true), await PullScreenTests.runOpenReviewWorkspaceStream()])()');`,
  });
  expect(results).toHaveLength(3);
}, deadline(30_000));

it.each(['sync', 'attention'])('finishes initial PR loading when %s supersedes it and ignores stale workspace responses', async trigger => {
  const result = await fixture.run<string>({
    body: `const results = await window.webContents.executeJavaScript('PullScreenTests.runListLoadingRace("${trigger}")');`,
  });
  expect(result).toBe('Latest list reads finish loading, recover from failure and isolate workspaces');
}, deadline(30_000));

it('hosts a task’s or a review’s private threads on the pull-request page for other people’s comments', async () => {
  const results = await fixture.run<string[]>({
    width: 1250, height: 850,
    body: `const results = await window.webContents.executeJavaScript('(async () => [await PullScreenTests.runPrivateThreads(), await PullScreenTests.runPrivateThreads("review")])()');`,
  });
  expect(results).toHaveLength(2);
}, deadline(30_000));

it.each([false, true])('retains Ask agent history after a development task and local review are created (task panel: %s)', async taskPanel => {
  const results = await fixture.run<string[]>({
    width: 1250, height: 850,
    body: `const results = await window.webContents.executeJavaScript('(async () => [await PullScreenTests.runPrivateThreads("history", ${taskPanel}), await PullScreenTests.runPrivateThreads("closed-history", ${taskPanel})])()');`,
  });
  expect(results).toEqual([
    'The pull-request page restores earlier Ask agent history and follows up through its original review',
    'The pull-request page retains closed review history read-only',
  ]);
}, deadline(30_000));

it('prepares comment context on send, preserves setup drafts and isolates late results', async () => {
  const results = await fixture.run<string[]>({
    width: 1250, height: 850,
    body: `const results = await window.webContents.executeJavaScript('(async () => { const results = []; for (const mode of ["ready", "failure", "model", "workspace", "switch"]) results.push(await PullScreenTests.runPreparePrivateThreads(mode)); return results; })()');`,
  });
  expect(results).toHaveLength(5);
}, deadline(30_000));

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
}, deadline(30_000));
