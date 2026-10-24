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
import type { IpcMain } from 'electron';
import { GeminiSetup } from './main/geminiSetup';
import { GeminiChat } from './main/geminiChat';
import type { GoogleStatus } from './contracts';

export { GeminiSetup } from './main/geminiSetup';
export { GeminiChat } from './main/geminiChat';

export type GoogleMainHost = {
  ipc: Pick<IpcMain, 'handle'>;
  home: string;
  userData: string;
  backend: <T>(path: string, init?: RequestInit) => Promise<T>;
};

const jsonInit = (method: string, body: unknown): RequestInit => ({ method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });

export function registerGoogleIpc({ ipc, home, userData, backend }: GoogleMainHost): () => void {
  const geminiSetup = new GeminiSetup(home);
  ipc.handle('gemini:status', () => geminiSetup.status());
  ipc.handle('gemini:save', async (_event, key: unknown) => { const status = await geminiSetup.save(key); googleChat.dispose(); return status; });
  ipc.handle('gemini:remove', () => { googleChat.dispose(); return geminiSetup.remove(); });
  const googleChat = new GeminiChat(userData, geminiSetup, profile =>
    backend<string[]>('/api/google/chat-command', jsonInit('POST', { profile })));
  ipc.handle('google-chat:status', (_event, profile: string) => googleChat.status(profile));
  ipc.handle('google-chat:prompt', (_event, profile: string, input: unknown) => googleChat.prompt(profile, input));
  ipc.handle('google-chat:approve', (_event, profile: string, id: string, option: string) => googleChat.approve(profile, id, option));
  ipc.handle('google-chat:stop', (_event, profile: string) => googleChat.stop(profile));
  ipc.handle('google-chat:clear', (_event, profile: string) => googleChat.clear(profile));
  ipc.handle('google:accounts:add', () => backend('/api/google/accounts', jsonInit('POST', {})));
  ipc.handle('google:account:select', (_event, profile: string) => backend('/api/google/account', jsonInit('PUT', { profile })));
  ipc.handle('google:archive', (_event, profile: string, messageId: string) => backend('/api/google/archive', jsonInit('POST', { profile, messageId })));
  ipc.handle('google:reply-recipients', (_event, profile: string, messageId: string) => backend('/api/google/reply-recipients', jsonInit('POST', { profile, messageId })));
  ipc.handle('google:send', (_event, request: unknown) => backend('/api/google/send', jsonInit('POST', request)));
  ipc.handle('google:status', () => backend('/api/google'));
  ipc.handle('google:install', () => backend('/api/google/install', { method: 'POST' }));
  ipc.handle('google:connect', () => backend('/api/google/connect', { method: 'POST' }));
  ipc.handle('google:disconnect', async () => {
    const status = await backend<GoogleStatus>('/api/google');
    googleChat.clear(status.profile);
    return backend('/api/google', { method: 'DELETE' });
  });
  ipc.handle('google:cancel', () => backend('/api/google/cancel', { method: 'POST' }));
  ipc.handle('google:agent-access', (_event, enabled: boolean) => backend('/api/google/agent-access', jsonInit('PUT', { enabled })));
  ipc.handle('google:mail', (_event, query: string, pageToken: string) => backend('/api/google/mail', jsonInit('POST', { query, pageToken })));
  ipc.handle('google:calendar', (_event, calendarId: string, timeMin: string, timeMax: string) =>
    backend('/api/google/calendar', jsonInit('POST', { calendarId, timeMin, timeMax })));
  return () => googleChat.dispose();
}
