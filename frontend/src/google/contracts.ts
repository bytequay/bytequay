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
export type GoogleMail = {
  id: string; threadId: string; subject: string; from: string; to: string;
  date: string; snippet: string; body: string; unread: boolean;
};
export type GoogleEvent = {
  id: string; summary: string; description: string; start: string; end: string; allDay: boolean; url: string;
};
export type GoogleSnapshot = {
  accountId: string; email: string; name: string; agentAccess: boolean;
  mail: GoogleMail[]; query: string; nextPageToken: string; mailSavedAt: string;
  calendars: { id: string; summary: string }[]; events: GoogleEvent[];
  calendarId: string; timeMin: string; timeMax: string; calendarSavedAt: string;
};
export type GoogleAccount = { profile: string; accountId: string; email: string; name: string };
export type GoogleStatus = { accounts: GoogleAccount[]; profile: string; installed: boolean; operation: string; error: string | null; snapshot: GoogleSnapshot | null };
export type GeminiSetupStatus = { configured: boolean; installed: boolean };
export type GoogleBridge = {
  getGeminiSetupStatus: () => Promise<GeminiSetupStatus>;
  saveGeminiSetupKey: (key: string) => Promise<GeminiSetupStatus>;
  removeGeminiSetupKey: () => Promise<GeminiSetupStatus>;
  getGoogleChat: (profile: string) => Promise<GoogleChatState>;
  promptGoogleChat: (profile: string, text: string) => Promise<GoogleChatState>;
  approveGoogleChat: (profile: string, id: string, option: string) => Promise<GoogleChatState>;
  stopGoogleChat: (profile: string) => Promise<GoogleChatState>;
  clearGoogleChat: (profile: string) => Promise<GoogleChatState>;
  addGoogleAccount: () => Promise<GoogleStatus>;
  selectGoogleAccount: (profile: string) => Promise<GoogleStatus>;
  archiveGoogleMail: (profile: string, messageId: string) => Promise<GoogleStatus>;
  sendGoogleMail: (request: GoogleSendRequest) => Promise<GoogleStatus>;
  getGoogleStatus: () => Promise<GoogleStatus>;
  installGoogleConnector: () => Promise<GoogleStatus>;
  connectGoogle: () => Promise<GoogleStatus>;
  disconnectGoogle: () => Promise<GoogleStatus>;
  cancelGoogleOperation: () => Promise<GoogleStatus>;
  setGoogleAgentAccess: (enabled: boolean) => Promise<GoogleStatus>;
  refreshGoogleMail: (query: string, pageToken: string) => Promise<GoogleStatus>;
  refreshGoogleCalendar: (calendarId: string, timeMin: string, timeMax: string) => Promise<GoogleStatus>;
};

export type GoogleSendRequest = { profile: string; to: string; subject: string; body: string; replyId: string };
export type GoogleChatState = {
  messages: { role: 'user' | 'assistant'; text: string }[];
  busy: boolean; activity: string; error: string | null;
  approval: { id: string; title: string; detail: string; options: { id: string; label: string }[] } | null;
};

export const GOOGLE_CHANGED = 'bytequay:google-changed';

export function googleLink(raw: string): string | null {
  try {
    const url = new URL(raw);
    return url.protocol === 'https:' && ['calendar.google.com', 'www.google.com'].includes(url.hostname)
      && !url.username && !url.password && !url.port ? url.href : null;
  } catch { return null; }
}

export function gmailLink(mail: GoogleMail, email: string): string {
  return `https://mail.google.com/mail/?authuser=${encodeURIComponent(email)}#all/${encodeURIComponent(mail.threadId || mail.id)}`;
}

export function agendaRange(date: string, days = 7): { timeMin: string; timeMax: string } {
  const start = new Date(`${date}T00:00:00`);
  const end = new Date(start);
  end.setDate(end.getDate() + days);
  return { timeMin: start.toISOString(), timeMax: end.toISOString() };
}

export function localDate(): string {
  const date = new Date();
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}

export function eventTime(event: GoogleEvent): string {
  if (event.allDay) return `${event.start} · All day`;
  const start = new Date(event.start);
  const end = new Date(event.end);
  return `${start.toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' })} – ${end.toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' })}`;
}
