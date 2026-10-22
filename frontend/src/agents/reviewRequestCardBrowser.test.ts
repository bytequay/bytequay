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
    prefix: 'bytequay-reviewRequestCard-', entry: 'src/agents/fixtures/reviewRequestCardBrowser.tsx', global: 'ReviewRequestTests', styles: true,
  });
}, deadline(30_000));
afterAll(() => fixture?.dispose());

it('folds saved developer fix prompts and opens their original findings, including queued steering', async () => {
  const results = await fixture.run<{ width: number; delivery: string; snapshot: boolean }[]>({
    body: `
      const results = [];
      for (const width of [760, 320]) {
        window.setContentSize(width, 900);
        await untilWidth(width);
        for (const delivery of ['TURN', 'STEER']) {
          for (const snapshot of [false, true]) {
            results.push(await window.webContents.executeJavaScript('ReviewRequestTests.fixRequest(' + JSON.stringify(delivery) + ', ' + snapshot + ')'));
          }
        }
      }
    `,
  });
  expect(results).toEqual([760, 320].flatMap(width => ['TURN', 'STEER'].flatMap(delivery => [false, true].map(snapshot => ({ width, delivery, snapshot })))));
}, deadline(30_000));

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
        await untilWidth(width);
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
}, deadline(30_000));

it('keeps development follow-ups compact with their authorization scope and no sent prompt', async () => {
  const results = await fixture.run<{ width: number; kind: string; snapshot: boolean; existing: boolean }[]>({
    body: `
      const results = [];
      for (const width of [760, 320]) {
        window.setContentSize(width, 900);
        await untilWidth(width);
        for (const kind of ['AUTO_COMMIT', 'REFINE_SUMMARY', 'CREATE_DRAFT_PR']) {
          for (const snapshot of [false, true]) {
            for (const existing of kind === 'CREATE_DRAFT_PR' ? [false, true] : [false]) {
              results.push(await window.webContents.executeJavaScript('ReviewRequestTests.generatedPrompt(' + JSON.stringify(kind) + ', ' + snapshot + ', ' + existing + ')'));
            }
          }
        }
      }
    `,
  });
  expect(results).toEqual([760, 320].flatMap(width => ['AUTO_COMMIT', 'REFINE_SUMMARY', 'CREATE_DRAFT_PR'].flatMap(kind =>
    [false, true].flatMap(snapshot => (kind === 'CREATE_DRAFT_PR' ? [false, true] : [false]).map(existing => ({ width, kind, snapshot, existing }))))));
}, deadline(30_000));

it('keeps current and historical review prompts out of the request card', async () => {
  const results = await fixture.run<{ width: number; snapshot: boolean }[]>({
    body: `
      const results = [];
      for (const width of [760, 320]) {
        window.setContentSize(width, 900);
        await untilWidth(width);
        for (const snapshot of [false, true]) {
          results.push(await window.webContents.executeJavaScript('ReviewRequestTests.generatedReview(' + snapshot + ')'));
        }
      }
    `,
  });
  expect(results).toEqual([760, 320].flatMap(width => [false, true].map(snapshot => ({ width, snapshot }))));
}, deadline(30_000));

it('keeps current and historical CI requests compact without the sent prompt while preserving user steering', async () => {
  const results = await fixture.run<{ width: number; snapshot: boolean }[]>({
    body: `
      const results = [];
      for (const width of [760, 320]) {
        window.setContentSize(width, 900);
        await untilWidth(width);
        for (const snapshot of [false, true]) {
          results.push(await window.webContents.executeJavaScript('ReviewRequestTests.generatedCi(' + snapshot + ')'));
        }
      }
    `,
  });
  expect(results).toEqual([760, 320].flatMap(width => [false, true].map(snapshot => ({ width, snapshot }))));
}, deadline(30_000));

it('keeps private code and learning discussions compact without their sent prompts', async () => {
  const results = await fixture.run<{ width: number; learning: boolean; snapshot: boolean }[]>({
    body: `
      const results = [];
      for (const width of [760, 320]) {
        window.setContentSize(width, 900);
        await untilWidth(width);
        for (const learning of [false, true]) {
          for (const snapshot of [false, true]) {
            results.push(await window.webContents.executeJavaScript('ReviewRequestTests.localDiscussion(' + learning + ', ' + snapshot + ')'));
          }
        }
      }
    `,
  });
  expect(results).toEqual([760, 320].flatMap(width => [false, true].flatMap(learning => [false, true].map(snapshot => ({ width, learning, snapshot })))));
}, deadline(30_000));
