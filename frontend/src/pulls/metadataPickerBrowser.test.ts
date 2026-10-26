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
    prefix: 'bytequay-metadataPicker-', entry: 'src/pulls/fixtures/metadataPickerBrowser.tsx', global: 'MetadataPickerTests', styles: true,
  });
}, deadline(30_000));
afterAll(() => fixture?.dispose());

it.each([
  ['stages people across searches and saves reviewer and assignee batches', 'runBatch'],
  ['discards unsaved selections and preserves completed reviews', 'runDismissal'],
  ['retains successful reviewer changes when a later request fails', 'runPartialFailure'],
  ['guards pending saves and isolates completion after navigating to another PR', 'runPendingSave'],
])('%s in Chromium', async (_name, scenario) => {
  const result = await fixture.run<string>({
    body: `const results = await window.webContents.executeJavaScript('MetadataPickerTests.${scenario}()');`,
  });
  expect(result).toBe('Passed');
}, deadline(30_000));
