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

const SCREENSHOTS = process.env.BYTEQUAY_TODAY_SCREENSHOTS !== undefined;

it('opens, marks, folds and suns the Today rows in Chromium', async () => {
  const results = await runBrowserFixture<ScenarioResult[]>({
    prefix: 'bytequay-today-',
    entry: 'src/home/fixtures/todayBrowser.tsx',
    global: 'TodayTests',
    styles: true,
    charset: true,
    width: 1000,
    height: 900,
    // Capturing screenshots in both themes outlasts the default lifetime of a fixture window.
    timeoutMs: SCREENSHOTS ? 90_000 : undefined,
    body: `
      const results = await window.webContents.executeJavaScript('TodayTests.run()');
      const screenshots = process.env.BYTEQUAY_TODAY_SCREENSHOTS;
      if (screenshots) {
        const { mkdir, writeFile } = require('node:fs/promises');
        const { join } = require('node:path');
        await mkdir(screenshots, { recursive: true });
        window.webContents.debugger.attach('1.3');
        for (const theme of ['light', 'dark']) {
          // A hidden window does not follow the system theme; the page's media query is emulated instead.
          await window.webContents.debugger.sendCommand('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-color-scheme', value: theme }] });
          for (const scope of ['global', 'workspace']) {
            await window.webContents.executeJavaScript('TodayTests.showPreview(' + JSON.stringify(scope) + ', ' + (scope === 'global') + ')');
            await new Promise(resolve => setTimeout(resolve, 150));
            await writeFile(join(screenshots, 'today-' + scope + '-' + theme + '.png'), (await window.webContents.capturePage()).toPNG());
          }
        }
      }
    `,
  });
  expect(results).toHaveLength(4);
  expect(results.filter(result => result.error !== undefined)).toEqual([]);
}, SCREENSHOTS ? 120_000 : 30_000);
