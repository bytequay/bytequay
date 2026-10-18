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
import { flushSync } from 'react-dom';
import { createRoot } from 'react-dom/client';
import App from '../../App';
import { createPreviewBridge } from '../../agents/fixtures/previewBridge';
import { assert, until } from '../../fixtures/harness';
import type { Bridge } from '../../types';
import { settingsRoute } from '../routes';
import '../../index.css';

/** Starts the whole app on a route. Every web page it asks for is added to `opened` instead of being loaded. */
export function start(route: string, opened: string[] = [], openLab: () => Promise<string> = async () => 'http://127.0.0.1:42311/'): () => void {
  const bridge = {
    ...createPreviewBridge('workspace'),
    hasPat: async () => true,
    openLab,
    getInAppBrowserState: async () => ({ tabs: [], activeTabId: null }),
    onInAppBrowserState: () => () => {},
    openInAppBrowser: async (url: string) => { opened.push(url); },
    getFullScreen: async () => false,
    onFullScreenChange: () => () => {},
    setPrAttentionHandled: async () => {},
    onPrAttentionUpdated: () => () => {},
    onPrNotificationOpen: () => () => {},
    onFindResult: () => () => {},
    stopFindInPage: async () => {},
    listJiraConnections: async () => [],
    listJiraAttention: async () => [],
    listWorkspaces: async () => [],
    fetchRecentChanges: async () => [],
    fetchRecent: async () => ({ day: null, rows: [], nextCursor: null, endOfWindow: true, activeDays: [], retentionDays: 14 }),
    fetchAttentionPrs: async () => [],
    onPullRequestsSynced: () => () => {},
    getUserProfile: async () => { throw new Error('No profile in this fixture'); },
    fetchPrs: async () => ({ pullRequests: [], page: 0, pageSize: 50, totalPullRequests: 0, openPullRequests: 0, hasMore: false }),
    getAppVersion: async () => ({ version: 'test' }),
    getAppPreferences: async () => ({ taskRetentionDays: 14, recentRetentionDays: 14, prWarmCount: 10, theme: 'SYSTEM' }),
  } satisfies Partial<Bridge>;
  window.bridge = bridge as unknown as Bridge;
  window.location.hash = route;
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  flushSync(() => root.render(<App />));
  return () => { flushSync(() => root.unmount()); host.remove(); };
}

const labItem = () => document.querySelector<HTMLButtonElement>('.app-sidebar [aria-label="Application settings"] [aria-label="Lab"]');
const toggle = () => document.querySelector<HTMLButtonElement>('.setw-page [role="switch"][aria-label="Show the Lab"]');
const openButton = () => document.querySelector<HTMLButtonElement>('.setw-page .setw-btn-primary');
const errorText = () => document.querySelector('.setw-page [role="alert"]')?.textContent ?? '';
const DASHBOARD = 'http://127.0.0.1:42311/';

export async function run(): Promise<{ name: string; error?: string }[]> {
  window.localStorage.removeItem('bytequay.lab-link');
  const opened: string[] = [];
  let launches = 0;
  let failNext = false;
  let ready: ((url: string) => void) | undefined;
  const openLab = async () => {
    launches++;
    ready = undefined;
    if (failNext) {
      failNext = false;
      throw new Error('Dashboard could not start');
    }
    return new Promise<string>(resolve => { ready = resolve; });
  };
  const finishOpening = async () => {
    const before = opened.length;
    assert(ready !== undefined, 'The app requested dashboard startup');
    ready(DASHBOARD);
    await until(() => opened.length === before + 1 && !openButton()?.disabled && !labItem()?.disabled, 'The ready dashboard opens and launch buttons recover');
    assert(opened.at(-1) === DASHBOARD, 'The browser opens the address returned by the managed dashboard');
  };
  let stop = start(settingsRoute('lab'), opened, openLab);
  const results: { name: string; error?: string }[] = [];
  const step = async (name: string, body: () => Promise<void>) => {
    try { await body(); results.push({ name }); }
    catch (error) { results.push({ name, error: error instanceof Error ? error.message : String(error) }); }
  };
  try {
    await step('Settings offers Open Lab without requiring a sidebar shortcut or an address', async () => {
      await until(() => toggle() !== null, 'Settings shows the Lab page');
      assert(document.querySelector('[aria-label="Settings navigation"] [aria-label="Lab"][aria-current="page"]') !== null, 'Lab is a page under Settings');
      assert(toggle()?.getAttribute('aria-checked') === 'false' && labItem() === null, 'The shortcut starts turned off');
      assert(document.querySelector('[aria-label="Dashboard address"]') === null, 'The dashboard address is managed by the app');
      assert(openButton()?.textContent?.trim() === 'Open Lab', 'Settings offers Open Lab');
      assert(launches === 0, 'Visiting Settings does not start the dashboard');
      openButton()?.click();
      await until(() => openButton()?.disabled === true, 'Opening shows a disabled button');
      assert(openButton()?.getAttribute('aria-busy') === 'true' && openButton()?.textContent?.includes('Opening Lab'), 'The launch is visibly in progress');
      assert(opened.length === 0, 'No web page opens before the dashboard is ready');
      await finishOpening();
      assert(toggle()?.getAttribute('aria-checked') === 'false', 'Opening the Lab does not change sidebar visibility');
    });

    await step('The sidebar starts and refreshes the dashboard and prevents concurrent launches', async () => {
      toggle()?.click();
      await until(() => labItem() !== null && toggle()?.getAttribute('aria-checked') === 'true', 'The switch shows on and the Lab item appears');
      const labels = [...document.querySelectorAll('[aria-label="Application settings"] button')].map(button => button.getAttribute('aria-label'));
      assert(labels[labels.indexOf('Lab') + 1] === 'Web pages', 'Lab sits just above Web pages');
      labItem()?.click();
      labItem()?.click();
      openButton()?.click();
      await until(() => labItem()?.disabled === true && openButton()?.disabled === true, 'Both entry points share launch progress');
      assert(launches === 2 && opened.length === 1, 'Repeated clicks start just one refresh and wait to open');
      await finishOpening();
      assert(Number(opened.length) === 2, 'The sidebar opens one page after readiness');
    });

    await step('A sidebar failure opens Lab Settings with the error and a working Retry button', async () => {
      window.location.hash = '#/recent';
      await until(() => document.querySelector('[aria-label="Recent"][aria-current="page"]') !== null, 'The app navigates to Recent');
      failNext = true;
      labItem()?.click();
      await until(() => errorText().includes('Dashboard could not start'), 'The failure is visible in Lab Settings');
      assert(window.location.hash === settingsRoute('lab'), 'A sidebar failure opens Lab Settings');
      assert(openButton()?.textContent?.trim() === 'Retry' && openButton()?.disabled === false, 'The failed launch can be retried');
      assert(opened.length === 2, 'Startup failure does not open a broken web page');
      openButton()?.click();
      await until(() => openButton()?.disabled === true, 'Retry starts another launch');
      assert(errorText() === '', 'Retry clears the previous error');
      await finishOpening();
      assert(launches === 4, 'Retry starts exactly one additional refresh');
    });

    await step('Sidebar visibility survives restart and opening from another page refreshes again', async () => {
      stop();
      stop = start('#/recent', opened, openLab);
      await until(() => document.querySelector('[aria-label="Recent"][aria-current="page"]') !== null, 'The app starts again on Recent');
      assert(labItem() !== null, 'The Lab shortcut is still there');
      assert(launches === 4, 'The visible shortcut does not launch the dashboard by itself');
      labItem()?.click();
      await until(() => labItem()?.disabled === true, 'The sidebar shows startup progress');
      await finishOpening();
      assert(Number(launches) === 5 && opened.length === 4, 'Every open asks for fresh data');
    });

    await step('Turning off the shortcut leaves Open Lab available in Settings', async () => {
      window.location.hash = settingsRoute('lab');
      await until(() => toggle() !== null, 'Settings shows the Lab page again');
      toggle()?.click();
      await until(() => labItem() === null, 'The Lab item goes away');
      assert(openButton()?.textContent?.trim() === 'Open Lab' && openButton()?.disabled === false, 'Open Lab remains available');
      assert(JSON.stringify(JSON.parse(window.localStorage.getItem('bytequay.lab-link') ?? '{}')) === '{"enabled":false}', 'Only shortcut visibility is saved');
    });
    return results;
  }
  finally {
    stop();
    window.localStorage.removeItem('bytequay.lab-link');
  }
}
