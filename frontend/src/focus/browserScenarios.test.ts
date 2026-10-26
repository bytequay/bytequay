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

testBrowserScenarios([{
  name: 'walks selectable companions in before locking the break and preserves work underneath', expected: 20, timeout: 45_000,
  fixture: {
    prefix: 'bytequay-focus-breaks-', entry: 'src/focus/fixtures/focusBreaksBrowser.tsx', global: 'FocusBreakTests', styles: true, width: 1120, height: 760, timeoutMs: 40_000,
    preload: "const {contextBridge,ipcRenderer}=require('electron'); contextBridge.exposeInMainWorld('pressEscape',()=>ipcRenderer.invoke('test:escape')); contextBridge.exposeInMainWorld('clickPoint',(x,y)=>ipcRenderer.invoke('test:click',x,y)); contextBridge.exposeInMainWorld('setReducedMotion',reduced=>ipcRenderer.invoke('test:motion',reduced));",
    setup: `
      ipcMain.handle('test:escape', event => { event.sender.sendInputEvent({type:'keyDown',keyCode:'Escape'}); event.sender.sendInputEvent({type:'keyUp',keyCode:'Escape'}); });
      ipcMain.handle('test:click', (event, x, y) => { event.sender.sendInputEvent({type:'mouseDown',x,y,button:'left',clickCount:1}); event.sender.sendInputEvent({type:'mouseUp',x,y,button:'left',clickCount:1}); });
      ipcMain.handle('test:motion', async (event, reduced) => {
        if (!event.sender.debugger.isAttached()) event.sender.debugger.attach('1.3');
        await event.sender.debugger.sendCommand('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-reduced-motion', value: reduced ? 'reduce' : 'no-preference' }] });
      });
    `,
  },
}]);
