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

it('switches tasks with the keyboard and isolates Escape', async () => {
  const results = await runBrowserFixture<ScenarioResult[]>({
    prefix: 'bytequay-task-overview-', entry: 'src/agents/fixtures/taskOverviewBrowser.tsx', global: 'taskOverview', styles: true,
    width: 1100, height: 800,
    preload: `const { contextBridge, ipcRenderer } = require('electron');
      contextBridge.exposeInMainWorld('overviewKey', key => ipcRenderer.invoke('overview:key', key));`,
    setup: `ipcMain.handle('overview:key', async (_event, keyCode) => {
      window.webContents.sendInputEvent({ type: 'keyDown', keyCode });
      if (keyCode === 'Enter') window.webContents.sendInputEvent({ type: 'char', keyCode: '\\r' });
      window.webContents.sendInputEvent({ type: 'keyUp', keyCode });
      await new Promise(resolve => setTimeout(resolve, 30));
    });`,
  });
  expect(results).toEqual([{ name: 'switches tasks with the keyboard and isolates Escape' }]);
}, deadline(30_000));
