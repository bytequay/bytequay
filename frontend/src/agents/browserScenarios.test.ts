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
import { testBrowserScenarios } from '../fixtures/browserScenarios';

testBrowserScenarios([
  {
    name: 'preserves accepted automation and continuation admission in Chromium',
    expected: 23, timeout: 30_000,
    fixture: {
      prefix: 'bytequay-development-automation-',
      entry: 'src/agents/fixtures/developmentAutomationBrowser.tsx',
      global: 'DevelopmentAutomationTests',
      styles: true,
    },
  },
  {
    name: 'offers to discuss a passage in Chromium whichever way it was selected',
    expected: 12, timeout: 30_000,
    fixture: {
      prefix: 'bytequay-discussion-selection-',
      entry: 'src/agents/fixtures/selectionBrowser.tsx',
      global: 'SelectionTests',
      styles: true,
      width: 800,
      height: 600,
      blockNetwork: false,
      setup: `window.webContents.on('console-message', event => console.error(event.message + ' @ ' + event.lineNumber));`,
    },
  },
  {
    name: 'preserves task reading position in Chromium while paging and following',
    expected: 13, timeout: 30_000,
    fixture: {
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
    },
  },
  {
    name: 'shows linked work by state with source, destination, revision and guarded Stop',
    expected: 1, timeout: 30_000,
    fixture: { prefix: 'bytequay-linked-',
      entry: 'src/agents/fixtures/linkedWorkspaceWorkBrowser.tsx', global: 'LinkedWorkspaceWorkTests', styles: true, charset: true,
      width: 1100, height: 950 },
  },
  {
    name: 'serializes workspace polling and recovers failed reads without discarding reviews',
    expected: 1, timeout: 30_000,
    fixture: { prefix: 'bytequay-work-poll-',
      entry: 'src/agents/fixtures/workspaceWorkBrowser.tsx', global: 'WorkspaceWorkTests' },
  },
]);
