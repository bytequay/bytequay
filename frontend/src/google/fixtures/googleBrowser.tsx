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
import { GOOGLE_CHANGED, type GoogleStatus } from '../contracts';
import type { Bridge } from '../../types';
import { assert, settle } from '../../fixtures/harness';
import '../../css/base.css';
import '../../css/settings-window.css';

let root: Root | null = null;
let status: GoogleStatus;
let searches: string[];
let opened: string[];

export async function mountPreview(settings = false) {
  root?.unmount();
  document.body.innerHTML = '<div id="root" style="height:100vh"></div>';
  searches = []; opened = [];
  status = { installed: true, operation: '', error: null, snapshot: {
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
    async 'shows the apps navigation only after completed sign-in and removes it on disconnect'() {
      await mountPreview(true);
      const saved = status.snapshot;
      status = { installed: false, snapshot: null, operation: '', error: null };
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
    async 'shows saved mail, escapes email markup, and retains data when refresh fails'() {
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
      assert(document.body.textContent?.includes('may omit events'), 'Agenda does not promise complete sync');
      assert(document.querySelectorAll('.google-screen__agenda article button').length === 1, 'Unsafe event URL has no action');
      button('Open Calendar').click(); await settle();
      assert(opened[0]?.startsWith('https://calendar.google.com/'), 'Opens the expected Google destination');
    },
    async 'requires explicit agent access and confirms local data removal'() {
      await mountPreview(true);
      const toggle = button('Let task agents read saved Google data');
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
