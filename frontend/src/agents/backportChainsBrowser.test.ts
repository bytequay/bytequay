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
    prefix: 'bytequay-backportChains-', entry: 'src/agents/fixtures/backportChainsBrowser.tsx', global: 'BackportChainsTests', styles: true,
  });
}, deadline(30_000));
afterAll(() => fixture?.dispose());

it('shows the saved route and operates existing chain controls', async () => {
  const results = await fixture.run<ScenarioResult[]>({
    width: 1100, height: 950 });
  expect(results).toEqual([{ name: 'backport chain groups, navigation, selection, Stop and Resume' }]);
}, deadline(30_000));

it('orders backport endpoints regardless of click or paste order', async () => {
  const results = await fixture.run<ScenarioResult[]>({
    width: 1100, height: 950, body: 'const results = await window.webContents.executeJavaScript("BackportChainsTests.runSelectionOrder()");' });
  expect(results).toEqual([{ name: 'Backport ranges preserve chronological order for clicks and pasted endpoints' }]);
}, deadline(30_000));

it('completes backport requests on workspace Home and Tasks without sending', async () => {
  const results = await fixture.run<ScenarioResult[]>({
    width: 1100, height: 950, body: 'const results = await window.webContents.executeJavaScript("BackportChainsTests.runComposer()");' });
  expect(results).toEqual([{ name: 'Backport discovery, completion and shortcuts on Home and Tasks only' }]);
}, deadline(30_000));

it('inherits Development settings and permits explicit provider changes and drafting before setup', async () => {
  const results = await fixture.run<ScenarioResult[]>({
    body: 'const results = await window.webContents.executeJavaScript("BackportChainsTests.runProviderChoice()");' });
  expect(results).toEqual([{ name: 'Optional provider accounts and editable Backport drafts on Home and Tasks' }]);
}, deadline(30_000));
