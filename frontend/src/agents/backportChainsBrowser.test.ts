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

it('shows the saved route and operates existing chain controls', async () => {
  const results = await runBrowserFixture<ScenarioResult[]>({ prefix: 'bytequay-backports-',
    entry: 'src/agents/fixtures/backportChainsBrowser.tsx', global: 'BackportChainsTests', styles: true, charset: true,
    width: 1100, height: 950 });
  expect(results.filter(result => result.error)).toEqual([]);
  expect(results).toHaveLength(1);
}, 30_000);

it('completes backport requests on workspace Home and Tasks without sending', async () => {
  const results = await runBrowserFixture<ScenarioResult[]>({ prefix: 'bytequay-backport-composer-',
    entry: 'src/agents/fixtures/backportChainsBrowser.tsx', global: 'BackportChainsTests', styles: true, charset: true,
    width: 1100, height: 950, body: 'const results = await window.webContents.executeJavaScript("BackportChainsTests.runComposer()");' });
  expect(results.filter(result => result.error)).toEqual([]);
  expect(results).toHaveLength(1);
}, 30_000);

it('inherits Development settings and permits explicit provider changes and drafting before setup', async () => {
  const results = await runBrowserFixture<ScenarioResult[]>({ prefix: 'bytequay-provider-choice-',
    entry: 'src/agents/fixtures/backportChainsBrowser.tsx', global: 'BackportChainsTests', styles: true, charset: true,
    body: 'const results = await window.webContents.executeJavaScript("BackportChainsTests.runProviderChoice()");' });
  expect(results.filter(result => result.error)).toEqual([]);
}, 30_000);
