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
import { runBrowserFixture } from './fixtures/browserFixture';

it('switches every app surface, status and terminal through Appearance and follows the native scheme', async () => {
  const results = await runBrowserFixture<{ dark: boolean; checked: number }[]>({
    prefix: 'bytequay-appearance-', entry: 'src/fixtures/appearanceBrowser.tsx', global: 'AppearanceTests', styles: true, charset: true,
    width: 1440, height: 1100,
    preload: "require('electron').contextBridge.exposeInMainWorld('appearance', { set: source => require('electron').ipcRenderer.invoke('appearance:set', source) });",
    setup: "ipcMain.handle('appearance:set', (_event, source) => { nativeTheme.themeSource = source; });",
    body: `await window.webContents.executeJavaScript('AppearanceTests.mount()');
      const results = [];
      for (const mode of ['Dark', 'Light', 'System']) {
        await window.webContents.executeJavaScript('AppearanceTests.choose(' + JSON.stringify(mode) + ')');
        const expected = mode === 'Dark' || (mode === 'System' && nativeTheme.shouldUseDarkColors);
        results.push(await window.webContents.executeJavaScript('AppearanceTests.check(' + expected + ')'));
        if (process.env.BYTEQUAY_THEME_CAPTURE) require('fs').writeFileSync(process.env.BYTEQUAY_THEME_CAPTURE + '-' + mode + '.png', (await window.webContents.capturePage()).toPNG());
      }`,
  });
  expect(results).toHaveLength(3);
  expect(results[0].dark).toBe(true);
  expect(results[1].dark).toBe(false);
  expect(results.every(value => value.checked >= 25)).toBe(true);
}, 30_000);
