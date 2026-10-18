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
import { runBrowserFixture, deadline } from '../fixtures/browserFixture';

it('zooms, drags, resizes and dismisses Goals without moving the page or losing drafts', async () => {
  expect(await runBrowserFixture<boolean>({
    prefix: 'bytequay-goal-window-', entry: 'src/goals/fixtures/goalWindowBrowser.tsx', global: 'GoalWindowTests',
    styles: true, charset: true, width: 1440, height: 1000, timeoutMs: 60_000,
    preload: `const { contextBridge, ipcRenderer } = require('electron');
      contextBridge.exposeInMainWorld('goalMouse', (event) => ipcRenderer.invoke('goal-mouse', event));`,
    setup: `ipcMain.handle('goal-mouse', (_event, input) => window.webContents.sendInputEvent(input));`,
  })).toBe(true);
}, deadline(90_000));
