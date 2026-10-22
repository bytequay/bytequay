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
import { runBrowserFixture, deadline, type ScenarioResult } from '../fixtures/browserFixture';

const SCREENSHOTS = process.env.BYTEQUAY_ASSISTANT_SCREENSHOTS !== undefined;

it('floats the Assistant over the page, pages its cards by keyboard, answers them, draws finished cards like running ones, opens at a card from its alert, sends what is on screen, draws lists, follows opens, completes tasks and creates drafted Epics', async () => {
  const results = await runBrowserFixture<ScenarioResult[]>({
    prefix: 'bytequay-assistant-',
    entry: 'src/assistant/fixtures/assistantBrowser.tsx',
    global: 'AssistantTests',
    styles: true,
    width: 1000,
    height: 800,
    timeoutMs: SCREENSHOTS ? 90_000 : undefined,
    body: `
      const results = await window.webContents.executeJavaScript('AssistantTests.run()');
      const screenshots = process.env.BYTEQUAY_ASSISTANT_SCREENSHOTS;
      if (screenshots) {
        const { mkdir, writeFile } = require('node:fs/promises');
        const { join } = require('node:path');
        await mkdir(screenshots, { recursive: true });
        window.webContents.debugger.attach('1.3');
        for (const theme of ['light', 'dark']) {
          // A hidden window does not follow the system theme; the page's media query is emulated instead.
          await window.webContents.debugger.sendCommand('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-color-scheme', value: theme }] });
          await window.webContents.executeJavaScript('AssistantTests.showPreview()');
          await writeFile(join(screenshots, 'assistant-' + theme + '.png'), (await window.webContents.capturePage()).toPNG());
          for (const style of ['ROWS', 'CARDS', 'TIMELINE', 'tasks', 'draft', 'receipts']) {
            await window.webContents.executeJavaScript('AssistantTests.showListPreview(' + JSON.stringify(style) + ')');
            await writeFile(join(screenshots, 'assistant-list-' + style.toLowerCase() + '-' + theme + '.png'), (await window.webContents.capturePage()).toPNG());
          }
        }
      }
    `,
  });
  expect(results.filter(result => result.error !== undefined)).toEqual([]);
  expect(results).toHaveLength(16);
}, deadline(SCREENSHOTS ? 120_000 : 30_000));
