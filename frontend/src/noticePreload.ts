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
import type { NoticeBridge, NoticeSnapshot } from './notices/contracts';

const bridge: NoticeBridge = {
  read: () => ipcRenderer.invoke('notices:read'),
  subscribe: listener => {
    const receive = (_event: unknown, state: NoticeSnapshot) => listener(state);
    ipcRenderer.on('notices:changed', receive);
    return () => { ipcRenderer.removeListener('notices:changed', receive); };
  },
  dismiss: id => ipcRenderer.invoke('notices:dismiss', id),
  open: id => ipcRenderer.invoke('notices:open', id),
  resize: height => ipcRenderer.send('notices:resize', height),
};
contextBridge.exposeInMainWorld('noticeBridge', bridge);
