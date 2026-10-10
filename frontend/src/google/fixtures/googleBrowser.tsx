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
import { createRoot, type Root } from 'react-dom/client';
import GoogleScreen from '../GoogleScreen';
import GooglePage from '../../settings/GooglePage';
import AppSidebar from '../../AppSidebar';
import { useGoogle } from '../useGoogle';
import { GOOGLE_CHANGED, type GoogleChatState, type GoogleStatus } from '../contracts';
import type { Bridge } from '../../types';
import { assert, changeInput, settle } from '../../fixtures/harness';
import '../../css/base.css';
import '../../css/settings-window.css';

let root: Root | null = null;
let status: GoogleStatus;
let searches: string[];
let opened: string[];
let chat: GoogleChatState;
const profile = '00000000-0000-0000-0000-000000000001';

export async function mountPreview(settings = false) {
  root?.unmount();
  document.body.innerHTML = '<div id="root" style="height:100vh"></div>';
  searches = []; opened = [];
  chat = { messages: [], busy: false, activity: '', error: null, approval: null };
  status = { profile, accounts: [{ profile, accountId: 'people/one', email: 'alex@example.com', name: 'Alex Chen' }], installed: true, operation: '', error: null, snapshot: {
    accountId: 'people/one', name: 'Alex Chen', email: 'alex@example.com', agentAccess: false,
    query: 'in:inbox', nextPageToken: 'next', mailSavedAt: '2026-10-09T02:00:00Z',
    mail: [
      { id: 'mail-one', threadId: 'thread-one', subject: 'Design review for next week', from: 'Maya <maya@example.com>', to: 'alex@example.com', date: 'Fri, 9 Oct 2026 09:15:00 +0800', snippet: 'Could we review the calendar integration on Monday?', body: 'Hi Alex,\n\nCould we review the calendar integration on Monday?\n\nThe agenda should show our saved events even when we’re offline. Please bring your notes on sign-in and permissions.\n\nThanks,\nMaya', unread: true },
      { id: 'mail-two', threadId: 'thread-two', subject: 'Release checklist', from: 'Sam <sam@example.com>', to: 'alex@example.com', date: 'Thu, 8 Oct 2026 16:20:00 +0800', snippet: 'The latest build is ready for review.', body: '<script>window.googleInjected = true</script><img src="https://example.test/tracker.png">Review notes', unread: false },
    ],
    calendars: [{ id: 'primary', summary: 'Personal' }], calendarId: 'primary', timeMin: '2026-10-09T00:00:00+08:00', timeMax: '2026-10-16T00:00:00+08:00', calendarSavedAt: '2026-10-09T02:00:00Z',
    events: [
      { id: 'event-one', summary: 'Design review', description: 'Review the Google integration and browser sign-in flow.', start: '2026-10-12T10:00:00+08:00', end: '2026-10-12T11:00:00+08:00', allDay: false, url: 'https://calendar.google.com/calendar/event?eid=one' },
      { id: 'event-two', summary: 'Focus day', description: '', start: '2026-10-13', end: '2026-10-14', allDay: true, url: 'javascript:alert(1)' },
    ],
  } };
  const bridge: Partial<Bridge> = {
    getGeminiSetupStatus: async () => ({ configured: false, installed: true }),
    saveGeminiSetupKey: async () => ({ configured: true, installed: true }),
    removeGeminiSetupKey: async () => ({ configured: false, installed: true }),
    getGoogleChat: async () => structuredClone(chat),
    promptGoogleChat: async (_profile, text) => { chat.messages.push({ role: 'user', text }, { role: 'assistant', text: 'Your booking has been confirmed.' }); return structuredClone(chat); },
    clearGoogleChat: async () => { chat.messages = []; return structuredClone(chat); },
    stopGoogleChat: async () => { chat.busy = false; chat.approval = null; return structuredClone(chat); },
    approveGoogleChat: async () => { chat.approval = null; return structuredClone(chat); },
    addGoogleAccount: async () => structuredClone(status),
    selectGoogleAccount: async selected => { status.profile = selected; return structuredClone(status); },
    archiveGoogleMail: async (_profile, id) => { if (status.snapshot) status.snapshot.mail = status.snapshot.mail.filter(mail => mail.id !== id); return structuredClone(status); },
    sendGoogleMail: async () => structuredClone(status),
    getGoogleStatus: async () => structuredClone(status),
    refreshGoogleMail: async (query, token) => { searches.push(`${query}:${token}`); status.error = 'Google is offline. Saved data remains available.'; return structuredClone(status); },
    refreshGoogleCalendar: async () => structuredClone(status),
    setGoogleAgentAccess: async enabled => { if (status.snapshot) status.snapshot.agentAccess = enabled; return structuredClone(status); },
    disconnectGoogle: async () => { status.snapshot = null; return structuredClone(status); },
    connectGoogle: async () => structuredClone(status),
    installGoogleConnector: async () => structuredClone(status),
    cancelGoogleOperation: async () => { status.operation = ''; return structuredClone(status); },
    openExternal: async url => { opened.push(url); },
  };
  window.bridge = bridge as Bridge;
  const host = document.getElementById('root');
  assert(host !== null, 'Fixture root exists');
  root = createRoot(host);
  root.render(settings ? <GooglePage /> : <GoogleScreen />);
  await settle(); await settle();
}

function button(label: string): HTMLButtonElement {
  const value = [...document.querySelectorAll('button')].find(item => item.textContent?.trim() === label || item.getAttribute('aria-label') === label);
  assert(value !== undefined, `Missing button ${label}`);
  return value;
}

function ConnectionNavigation() {
  const { status: current } = useGoogle();
  return <><AppSidebar page="home" workspace={null} workspaceTab="home" onWorkspaceTab={() => {}}
    onNavigate={() => {}} canGoBack={false} canGoForward={false} onBack={() => {}} onForward={() => {}}
    browserRailVisible={false} onToggleBrowserRail={() => {}} googleConnected={Boolean(current?.snapshot)} /><GooglePage /></>;
}

export async function run() {
  const results: { name: string; error?: string }[] = [];
  const scenarios: Record<string, () => Promise<void>> = {
    async 'sets up Gemini access without changing Google app consent'() {
      await mountPreview(true);
      button('Create API key…').click(); await settle();
      assert(opened[0] === 'https://aistudio.google.com/apikey', 'Key creation goes to Google');
      assert(button('Save key').disabled, 'Empty key cannot be saved');
      const input = document.querySelector<HTMLInputElement>('[aria-label="Gemini API key"]');
      assert(input !== null && input.type === 'password', 'Key input is masked');
      changeInput(input, 'AQ.fixture-only-key'); await settle();
      window.bridge.saveGeminiSetupKey = async () => { throw new Error('Google did not accept this key.'); };
      button('Save key').click(); await settle(); await settle();
      assert(document.querySelector('[role="alert"]')?.textContent?.includes('did not accept'), 'Failed save is visible');
      assert(input.value === 'AQ.fixture-only-key', 'Failed save preserves input for correction');
      window.bridge.saveGeminiSetupKey = async key => {
        assert(key === 'AQ.fixture-only-key', 'Only the entered key is sent');
        return { configured: true, installed: true };
      };
      button('Save key').click(); await settle(); await settle();
      assert(document.querySelector('[aria-label="Gemini API key"]') === null, 'Saved secret leaves the form');
      assert(!status.snapshot?.agentAccess, 'Saving a model key does not enable task access');
      button('Replace key…').click(); await settle();
      assert(document.querySelector<HTMLInputElement>('[aria-label="Gemini API key"]')?.value === '', 'Replacement never reveals stored key');
      button('Remove key').click(); await settle(); await settle();
      assert(document.querySelector('[aria-label="Gemini API key"]') !== null, 'Removing key restores setup');
      assert(status.snapshot !== null, 'Removing model key preserves Google login and data');
    },
    async 'shows the apps navigation only after completed sign-in and removes it on disconnect'() {
      await mountPreview(true);
      const saved = status.snapshot;
      status = { profile: '', accounts: [], installed: false, snapshot: null, operation: '', error: null };
      root?.render(<ConnectionNavigation />); await settle(); await settle();
      const googleTab = () => document.querySelector('[aria-label="Application navigation"] [aria-label="Google Apps"]');
      assert(googleTab() === null, 'Unconfigured integration stays out of navigation');
      status.installed = true;
      window.dispatchEvent(new Event(GOOGLE_CHANGED)); await settle(); await settle();
      assert(googleTab() === null, 'Installing alone does not enable the integration');
      window.bridge.connectGoogle = async () => { status.operation = 'Waiting for Google sign-in'; return structuredClone(status); };
      button('Sign in with Google').click(); await settle(); await settle();
      assert(googleTab() === null, 'Pending sign-in stays out of navigation');
      status.snapshot = saved; status.operation = '';
      const deadline = Date.now() + 4000;
      while (googleTab() === null && Date.now() < deadline) await settle();
      assert(googleTab() !== null, 'Completed browser sign-in adds Google Apps without a reload');
      button('Disconnect…').click(); await settle();
      button('Disconnect and clear').click(); await settle(); await settle();
      assert(googleTab() === null, 'Disconnect removes Google Apps without a reload');
    },
    async 'shows cached mail, escapes email markup, and retains data when refresh fails'() {
      await mountPreview();
      assert(document.querySelector('h2')?.textContent === 'Design review for next week', 'First message opens');
      const messages = document.querySelectorAll<HTMLButtonElement>('.google-screen__list > button');
      messages[1].click(); await settle();
      assert(document.querySelector('article pre')?.textContent?.includes('<script>'), 'HTML is rendered as text');
      assert(!document.querySelector('article script, article img'), 'Email cannot create executable or remote elements');
      button('Search / refresh').click(); await settle(); await settle();
      assert(searches[0] === 'in:inbox:', 'Search uses current query');
      assert(document.querySelector('[role="alert"]')?.textContent?.includes('offline'), 'Refresh failure is visible');
      assert(document.querySelectorAll('.google-screen__list > button').length === 2, 'Saved mail remains readable');
      button('Next page').click(); await settle(); await settle();
      assert(searches[1] === 'in:inbox:next', 'Pagination uses saved search token');
      const screen = document.querySelector('.google-screen');
      assert(screen !== null && screen.scrollWidth <= screen.clientWidth, 'Page fits the viewport');
    },
    async 'shows calendar coverage and rejects non-Google event links'() {
      await mountPreview(); button('Calendar').click(); await settle();
      assert(document.body.textContent?.includes('2026-10-13 · All day'), 'All-day dates stay date-only');
      assert(document.body.textContent?.includes('Cached range:'), 'Calendar shows the fetched range');
      assert(document.querySelectorAll('.google-calendar article button').length === 1, 'Unsafe event URL has no action');
      button('Open event').click(); await settle();
      assert(opened[0]?.startsWith('https://calendar.google.com/'), 'Opens the expected Google destination');
    },
    async 'supports manual day and week views without fetching on view changes'() {
      await mountPreview(); button('Calendar').click(); await settle();
      let refreshed = 0;
      window.bridge.refreshGoogleCalendar = async (_calendar, start, end) => {
        refreshed++; assert(new Date(end).getTime() - new Date(start).getTime() === 86400000, 'Day refresh requests one day');
        return structuredClone(status);
      };
      const view = document.querySelector<HTMLSelectElement>('[aria-label="Calendar view"]');
      assert(view !== null, 'Calendar view exists'); view.value = '1'; view.dispatchEvent(new Event('change', { bubbles: true })); await settle();
      assert(document.querySelectorAll('.google-calendar > section').length === 1 && refreshed === 0, 'View change uses memory only');
      button('Refresh agenda').click(); await settle(); await settle();
      assert(Number(refreshed) === 1, 'Manual refresh fetches the selected range');
    },
    async 'archives selected mail and keeps explicit send confirmation'() {
      await mountPreview();
      let sent = 0;
      window.bridge.sendGoogleMail = async request => { sent++; assert(request.profile === profile && request.replyId === 'mail-one' && request.to === 'maya@example.com', 'Reply binds account and original message'); return structuredClone(status); };
      button('Reply').click(); await settle();
      const body = document.querySelector<HTMLTextAreaElement>('[aria-label="Email body"]');
      assert(body !== null, 'Reply body exists'); changeInput(body, 'Thanks, see you then.'); await settle();
      button('Review email').click(); await settle();
      assert(sent === 0 && document.querySelector('[aria-label="Confirm send"]') !== null, 'Review never sends');
      button('Send email').click(); await settle(); await settle();
      assert(Number(sent) === 1, 'Explicit send sends once');
      button('Archive').click(); await settle(); await settle();
      assert(status.snapshot?.mail.length === 1, 'Archived mail leaves memory view');
    },
    async 'sends prompts and displays Gemini responses in the page'() {
      await mountPreview();
      const input = document.querySelector<HTMLTextAreaElement>('[aria-label="Message Gemini"]');
      assert(input !== null, 'Gemini composer exists'); changeInput(input, 'Has my booking been confirmed?'); await settle();
      button('Send to Gemini').click(); await settle(); await settle();
      assert(document.querySelector('[aria-label="Conversation messages"]')?.textContent?.includes('Your booking has been confirmed.'), 'Response appears inside Google Apps');
      button('Clear conversation').click(); await settle(); await settle();
      assert(chat.messages.length === 0, 'Clear removes conversation');
    },
    async 'requires explicit agent access and confirms local data removal'() {
      await mountPreview(true);
      const toggle = button('Let task agents read cached Google data');
      assert(toggle.getAttribute('aria-checked') === 'false', 'Agent access starts off');
      toggle.click(); await settle(); await settle();
      assert(status.snapshot?.agentAccess, 'Explicit toggle enables saved context');
      button('Disconnect…').click(); await settle();
      assert(status.snapshot !== null, 'Opening confirmation does not remove data');
      button('Disconnect and clear').click(); await settle(); await settle();
      assert(status.snapshot === null && document.body.textContent?.includes('Sign in with Google'), 'Disconnect clears the local account');
    },
    async 'restores status when reopening during a sign-in'() {
      await mountPreview(true);
      status.operation = 'Waiting for Google sign-in'; window.dispatchEvent(new Event(GOOGLE_CHANGED)); await settle(); await settle();
      assert(button('Reconnect').disabled, 'Cannot start a second login');
      button('Cancel').click(); await settle(); await settle();
      assert(!button('Reconnect').disabled, 'Cancelled operation releases controls');
    },
  };
  for (const [name, scenario] of Object.entries(scenarios)) {
    try { await scenario(); results.push({ name }); }
    catch (cause) { results.push({ name, error: cause instanceof Error ? cause.message : String(cause) }); }
  }
  root?.unmount();
  return results;
}
