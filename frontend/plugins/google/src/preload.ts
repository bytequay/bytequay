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
import type { IpcRenderer } from 'electron';
import type { GoogleBridge } from './contracts';

export function createGoogleBridge(ipcRenderer: Pick<IpcRenderer, 'invoke'>): GoogleBridge {
  return {
    getGeminiSetupStatus: () => ipcRenderer.invoke('gemini:status'),
    saveGeminiSetupKey: key => ipcRenderer.invoke('gemini:save', key),
    removeGeminiSetupKey: () => ipcRenderer.invoke('gemini:remove'),
    getGoogleChat: profile => ipcRenderer.invoke('google-chat:status', profile),
    promptGoogleChat: (profile, text) => ipcRenderer.invoke('google-chat:prompt', profile, text),
    approveGoogleChat: (profile, id, option) => ipcRenderer.invoke('google-chat:approve', profile, id, option),
    stopGoogleChat: profile => ipcRenderer.invoke('google-chat:stop', profile),
    clearGoogleChat: profile => ipcRenderer.invoke('google-chat:clear', profile),
    addGoogleAccount: () => ipcRenderer.invoke('google:accounts:add'),
    selectGoogleAccount: profile => ipcRenderer.invoke('google:account:select', profile),
    archiveGoogleMail: (profile, messageId) => ipcRenderer.invoke('google:archive', profile, messageId),
    sendGoogleMail: request => ipcRenderer.invoke('google:send', request),
    getGoogleStatus: () => ipcRenderer.invoke('google:status'),
    installGoogleConnector: () => ipcRenderer.invoke('google:install'),
    connectGoogle: () => ipcRenderer.invoke('google:connect'),
    disconnectGoogle: () => ipcRenderer.invoke('google:disconnect'),
    cancelGoogleOperation: () => ipcRenderer.invoke('google:cancel'),
    setGoogleAgentAccess: enabled => ipcRenderer.invoke('google:agent-access', enabled),
    refreshGoogleMail: (query, pageToken) => ipcRenderer.invoke('google:mail', query, pageToken),
    refreshGoogleCalendar: (calendarId, timeMin, timeMax) => ipcRenderer.invoke('google:calendar', calendarId, timeMin, timeMax),
  };
}
