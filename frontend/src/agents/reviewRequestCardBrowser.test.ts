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
import { runBrowserFixture } from '../fixtures/browserFixture';

it('discloses complete review requests with keyboard and pointer without overflow or side effects in Chromium', async () => {
  const results = await runBrowserFixture<{ width: number; open: boolean }[]>({
    prefix: 'bytequay-review-requests-',
    entry: 'src/agents/fixtures/reviewRequestCardBrowser.tsx',
    global: 'ReviewRequestTests',
    styles: true,
    charset: true,
    setup: `
      let navigations = 0;
      window.webContents.on('will-navigate', event => { event.preventDefault(); navigations++; });
      window.webContents.setWindowOpenHandler(() => { navigations++; return { action: 'deny' }; });
      const run = code => window.webContents.executeJavaScript(code);
      const key = keyCode => {
        window.webContents.sendInputEvent({ type: 'keyDown', keyCode });
        window.webContents.sendInputEvent({ type: 'char', keyCode: keyCode === 'Enter' ? '\\r' : ' ' });
        window.webContents.sendInputEvent({ type: 'keyUp', keyCode });
      };
    `,
    body: `
      const results = [];
      for (const width of [760, 320]) {
        window.setContentSize(width, 900);
        await run('ReviewRequestTests.mount()');
        await run('ReviewRequestTests.verify(false)');
        key('Enter');
        await run('ReviewRequestTests.verify(true)');
        key('Space');
        await run('ReviewRequestTests.verify(false)');
        await run('ReviewRequestTests.clickToggle()');
        await run('ReviewRequestTests.verify(true)');
        await run('ReviewRequestTests.retry()');
        await run('ReviewRequestTests.verify(true, 1)');
        await run('ReviewRequestTests.openReview()');
        results.push(await run('ReviewRequestTests.verify(true, 1, 1)'));
      }
      if (navigations !== 0) throw new Error('Disclosure must not navigate or open a window');
    `,
  });
  expect(results.map(result => result.width)).toEqual([760, 320]);
  expect(results.every(result => result.open)).toBe(true);
}, 30_000);
