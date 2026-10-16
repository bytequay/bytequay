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
import { subscribe } from './ipcSubscribe';
import { contextBridge, ipcRenderer } from 'electron';
import type { IdeWorkbenchBridge } from './ide/contracts';

const bridge: IdeWorkbenchBridge = {
  terminal: {
    openCodeTerminal: (_host, cols, rows) => ipcRenderer.invoke('ide:terminal-open', cols, rows),
    writeReviewTerminal: (id, data) => ipcRenderer.send('terminal:write', id, data),
    resizeReviewTerminal: (id, cols, rows) => ipcRenderer.send('terminal:resize', id, cols, rows),
    onReviewTerminalEvent: listener => {
      const data = subscribe('terminal:data', listener);
      const exit = subscribe('terminal:exit', listener);
      return () => { data(); exit(); };
    },
  },
  startExecution: request => ipcRenderer.invoke('ide:execution', { operation: 'start', request }),
  outputExecution: (id, request) => ipcRenderer.invoke('ide:execution', { operation: 'output', id, request }),
  finishExecution: (id, request) => ipcRenderer.invoke('ide:execution', { operation: 'finish', id, request }),
  stopExecution: id => ipcRenderer.invoke('ide:execution', { operation: 'stop', id }),
  queryReady: () => ipcRenderer.send('ide:query-ready'),
  queryReply: (id, reply) => ipcRenderer.send('ide:query-reply', id, reply),
  onQuery: listener => subscribe('ide:query', listener),
  save: (path, content, expectedHash) => ipcRenderer.invoke('ide:save', path, content, expectedHash),
  bootstrap: () => ipcRenderer.invoke('ide:bootstrap'),
  request: (method, ...args) => ipcRenderer.invoke('ide:request', method, args, navigator.userActivation.isActive),
  report: event => ipcRenderer.send('ide:report', event),
  onContext: listener => subscribe('ide:context', listener),
};
contextBridge.exposeInMainWorld('ideWorkbench', bridge);
