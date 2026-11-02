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

it('merges a ready My work pull request directly with its revision and account in Chromium', async () => {
  const results = await runBrowserFixture<ScenarioResult[]>({
    prefix: 'bytequay-my-work-',
    entry: 'src/home/fixtures/myWorkBrowser.tsx',
    global: 'MyWorkTests',
    styles: true,
    width: 1200,
    height: 700,
    body: `
      const results = await window.webContents.executeJavaScript('MyWorkTests.run()');
      const screenshots = process.env.BYTEQUAY_MY_WORK_SCREENSHOTS;
      if (screenshots) {
        const { mkdir, writeFile } = require('node:fs/promises');
        const { join } = require('node:path');
        await mkdir(screenshots, { recursive: true });
        await window.webContents.executeJavaScript('MyWorkTests.showPreview()');
        window.webContents.debugger.attach('1.3');
        for (const theme of ['light', 'dark']) {
          await window.webContents.debugger.sendCommand('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-color-scheme', value: theme }] });
          await new Promise(resolve => setTimeout(resolve, 100));
          await writeFile(join(screenshots, 'my-work-' + theme + '.png'), (await window.webContents.capturePage()).toPNG());
        }
      }
    `,
  });
  expect(results).toHaveLength(5);
  expect(results.filter(result => result.error !== undefined)).toEqual([]);
}, deadline(30_000));
