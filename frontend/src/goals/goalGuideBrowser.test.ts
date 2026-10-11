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
    prefix: 'bytequay-goalGuide-', entry: 'src/goals/fixtures/goalGuideBrowser.tsx', global: 'GoalGuideTests', styles: true, width: 1200, height: 900,
  });
}, 30_000);
afterAll(() => fixture?.dispose());

it('colors numbers, resolves inline titles, and previews and zooms Goal references', async () => {
  expect(await fixture.run<boolean>({
    body: "const results = await window.webContents.executeJavaScript('GoalGuideTests.references()');",
  })).toBe(true);
}, 30_000);

it('clarifies a goal from free text and a suggested answer to an accepted definition', async () => {
  expect(await fixture.run<boolean>()).toBe(true);
}, 30_000);

it('guides an unsure user by suggested answers alone, by keyboard, in a narrow window', async () => {
  expect(await fixture.run<boolean>({ width: 380, height: 760,
    preload: `const { contextBridge, ipcRenderer } = require('electron');
      contextBridge.exposeInMainWorld('goalKeys', (keys) => ipcRenderer.invoke('goal-keys', keys));`,
    setup: `ipcMain.handle('goal-keys', (_event, keys) => {
      for (const key of keys) {
        window.webContents.sendInputEvent({ type: 'keyDown', keyCode: key.keyCode, modifiers: key.modifiers ?? [] });
        if (key.keyCode === 'Enter') window.webContents.sendInputEvent({ type: 'char', keyCode: key.keyCode, modifiers: key.modifiers ?? [] });
        window.webContents.sendInputEvent({ type: 'keyUp', keyCode: key.keyCode, modifiers: key.modifiers ?? [] });
      }
    });`,
    body: "const results = await window.webContents.executeJavaScript('GoalGuideTests.unsure()');",
  })).toBe(true);
}, 30_000);

it('walks the reference journey from a vague Iceberg goal to a merged pull request on its work', async () => {
  expect(await fixture.run<boolean>({
    body: "const results = await window.webContents.executeJavaScript('GoalGuideTests.reference()');",
  })).toBe(true);
}, 60_000);
