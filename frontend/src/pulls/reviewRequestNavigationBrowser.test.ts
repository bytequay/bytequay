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

it('opens saved review requests in the matching Summary card in the real PR workspace', async () => {
  const results = await runBrowserFixture<ScenarioResult[]>({
    prefix: 'bytequay-review-navigation-',
    entry: 'src/pulls/agent-review/fixtures/reviewRequestNavigationBrowser.tsx',
    global: 'ReviewNavigationTests',
    styles: true,
    charset: true,
    width: 1500,
    height: 900,
    setup: `
      window.webContents.on('will-navigate', event => { event.preventDefault(); });
      window.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
    `,
    timeoutMs: 30_000,
  });
  expect(results).toHaveLength(5);
}, 40_000);
