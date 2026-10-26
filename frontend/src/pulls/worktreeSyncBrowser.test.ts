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
    prefix: 'bytequay-worktreeSync-', entry: 'src/pulls/code-tests/fixtures/worktreeSyncBrowser.tsx', global: 'WorktreeSyncTests', styles: true, width: 1100, height: 900,
  });
}, deadline(30_000));
afterAll(() => fixture?.dispose());

it.each([false, true])('confirms IDE startup for an existing worktree (task: %s)', async taskHost => {
  const result = await fixture.run<string>({
    body: `window.webContents.debugger.attach('1.3');
      await window.webContents.debugger.sendCommand('Emulation.setFocusEmulationEnabled', { enabled: true });
      const results = await window.webContents.executeJavaScript('WorktreeSyncTests.runStartup(${taskHost})');` });
  expect(result).toContain('Startup requires confirmation');
}, deadline(30_000));

it('creates an exploration worktree on demand and opens it without starting a review', async () => {
  const result = await fixture.run<string>({
    body: "const results = await window.webContents.executeJavaScript('WorktreeSyncTests.runExploration()');" });
  expect(result).toContain('opens the prepared checkout');
}, deadline(30_000));

it('retains the editor and unsaved-change guard when a review completes', async () => {
  const result = await fixture.run<string>();
  expect(result).toBe('Completing a review retains the editor and its unsaved-change guard');
}, deadline(30_000));
