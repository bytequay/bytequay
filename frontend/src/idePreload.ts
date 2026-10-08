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
import type { CodeQueryRequest } from './ide/codeQuery';
import type { IdeContext, IdeWorkbenchBridge } from './ide/contracts';

const bridge: IdeWorkbenchBridge = {
  terminal: {
    openCodeTerminal: (_host, cols, rows) => ipcRenderer.invoke('ide:terminal-open', cols, rows),
    writeReviewTerminal: (id, data) => ipcRenderer.send('terminal:write', id, data),
    resizeReviewTerminal: (id, cols, rows) => ipcRenderer.send('terminal:resize', id, cols, rows),
    onReviewTerminalEvent: listener => {
      const data = (_event: Electron.IpcRendererEvent, value: { terminalId: string; data: string }) => listener(value);
      const exit = (_event: Electron.IpcRendererEvent, value: { terminalId: string; exitCode: number }) => listener(value);
      ipcRenderer.on('terminal:data', data); ipcRenderer.on('terminal:exit', exit);
      return () => { ipcRenderer.removeListener('terminal:data', data); ipcRenderer.removeListener('terminal:exit', exit); };
    },
  },
  startExecution: request => ipcRenderer.invoke('ide:execution', { operation: 'start', request }),
  outputExecution: (id, request) => ipcRenderer.invoke('ide:execution', { operation: 'output', id, request }),
  finishExecution: (id, request) => ipcRenderer.invoke('ide:execution', { operation: 'finish', id, request }),
  stopExecution: id => ipcRenderer.invoke('ide:execution', { operation: 'stop', id }),
  queryReady: () => ipcRenderer.send('ide:query-ready'),
  queryReply: (id, reply) => ipcRenderer.send('ide:query-reply', id, reply),
  onQuery: listener => {
    const receive = (_event: Electron.IpcRendererEvent, request: CodeQueryRequest & { cancel?: boolean }) => listener(request);
    ipcRenderer.on('ide:query', receive);
    return () => ipcRenderer.removeListener('ide:query', receive);
  },
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
