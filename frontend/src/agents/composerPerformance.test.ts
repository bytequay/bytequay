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
    prefix: 'bytequay-composer-performance-', entry: 'src/agents/fixtures/composerPerformanceBrowser.tsx',
    global: 'ComposerPerformanceTests', styles: true, width: 900, height: 700,
  });
}, deadline(45_000));
afterAll(() => fixture?.dispose());

it('keeps typing and sending independent of historical event projection in Chromium', async () => {
  const results = await fixture.run<ScenarioResult[]>();
  expect(results).toHaveLength(2);
  expect(results.filter(result => result.error !== undefined)).toEqual([]);
}, deadline(45_000));

it('shows the sent message and first logs while the start request is still pending in Chromium', async () => {
  const result = await fixture.run<string>({
    body: "const results = await window.webContents.executeJavaScript('ComposerPerformanceTests.sendProgress()');",
  });
  expect(result).toContain('immediate history loading and no duplicate message');
}, deadline(30_000));

it('shows a new task first message and startup progress while creation is still pending in Chromium', async () => {
  const result = await fixture.run<string>({
    body: "const results = await window.webContents.executeJavaScript('ComposerPerformanceTests.firstMessageProgress()');",
  });
  expect(result).toContain('before creation responds, then open the accepted task once');
}, deadline(30_000));

it('shows submission failures without waiting for reconciliation and safely retries lost responses in Chromium', async () => {
  const results = await fixture.run<ScenarioResult[]>({
    body: "const results = await window.webContents.executeJavaScript('ComposerPerformanceTests.sendFailures()');",
  });
  expect(results).toHaveLength(3);
  expect(results.filter(result => result.error !== undefined)).toEqual([]);
}, deadline(30_000));

it('keeps live sending bounded across large histories, page rollover and bursts in Chromium', async () => {
  const results = await fixture.run<ScenarioResult[]>({
    body: "const results = await window.webContents.executeJavaScript('ComposerPerformanceTests.sendHistoryPerformance(3)');",
    timeoutMs: 45_000,
  });
  console.info('Send history measurements:', JSON.stringify(results));
  expect(results).toHaveLength(6);
  expect(results.filter(result => result.error !== undefined)).toEqual([]);
}, deadline(60_000));

it('shows first-message errors during recovery and replays the same creation identity in Chromium', async () => {
  const results = await fixture.run<ScenarioResult[]>({
    body: "const results = await window.webContents.executeJavaScript('ComposerPerformanceTests.firstMessageFailures()');",
  });
  expect(results).toHaveLength(4);
  expect(results.filter(result => result.error !== undefined)).toEqual([]);
}, deadline(30_000));

it('does not turn an uncertain or already-delivered send into a fresh draft after navigation in Chromium', async () => {
  const results = await fixture.run<ScenarioResult[]>({
    body: "const results = await window.webContents.executeJavaScript('ComposerPerformanceTests.sendAfterNavigation()');",
  });
  expect(results).toHaveLength(3);
  expect(results.filter(result => result.error !== undefined)).toEqual([]);
}, deadline(30_000));
