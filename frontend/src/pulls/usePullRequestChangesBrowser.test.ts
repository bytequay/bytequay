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
import { runBrowserFixture, deadline } from '../fixtures/browserFixture';

it('loads selected PR commits and rejects stale comparison responses in Chromium', async () => {
  const results = await runBrowserFixture<string[]>({
    prefix: 'bytequay-pull-changes-',
    entry: 'src/pulls/fixtures/pullRequestChangesBrowser.tsx',
    global: 'PullChangesTests',
    styles: true,
    body: `
      window.webContents.debugger.attach('1.3');
      await window.webContents.debugger.sendCommand('Emulation.setFocusEmulationEnabled', { enabled: true });
      const results = await window.webContents.executeJavaScript('PullChangesTests.run()');
    `,
  });
  expect(results).toEqual([
    'Select a commit and return to all commits',
    'Ignore out-of-order comparison responses',
    'Reset selections across PR and head changes',
    'Keep failed and invalid comparisons distinct from all commits',
    'Defer hidden reads until activated',
    'Select a published commit before first opening Changes',
    'Reject unavailable commits and allow a valid published range',
    'Reject removed commits after a force push',
    'Refresh clears unavailable published commit selection',
    'Show commit messages without changing the selection',
  ]);
}, deadline(30_000));
