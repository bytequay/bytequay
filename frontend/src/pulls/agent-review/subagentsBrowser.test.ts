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
import { buildBrowserFixture, deadline } from '../../fixtures/browserFixture';

let fixture: Awaited<ReturnType<typeof buildBrowserFixture>>;
beforeAll(async () => {
  fixture = await buildBrowserFixture({
    prefix: 'bytequay-subagents-', entry: 'src/pulls/agent-review/fixtures/subagentsBrowser.tsx', global: 'SubagentsTests', styles: true, width: 1550, height: 950,
  });
}, deadline(30_000));
afterAll(() => fixture?.dispose());

it.each([false, true])('opens review subagents and passage discussions in the shared right panel in Chromium (local: %s)', async local => {
  const result = await fixture.run<string>({
    body: `const results = await window.webContents.executeJavaScript('SubagentsTests.run(${local})');` });
  expect(result).toContain('live, completed and nested subagents');
  expect(result).toContain('selected answers open reusable discussions');
}, deadline(30_000));
