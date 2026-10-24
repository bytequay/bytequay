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
import { GoogleScreen, GooglePage } from '../google';
import AppSidebar from '../../AppSidebar';
import { useGoogle } from '@bytequay/google/renderer';
import { GOOGLE_CHANGED, type GoogleChatState, type GoogleStatus } from '@bytequay/google/contracts';
import { ZOOM_REFERENCE, type ZoomedReference } from '../../githubReference';
import type { Bridge } from '../../types';
import { assert, changeInput, settle, element, runScenarios } from '../../fixtures/harness';
import '../../css/base.css';
import '../../css/settings-window.css';

let root: Root | null = null;
let status: GoogleStatus;
let searches: string[];
let opened: string[];
let browserOpened: string[];
let chat: GoogleChatState;
let chatReads: number;
const profile = '00000000-0000-0000-0000-000000000001';
// The calendar opens on today, so its fixture events are dated from today.
const day = (offset: number) => { const value = new Date(); value.setDate(value.getDate() + offset); return `${value.getFullYear()}-${String(value.getMonth() + 1).padStart(2, '0')}-${String(value.getDate()).padStart(2, '0')}`; };
const at = (offset: number, hour: number) => new Date(`${day(offset)}T${String(hour).padStart(2, '0')}:00:00`).toISOString();

export async function mountPreview(settings = false) {
  root?.unmount();
  document.body.innerHTML = '<div id="root" style="height:100vh"></div>';
  searches = []; opened = []; browserOpened = [];
  chatReads = 0;
  chat = { messages: [], busy: false, activity: '', error: null, approval: null };
  status = { profile, accounts: [{ profile, accountId: 'people/one', email: 'alex@example.com', name: 'Alex Chen' }], installed: true, operation: '', error: null, snapshot: {
    accountId: 'people/one', name: 'Alex Chen', email: 'alex@example.com', agentAccess: false,
    query: 'in:inbox', nextPageToken: 'next', mailSavedAt: '2026-10-09T02:00:00Z',
    mail: [
      { id: 'mail-one', threadId: 'thread-one', subject: 'Design review for next week', from: 'Maya <maya@example.com>', to: 'alex@example.com', date: 'Fri, 9 Oct 2026 09:15:00 +0800', snippet: 'Could we review the calendar integration on Monday?', body: 'Hi Alex,\n\nCould we review the calendar integration on Monday?\n\nThe agenda should show our saved events even when we’re offline. Please bring your notes on sign-in and permissions.\n\nThanks,\nMaya', unread: true },
      { id: 'mail-two', threadId: 'thread-two', subject: 'Release checklist', from: 'Sam <sam@example.com>', to: 'alex@example.com', date: 'Thu, 8 Oct 2026 16:20:00 +0800', snippet: 'The latest build is ready for review.', body: '<script>window.googleInjected = true</script><img src="https://example.test/tracker.png">Review notes', unread: false },
      { id: 'mail-three', threadId: 'thread-three', subject: 'Re: [bytequay/bytequay] Fix flaky sign-in test (PR #42)', from: 'Maya <notifications@github.com>', to: '"bytequay/bytequay" <bytequay@noreply.github.com>', date: 'Thu, 8 Oct 2026 11:05:00 +0800', snippet: '@maya commented on this pull request.',
        body: '@maya commented on this pull request.\n\n\n\nIn src/signIn.ts:\n\n>     }\n\n+  await settle();\n-  await sleep(500);\n\nSame cause as #40.\n\n- Wait for sign-in to finish before checking the account.\n- Keep the same signature.\n\n-Callers do not change.\n\n-- \nReply to this email directly or view it on GitHub:\nhttps://github.com/bytequay/bytequay/pull/42#discussion_r1\nYou are receiving this because you are subscribed to this thread.\n\nMessage ID: <bytequay/bytequay/pull/42/review/1@github.com>', unread: false },
    ],
    calendars: [{ id: 'primary', summary: 'Personal' }], calendarId: 'primary', timeMin: at(0, 0), timeMax: at(7, 0), calendarSavedAt: '2026-10-09T02:00:00Z',
    events: [
      { id: 'event-one', summary: 'Design review', description: 'Review the Google integration and browser sign-in flow.', start: at(1, 10), end: at(1, 11), allDay: false, url: 'https://calendar.google.com/calendar/event?eid=one' },
      { id: 'event-two', summary: 'Focus day', description: '', start: day(2), end: day(3), allDay: true, url: 'javascript:alert(1)' },
    ],
  } };
  const bridge: Partial<Bridge> = {
    getGeminiSetupStatus: async () => ({ configured: !settings, installed: true }),
    saveGeminiSetupKey: async () => ({ configured: true, installed: true }),
    removeGeminiSetupKey: async () => ({ configured: false, installed: true }),
    getGoogleChat: async () => { chatReads++; return structuredClone(chat); },
    promptGoogleChat: async (_profile, text) => { chat.messages.push({ role: 'user', text }, { role: 'assistant', text: 'Your booking has been confirmed.' }); return structuredClone(chat); },
    clearGoogleChat: async () => { chat.messages = []; return structuredClone(chat); },
    stopGoogleChat: async () => { chat.busy = false; chat.approval = null; return structuredClone(chat); },
    approveGoogleChat: async () => { chat.approval = null; return structuredClone(chat); },
    addGoogleAccount: async () => structuredClone(status),
    selectGoogleAccount: async selected => { status.profile = selected; return structuredClone(status); },
    archiveGoogleMail: async (_profile, id) => { if (status.snapshot) status.snapshot.mail = status.snapshot.mail.filter(mail => mail.id !== id); return structuredClone(status); },
    getGoogleReplyRecipients: async () => ['maya@example.com'],
    sendGoogleMail: async () => structuredClone(status),
    getGoogleStatus: async () => structuredClone(status),
    refreshGoogleMail: async (query, token) => { searches.push(`${query}:${token}`); status.error = 'Google is offline. Saved data remains available.'; return structuredClone(status); },
    refreshGoogleCalendar: async () => structuredClone(status),
    setGoogleAgentAccess: async enabled => { if (status.snapshot) status.snapshot.agentAccess = enabled; return structuredClone(status); },
    disconnectGoogle: async () => { status.snapshot = null; return structuredClone(status); },
    connectGoogle: async () => structuredClone(status),
    installGoogleConnector: async () => structuredClone(status),
    cancelGoogleOperation: async () => { status.operation = ''; return structuredClone(status); },
    listWorkspaces: async () => [],
    openInAppBrowser: async url => { browserOpened.push(url); },
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
  const { status: current } = useGoogle(window.bridge);
  return <><AppSidebar page="home" workspace={null} workspaceTab="home" onWorkspaceTab={() => {}}
    onNavigate={() => {}} canGoBack={false} canGoForward={false} onBack={() => {}} onForward={() => {}}
    browserRailVisible={false} onToggleBrowserRail={() => {}} googleConnected={Boolean(current?.snapshot)} /><GooglePage /></>;
}

export async function run() {
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
      assert(document.querySelector('.google-reader h2')?.textContent === 'Design review for next week', 'First message opens');
      assert(document.querySelector('.google-screen__tabs [aria-label="1 unread"]') !== null, 'The tab counts unread mail');
      const messages = document.querySelectorAll<HTMLButtonElement>('.google-mail__item');
      messages[1].click(); await settle();
      assert(document.querySelector('.google-mail-text')?.textContent?.includes('<script>'), 'HTML is rendered as text');
      assert(!document.querySelector('.google-reader script, .google-reader img'), 'Email cannot create executable or remote elements');
      button('Search / refresh').click(); await settle(); await settle();
      assert(searches[0] === 'in:inbox:', 'Search uses current query');
      assert(document.querySelector('[role="alert"]')?.textContent?.includes('offline'), 'Refresh failure is visible');
      assert(document.querySelectorAll('.google-mail__item').length === 3, 'Saved mail remains readable');
      button('Next page').click(); await settle(); await settle();
      assert(searches[1] === 'in:inbox:next', 'Pagination uses saved search token');
      button('Unread').click(); await settle(); await settle();
      assert(searches[2] === 'in:inbox is:unread:', 'A filter searches with its query');
      const screen = document.querySelector('.google-screen');
      assert(screen !== null && screen.scrollWidth <= screen.clientWidth, 'Page fits the viewport');
    },
    async 'reads a GitHub notification as a comment card with GitHub-only links'() {
      await mountPreview();
      const item = document.querySelectorAll<HTMLButtonElement>('.google-mail__item')[2];
      assert(item.querySelector('.google-mail__subject')?.textContent === 'Fix flaky sign-in test', 'The list drops the repository and PR from the subject');
      assert(item.querySelector('.google-labels')?.textContent === 'bytequay/bytequay#42', 'They become labels');
      item.click(); await settle();
      assert(document.querySelector('.google-notice__head')?.textContent?.includes('@maya') === true, 'The card names who acted');
      assert(document.querySelector('.google-quote__file')?.textContent === 'src/signIn.ts', 'The quoted diff names its file');
      assert(document.querySelectorAll('.google-quote__lines .is-add').length === 1 && document.querySelectorAll('.google-quote__lines .is-del').length === 1, 'Added and removed lines are told apart');
      assert(document.querySelectorAll('.google-quote').length === 1 && document.querySelectorAll('.google-quote code').length === 4, 'Blank lines stay inside the code block');
      assert(document.querySelectorAll('.google-notice__body ul > li').length === 2, 'Markdown dash-space items render as bullets');
      assert([...document.querySelectorAll('.google-notice__body p')].some(part => part.textContent === '-Callers do not change.'), 'A dash without a space stays literal');
      const code = document.querySelector('.google-quote code');
      assert(code !== null && getComputedStyle(code).whiteSpace === 'pre', 'Code keeps its indentation');
      button('#40').click(); button('View on GitHub').click(); await settle();
      assert(browserOpened[0] === 'https://github.com/bytequay/bytequay/issues/40' && browserOpened[1] === 'https://github.com/bytequay/bytequay/pull/42#discussion_r1' && opened.length === 0, 'Unwatched links open in the internal browser');
      button('to bytequay/bytequay').click(); await settle();
      assert(document.querySelector('.google-reader__details')?.textContent?.includes('<bytequay/bytequay/pull/42/review/1@github.com>') === true, 'Details show the message ID');
      const press = (target: Element) => target.dispatchEvent(new KeyboardEvent('keydown', { key: 'r', bubbles: true, cancelable: true }));
      press(document.querySelector('[aria-label="Search Gmail"]') ?? document.body); await settle();
      assert(document.querySelector('[aria-label="Reply to email"]') === null, 'Typing r in a field is only typing');
      press(document.body); await settle();
      assert(document.querySelector('[aria-label="Reply to email"]') !== null, 'R replies to the open message');
    },
    async 'opens mail PR numbers and URLs in the watched repository zoom'() {
      await mountPreview();
      window.bridge.listWorkspaces = async () => [{ id: 'workspace-1', monitoredOwner: 'ByteQuay', monitoredRepo: 'ByteQuay' }] as Awaited<ReturnType<Bridge['listWorkspaces']>>;
      const message = status.snapshot?.mail[2];
      assert(message !== undefined, 'GitHub notification exists');
      message.body = '#42 entered the merge queue. See (https://github.com/bytequay/bytequay/pull/43#discussion_r1).';
      window.dispatchEvent(new Event(GOOGLE_CHANGED)); await settle(); await settle();
      document.querySelectorAll<HTMLButtonElement>('.google-mail__item')[2].click(); await settle();
      const targets: ZoomedReference[] = [];
      const zoom = (event: Event) => targets.push((event as CustomEvent<ZoomedReference>).detail);
      window.addEventListener(ZOOM_REFERENCE, zoom);
      try {
        const number = document.querySelector<HTMLButtonElement>('.google-notice__body .google-link');
        assert(number?.textContent === '#42', 'The notification number is clickable');
        number?.click(); await settle();
        button('https://github.com/bytequay/bytequay/pull/43#discussion_r1').click(); await settle();
        document.querySelector<HTMLButtonElement>('.google-reader__body > .google-labels button')?.click(); await settle();
        button('View on GitHub').click(); await settle();
        assert(targets.length === 4 && targets.every(target => target.kind === 'pull' && target.repo === 'bytequay/bytequay'), 'Every entry point opens a PR zoom');
        assert(targets[0].kind === 'pull' && targets[0].number === 42 && targets[1].kind === 'pull' && targets[1].number === 43, 'Numbers resolve to the intended PR');
        assert(opened.length === 0 && browserOpened.length === 0, 'Watched PRs stay in the native view');
        window.bridge.listWorkspaces = async () => [];
        number?.click(); await settle();
        assert(browserOpened[0] === 'https://github.com/bytequay/bytequay/pull/42', 'Unwatched PR number uses the internal browser');
        window.bridge.openInAppBrowser = async () => { throw new Error('Browser unavailable'); };
        number?.click(); await settle();
        assert(document.querySelector('[role="alert"]')?.textContent?.includes('Browser unavailable'), 'Navigation failures remain visible');
      }
      finally { window.removeEventListener(ZOOM_REFERENCE, zoom); }
    },
    async 'recovers sign-in polling after a transient status failure'() {
      await mountPreview(true);
      let checks = 0;
      window.bridge.connectGoogle = async () => { status.operation = 'Checking Google connection'; return structuredClone(status); };
      window.bridge.getGoogleStatus = async () => {
        checks++;
        if (checks === 2) throw new Error('Temporary connection failure');
        if (checks >= 3) status.operation = '';
        return structuredClone(status);
      };
      button('Reconnect').click(); await settle(); await settle();
      assert(document.body.textContent?.includes('Checking Google connection'), 'Existing connection is checked first');
      assert(!document.body.textContent?.includes('Complete sign-in in your browser'), 'Checking credentials does not claim a browser login is needed');
      const deadline = Date.now() + 5000;
      while (button('Reconnect').disabled && Date.now() < deadline) await settle();
      assert(checks >= 3, 'Polling continues after a failed status request');
      assert(!button('Reconnect').disabled, 'Completion clears the pending operation');
      assert(!document.querySelector('[role="alert"]'), 'Successful status clears the transient error');
    },
    async 'shows calendar coverage and rejects non-Google event links'() {
      await mountPreview(); button('Calendar').click(); await settle();
      assert(document.querySelector('.google-cal')?.getAttribute('aria-label') === 'Week calendar' && document.querySelectorAll('.google-cal__day').length === 7, 'The week opens on today');
      assert(document.querySelector('.google-status.is-bar')?.textContent?.startsWith('Cached ') === true, 'Calendar shows the fetched range');
      document.querySelector<HTMLButtonElement>('.google-cal__chip')?.click(); await settle();
      const detail = () => document.querySelector('.google-cal__detail');
      assert(detail()?.textContent?.includes('All day') === true, 'All-day events have no times');
      assert(![...detail()?.querySelectorAll('button') ?? []].some(item => item.textContent === 'Open event'), 'Unsafe event URL has no action');
      document.querySelector<HTMLButtonElement>('.google-cal__event')?.click(); await settle();
      assert(detail()?.textContent?.includes('Design review') === true, 'A timed event opens its details');
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
      button('Day').click(); await settle();
      assert(document.querySelectorAll('.google-cal__day').length === 1 && refreshed === 0, 'View change uses memory only');
      for (let step = 0; step < 8; step++) button('Next').click();
      await settle();
      assert(document.querySelector('.google-status.is-bar')?.textContent?.startsWith('Not cached') === true && refreshed === 0, 'Leaving the cached range says so without fetching');
      button('Refresh agenda').click(); await settle(); await settle();
      assert(Number(refreshed) === 1, 'Manual refresh fetches the selected range');
      button('Today').click(); await settle();
      assert(document.querySelector('.google-cal__head .is-today') !== null, 'Today returns to the current day');
    },
    async 'archives selected mail and keeps explicit send confirmation'() {
      await mountPreview();
      let sent = 0;
      window.bridge.sendGoogleMail = async request => { sent++; assert(request.profile === profile && request.replyId === 'mail-one' && request.to === 'maya@example.com', 'Reply binds account and original message'); return structuredClone(status); };
      button('Reply').click(); await settle();
      const body = element<HTMLTextAreaElement>('[aria-label="Email body"]', document, 'Reply body exists');
      changeInput(body, 'Thanks, see you then.'); await settle();
      button('Review email').click(); await settle();
      assert(sent === 0 && document.querySelector('[aria-label="Confirm send"]') !== null, 'Review never sends');
      button('Send email').click(); await settle(); await settle();
      assert(Number(sent) === 1, 'Explicit send sends once');
      assert(document.querySelector('[aria-label="Email body"]') === null && document.querySelector('.google-toast')?.textContent === 'Reply sent', 'A sent reply closes and confirms');
      button('Archive').click(); await settle(); await settle();
      assert(status.snapshot?.mail.length === 2, 'Archived mail leaves memory view');
      button('Compose').click(); await settle();
      assert(document.querySelector('[aria-label="Email recipients"]') !== null && button('Review email').disabled, 'A new email needs a recipient and a body');
    },
    async 'keeps reply drafts until explicitly discarded and reads the actual reply recipients'() {
      await mountPreview();
      window.bridge.getGoogleReplyRecipients = async () => ['support@example.com', 'team@example.com'];
      button('Reply').click(); await settle(); await settle();
      const input = element<HTMLTextAreaElement>('[aria-label="Email body"]');
      changeInput(input, 'Keep my unfinished reply.'); await settle();
      for (const item of document.querySelectorAll<HTMLButtonElement>('.google-mail__item')) { assert(item.disabled, 'Mail navigation waits until the draft is finished'); item.click(); }
      for (const label of ['Older', 'Next page', 'Archive', 'Search / refresh']) { assert(button(label).disabled, `${label} cannot discard a draft`); button(label).click(); }
      assert(element<HTMLTextAreaElement>('[aria-label="Email body"]').value === 'Keep my unfinished reply.', 'Navigation preserves the reply text');
      assert(status.snapshot !== null, 'Mailbox is connected');
      status.snapshot.mail = status.snapshot.mail.slice(1); window.dispatchEvent(new Event(GOOGLE_CHANGED)); await settle(); await settle();
      assert(element<HTMLTextAreaElement>('[aria-label="Email body"]').value === 'Keep my unfinished reply.' && document.querySelector('.google-reader h2')?.textContent === 'Design review for next week', 'A background mailbox refresh keeps the original reply and its draft visible');
      button('Review email').click(); await settle();
      assert(element('[aria-label="Confirm send"]').textContent?.includes('support@example.com, team@example.com') === true, 'Confirmation displays Reply-To recipients rather than From');
      let request: Parameters<Bridge['sendGoogleMail']>[0] | undefined;
      let acceptSend: (value: GoogleStatus) => void = () => { throw new Error('Send was not started'); };
      window.bridge.sendGoogleMail = value => { request = value; return new Promise(resolve => { acceptSend = resolve; }); };
      button('Send email').click(); await settle();
      assert(input.disabled && button('Discard draft').disabled, 'The draft cannot change while a send is being accepted');
      acceptSend(structuredClone(status)); await settle(); await settle();
      assert(request?.to === 'support@example.com, team@example.com', 'The confirmed recipients are sent unchanged');
      button('Reply').click(); await settle(); await settle();
      button('Discard draft').click(); await settle();
      window.bridge.getGoogleReplyRecipients = async () => { throw new Error('Could not read reply address'); };
      button('Reply').click(); await settle(); await settle();
      assert(document.querySelector('[aria-label="Email body"]') === null && document.body.textContent?.includes('Could not read reply address') === true, 'A failed header read never falls back to the From address');
    },
    async 'locks the Gemini draft until prompt acceptance and preserves it after a failure'() {
      await mountPreview();
      const input = element<HTMLTextAreaElement>('[aria-label="Message Gemini"]');
      let rejectPrompt: (error: Error) => void = () => { throw new Error('Prompt was not started'); };
      window.bridge.promptGoogleChat = () => new Promise((_resolve, reject) => { rejectPrompt = reject; });
      changeInput(input, 'Read my inbox.'); await settle(); button('Send to Gemini').click(); await settle();
      assert(input.disabled, 'Typing cannot race a pending prompt acceptance');
      rejectPrompt(new Error('Prompt failed')); await settle(); await settle();
      assert(!input.disabled && input.value === 'Read my inbox.', 'The failed prompt remains editable');
    },
    async 'sends prompts and displays Gemini responses in the page'() {
      await mountPreview();
      const input = element<HTMLTextAreaElement>('[aria-label="Message Gemini"]', document, 'Gemini composer exists');
      button('Ask Gemini').click(); await settle();
      assert(document.querySelector('.google-chat__context')?.textContent === 'Design review for next week', 'Ask Gemini attaches the open email');
      changeInput(input, 'Has my booking been confirmed?'); await settle();
      button('Send to Gemini').click(); await settle(); await settle();
      assert(chat.messages[0].text.includes('Gmail message ID mail-one') && chat.messages[0].text.endsWith('Has my booking been confirmed?'), 'The question names the email it is about');
      assert(document.querySelector('.google-chat__context') === null, 'The attachment is used once');
      assert(document.querySelector('[aria-label="Conversation messages"]')?.textContent?.includes('Your booking has been confirmed.'), 'Response appears inside Google Apps');
      button('Clear conversation').click(); await settle(); await settle();
      assert(chat.messages.length === 0, 'Clear removes conversation');
    },
    async 'polls active Gemini work once per tick and pauses idle or hidden conversations'() {
      const originalSet = window.setInterval;
      const originalClear = window.clearInterval;
      const visibility = Object.getOwnPropertyDescriptor(document, 'visibilityState');
      const timers = new Map<number, () => void>();
      let nextTimer = -1;
      window.setInterval = ((handler: TimerHandler, delay?: number, ...args: unknown[]) => {
        if (delay !== 700 || typeof handler !== 'function') return originalSet(handler, delay, ...args);
        const id = nextTimer--;
        timers.set(id, () => handler(...args));
        return id;
      }) as typeof window.setInterval;
      window.clearInterval = id => { if (typeof id === 'number' && timers.delete(id)) return; originalClear(id); };
      const tick = () => [...timers.values()].forEach(run => run());
      const visible = (value: 'visible' | 'hidden') => {
        Object.defineProperty(document, 'visibilityState', { configurable: true, value });
        document.dispatchEvent(new Event('visibilitychange'));
      };
      const busy = () => document.querySelector('[aria-label="Gemini is responding"]') !== null;
      let pendingRead: PromiseWithResolvers<GoogleChatState> | null = null;
      let failRead = false;
      try {
        visible('visible');
        await mountPreview();
        assert(chatReads === 1 && timers.size === 1, 'The conversation reads once when mounted');
        tick(); tick(); await settle();
        assert(chatReads === 1, 'Idle conversations make no repeated IPC reads');
        window.bridge.getGoogleChat = async () => {
          chatReads++;
          if (failRead) throw new Error('Temporary read failure');
          return pendingRead?.promise ?? structuredClone(chat);
        };
        window.bridge.promptGoogleChat = async (_profile, text) => {
          chat = { ...chat, busy: true, activity: 'Searching mail', messages: [...chat.messages, { role: 'user', text }] };
          return structuredClone(chat);
        };
        const ask = async (message: string) => {
          changeInput(element<HTMLTextAreaElement>('[aria-label="Message Gemini"]'), message); await settle();
          button('Send to Gemini').click(); await settle(); await settle();
          assert(busy(), 'A submitted prompt starts busy polling');
        };
        await ask('Check my booking');
        pendingRead = Promise.withResolvers<GoogleChatState>();
        let before = chatReads;
        tick(); tick(); tick();
        assert(chatReads === before + 1, 'Repeated 700ms ticks never overlap a slow read');
        chat.activity = 'Reading the booking reply';
        pendingRead.resolve(structuredClone(chat)); pendingRead = null;
        await settle();
        assert(document.querySelector('.google-chat__activity')?.textContent === chat.activity, 'The active read updates the conversation');

        visible('hidden'); before = chatReads;
        tick(); tick(); await settle();
        assert(chatReads === before, 'Hidden conversations skip reads even while busy');
        chat.activity = 'Checking calendar availability';
        visible('visible'); await settle();
        assert(chatReads === before + 1 && document.querySelector('.google-chat__activity')?.textContent === chat.activity,
          'Returning immediately reads and shows fresh activity');

        failRead = true; tick(); await settle();
        assert(document.querySelector('.google-chat [role="alert"]')?.textContent?.includes('Unable to read'), 'A failed read remains visible');
        failRead = false; tick(); await settle();
        assert(document.querySelector('.google-chat [role="alert"]') === null && busy(), 'The next successful active read clears its transient error');

        chat = { ...chat, busy: false, activity: '', messages: [...chat.messages, { role: 'assistant', text: 'Your booking is confirmed.' }] };
        tick(); await settle();
        assert(!busy() && document.querySelector('.google-chat__messages')?.textContent?.includes('Your booking is confirmed.'), 'Completion reaches the conversation');
        before = chatReads;
        tick(); tick(); await settle();
        assert(chatReads === before, 'Polling stops after completion');

        await ask('Check another booking');
        window.bridge.stopGoogleChat = async () => { throw new Error('Unable to stop this turn'); };
        button('Stop').click(); await settle(); await settle();
        tick(); await settle();
        assert(document.querySelector('.google-chat [role="alert"]')?.textContent === 'Unable to stop this turn' && busy(),
          'A successful status read must not erase a failed user action');
        window.bridge.stopGoogleChat = async () => { chat = { ...chat, busy: false, approval: null }; return structuredClone(chat); };
        pendingRead = Promise.withResolvers<GoogleChatState>();
        tick();
        const late = structuredClone(chat);
        button('Stop').click(); await settle(); await settle();
        assert(!busy(), 'Stop publishes its idle state');
        pendingRead.resolve(late); pendingRead = null;
        await settle();
        assert(!busy(), 'A late busy poll cannot undo Stop');
        before = chatReads;
        tick(); await settle();
        assert(chatReads === before, 'A stopped conversation stays idle');
        root?.unmount(); root = null;
        tick(); visible('visible'); await settle();
        assert(chatReads === before && Number(timers.size) === 0, 'Unmounting releases polling and visibility listeners');
      }
      finally {
        root?.unmount(); root = null;
        window.setInterval = originalSet;
        window.clearInterval = originalClear;
        if (visibility === undefined) Reflect.deleteProperty(document, 'visibilityState');
        else Object.defineProperty(document, 'visibilityState', visibility);
      }
    },
    async 'guides missing Gemini setup to settings while keeping Google Apps usable'() {
      await mountPreview();
      window.bridge.getGeminiSetupStatus = async () => ({ configured: false, installed: true });
      window.dispatchEvent(new Event('focus')); await settle(); await settle();
      assert(document.querySelector('[aria-label="Message Gemini"]') === null, 'Cannot submit without a saved key');
      const link = document.querySelector<HTMLAnchorElement>('.google-chat a.google-primary-btn');
      assert(link?.textContent === 'Add API key' && link.getAttribute('href') === '#/settings/integrations/google', 'Setup links to Google Apps settings');
      assert(!button('Search / refresh').disabled, 'Gmail still works without a Gemini key');
      assert(chat.messages.length === 0, 'Setup does not start a failed conversation');
      window.bridge.getGeminiSetupStatus = async () => ({ configured: true, installed: false });
      window.dispatchEvent(new Event('focus')); await settle(); await settle();
      assert(document.body.textContent?.includes('Install Gemini CLI to use the assistant'), 'Missing CLI has its own setup guidance');
      window.bridge.getGeminiSetupStatus = async () => { throw new Error('Keychain locked'); };
      window.dispatchEvent(new Event('focus')); await settle(); await settle();
      assert(document.querySelector('.google-chat [role="alert"]')?.textContent?.includes('Unable to check'), 'Status failure remains visible');
      window.bridge.getGeminiSetupStatus = async () => ({ configured: true, installed: true });
      window.dispatchEvent(new Event('focus')); await settle(); await settle();
      assert(document.querySelector('[aria-label="Message Gemini"]') !== null, 'Returning after setup enables the composer');
      assert(document.querySelector('.google-chat [role="alert"]') === null, 'Successful check clears the setup error');
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
  const results = await runScenarios(Object.entries(scenarios));
  root?.unmount();
  return results;
}
