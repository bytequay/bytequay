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
import { contextBridge, ipcRenderer } from 'electron';
import type { IdeContext, IdeWorkbenchBridge } from './ide/contracts';

const bridge: IdeWorkbenchBridge = {
  save: (path, content, expectedHash) => ipcRenderer.invoke('ide:save', path, content, expectedHash),
  bootstrap: () => ipcRenderer.invoke('ide:bootstrap'),
  request: (method, ...args) => ipcRenderer.invoke('ide:request', method, args, navigator.userActivation.isActive),
  report: event => ipcRenderer.send('ide:report', event),
  onContext: listener => {
    const receive = (_event: Electron.IpcRendererEvent, context: IdeContext) => listener(context);
    ipcRenderer.on('ide:context', receive);
    return () => ipcRenderer.removeListener('ide:context', receive);
  },
};
contextBridge.exposeInMainWorld('ideWorkbench', bridge);
