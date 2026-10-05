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
import { runBrowserFixture } from '../fixtures/browserFixture';

it('reveals, pins and dismisses the Epic drawer at the sidebar edge without moving the page', async () => {
  expect(await runBrowserFixture<boolean>({
    prefix: 'bytequay-epic-drawer-', entry: 'src/workspaces/fixtures/epicDrawerBrowser.tsx', global: 'EpicDrawerTests',
    styles: true, charset: true, width: 1180, height: 760,
    preload: `const { contextBridge, ipcRenderer } = require('electron');
      contextBridge.exposeInMainWorld('epicInput', (event) => ipcRenderer.invoke('epic-input', event));`,
    setup: `ipcMain.handle('epic-input', (_event, input) => window.webContents.sendInputEvent(input));`,
  })).toBe(true);
}, 30_000);
