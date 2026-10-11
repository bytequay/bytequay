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
import { buildBrowserFixture } from '../fixtures/browserFixture';

let fixture: Awaited<ReturnType<typeof buildBrowserFixture>>;
beforeAll(async () => {
  fixture = await buildBrowserFixture({
    prefix: 'bytequay-reviewRequestCard-', entry: 'src/agents/fixtures/reviewRequestCardBrowser.tsx', global: 'ReviewRequestTests', styles: true,
  });
}, 30_000);
afterAll(() => fixture?.dispose());

it('folds saved developer fix prompts and opens their original findings, including queued steering', async () => {
  const results = await fixture.run<{ width: number; delivery: string }[]>({
    body: `
      const results = [];
      for (const width of [760, 320]) {
        window.setContentSize(width, 900);
        for (const delivery of ['TURN', 'STEER']) {
          results.push(await window.webContents.executeJavaScript('ReviewRequestTests.fixRequest(' + JSON.stringify(delivery) + ')'));
        }
      }
    `,
  });
  expect(results).toEqual([{ width: 760, delivery: 'TURN' }, { width: 760, delivery: 'STEER' },
    { width: 320, delivery: 'TURN' }, { width: 320, delivery: 'STEER' }]);
}, 30_000);

it('discloses complete review requests with keyboard and pointer without overflow or side effects in Chromium', async () => {
  const results = await fixture.run<{ width: number; open: boolean }[]>({
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

it('folds generated summary prompts into compact request cards with complete expandable instructions', async () => {
  const results = await fixture.run<{ width: number; compact: boolean }[]>({
    body: `
      const results = [];
      for (const width of [760, 320]) {
        window.setContentSize(width, 900);
        results.push(await window.webContents.executeJavaScript('ReviewRequestTests.generatedPrompt()'));
        results.push(await window.webContents.executeJavaScript('ReviewRequestTests.generatedPrompt(true)'));
      }
    `,
  });
  expect(results).toEqual([{ width: 760, compact: true }, { width: 760, compact: true }, { width: 320, compact: true }, { width: 320, compact: true }]);
}, 30_000);
