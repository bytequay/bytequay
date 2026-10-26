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
import { deadline, runBrowserFixture, type ScenarioResult } from '../fixtures/browserFixture';

it('dictates into the shared composer in Chromium', async () => {
  const result = await runBrowserFixture<ScenarioResult[]>({
    prefix: 'bytequay-dictation-composer-', entry: 'src/dictation/fixtures/dictationComposerBrowser.tsx', global: 'DictationComposerTests',
    styles: true, charset: true, width: 760, height: 600,
  });
  expect(result).toHaveLength(7);
  expect(result.filter(value => value.error)).toEqual([]);
}, deadline(30_000));
