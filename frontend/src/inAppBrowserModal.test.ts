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
import { testBrowserScenarios } from './fixtures/browserScenarios';

testBrowserScenarios([{
  name: 'keeps native browser pages behind active app modals',
  expected: 4,
  timeout: 40_000,
  fixture: {
    prefix: 'bytequay-browser-modal-', entry: 'src/fixtures/inAppBrowserModal.tsx', global: 'browserModalFixture', timeoutMs: 35_000,
    preload: "const {contextBridge,ipcRenderer}=require('electron'); contextBridge.exposeInMainWorld('pressBrowserModalEscape',()=>ipcRenderer.invoke('test:escape'));",
    setup: "ipcMain.handle('test:escape', event => { event.sender.sendInputEvent({type:'keyDown',keyCode:'Escape'}); event.sender.sendInputEvent({type:'keyUp',keyCode:'Escape'}); });",
    body: `window.webContents.debugger.attach('1.3');
      await window.webContents.debugger.sendCommand('Emulation.setFocusEmulationEnabled', { enabled: true });
      const results = await window.webContents.executeJavaScript('browserModalFixture.run()');`,
  },
}]);
