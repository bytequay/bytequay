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

it('preserves task reading position in Chromium while paging and following', async () => {
  const results = await runBrowserFixture<ScenarioResult[]>({
    prefix: 'bytequay-history-scroll-',
    entry: 'src/agents/fixtures/historyScrollBrowser.tsx',
    global: 'HistoryScrollTests',
    styles: true,
    width: 800,
    height: 600,
    blockNetwork: false,
    preload: `const { contextBridge, ipcRenderer } = require('electron'); contextBridge.exposeInMainWorld('historyTestWheel', event => ipcRenderer.invoke('history-wheel', event));`,
    setup: `
      window.webContents.debugger.attach('1.3');
      ipcMain.handle('history-wheel', (_event, input) => window.webContents.debugger.sendCommand('Input.dispatchMouseEvent', { type: 'mouseWheel', deltaX: 0, ...input }));
      window.webContents.on('console-message', (_event, details) => { if (details.level === 'error') console.error(details.message); });
    `,
  });
  expect(results).toHaveLength(13);
  expect(results.filter(result => result.error !== undefined)).toEqual([]);
}, deadline(30_000));
