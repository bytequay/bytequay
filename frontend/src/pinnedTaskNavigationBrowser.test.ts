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
import { runBrowserFixture, deadline } from './fixtures/browserFixture';

it('opens saved task pins over the current page through loading and failure', async () => {
  const results = await runBrowserFixture<{ name: string }[]>({
    prefix: 'bytequay-pinned-task-navigation-',
    entry: 'src/fixtures/pinnedTaskNavigationBrowser.tsx',
    global: 'PinnedTaskNavigationTests',
    styles: true,
    width: 1200,
    height: 800,
  });
  expect(results).toHaveLength(1);
}, deadline(30_000));
